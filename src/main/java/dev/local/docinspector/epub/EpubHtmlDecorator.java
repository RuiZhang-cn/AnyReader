package dev.local.docinspector.epub;

import dev.local.docinspector.model.ImagePrivacyMode;
import dev.local.docinspector.model.ReaderSettings;
import dev.local.docinspector.model.ThemePreset;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * EPUB 章节 HTML 装饰器：注入 CSS 主题变量、三档图片防社死样式与胶囊折叠、
 * Lightbox 浮层、位置恢复（300ms 校准 + 1200ms 防污染锁）、400ms 防抖进度上报、
 * 高亮检索与自动微滚屏接口。
 */
public final class EpubHtmlDecorator {

    private EpubHtmlDecorator() {
    }

    /**
     * 装饰选项（与具体 IDE 主题组件解耦，便于单元测试独立验证）。
     */
    public record StylePalette(String bgHex,
                               String fgHex,
                               String mutedFgHex,
                               String borderHex,
                               String accentHex,
                               String fontFamily,
                               int fontSize,
                               double lineHeight,
                               int horizontalPadding,
                               double darkImageBrightness,
                               ImagePrivacyMode imagePrivacyMode) {

        public static StylePalette fromSettings(ReaderSettings settings, String ideBgHex, String ideFgHex) {
            ReaderSettings s = settings != null ? settings : new ReaderSettings();
            ThemePreset preset = s.getThemePreset();
            String bg;
            String fg;
            String muted;
            String border;
            String accent;

            switch (preset) {
                case CODE_CAMOUFLAGE -> {
                    bg = "#1E1F22";
                    fg = "#BCBEC4";
                    muted = "#7A7E85";
                    border = "#393B40";
                    accent = "#56A8F5";
                }
                case NIGHT_DARK -> {
                    bg = "#181A1F";
                    fg = "#CDD3DE";
                    muted = "#7B8496";
                    border = "#2C313C";
                    accent = "#61AFEF";
                }
                case WARM_PARCHMENT -> {
                    bg = "#F4ECD8";
                    fg = "#3B3124";
                    muted = "#7E6E58";
                    border = "#DECFAF";
                    accent = "#8B5E3C";
                }
                case LIGHT_PAPER -> {
                    bg = "#FAFAFA";
                    fg = "#24292F";
                    muted = "#57606A";
                    border = "#D0D7DE";
                    accent = "#0969DA";
                }
                case FOLLOW_IDE -> {
                    bg = (ideBgHex != null && !ideBgHex.isBlank()) ? ideBgHex : "#2B2D30";
                    fg = (ideFgHex != null && !ideFgHex.isBlank()) ? ideFgHex : "#DFE1E5";
                    boolean dark = isDarkHex(bg);
                    muted = dark ? "#868A91" : "#6E7781";
                    border = dark ? "#3E4147" : "#D8DEE4";
                    accent = dark ? "#548AF7" : "#0969DA";
                }
                default -> {
                    bg = "#2B2D30";
                    fg = "#DFE1E5";
                    muted = "#868A91";
                    border = "#3E4147";
                    accent = "#548AF7";
                }
            }

            if (s.getCustomBgHex() != null && !s.getCustomBgHex().isBlank()) {
                bg = s.getCustomBgHex();
            }
            if (s.getCustomFgHex() != null && !s.getCustomFgHex().isBlank()) {
                fg = s.getCustomFgHex();
            }

            return new StylePalette(
                    bg,
                    fg,
                    muted,
                    border,
                    accent,
                    s.getFontFamily(),
                    s.getFontSize(),
                    s.getLineHeight(),
                    s.getHorizontalPadding(),
                    s.getDarkImageBrightness(),
                    s.getImagePrivacyMode()
            );
        }

        public boolean isDark() {
            return isDarkHex(bgHex);
        }

        private static boolean isDarkHex(String hex) {
            if (hex == null) {
                return true;
            }
            String clean = hex.startsWith("#") ? hex.substring(1) : hex;
            if (clean.length() != 6) {
                return true;
            }
            try {
                int r = Integer.parseInt(clean.substring(0, 2), 16);
                int g = Integer.parseInt(clean.substring(2, 4), 16);
                int b = Integer.parseInt(clean.substring(4, 6), 16);
                double lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
                return lum < 0.5;
            } catch (NumberFormatException e) {
                return true;
            }
        }
    }

