package de.kumpewo.autohotkey.formatter;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spelling of the keywords from names/keyword-case.txt: groups with a default and words in their "list"
 * spelling. Each group is a switch on the tab "Case" (AhkCaseCodeStylePanel); a new group in the file needs no
 * code.
 */
public final class AhkKeywordCase {

    /** A group: name, default and words (lower-case key, value = spelling of the list). */
    public record Group(@NotNull String name, @NotNull AhkCaseMode defaultMode, @NotNull Map<String, String> words) {
    }

    private static final String PATH = "/names/keyword-case.txt";
    private static final List<Group> GROUPS = load();
    private static final Map<String, Group> BY_WORD = index(GROUPS);

    private AhkKeywordCase() {
    }

    /** All groups in the order of the file. */
    public static @NotNull List<Group> groups() {
        return GROUPS;
    }

    /** Group of the word (case-insensitive), otherwise null. */
    public static @Nullable Group groupOf(@NotNull String word) {
        return BY_WORD.get(word.toLowerCase(Locale.ROOT));
    }

    /**
     * Spelling according to the switch of the group: AS_LIST = list, LOWER = lower case, KEEP = null (don't change).
     * Word in no group: null.
     */
    public static @Nullable String spelling(@NotNull String word, @NotNull AhkCodeStyleSettings settings) {
        Group group = groupOf(word);
        if (group == null) return null;
        return switch (settings.keywordCase(group.name())) {
            case AS_LIST -> group.words().get(word.toLowerCase(Locale.ROOT));
            case LOWER -> word.toLowerCase(Locale.ROOT);
            case KEEP -> null;
        };
    }

    /**
     * Spelling for completion: "lowercase" = lower case, otherwise as in the list (also for "keep"; the main switch
     * only affects formatting). Word in no group: unchanged.
     */
    public static @NotNull String completionSpelling(@NotNull String word, @NotNull AhkCodeStyleSettings settings) {
        Group group = groupOf(word);
        if (group == null) return word;
        if (settings.keywordCase(group.name()) == AhkCaseMode.LOWER) return word.toLowerCase(Locale.ROOT);
        return group.words().get(word.toLowerCase(Locale.ROOT));
    }

    /** Table word -> group; the first group of a word wins. */
    private static Map<String, Group> index(List<Group> groups) {
        Map<String, Group> map = new HashMap<>();
        for (Group g : groups) for (String key : g.words().keySet()) map.putIfAbsent(key, g);
        return Collections.unmodifiableMap(map);
    }

    /** Reads keyword-case.txt: "[Group] default" headers followed by the words of the group. */
    private static List<Group> load() {
        Pattern header = Pattern.compile("\\[([^\\]]+)]\\s*(.*)");
        List<Group> groups = new ArrayList<>();
        String name = null;
        AhkCaseMode mode = null;
        Map<String, String> words = null;
        try (InputStream in = AhkKeywordCase.class.getResourceAsStream(PATH)) {
            if (in == null) throw new IllegalStateException("Name list missing: " + PATH);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                int comment = line.indexOf(';');
                if (comment >= 0) line = line.substring(0, comment);
                line = line.trim();
                if (line.isEmpty()) continue;
                Matcher m = header.matcher(line);
                if (m.matches()) {
                    if (name != null) groups.add(new Group(name, mode, Collections.unmodifiableMap(words)));
                    name = m.group(1).trim();
                    mode = m.group(2).isBlank() ? AhkCaseMode.AS_LIST : AhkCaseMode.parse(m.group(2));
                    if (mode == null) throw new IllegalStateException("Unknown default in " + PATH + ": " + line);
                    words = new LinkedHashMap<>();
                    continue;
                }
                if (name == null) throw new IllegalStateException("Word before the first group in " + PATH + ": " + line);
                for (String word : line.split("[\\s,]+")) {
                    if (!word.isEmpty()) words.putIfAbsent(word.toLowerCase(Locale.ROOT), word);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Name list not readable: " + PATH, e);
        }
        if (name != null) groups.add(new Group(name, mode, Collections.unmodifiableMap(words)));
        return List.copyOf(groups);
    }
}
