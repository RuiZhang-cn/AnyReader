package dev.local.docinspector.epub;

import dev.local.docinspector.model.BookRecord;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * EPUB2/3 解析器：container.xml -> OPF -> Spine -> EPUB3 nav.xhtml / EPUB2 toc.ncx / Spine 回退。
 */
public final class EpubBookParser {

    private static final int BUFFER_SIZE = 8192;

    private EpubBookParser() {
    }

    public static EpubBook parse(File epubFile) throws IOException {
        String bookId = epubFile != null
                ? BookRecord.computeBookId(epubFile.toPath().toAbsolutePath().normalize().toString())
                : "";
        return parse(epubFile, bookId);
    }

    public static EpubBook parse(File epubFile, String bookId) throws IOException {
        if (epubFile == null || !epubFile.isFile()) {
            throw new IOException("EPUB file does not exist: " + epubFile);
        }

        ZipFile zipFile = new ZipFile(epubFile, StandardCharsets.UTF_8);
        try {
            String opfPath = locateOpfPath(zipFile);
            String opfDir = EpubBook.parentDir(opfPath);
            String opfXml = readEntryText(zipFile, opfPath);
            Document opfDoc = Jsoup.parse(opfXml, "", Parser.xmlParser());

            String title = extractMetadataText(opfDoc, "dc|title, title");
            if (title.isBlank()) {
                title = stripExtension(epubFile.getName());
            }
            String creator = extractMetadataText(opfDoc, "dc|creator, creator");

            Map<String, ManifestItem> manifestById = new LinkedHashMap<>();
            Map<String, String> manifestMimeTypes = new HashMap<>();
            ManifestItem navItem = null;

            for (Element item : opfDoc.select("manifest > item")) {
                String id = item.attr("id").trim();
                String href = decodeHref(item.attr("href").trim());
                String mediaType = item.attr("media-type").trim();
                String properties = item.attr("properties").trim();
                if (id.isEmpty() || href.isEmpty()) {
                    continue;
                }
                String normalizedPath = EpubBook.resolveRelativeEntryPath(opfDir, href);
                ManifestItem mi = new ManifestItem(id, normalizedPath, mediaType, properties);
                manifestById.put(id, mi);
                manifestMimeTypes.put(normalizedPath, mediaType);
                if (hasPropertyToken(properties, "nav")) {
                    navItem = mi;
                }
            }

            Element spineEl = opfDoc.selectFirst("spine");
            List<SpineChapter> spineChapters = new ArrayList<>();
            Map<String, Integer> pathToSpineIndex = new HashMap<>();

            if (spineEl != null) {
                int idx = 0;
                for (Element itemref : spineEl.select("> itemref")) {
                    String idref = itemref.attr("idref").trim();
                    ManifestItem mi = manifestById.get(idref);
                    if (mi == null) {
                        continue;
                    }
                    String defaultTitle = "Section " + (idx + 1);
                    SpineChapter chapter = new SpineChapter(idx, idref, mi.normalizedPath, defaultTitle, mi.mediaType);
                    spineChapters.add(chapter);
                    pathToSpineIndex.putIfAbsent(mi.normalizedPath, idx);
                    idx++;
                }
            }

            // 解析目录：优先 EPUB3 nav.xhtml，其次 EPUB2 toc.ncx，最终回退 Spine
            List<TocItem> tocItems = List.of();
            if (navItem != null) {
                tocItems = parseEpub3Nav(zipFile, navItem.normalizedPath, pathToSpineIndex);
            }
            if (tocItems.isEmpty() && spineEl != null) {
                String tocId = spineEl.attr("toc").trim();
                ManifestItem ncxItem = !tocId.isEmpty() ? manifestById.get(tocId) : findNcxFallback(manifestById);
                if (ncxItem != null) {
                    tocItems = parseEpub2Ncx(zipFile, ncxItem.normalizedPath, pathToSpineIndex);
                }
            }
            if (tocItems.isEmpty()) {
                tocItems = buildSpineFallbackToc(spineChapters);
            } else {
                enrichSpineTitlesFromToc(tocItems, spineChapters);
            }

            return new EpubBook(
                    zipFile,
                    epubFile,
                    bookId,
                    title,
                    creator,
                    opfDir,
                    spineChapters,
                    tocItems,
                    manifestMimeTypes,
                    pathToSpineIndex
            );
        } catch (RuntimeException | IOException e) {
            try {
                zipFile.close();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            if (e instanceof IOException io) {
                throw io;
            }
            throw new IOException("Failed to parse EPUB: " + epubFile.getName(), e);
        }
    }

    private static String locateOpfPath(ZipFile zipFile) throws IOException {
        String containerXml = readEntryText(zipFile, "META-INF/container.xml");
        Document containerDoc = Jsoup.parse(containerXml, "", Parser.xmlParser());
        Element rootfile = containerDoc.selectFirst("rootfile[full-path]");
        if (rootfile == null) {
            throw new IOException("Missing <rootfile full-path> in META-INF/container.xml");
        }
        String fullPath = rootfile.attr("full-path").trim();
        return EpubBook.validateEntryPath(fullPath);
    }

    private static List<TocItem> parseEpub3Nav(ZipFile zipFile,
                                               String navPath,
                                               Map<String, Integer> pathToSpineIndex) {
        try {
            String navHtml = readEntryText(zipFile, navPath);
            Document doc = Jsoup.parse(navHtml, "", Parser.xmlParser());
            Element tocNav = findTocNavElement(doc);
            if (tocNav == null) {
                return List.of();
            }
            Element rootOl = tocNav.selectFirst("> ol");
            if (rootOl == null) {
                rootOl = tocNav.selectFirst("ol");
            }
            if (rootOl == null) {
                return List.of();
            }
            String navDir = EpubBook.parentDir(navPath);
            return parseNavOl(rootOl, navDir, pathToSpineIndex);
        } catch (IOException e) {
            return List.of();
        }
    }

    private static Element findTocNavElement(Document doc) {
        for (Element nav : doc.select("nav")) {
            String epubType = nav.attr("epub:type");
            String type = nav.attr("type");
            if (containsWord(epubType, "toc") || containsWord(type, "toc")) {
                return nav;
            }
        }
        return doc.selectFirst("nav");
    }

    private static List<TocItem> parseNavOl(Element ol,
                                            String navDir,
                                            Map<String, Integer> pathToSpineIndex) {
        List<TocItem> items = new ArrayList<>();
        for (Element li : ol.select("> li")) {
            Element link = li.selectFirst("> a[href]");
            Element labelSpan = link == null ? li.selectFirst("> span") : null;
            String title = link != null ? link.text() : (labelSpan != null ? labelSpan.text() : "");
            String rawHref = link != null ? decodeHref(link.attr("href").trim()) : "";

            String targetPath = "";
            String fragment = "";
            int spineIdx = -1;
            if (!rawHref.isEmpty()) {
                int hash = rawHref.indexOf('#');
                String pathPart = hash >= 0 ? rawHref.substring(0, hash) : rawHref;
                fragment = hash >= 0 ? rawHref.substring(hash + 1) : "";
                targetPath = pathPart.isEmpty()
                        ? ""
                        : EpubBook.resolveRelativeEntryPath(navDir, pathPart);
                spineIdx = lookupSpineIndex(targetPath, pathToSpineIndex);
            }

            Element childOl = li.selectFirst("> ol");
            List<TocItem> children = childOl != null
                    ? parseNavOl(childOl, navDir, pathToSpineIndex)
                    : List.of();
            if (!title.isBlank() || !children.isEmpty()) {
                items.add(new TocItem(title.isBlank() ? "Untitled" : title, targetPath, fragment, spineIdx, children));
            }
        }
        return items;
    }

    private static List<TocItem> parseEpub2Ncx(ZipFile zipFile,
                                               String ncxPath,
                                               Map<String, Integer> pathToSpineIndex) {
        try {
            String ncxXml = readEntryText(zipFile, ncxPath);
            Document doc = Jsoup.parse(ncxXml, "", Parser.xmlParser());
            Element navMap = doc.selectFirst("navMap");
            if (navMap == null) {
                return List.of();
            }
            String ncxDir = EpubBook.parentDir(ncxPath);
            return parseNavPoints(navMap.select("> navPoint"), ncxDir, pathToSpineIndex);
        } catch (IOException e) {
            return List.of();
        }
    }

    private static List<TocItem> parseNavPoints(Elements navPoints,
                                                String ncxDir,
                                                Map<String, Integer> pathToSpineIndex) {
        List<TocItem> items = new ArrayList<>();
        for (Element np : navPoints) {
            Element textEl = np.selectFirst("> navLabel > text");
            String title = textEl != null ? textEl.text() : "";
            Element contentEl = np.selectFirst("> content[src]");
            String rawSrc = contentEl != null ? decodeHref(contentEl.attr("src").trim()) : "";

            String targetPath = "";
            String fragment = "";
            int spineIdx = -1;
            if (!rawSrc.isEmpty()) {
                int hash = rawSrc.indexOf('#');
                String pathPart = hash >= 0 ? rawSrc.substring(0, hash) : rawSrc;
                fragment = hash >= 0 ? rawSrc.substring(hash + 1) : "";
                targetPath = pathPart.isEmpty()
                        ? ""
                        : EpubBook.resolveRelativeEntryPath(ncxDir, pathPart);
                spineIdx = lookupSpineIndex(targetPath, pathToSpineIndex);
            }

            List<TocItem> children = parseNavPoints(np.select("> navPoint"), ncxDir, pathToSpineIndex);
            if (!title.isBlank() || !children.isEmpty()) {
                items.add(new TocItem(title.isBlank() ? "Untitled" : title, targetPath, fragment, spineIdx, children));
            }
        }
        return items;
    }

    private static List<TocItem> buildSpineFallbackToc(List<SpineChapter> spineChapters) {
        List<TocItem> fallback = new ArrayList<>(spineChapters.size());
        for (SpineChapter ch : spineChapters) {
            fallback.add(new TocItem(
                    ch.getTitle(),
                    ch.getNormalizedEntryPath(),
                    "",
                    ch.getSpineIndex(),
                    List.of()
            ));
        }
        return fallback;
    }

    private static void enrichSpineTitlesFromToc(List<TocItem> items, List<SpineChapter> spineChapters) {
        for (TocItem item : items) {
            int idx = item.getSpineIndex();
            if (idx >= 0 && idx < spineChapters.size() && !item.getTitle().isBlank()) {
                SpineChapter ch = spineChapters.get(idx);
                if (ch.getTitle().startsWith("Section ")) {
                    ch.setTitle(item.getTitle());
                }
            }
            if (!item.getChildren().isEmpty()) {
                enrichSpineTitlesFromToc(item.getChildren(), spineChapters);
            }
        }
    }

    private static int lookupSpineIndex(String targetPath, Map<String, Integer> pathToSpineIndex) {
        if (targetPath == null || targetPath.isEmpty()) {
            return -1;
        }
        Integer idx = pathToSpineIndex.get(targetPath);
        if (idx != null) {
            return idx;
        }
        String lower = targetPath.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Integer> entry : pathToSpineIndex.entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).equals(lower)) {
                return entry.getValue();
            }
        }
        return -1;
    }

    private static ManifestItem findNcxFallback(Map<String, ManifestItem> manifestById) {
        for (ManifestItem item : manifestById.values()) {
            if ("application/x-dtbncx+xml".equalsIgnoreCase(item.mediaType)
                    || item.normalizedPath.toLowerCase(Locale.ROOT).endsWith(".ncx")) {
                return item;
            }
        }
        return null;
    }

    private static String extractMetadataText(Document opfDoc, String cssQuery) {
        Element el = opfDoc.selectFirst("metadata " + cssQuery);
        return el != null ? el.text().trim() : "";
    }

    private static boolean hasPropertyToken(String properties, String token) {
        if (properties == null || properties.isBlank()) {
            return false;
        }
        for (String p : properties.trim().split("\\s+")) {
            if (token.equalsIgnoreCase(p)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsWord(String text, String word) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String token : text.trim().split("\\s+")) {
            if (word.equalsIgnoreCase(token)) {
                return true;
            }
        }
        return false;
    }

    private static String decodeHref(String href) {
        try {
            return URLDecoder.decode(href, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return href;
        }
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String readEntryText(ZipFile zipFile, String entryPath) throws IOException {
        ZipEntry entry = zipFile.getEntry(entryPath);
        if (entry == null) {
            throw new IOException("Missing required EPUB entry: " + entryPath);
        }
        try (InputStream in = zipFile.getInputStream(entry);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            return CharsetSniffer.decode(out.toByteArray());
        }
    }

    private record ManifestItem(String id, String normalizedPath, String mediaType, String properties) {
    }
}