    public static String decorateChapterHtml(String rawHtml,
                                             String bookId,
                                             String chapterEntryPath,
                                             StylePalette palette,
                                             double initialRatio,
                                             String initialAnchor,
                                             String jsBridgeCallbackTemplate) {
        Document sourceDoc = Jsoup.parse(rawHtml != null ? rawHtml : "");
        String chapterDir = EpubBook.parentDir(chapterEntryPath);
        String baseUrl = EpubVirtualResourceResolver.buildBaseUrl(bookId, chapterDir);

        // 保留原章节的 stylesheet <link> 与 <style>
        StringBuilder preservedHeadAssets = new StringBuilder();
        Elements links = sourceDoc.head().select("link[rel=stylesheet]");
        for (Element link : links) {
            preservedHeadAssets.append(link.outerHtml()).append('\n');
        }
        Elements styles = sourceDoc.head().select("style");
        for (Element style : styles) {
            preservedHeadAssets.append(style.outerHtml()).append('\n');
        }

        // 处理正文中的 <img> 及内嵌 <svg><image/></svg>，为所有图片附加低调占位胶囊，以便运行时无刷新切换三档模式
        Element body = sourceDoc.body();
        Elements imgs = body.select("img");
        for (Element img : imgs) {
            img.addClass("dl-content-img");
            appendFigurePillAfter(img);
        }
        Elements svgs = body.select("svg");
        for (Element svg : svgs) {
            if (!svg.select("image").isEmpty()) {
                svg.addClass("dl-content-svg");
                appendFigurePillAfter(svg);
            }
        }

        String bodyInnerHtml = body.html();
        String bodyImageModeClass = toImageModeClass(palette.imagePrivacyMode());
        String bridgeCallExpr = buildBridgeCallExpression(jsBridgeCallbackTemplate);

        StringBuilder html = new StringBuilder(bodyInnerHtml.length() + 4096);
        html.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n");
        html.append("<meta charset=\"UTF-8\">\n");
        html.append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'self' https://doclens.local 'unsafe-inline' data:; img-src https://doclens.local data:; style-src https://doclens.local 'unsafe-inline'; script-src 'unsafe-inline';\">\n");
        html.append("<base href=\"").append(escapeHtmlAttr(baseUrl)).append("\">\n");
        html.append(preservedHeadAssets);
        html.append("<style id=\"dl-injected-style\">\n");
        html.append(buildCoreCss(palette));
        html.append("</style>\n</head>\n");
        html.append("<body class=\"").append(bodyImageModeClass).append("\">\n");
        html.append("<main id=\"dl-reader-root\">\n");
        html.append(bodyInnerHtml);
        html.append("\n<nav class=\"dl-chapter-nav\" aria-label=\"Chapter navigation\">"
                + "<button type=\"button\" data-dl-nav=\"prev\">‹ 上一章</button>"
                + "<button type=\"button\" data-dl-nav=\"next\">下一章 ›</button>"
                + "</nav>\n</main>\n");
        html.append(buildLightboxHtml());
        html.append("<script>\n");
        html.append(buildRuntimeScript(initialRatio, initialAnchor, bridgeCallExpr));
        html.append("\n</script>\n</body>\n</html>");
        return html.toString();
    }

    public static String toImageModeClass(ImagePrivacyMode mode) {
        if (mode == null) {
            return "dl-img-muted";
        }
        return switch (mode) {
            case NORMAL -> "dl-img-normal";
            case MUTED_GRAYSCALE -> "dl-img-muted";
            case COLLAPSED_PILL -> "dl-img-collapsed";
        };
    }

