package dev.local.docinspector.epub;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefCallback;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.handler.CefResourceHandler;
import org.cef.handler.CefResourceHandlerAdapter;
import org.cef.handler.CefResourceRequestHandler;
import org.cef.handler.CefResourceRequestHandlerAdapter;
import org.cef.misc.BoolRef;
import org.cef.misc.IntRef;
import org.cef.misc.StringRef;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * JCEF 内存虚拟协议处理器：
 * 仅放行 {@code https://doclens.local/<bookId>/...}，按需从 {@link EpubBook} 流式输出章节 HTML 与静态资源，
 * 严厉阻断一切外网 http/https 与越权路径请求。
 */
public final class EpubCefResourceHandler extends CefRequestHandlerAdapter {

    public interface ChapterHtmlProvider {
        String renderChapterHtml(int spineIndex);
    }

    public interface InternalLinkHandler {
        void onInternalLink(int spineIndex, String fragmentId);
    }

    private final Supplier<EpubBook> bookSupplier;
    private final Supplier<Integer> currentSpineSupplier;
    private final ChapterHtmlProvider chapterHtmlProvider;
    private final InternalLinkHandler internalLinkHandler;

    public EpubCefResourceHandler(Supplier<EpubBook> bookSupplier,
                                  Supplier<Integer> currentSpineSupplier,
                                  ChapterHtmlProvider chapterHtmlProvider,
                                  InternalLinkHandler internalLinkHandler) {
        this.bookSupplier = bookSupplier;
        this.currentSpineSupplier = currentSpineSupplier;
        this.chapterHtmlProvider = chapterHtmlProvider;
        this.internalLinkHandler = internalLinkHandler;
    }

    @Override
    public boolean onBeforeBrowse(CefBrowser browser,
                                  CefFrame frame,
                                  CefRequest request,
                                  boolean userGesture,
                                  boolean isRedirect) {
        String url = request != null ? request.getURL() : "";
        if (!EpubVirtualResourceResolver.isAllowedNavigationUrl(url)) {
            return true; // 阻断一切外网与非虚拟域名导航
        }
        if ("about:blank".equalsIgnoreCase(url.trim())) {
            return false;
        }

        EpubBook book = bookSupplier.get();
        if (book == null) {
            return true;
        }
        int curSpine = currentSpineSupplier.get() != null ? currentSpineSupplier.get() : 0;
        EpubVirtualResourceResolver.ResolutionResult res =
                EpubVirtualResourceResolver.resolve(url, book.getBookId(), book, curSpine);

        if (!res.isAllowed()) {
            return true;
        }
        // 若用户在正文点击同书章节链接，转为内部受控切章（保持注入桥接与状态一致）
        if (res.kind() == EpubVirtualResourceResolver.RouteKind.ALLOWED_STATIC_RESOURCE
                && res.spineIndex() >= 0
                && internalLinkHandler != null) {
            internalLinkHandler.onInternalLink(res.spineIndex(), res.fragmentId());
            return true;
        }
        return false;
    }

    @Override
    public CefResourceRequestHandler getResourceRequestHandler(CefBrowser browser,
                                                               CefFrame frame,
                                                               CefRequest request,
                                                               boolean isNavigation,
                                                               boolean isDownload,
                                                               String requestInitiator,
                                                               BoolRef disableDefaultHandling) {
        return new CefResourceRequestHandlerAdapter() {
            @Override
            public boolean onBeforeResourceLoad(CefBrowser b, CefFrame f, CefRequest req) {
                String url = req != null ? req.getURL() : "";
                if ("about:blank".equalsIgnoreCase(url.trim())) {
                    return false;
                }
                if (!EpubVirtualResourceResolver.isVirtualOriginUrl(url)) {
                    return true; // 严厉阻断外部 http/https 资源加载
                }
                return false;
            }

            @Override
            public CefResourceHandler getResourceHandler(CefBrowser b, CefFrame f, CefRequest req) {
                String url = req != null ? req.getURL() : "";
                EpubBook book = bookSupplier.get();
                if (book == null) {
                    return new EmptyStatusResourceHandler(404, "text/plain");
                }
                int curSpine = currentSpineSupplier.get() != null ? currentSpineSupplier.get() : 0;
                EpubVirtualResourceResolver.ResolutionResult res =
                        EpubVirtualResourceResolver.resolve(url, book.getBookId(), book, curSpine);

                if (!res.isAllowed()) {
                    return new EmptyStatusResourceHandler(403, "text/plain");
                }

                if (res.kind() == EpubVirtualResourceResolver.RouteKind.ALLOWED_VIRTUAL_CHAPTER) {
                    String html = chapterHtmlProvider.renderChapterHtml(res.spineIndex());
                    byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
                    return new InMemoryResourceHandler(bytes, "text/html");
                }

                try {
                    EpubBook.ResourceData data = book.readResource(res.normalizedEntryPath());
                    return new InMemoryResourceHandler(data.getBytes(), data.getMimeType());
                } catch (IOException | RuntimeException e) {
                    return new EmptyStatusResourceHandler(404, "text/plain");
                }
            }
        };
    }

    /**
     * CefResponse.setMimeType 只接受纯媒体类型 (type/subtype)，携带分号参数会导致 Chromium 回退按纯文本渲染。
     */
    public static String sanitizeBareMimeType(String rawMime) {
        if (rawMime == null || rawMime.isBlank()) {
            return "application/octet-stream";
        }
        int semi = rawMime.indexOf(';');
        String bare = (semi >= 0 ? rawMime.substring(0, semi) : rawMime).trim().toLowerCase(Locale.ROOT);
        return bare.isEmpty() ? "application/octet-stream" : bare;
    }

    private static void applyMimeAndCharsetHeaders(CefResponse response, String rawMime) {
        String bareMime = sanitizeBareMimeType(rawMime);
        response.setMimeType(bareMime);
        if (bareMime.startsWith("text/")
                || "application/xml".equals(bareMime)
                || "application/xhtml+xml".equals(bareMime)) {
            response.setHeaderByName("Content-Type", bareMime + "; charset=utf-8", true);
        }
    }

    private static final class InMemoryResourceHandler extends CefResourceHandlerAdapter {
        private final byte[] bytes;
        private final String mimeType;
        private int offset = 0;

        InMemoryResourceHandler(byte[] bytes, String mimeType) {
            this.bytes = bytes != null ? bytes : new byte[0];
            this.mimeType = sanitizeBareMimeType(mimeType);
        }

        @Override
        public boolean processRequest(CefRequest request, CefCallback callback) {
            callback.Continue();
            return true;
        }

        @Override
        public void getResponseHeaders(CefResponse response, IntRef responseLength, StringRef redirectUrl) {
            response.setStatus(200);
            applyMimeAndCharsetHeaders(response, mimeType);
            responseLength.set(bytes.length);
        }

        @Override
        public boolean readResponse(byte[] dataOut, int bytesToRead, IntRef bytesRead, CefCallback callback) {
            if (offset >= bytes.length) {
                bytesRead.set(0);
                return false;
            }
            int toCopy = Math.min(bytesToRead, bytes.length - offset);
            System.arraycopy(bytes, offset, dataOut, 0, toCopy);
            offset += toCopy;
            bytesRead.set(toCopy);
            return true;
        }
    }

    private static final class EmptyStatusResourceHandler extends CefResourceHandlerAdapter {
        private final int status;
        private final String mimeType;

        EmptyStatusResourceHandler(int status, String mimeType) {
            this.status = status;
            this.mimeType = sanitizeBareMimeType(mimeType);
        }

        @Override
        public boolean processRequest(CefRequest request, CefCallback callback) {
            callback.Continue();
            return true;
        }

        @Override
        public void getResponseHeaders(CefResponse response, IntRef responseLength, StringRef redirectUrl) {
            response.setStatus(status);
            applyMimeAndCharsetHeaders(response, mimeType);
            responseLength.set(0);
        }

        @Override
        public boolean readResponse(byte[] dataOut, int bytesToRead, IntRef bytesRead, CefCallback callback) {
            bytesRead.set(0);
            return false;
        }
    }
}
