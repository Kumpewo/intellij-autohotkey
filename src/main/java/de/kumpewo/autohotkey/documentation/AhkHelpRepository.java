package de.kumpewo.autohotkey.documentation;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Help pages. Which identifiers have help is defined by two lookup files under {@code /help/lookup/}:
 * {@code docs.txt} (the help shipped with AutoHotkey) and {@code extra.txt} (own pages, e.g. for the pragmas).
 * Both have the same format, one entry per line:
 * <pre>word&lt;TAB&gt;path&lt;TAB&gt;anchor[&lt;TAB&gt;class]</pre>
 * <ul>
 *   <li>{@code word}: the identifier as written in the code ({@code MsgBox}, {@code #Include},
 *       {@code Gui.Button}); case does not matter.</li>
 *   <li>{@code path}: page relative to {@code /help/} ({@code /docs/lib/Gui.htm}); loaded in lower case.</li>
 *   <li>{@code anchor}: {@code id} of a direct child of {@code <body>}, or {@code <null>} for the whole page.
 *       The popup removes everything between {@code <body>} and that element.</li>
 *   <li>{@code class} (optional): for a word with several entries ({@code Add} of {@code Gui}, {@code Menu} ...)
 *       the class the entry belongs to; used to narrow the entries by the type left of the dot.</li>
 * </ul>
 * {@code docs.txt} is searched first; {@code extra.txt} only if the word is not in {@code docs.txt}. The external
 * key ({@code path} or {@code path#anchor}) names the topic in the AutoHotkey.chm (docs.txt) or in the plugin's
 * {@code /help/extra.chm} (extra.txt, see {@link AhkExternalHelp#extraChm}). Empty
 * lines and lines starting with {@code ;} are ignored; malformed lines and entries whose page does not exist are
 * logged and ignored.
 * <p>
 * Links inside the pages name files in any case; inside the JAR the case matters. Therefore a linked path is tried
 * as given, with a lower-case file name, and completely in lower case.
 */
public final class AhkHelpRepository {

    /**
     * An entry of the lookup files: word, path as in the file ({@code /docs/lib/File.htm}), anchor (or
     * {@code null}), class (or {@code null}) and whether it comes from {@code docs.txt} (external help in the
     * AutoHotkey.chm) or from {@code extra.txt} (external help in the plugin's own {@code extra.chm}).
     */
    public record Topic(@NotNull String name, @NotNull String path, @Nullable String anchor,
                        @Nullable String className, boolean fromDocs) {

        /** Path of the page under {@code /help/} in lower case, without leading {@code /} ({@code docs/lib/file.htm}). */
        public @NotNull String page() {
            String page = normalize(path.toLowerCase(Locale.ROOT));
            return page == null ? "" : page;
        }

        /**
         * Key for the external viewer: {@code path} or {@code path#anchor} as in the lookup file
         * ({@code /docs/lib/File.htm#Close}, {@code /extra/declare-type.htm}); the topic inside the CHM file.
         */
        public @NotNull String externalKey() {
            return anchor == null ? path : path + "#" + anchor;
        }

        /** Target of a link in the overview page: {@code page} or {@code page#anchor}. */
        public @NotNull String link() {
            return anchor == null ? page() : page() + "#" + anchor;
        }

        /** Text in choosers: {@code Gui.Add} with a class, otherwise the name with the page ({@code Add (Gui.htm#Add)}). */
        public @NotNull String label() {
            if (className != null) return className + "." + name;
            String file = path.substring(path.lastIndexOf('/') + 1);
            return name + " (" + file + (anchor == null ? "" : "#" + anchor) + ")";
        }
    }

    /** Value of the anchor column for "whole page". */
    private static final String NO_ANCHOR = "<null>";

    private static final ConcurrentHashMap<String, Optional<String>> CACHE = new ConcurrentHashMap<>();
    private static final Pattern HREF =
            Pattern.compile("href\\s*=\\s*([\"'])(.*?)\\1", Pattern.CASE_INSENSITIVE);

    private AhkHelpRepository() {}

    /** Key for the lookup: the word in lower case ({@code #Include} = {@code #include}). */
    public static @NotNull String listKey(@NotNull String identifier) {
        return identifier.toLowerCase(Locale.ROOT);
    }

    /**
     * All entries for the identifier ({@code MsgBox}, {@code #Include}, {@code Gui.Button}) in file order: those
     * from docs.txt, or if there are none, those from extra.txt; empty = no help.
     */
    public static @NotNull List<Topic> topics(@NotNull String identifier) {
        return Index.current().lookup(listKey(identifier));
    }

    /** First entry for the identifier or {@code null}. */
    public static @Nullable Topic topic(@NotNull String identifier) {
        List<Topic> topics = topics(identifier);
        return topics.isEmpty() ? null : topics.get(0);
    }

    /** Prepared help page of the first entry for the identifier, or {@code null} if there is none. */
    public static @Nullable String getHelp(@NotNull String identifier) {
        Topic topic = topic(identifier);
        return topic == null ? null : getTopicPage(topic);
    }

    /** Prepared page of an entry; with an anchor, from the anchor on. */
    public static @Nullable String getTopicPage(@NotNull Topic topic) {
        String html = getPage(topic.page());
        return html == null ? null : fromAnchor(html, topic.anchor());
    }

    /**
     * Overview page for several entries: one link per entry ({@code psi_element://page#anchor}); a click shows the
     * page in the same popup.
     */
    public static @NotNull String overview(@NotNull String name, @NotNull List<Topic> topics) {
        StringBuilder html = new StringBuilder("<html><head></head><body><h1>")
                .append(escape(name)).append("</h1><p>")
                .append(escape(de.kumpewo.autohotkey.AhkBundle.message("help.variants.intro"))).append("</p><ul>");
        for (Topic topic : topics) {
            html.append("<li><a href=\"psi_element://").append(escape(topic.link())).append("\">")
                    .append(escape(topic.label())).append("</a></li>");
        }
        return withStyle(html.append("</ul></body></html>").toString());
    }

    /** Escapes text for HTML. */
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * Removes everything between the {@code <body>} tag and the element with {@code id} = anchor (for links from
     * other pages also {@code name} = anchor); without anchor or without a match the whole page.
     */
    public static @NotNull String fromAnchor(@NotNull String html, @Nullable String anchor) {
        if (anchor == null || anchor.isEmpty()) return html;
        Matcher m = anchorPattern("id", anchor).matcher(html);
        if (!m.find()) {
            m = anchorPattern("name", anchor).matcher(html);
            if (!m.find()) return html;
        }
        int bodyStart = html.toLowerCase(Locale.ROOT).indexOf("<body");
        int bodyEnd = bodyStart >= 0 ? html.indexOf('>', bodyStart) + 1 : 0;
        if (bodyEnd <= 0 || m.start() < bodyEnd) return html;
        return html.substring(0, bodyEnd) + html.substring(m.start());
    }

    /** Start tag with the attribute {@code attribute} = anchor. */
    private static Pattern anchorPattern(String attribute, String anchor) {
        return Pattern.compile("<[a-z][a-z0-9]*\\s+[^>]*\\b" + attribute + "\\s*=\\s*[\"']" + Pattern.quote(anchor)
                + "[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
    }

    /** Prepared page for the path under {@code /help/} (e.g. {@code docs/misc/WinTitle.htm}) or {@code null}. */
    public static @Nullable String getPage(@NotNull String path) {
        String normalized = normalize(path);
        if (normalized == null) return null;
        return CACHE.computeIfAbsent(normalized.toLowerCase(Locale.ROOT),
                k -> Optional.ofNullable(load(normalized))).orElse(null);
    }

    /** Reads the first existing spelling of the page and prepares it (style, links). */
    private static @Nullable String load(@NotNull String path) {
        for (String candidate : candidates(path)) {
            try (InputStream in = AhkHelpRepository.class.getResourceAsStream("/help/" + candidate)) {
                if (in == null) continue;
                String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return rewriteLinks(withStyle(withoutClutter(html)), linkBase(candidate));
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Removes what the popup does not need and what the IDE could show as text: the {@code <!DOCTYPE>},
     * {@code <title>}, {@code <meta>} and {@code <script>} elements; {@code <html lang="en">} becomes
     * {@code <html>}, so that the page starts with {@code <html>} and is taken as a complete document (otherwise the
     * IDE showed the title and the embedded CSS as text, e.g. for {@code Menu.Add}).
     */
    static String withoutClutter(String html) {
        html = CLUTTER.matcher(html).replaceAll("");
        return HTML_START.matcher(html).replaceFirst("<html>").strip();
    }

    private static final Pattern CLUTTER = Pattern.compile(
            "<!DOCTYPE[^>]*>|<title\\b.*?</title\\s*>|<meta\\b[^>]*>|<script\\b.*?</script\\s*>[ \\t]*\\r?\\n?",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HTML_START = Pattern.compile("<html\\b[^>]*>", Pattern.CASE_INSENSITIVE);

    /** Path as given, with a lower-case file name, completely lower case (without duplicates). */
    private static Set<String> candidates(@NotNull String path) {
        Set<String> result = new LinkedHashSet<>();
        result.add(path);
        int slash = path.lastIndexOf('/');
        result.add(path.substring(0, slash + 1) + path.substring(slash + 1).toLowerCase(Locale.ROOT));
        result.add(path.toLowerCase(Locale.ROOT));
        return result;
    }

    /** Directory of the page (with a trailing {@code /}), against which its relative links are resolved. */
    private static String linkBase(@NotNull String page) {
        return page.substring(0, page.lastIndexOf('/') + 1);
    }

    /**
     * Path without {@code .} and {@code ..}, separator {@code /}, without leading {@code /}; {@code null} if it
     * leads outside {@code /help/} or is empty.
     */
    static @Nullable String normalize(@NotNull String path) {
        Deque<String> parts = new ArrayDeque<>();
        for (String part : path.replace('\\', '/').split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (parts.isEmpty()) return null;
                parts.removeLast();
            } else {
                parts.addLast(part);
            }
        }
        return parts.isEmpty() ? null : String.join("/", parts);
    }

    /**
     * Replaces all links to stylesheets ({@code <link rel="stylesheet">}) with a {@code <style>} block containing
     * {@link Style#CSS}. The popup has no base URL and could not load the files anyway.
     */
    public static String withStyle(String html) {
        html = STYLESHEET_LINK.matcher(html).replaceAll("");
        String style = "<style>\n" + Style.CSS + "\n</style>\n";
        int head = html.toLowerCase(Locale.ROOT).indexOf("</head>");
        if (head >= 0) return html.substring(0, head) + style + html.substring(head);
        return style + html;
    }

    private static final Pattern STYLESHEET_LINK = Pattern.compile(
            "<link\\b[^>]*\\brel\\s*=\\s*[\"']?stylesheet[\"']?[^>]*>[ \\t]*\\r?\\n?", Pattern.CASE_INSENSITIVE);

    /**
     * Merged CSS from {@code help/styles/highlighter/highlighter.css}, {@code highlighter/light.css},
     * {@code content.css} and {@code theme.css} (a copy of the styles of the AutoHotkey help, so that
     * {@code help/docs/} can be replaced by a new version of the AutoHotkey help at any time)
     * (order as after resolving the {@code @import} chain theme → content → light → highlighter), without the
     * {@code @import} lines. {@code dark.css} is deliberately not used.
     */
    private static final class Style {
        static final String CSS = merge("styles/highlighter/highlighter.css", "styles/highlighter/light.css",
                "styles/content.css", "styles/theme.css");

        /** Concatenates the files without their {@code @import} lines; missing files are skipped. */
        private static String merge(String... files) {
            StringBuilder sb = new StringBuilder();
            for (String file : files) {
                try (InputStream in = AhkHelpRepository.class.getResourceAsStream("/help/" + file)) {
                    if (in == null) continue;
                    String css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    sb.append(css.replaceAll("(?im)^\\s*@import[^;]*;[ \\t]*\\r?\\n?", "")).append('\n');
                } catch (IOException ignored) {
                    // missing file: continue without this part
                }
            }
            return sb.toString();
        }
    }

    /**
     * Links to other help pages ({@code .htm}/{@code .html}) become {@code psi_element://path#anchor} with the path
     * under {@code /help/}, resolved against {@code base}. External links, pure anchors and other files stay
     * unchanged.
     */
    static String rewriteLinks(String html, String base) {
        return HREF.matcher(html).replaceAll(m -> {
            String target = m.group(2);
            if (target.matches("(?i)^([a-z][a-z0-9+.-]*:|#).*")) return Matcher.quoteReplacement(m.group());
            int hash = target.indexOf('#');
            String anchor = hash >= 0 ? target.substring(hash) : "";
            String file = hash >= 0 ? target.substring(0, hash) : target;
            if (!file.matches("(?i).*\\.html?")) return Matcher.quoteReplacement(m.group());
            String path = normalize(file.startsWith("/") ? file : base + file);
            if (path == null) return Matcher.quoteReplacement(m.group());
            return Matcher.quoteReplacement("href=\"psi_element://" + path + anchor + "\"");
        });
    }

    /** Replaces the lookup files until the end of the test (lines as in the files). */
    @org.jetbrains.annotations.TestOnly
    public static void useListsForTest(@NotNull String docs, @NotNull String extra,
                                       @NotNull com.intellij.openapi.Disposable disposable) {
        Index previous = Index.current();
        Index.current = new Index(lines(docs), lines(extra));
        com.intellij.openapi.util.Disposer.register(disposable, () -> Index.current = previous);
    }

    /** Lines without comment and empty lines. */
    private static List<String> lines(String text) {
        return text.lines().filter(l -> !l.isBlank() && !l.stripLeading().startsWith(";")).toList();
    }

    /** The two lookup files; loaded once (thread-safe), replaceable in tests. */
    private static final class Index {
        /** Logs malformed lines and missing pages (idea.log, in tests on the console) to help maintaining the files. */
        private static final com.intellij.openapi.diagnostic.Logger LOG =
                com.intellij.openapi.diagnostic.Logger.getInstance(AhkHelpRepository.class);
        private static volatile Index current;

        final Map<String, List<Topic>> docs = new HashMap<>();
        final Map<String, List<Topic>> extra = new HashMap<>();

        /** The current index, loaded from the resources on first access. */
        static Index current() {
            Index index = current;
            if (index == null) {
                synchronized (Index.class) {
                    index = current;
                    if (index == null) current = index = new Index(resource("docs.txt"), resource("extra.txt"));
                }
            }
            return index;
        }

        /** Builds the tables from the lines of docs.txt and extra.txt. */
        Index(List<String> docsLines, List<String> extraLines) {
            read(docsLines, "docs.txt", true, docs);
            read(extraLines, "extra.txt", false, extra);
        }

        /** Entries for the key: docs.txt first, otherwise extra.txt. */
        List<Topic> lookup(String key) {
            List<Topic> found = docs.get(key);
            return found != null ? found : extra.getOrDefault(key, List.of());
        }

        /** Reads the lines of one lookup file into {@code target}; identical entries count once. */
        private static void read(List<String> lines, String file, boolean fromDocs, Map<String, List<Topic>> target) {
            for (String line : lines) {
                String[] parts = line.split("\t", -1);
                if (parts.length < 3 || parts.length > 4 || parts[0].isBlank() || parts[1].isBlank()) {
                    LOG.warn("Malformed help entry, ignored (" + file + "): " + line);
                    continue;
                }
                String anchor = parts[2].trim();
                String className = parts.length > 3 ? parts[3].trim() : "";
                Topic topic = new Topic(parts[0].trim(), parts[1].trim(),
                        anchor.isEmpty() || anchor.equals(NO_ANCHOR) ? null : anchor,
                        className.isEmpty() ? null : className, fromDocs);
                if (AhkHelpRepository.class.getResource("/help/" + topic.page()) == null) {
                    LOG.warn("Help page missing, entry ignored (" + file + "): " + line);
                    continue;
                }
                List<Topic> topics = target.computeIfAbsent(listKey(topic.name()), k -> new ArrayList<>());
                if (topics.stream().noneMatch(t -> sameTarget(t, topic))) topics.add(topic);
            }
            target.replaceAll((k, v) -> List.copyOf(v));
        }

        /** Same page, anchor and class (ignoring case), e.g. A_OSVersion and A_OsVersion. */
        private static boolean sameTarget(Topic a, Topic b) {
            return a.page().equals(b.page()) && Objects.equals(a.anchor(), b.anchor())
                    && (a.className() == null ? b.className() == null : a.className().equalsIgnoreCase(b.className()));
        }

        /** Lines of the lookup file under help/lookup without comment and empty lines. */
        private static List<String> resource(String file) {
            try (InputStream in = AhkHelpRepository.class.getResourceAsStream("/help/lookup/" + file)) {
                if (in == null) return List.of();
                return lines(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                return List.of();
            }
        }
    }
}