    public static String buildApplyStyleCallJs(StylePalette palette) {
        String json = "{"
                + "\"bg\":\"" + escapeJs(palette.bgHex()) + "\","
                + "\"fg\":\"" + escapeJs(palette.fgHex()) + "\","
                + "\"muted\":\"" + escapeJs(palette.mutedFgHex()) + "\","
                + "\"border\":\"" + escapeJs(palette.borderHex()) + "\","
                + "\"accent\":\"" + escapeJs(palette.accentHex()) + "\","
                + "\"colorScheme\":\"" + (palette.isDark() ? "dark" : "light") + "\","
                + "\"fontFamily\":\"" + escapeJs(palette.fontFamily()) + "\","
                + "\"fontSize\":" + palette.fontSize() + ","
                + "\"lineHeight\":" + palette.lineHeight() + ","
                + "\"padding\":" + palette.horizontalPadding() + ","
                + "\"imgBrightness\":" + palette.darkImageBrightness() + ","
                + "\"imgMode\":\"" + toImageModeClass(palette.imagePrivacyMode()) + "\""
                + "}";
        return "window.__dlApplyStyle && window.__dlApplyStyle(" + json + ");";
    }

    private static String buildCoreCss(StylePalette p) {
        return """
                :root {
                  color-scheme: %s;
                  --dl-bg: %s;
                  --dl-fg: %s;
                  --dl-muted-fg: %s;
                  --dl-border: %s;
                  --dl-accent: %s;
                  --dl-font-family: %s, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                  --dl-font-size: %dpx;
                  --dl-line-height: %.2f;
                  --dl-padding: %dpx;
                  --dl-img-brightness: %.2f;
                }
                ::-webkit-scrollbar {
                  width: 6px;
                  height: 6px;
                  background: transparent;
                }
                ::-webkit-scrollbar-track,
                ::-webkit-scrollbar-corner {
                  background: transparent;
                }
                ::-webkit-scrollbar-thumb {
                  background: rgba(128, 134, 145, 0.24);
                  border-radius: 3px;
                }
                ::-webkit-scrollbar-thumb:hover {
                  background: rgba(128, 134, 145, 0.48);
                }
                html, body {
                  margin: 0;
                  padding: 0;
                  background: var(--dl-bg) !important;
                  color: var(--dl-fg) !important;
                  font-family: var(--dl-font-family) !important;
                  font-size: var(--dl-font-size) !important;
                  line-height: var(--dl-line-height) !important;
                  overflow-x: hidden;
                }
                #dl-reader-root {
                  box-sizing: border-box;
                  max-width: 920px;
                  margin: 0 auto;
                  padding: 18px var(--dl-padding) 48px var(--dl-padding);
                  word-break: break-word;
                }
                #dl-reader-root p, #dl-reader-root li, #dl-reader-root div, #dl-reader-root span {
                  color: inherit;
                  line-height: inherit;
                }
                #dl-reader-root a {
                  color: var(--dl-accent);
                  text-decoration: none;
                }
                #dl-reader-root a:hover {
                  text-decoration: underline;
                }
                #dl-reader-root pre, #dl-reader-root code {
                  font-family: "JetBrains Mono", Consolas, monospace;
                  border: 1px solid var(--dl-border);
                  border-radius: 3px;
                }
                img.dl-content-img, svg.dl-content-svg {
                  max-width: 100%%;
                  max-height: 82vh;
                  height: auto;
                  cursor: zoom-in;
                  border-radius: 3px;
                  transition: filter 0.2s ease, opacity 0.2s ease;
                }
                body.dl-img-normal img.dl-content-img,
                body.dl-img-normal svg.dl-content-svg {
                  display: inline-block;
                  filter: none;
                  opacity: 1;
                }
                body.dl-img-normal .dl-fig-pill {
                  display: none;
                }
                body.dl-img-muted img.dl-content-img,
                body.dl-img-muted svg.dl-content-svg {
                  display: inline-block;
                  filter: grayscale(100%%) brightness(var(--dl-img-brightness));
                  opacity: 0.55;
                }
                body.dl-img-muted img.dl-content-img:hover,
                body.dl-img-muted svg.dl-content-svg:hover {
                  filter: none;
                  opacity: 1;
                }
                body.dl-img-muted .dl-fig-pill {
                  display: none;
                }
                body.dl-img-collapsed img.dl-content-img:not(.dl-img-revealed),
                body.dl-img-collapsed svg.dl-content-svg:not(.dl-img-revealed) {
                  display: none;
                }
                body.dl-img-collapsed .dl-fig-pill {
                  display: inline-flex;
                  align-items: center;
                  gap: 4px;
                  padding: 2px 9px;
                  margin: 4px 2px;
                  font-size: 12px;
                  color: var(--dl-muted-fg);
                  border: 1px dashed var(--dl-border);
                  border-radius: 4px;
                  cursor: pointer;
                  user-select: none;
                }
                body.dl-img-collapsed .dl-fig-pill:hover {
                  color: var(--dl-fg);
                  border-color: var(--dl-accent);
                }
                mark.dl-hl {
                  background: rgba(255, 213, 79, 0.35);
                  color: inherit;
                  padding: 0 2px;
                  border-radius: 2px;
                }
                mark.dl-hl.dl-hl-active {
                  background: rgba(255, 160, 0, 0.72);
                  outline: 1px solid var(--dl-accent);
                }
                #dl-lightbox {
                  position: fixed;
                  inset: 0;
                  z-index: 99999;
                  display: none;
                  align-items: center;
                  justify-content: center;
                  background: rgba(12, 14, 18, 0.88);
                  overflow: hidden;
                  user-select: none;
                }
                #dl-lightbox.dl-open {
                  display: flex;
                }
                #dl-lightbox img {
                  max-width: 92vw;
                  max-height: 90vh;
                  transform-origin: center center;
                  cursor: grab;
                  box-shadow: 0 8px 32px rgba(0,0,0,0.55);
                }
                #dl-lightbox .dl-lb-hint {
                  position: fixed;
                  bottom: 12px;
                  left: 50%%;
                  transform: translateX(-50%%);
                  color: #b0b7c3;
                  font-size: 12px;
                  background: rgba(0,0,0,0.45);
                  padding: 3px 10px;
                  border-radius: 4px;
                  pointer-events: none;
                }
                .dl-chapter-nav {
                  display: flex;
                  justify-content: space-between;
                  align-items: center;
                  margin-top: 28px;
                  padding-top: 12px;
                  border-top: 1px solid var(--dl-border);
                }
                .dl-chapter-nav button {
                  background: transparent;
                  color: var(--dl-muted-fg);
                  border: 1px solid var(--dl-border);
                  border-radius: 4px;
                  padding: 4px 12px;
                  font-size: 12px;
                  cursor: pointer;
                  font-family: inherit;
                  opacity: 0.65;
                  transition: opacity 0.15s ease, border-color 0.15s ease;
                }
                .dl-chapter-nav button:hover {
                  opacity: 1;
                  color: var(--dl-fg);
                  border-color: var(--dl-muted-fg);
                }
                """.formatted(
                p.isDark() ? "dark" : "light",
                p.bgHex(),
                p.fgHex(),
                p.mutedFgHex(),
                p.borderHex(),
                p.accentHex(),
                quoteCssFont(p.fontFamily()),
                p.fontSize(),
                p.lineHeight(),
                p.horizontalPadding(),
                p.darkImageBrightness()
        );
    }

