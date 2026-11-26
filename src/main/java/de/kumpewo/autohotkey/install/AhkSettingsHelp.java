package de.kumpewo.autohotkey.install;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The explanations of {@code settings.ahk-v2-plugin.ini}, from the resource {@value #RESOURCE}: one {@code <topic>} for
 * the header of the file ({@link #FILE}, {@link #FILE_LIBRARY}, both followed by {@link #FORMAT}), for each section and
 * for each option of {@code [Deploy]} ({@code Deploy.<name>}). {@link #html} is the text of the quick documentation
 * ({@link AhkSettingsDocumentation}), {@link #comment} the same text as comment lines of the file
 * ({@link AhkSettingsExplain}), without the examples ({@code <pre>}), which keep the file short.
 */
final class AhkSettingsHelp {

    /** Resource with the texts. */
    static final String RESOURCE = "/install/settings-help.html";

    /** The header of the settings of a tool. */
    static final String FILE = "file";

    /** The header of the settings of a library project. */
    static final String FILE_LIBRARY = "file-library";

    /** The second part of both headers. */
    private static final String FORMAT = "format";

    /** Prefix of the topics of the options. */
    static final String OPTION = "Deploy.";

    /** The marker of an explanation in the file. */
    static final String MARKER = ";;";

    /** Right margin of the comment lines. */
    private static final int COLUMNS = 120;

    private static final Pattern TOPIC = Pattern.compile("<topic id=\"([^\"]+)\" anchor=\"([^\"]+)\">(.*?)</topic>",
            Pattern.DOTALL);

    /** A paragraph or list item; an example ({@code <pre>}) is neither. */
    private static final Pattern BLOCK = Pattern.compile("<(p|li)>(.*?)</\\1>", Pattern.DOTALL);

    /**
     * One topic.
     *
     * @param anchor anchor in {@code installer.htm} of the manual
     * @param html   the text
     */
    record Topic(@NotNull String anchor, @NotNull String html) {
    }

    /** Holder of the loaded texts. */
    private static final class Topics {
        static final Map<String, Topic> ALL = load();

        /** Reads the resource. */
        private static Map<String, Topic> load() {
            Map<String, Topic> result = new LinkedHashMap<>();
            Matcher m = TOPIC.matcher(AhkSettingsExplain.template(RESOURCE));
            while (m.find()) result.put(m.group(1), new Topic(m.group(2), m.group(3).strip()));
            return result;
        }
    }

    private AhkSettingsHelp() {
    }

    /** The topic, {@code null} if there is none. */
    static @Nullable Topic topic(@NotNull String id) {
        return Topics.ALL.get(id);
    }

    /** The ids of all topics. */
    static @NotNull List<String> ids() {
        return List.copyOf(Topics.ALL.keySet());
    }

    /** The text of the topic; a file header with the part about the format. Empty if there is none. */
    static @NotNull String html(@NotNull String id) {
        Topic topic = topic(id);
        if (topic == null) return "";
        Topic format = id.startsWith(FILE) ? topic(FORMAT) : null;
        return format == null ? topic.html() : topic.html() + "\n" + format.html();
    }

    /**
     * The text of the topic as comment lines ({@code ;; text}), wrapped at 120 columns; the paragraphs of a file header
     * are separated by lines {@code ;;}. Examples are left out. Empty if there is no such topic.
     */
    static @NotNull List<String> comment(@NotNull String id) {
        List<String> result = new ArrayList<>();
        Matcher m = BLOCK.matcher(html(id));
        while (m.find()) {
            boolean item = m.group(1).equals("li");
            if (id.startsWith(FILE) && !result.isEmpty()) result.add(MARKER);
            wrap(plain(m.group(2)), item ? "- " : "", item ? "  " : "", result);
        }
        return result;
    }

    /** The text without tags, with resolved entities and single blanks. */
    private static @NotNull String plain(@NotNull String html) {
        return html.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").strip()
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&");
    }

    /** Adds the text in comment lines; {@code first} starts the first line, {@code next} the others. */
    private static void wrap(@NotNull String text, @NotNull String first, @NotNull String next,
                             @NotNull List<String> result) {
        StringBuilder line = new StringBuilder(MARKER).append(' ').append(first);
        boolean empty = true;
        for (String word : text.split(" ")) {
            if (!empty && line.length() + 1 + word.length() > COLUMNS) {
                result.add(line.toString());
                line = new StringBuilder(MARKER).append(' ').append(next);
                empty = true;
            }
            if (!empty) line.append(' ');
            line.append(word);
            empty = false;
        }
        result.add(line.toString());
    }
}