    private static String buildLightboxHtml() {
        return """
                <div id="dl-lightbox" aria-hidden="true">
                  <img id="dl-lightbox-img" alt="Figure preview" />
                  <div class="dl-lb-hint">滚轮缩放 · 拖拽平移 · 双击复位 · Esc 关闭</div>
                </div>
                """;
    }

    private static String buildBridgeCallExpression(String template) {
        if (template == null || template.isBlank()) {
            return "/* no bridge */";
        }
        return template;
    }

    private static String buildRuntimeScript(double initialRatio, String initialAnchor, String bridgeCallExpr) {
        String safeAnchor = initialAnchor != null ? escapeJs(initialAnchor) : "";
        double clampedRatio = Math.max(0.0, Math.min(1.0, initialRatio));
        return """
                (function() {
                  var initialRatio = %.6f;
                  var initialAnchor = "%s";
                  var scrollLockUntil = Date.now() + 1200;
                  var debounceTimer = null;
                  var autoScrollRaf = null;
                  var autoScrollSpeed = 0;
                  var lastAutoTs = 0;

                  function computeScrollRatio() {
                    var docEl = document.documentElement;
                    var maxScroll = Math.max(0, docEl.scrollHeight - window.innerHeight);
                    if (maxScroll <= 0) return 0;
                    return Math.max(0, Math.min(1, window.scrollY / maxScroll));
                  }

                  function extractViewportSnippet() {
                    var blocks = document.querySelectorAll('#dl-reader-root p, #dl-reader-root h1, #dl-reader-root h2, #dl-reader-root h3, #dl-reader-root li, #dl-reader-root div');
                    var vh = window.innerHeight || 600;
                    for (var i = 0; i < blocks.length; i++) {
                      var el = blocks[i];
                      var rect = el.getBoundingClientRect();
                      if (rect.bottom > 10 && rect.top < vh) {
                        var txt = (el.innerText || el.textContent || '').replace(/\\s+/g, ' ').trim();
                        if (txt.length >= 4) {
                          return txt.length > 80 ? txt.substring(0, 80) : txt;
                        }
                      }
                    }
                    var fallback = (document.body.innerText || '').replace(/\\s+/g, ' ').trim();
                    return fallback.length > 80 ? fallback.substring(0, 80) : fallback;
                  }

                  function sendBridge(payload) {
                    %s
                  }

                  function reportViewport() {
                    if (Date.now() < scrollLockUntil) return;
                    var payload = JSON.stringify({
                      ratio: computeScrollRatio(),
                      snippet: extractViewportSnippet()
                    });
                    sendBridge(payload);
                  }

                  function restorePosition() {
                    if (initialAnchor) {
                      var target = document.getElementById(initialAnchor);
                      if (target) {
                        target.scrollIntoView({ behavior: 'auto', block: 'start' });
                        return;
                      }
                    }
                    var applyRatio = function() {
                      var docEl = document.documentElement;
                      var maxScroll = Math.max(0, docEl.scrollHeight - window.innerHeight);
                      if (maxScroll > 0 && initialRatio > 0) {
                        window.scrollTo(0, Math.round(initialRatio * maxScroll));
                      }
                    };
                    applyRatio();
                    setTimeout(applyRatio, 300);
                  }

                  window.addEventListener('DOMContentLoaded', restorePosition);
                  window.addEventListener('load', restorePosition);

                  window.addEventListener('scroll', function() {
                    if (Date.now() < scrollLockUntil) return;
                    if (debounceTimer) clearTimeout(debounceTimer);
                    debounceTimer = setTimeout(reportViewport, 400);
                  }, { passive: true });

                  // 胶囊折叠点击展开/收起 & 章节导航点击
                  document.addEventListener('click', function(e) {
                    var navBtn = e.target.closest('[data-dl-nav]');
                    if (navBtn) {
                      var dir = navBtn.getAttribute('data-dl-nav');
                      if (dir === 'prev' || dir === 'next') {
                        sendBridge(JSON.stringify({ nav: dir }));
                      }
                      return;
                    }
                    var pill = e.target.closest('.dl-fig-pill');
                    if (pill) {
                      var prev = pill.previousElementSibling;
                      var prevTag = prev && prev.tagName ? prev.tagName.toUpperCase() : '';
                      if (prevTag === 'IMG' || prevTag === 'SVG') {
                        var revealed = prev.classList.toggle('dl-img-revealed');
                        pill.textContent = revealed ? '[Figure · 收起]' : '[Figure · 点击查看]';
                      }
                      return;
                    }
                    var img = e.target.closest('img.dl-content-img');
                    if (img) {
                      openLightbox(img.src);
                      return;
                    }
                    var svg = e.target.closest('svg.dl-content-svg');
                    if (svg) {
                      var svgImg = svg.querySelector('image');
                      if (svgImg) {
                        var rawHref = svgImg.getAttribute('href')
                                || svgImg.getAttribute('xlink:href')
                                || svgImg.getAttributeNS('http://www.w3.org/1999/xlink', 'href');
                        if (rawHref) {
                          try {
                            openLightbox(new URL(rawHref, document.baseURI).href);
                          } catch (err) {
                            openLightbox(rawHref);
                          }
                        }
                      }
                    }
                  });

                  // Lightbox 缩放与拖拽
                  var lb = document.getElementById('dl-lightbox');
                  var lbImg = document.getElementById('dl-lightbox-img');
                  var lbScale = 1, lbTx = 0, lbTy = 0, dragging = false, startX = 0, startY = 0;

                  function updateLbTransform() {
                    lbImg.style.transform = 'translate(' + lbTx + 'px, ' + lbTy + 'px) scale(' + lbScale + ')';
                  }

                  function openLightbox(src) {
                    if (!lb || !lbImg) return;
                    lbImg.src = src;
                    lbScale = 1; lbTx = 0; lbTy = 0;
                    updateLbTransform();
                    lb.classList.add('dl-open');
                    lb.setAttribute('aria-hidden', 'false');
                  }

                  function closeLightbox() {
                    if (!lb) return;
                    lb.classList.remove('dl-open');
                    lb.setAttribute('aria-hidden', 'true');
                  }

                  if (lb) {
                    lb.addEventListener('click', function(e) {
                      if (e.target === lb) closeLightbox();
                    });
                    lb.addEventListener('wheel', function(e) {
                      e.preventDefault();
                      var factor = e.deltaY < 0 ? 1.15 : 0.87;
                      lbScale = Math.max(0.5, Math.min(5.0, lbScale * factor));
                      updateLbTransform();
                    }, { passive: false });
                    lbImg.addEventListener('dblclick', function() {
                      lbScale = 1; lbTx = 0; lbTy = 0;
                      updateLbTransform();
                    });
                    lbImg.addEventListener('mousedown', function(e) {
                      e.preventDefault();
                      dragging = true;
                      startX = e.clientX - lbTx;
                      startY = e.clientY - lbTy;
                    });
                    window.addEventListener('mousemove', function(e) {
                      if (!dragging) return;
                      lbTx = e.clientX - startX;
                      lbTy = e.clientY - startY;
                      updateLbTransform();
                    });
                    window.addEventListener('mouseup', function() { dragging = false; });
                    window.addEventListener('keydown', function(e) {
                      if (e.key === 'Escape' && lb.classList.contains('dl-open')) {
                        closeLightbox();
                      }
                    });
                  }

                  // 对外 JS 控制接口
                  window.__dlApplyStyle = function(cfg) {
                    if (!cfg) return;
                    var r = document.documentElement.style;
                    if (cfg.colorScheme) r.setProperty('color-scheme', cfg.colorScheme);
                    if (cfg.bg) r.setProperty('--dl-bg', cfg.bg);
                    if (cfg.fg) r.setProperty('--dl-fg', cfg.fg);
                    if (cfg.muted) r.setProperty('--dl-muted-fg', cfg.muted);
                    if (cfg.border) r.setProperty('--dl-border', cfg.border);
                    if (cfg.accent) r.setProperty('--dl-accent', cfg.accent);
                    if (cfg.fontFamily) r.setProperty('--dl-font-family', cfg.fontFamily + ', sans-serif');
                    if (cfg.fontSize) r.setProperty('--dl-font-size', cfg.fontSize + 'px');
                    if (cfg.lineHeight) r.setProperty('--dl-line-height', String(cfg.lineHeight));
                    if (cfg.padding) r.setProperty('--dl-padding', cfg.padding + 'px');
                    if (cfg.imgBrightness) r.setProperty('--dl-img-brightness', String(cfg.imgBrightness));
                    if (cfg.imgMode) {
                      document.body.classList.remove('dl-img-normal', 'dl-img-muted', 'dl-img-collapsed');
                      document.body.classList.add(cfg.imgMode);
                    }
                  };

                  window.__dlClearHighlight = function() {
                    var marks = document.querySelectorAll('mark.dl-hl');
                    for (var i = 0; i < marks.length; i++) {
                      var m = marks[i];
                      var p = m.parentNode;
                      if (p) {
                        p.replaceChild(document.createTextNode(m.textContent), m);
                        p.normalize();
                      }
                    }
                  };

                  window.__dlHighlight = function(keyword, caseSensitive, targetOrdinal) {
                    window.__dlClearHighlight();
                    if (!keyword) return 0;
                    var root = document.getElementById('dl-reader-root');
                    if (!root) return 0;
                    var flags = caseSensitive ? 'g' : 'gi';
                    var escaped = keyword.replace(/[.*+?^${}()|[\\]\\\\]/g, '\\\\$&');
                    var regex = new RegExp(escaped, flags);
                    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
                    var textNodes = [];
                    var node;
                    while ((node = walker.nextNode())) {
                      if (node.parentElement && (node.parentElement.tagName === 'SCRIPT' || node.parentElement.tagName === 'STYLE')) continue;
                      if (node.nodeValue && regex.test(node.nodeValue)) {
                        textNodes.push(node);
                      }
                      regex.lastIndex = 0;
                    }
                    var count = 0;
                    for (var i = 0; i < textNodes.length; i++) {
                      var tn = textNodes[i];
                      var text = tn.nodeValue;
                      var frag = document.createDocumentFragment();
                      var lastIdx = 0;
                      regex.lastIndex = 0;
                      var match;
                      while ((match = regex.exec(text)) !== null) {
                        if (match.index > lastIdx) {
                          frag.appendChild(document.createTextNode(text.substring(lastIdx, match.index)));
                        }
                        var mark = document.createElement('mark');
                        mark.className = 'dl-hl' + (count === targetOrdinal ? ' dl-hl-active' : '');
                        mark.textContent = match[0];
                        frag.appendChild(mark);
                        count++;
                        lastIdx = match.index + match[0].length;
                        if (match[0].length === 0) break;
                      }
                      if (lastIdx < text.length) {
                        frag.appendChild(document.createTextNode(text.substring(lastIdx)));
                      }
                      tn.parentNode.replaceChild(frag, tn);
                    }
                    if (targetOrdinal >= 0) {
                      var active = document.querySelector('mark.dl-hl.dl-hl-active');
                      if (active) {
                        active.scrollIntoView({ behavior: 'smooth', block: 'center' });
                      }
                    }
                    return count;
                  };

                  window.__dlAutoScroll = function(pxPerSec) {
                    autoScrollSpeed = Number(pxPerSec) || 0;
                    if (autoScrollSpeed <= 0) {
                      if (autoScrollRaf) cancelAnimationFrame(autoScrollRaf);
                      autoScrollRaf = null;
                      return;
                    }
                    lastAutoTs = performance.now();
                    function step(now) {
                      if (autoScrollSpeed <= 0) return;
                      var dt = (now - lastAutoTs) / 1000.0;
                      lastAutoTs = now;
                      window.scrollBy(0, autoScrollSpeed * dt);
                      autoScrollRaf = requestAnimationFrame(step);
                    }
                    if (!autoScrollRaf) {
                      autoScrollRaf = requestAnimationFrame(step);
                    }
                  };

                  window.addEventListener('keydown', function(e) {
                    if (e.ctrlKey || e.metaKey || e.altKey) return;
                    var t = e.target;
                    var tag = t && t.tagName ? t.tagName.toUpperCase() : '';
                    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || (t && t.isContentEditable)) return;
                    if (lb && lb.classList.contains('dl-open')) return;
                    if (e.key === '[') {
                      e.preventDefault();
                      sendBridge(JSON.stringify({ nav: 'prev' }));
                    } else if (e.key === ']') {
                      e.preventDefault();
                      sendBridge(JSON.stringify({ nav: 'next' }));
                    } else if (e.key === 'j') {
                      e.preventDefault();
                      window.scrollBy({ top: 120, behavior: 'smooth' });
                    } else if (e.key === 'k') {
                      e.preventDefault();
                      window.scrollBy({ top: -120, behavior: 'smooth' });
                    }
                  });
                })();
                """.formatted(clampedRatio, safeAnchor, bridgeCallExpr);
    }

    private static void appendFigurePillAfter(Element target) {
        if (target.nextElementSibling() == null || !target.nextElementSibling().hasClass("dl-fig-pill")) {
            Element pill = new Element("span");
            pill.addClass("dl-fig-pill");
            pill.attr("role", "button");
            pill.attr("tabindex", "0");
            pill.text("[Figure · 点击查看]");
            target.after(pill);
        }
    }

    private static String quoteCssFont(String fontFamily) {
        if (fontFamily == null || fontFamily.isBlank()) {
            return "\"Microsoft YaHei\"";
        }
        String trimmed = fontFamily.trim();
        if (trimmed.contains(",") || trimmed.startsWith("\"") || trimmed.startsWith("'")) {
            return trimmed;
        }
        return "\"" + trimmed.replace("\"", "") + "\"";
    }

    private static String escapeHtmlAttr(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String escapeJs(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }
}
