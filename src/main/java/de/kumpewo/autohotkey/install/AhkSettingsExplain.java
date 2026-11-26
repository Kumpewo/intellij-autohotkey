package de.kumpewo.autohotkey.install;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Removes the explanations of a {@code settings.ahk-v2-plugin.ini} ({@link #compact}) and restores them
 * ({@link #restore}). An explanation is a line with {@code ;;} in the first column followed by a blank or nothing. All
 * other lines that are not empty belong to the user: they are never changed and never change their order.
 * <p>
 * {@link #restore} also adds what the file lacks of the template ({@link AhkSettingsFile#TEMPLATE},
 * {@link AhkNewLibraryProject#TEMPLATE}): sections, options and examples, always commented out, so that the meaning of
 * the file stays the same. Which lines of the template count is decided in one place, {@link #generic}.
 */
final class AhkSettingsExplain {

    /** An explanation. */
    private static final Pattern EXPLANATION = Pattern.compile("^;;( .*)?$");

    /** A line of the template that stands for lines generated from the project. */
    private static final Pattern GENERATED = Pattern.compile("^\\$[a-z-]+\\$$");

    /** The section of the options (lower case). */
    private static final String DEPLOY = "deploy";

    private AhkSettingsExplain() {
    }

    /** A section of the file or, with the header {@code null}, the lines before the first one. */
    private static final class Part {
        final String header;
        /** Name of the section, lower case. */
        final String name;
        final List<String> lines = new ArrayList<>();

        /** A part without lines. */
        Part(@Nullable String header) {
            this.header = header;
            this.name = header == null ? "" : sectionName(header);
        }
    }

    /** The lines of one option of {@code [Deploy]} with the comments above them; without name: other lines. */
    private static final class Group {
        /** Name of the option, lower case. */
        final String name;
        final List<String> lines = new ArrayList<>();

        /** A group without lines. */
        Group(@NotNull String name) {
            this.name = name;
        }
    }

    /** A line {@code key=value}; the key is normalized ({@link #entry}). */
    record Entry(@NotNull String key, @NotNull String value, boolean active) {
    }

    /** The text without explanations and without empty lines, except one between the sections. */
    static @NotNull String compact(@NotNull String text) {
        StringBuilder result = new StringBuilder();
        for (Part part : parse(text)) {
            List<String> lines = trimmed(part.lines);
            if (part.header != null) {
                lines.removeIf(String::isBlank);
                lines.add(0, part.header);
            }
            append(result, lines);
        }
        return finish(text, result);
    }

    /**
     * The text with all explanations and everything the template offers.
     *
     * @param library is it the settings file of a library project?
     */
    static @NotNull String restore(@NotNull String text, boolean library) {
        List<Part> parts = parse(text);
        Map<String, Part> template = generic(library);
        addSections(parts, template);
        StringBuilder result = new StringBuilder();
        append(result, AhkSettingsHelp.comment(library ? AhkSettingsHelp.FILE_LIBRARY : AhkSettingsHelp.FILE));
        append(result, trimmed(parts.get(0).lines));
        Set<String> explained = new HashSet<>();
        Set<String> completed = new HashSet<>();
        for (Part part : parts.subList(1, parts.size())) {
            List<String> all = new ArrayList<>();
            for (Part other : parts) {
                if (other.name.equals(part.name)) all.addAll(other.lines);
            }
            // a section that appears twice is completed and explained only the first time
            Part model = completed.add(part.name) ? template.get(part.name) : null;
            if (part.name.equals(DEPLOY)) {
                append(result, List.of(part.header));
                for (Group group : groups(part, model, all)) {
                    List<String> lines = new ArrayList<>();
                    String id = explained.add(group.name) ? optionId(group.name) : null;
                    if (id != null) lines.addAll(AhkSettingsHelp.comment(id));
                    lines.addAll(group.lines);
                    append(result, lines);
                }
                continue;
            }
            List<String> lines = new ArrayList<>(List.of(part.header));
            if (model != null) lines.addAll(AhkSettingsHelp.comment(writtenName(model.header)));
            lines.addAll(trimmed(part.lines));
            if (model != null) lines.addAll(missingExamples(model, all));
            append(result, lines);
        }
        return finish(text, result);
    }

    /**
     * The id of the topic ({@link AhkSettingsHelp}) for a position: on an option of {@code [Deploy]} (also commented
     * out) the option, elsewhere the section ({@code Deploy} for the list of all options), before the first section the
     * header of the file; {@code null} in an unknown section.
     */
    static @Nullable String topicAt(@NotNull CharSequence text, int offset, boolean library) {
        String section = null;
        String line;
        int start = 0;
        while (true) {
            int end = start;
            while (end < text.length() && text.charAt(end) != '\n' && text.charAt(end) != '\r') end++;
            line = text.subSequence(start, end).toString();
            if (isHeader(line)) section = sectionName(line);
            if (offset <= end || end >= text.length()) break;
            start = end + 1;
        }
        if (section == null) return library ? AhkSettingsHelp.FILE_LIBRARY : AhkSettingsHelp.FILE;
        if (section.equals(DEPLOY)) {
            Entry entry = entry(line);
            String id = entry == null ? null : optionId(entry.key());
            return id != null ? id : "Deploy";
        }
        for (String id : AhkSettingsHelp.ids()) {
            // sections are written with a capital letter, the parts of the file header are not
            if (Character.isUpperCase(id.charAt(0)) && id.equalsIgnoreCase(section)) return id;
        }
        return null;
    }

    /** The topic id of an option (any case), {@code null} if there is no such option. */
    private static @Nullable String optionId(@NotNull String name) {
        for (String id : AhkSettingsHelp.ids()) {
            if (id.equalsIgnoreCase(AhkSettingsHelp.OPTION + name)) return id;
        }
        return null;
    }

    /**
     * The lines of the template that {@link #restore} adds, by section (lower case) in the order of the template, all
     * commented out: every line except the placeholder {@code $install$}.
     */
    private static @NotNull Map<String, Part> generic(boolean library) {
        Map<String, Part> result = new LinkedHashMap<>();
        Part part = null;
        for (String line : template(library ? AhkNewLibraryProject.TEMPLATE : AhkSettingsFile.TEMPLATE).split("\n")) {
            if (line.isBlank() || GENERATED.matcher(line).matches()) continue;
            if (isHeader(line)) {
                part = new Part(line);
                result.put(part.name, part);
            } else if (part != null) {
                part.lines.add(line.startsWith(";") ? line : ";" + line);
            }
        }
        return result;
    }

    /** The text of a resource ({@code \n} as separator). */
    static @NotNull String template(@NotNull String resource) {
        try (InputStream in = AhkSettingsExplain.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing resource " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Adds the missing sections: each after the existing section that precedes it in the template. */
    private static void addSections(@NotNull List<Part> parts, @NotNull Map<String, Part> template) {
        int at = 1;
        for (Part model : template.values()) {
            int last = -1;
            for (int i = 1; i < parts.size(); i++) {
                if (parts.get(i).name.equals(model.name)) last = i;
            }
            if (last < 0) {
                parts.add(at, new Part(model.header));
                last = at;
            }
            at = last + 1;
        }
    }

    /**
     * The lines of {@code [Deploy]} in groups: an option with the comments above it, several lines of the same option
     * together; comments after the last option at the end. With {@code model} the missing options are added.
     */
    private static @NotNull List<Group> groups(@NotNull Part part, @Nullable Part model, @NotNull List<String> all) {
        List<Group> groups = new ArrayList<>();
        List<String> above = new ArrayList<>();
        for (String line : part.lines) {
            if (line.isBlank()) continue;
            Entry entry = entry(line);
            boolean known = entry != null && optionId(entry.key()) != null;
            if (!known && (entry == null || !entry.active())) {
                above.add(line);
                continue;
            }
            String name = known ? entry.key() : "";
            Group group = groups.isEmpty() ? null : groups.get(groups.size() - 1);
            if (group == null || !group.name.equals(name)) {
                group = new Group(name);
                groups.add(group);
            }
            group.lines.addAll(above);
            group.lines.add(line);
            above.clear();
        }
        if (model != null) addOptions(groups, model, all);
        if (!above.isEmpty()) {
            Group rest = new Group("");
            rest.lines.addAll(above);
            groups.add(rest);
        }
        return groups;
    }

    /**
     * Adds the missing options of the template. An active option gets nothing; otherwise the lines that are missing,
     * for an option with a single line in the template only if there is none at all. A new group comes after the option
     * that precedes it in the template.
     */
    private static void addOptions(@NotNull List<Group> groups, @NotNull Part model, @NotNull List<String> all) {
        Map<String, List<String>> options = new LinkedHashMap<>();
        for (String line : model.lines) {
            options.computeIfAbsent(entry(line).key(), k -> new ArrayList<>()).add(line);
        }
        int at = 0;
        for (Map.Entry<String, List<String>> option : options.entrySet()) {
            String name = option.getKey();
            boolean single = option.getValue().size() == 1;
            List<String> missing = new ArrayList<>(option.getValue());
            for (String line : all) {
                Entry existing = entry(line);
                if (existing == null || !existing.key().equals(name)) continue;
                if (existing.active() || single) {
                    missing.clear();
                } else {
                    missing.removeIf(m -> entry(m).value().equalsIgnoreCase(existing.value()));
                }
            }
            int last = -1;
            for (int i = 0; i < groups.size(); i++) {
                if (groups.get(i).name.equals(name)) last = i;
            }
            if (last < 0 && !missing.isEmpty()) {
                groups.add(at, new Group(name));
                last = at;
            }
            if (last < 0) continue;
            groups.get(last).lines.addAll(missing);
            at = last + 1;
        }
    }

    /** The examples of the template whose key appears neither active nor commented out in the section. */
    private static @NotNull List<String> missingExamples(@NotNull Part model, @NotNull List<String> all) {
        Set<String> keys = new HashSet<>();
        for (String line : all) {
            Entry entry = entry(line);
            if (entry != null) keys.add(entry.key());
        }
        List<String> result = new ArrayList<>(model.lines);
        result.removeIf(line -> keys.contains(entry(line).key()));
        return result;
    }

    /**
     * The line as {@code key=value}, also commented out ({@code ;key=value}: one {@code ;} in the first column, no
     * blank after it); the key in lower case with {@code \}. {@code null} for other lines.
     */
    static Entry entry(@NotNull String line) {
        boolean active = !line.stripLeading().startsWith(";");
        if (!active && !line.matches(";[^;\\s].*")) return null;
        String text = active ? line : line.substring(1);
        int eq = text.indexOf('=');
        if (eq <= 0) return null;
        String key = AhkDeployConfig.unquote(text.substring(0, eq).strip());
        return new Entry(key.replace('/', '\\').toLowerCase(Locale.ROOT),
                AhkDeployConfig.unquote(text.substring(eq + 1).strip()), active);
    }

    /** Is the line an explanation? */
    static boolean isExplanation(@NotNull String line) {
        return EXPLANATION.matcher(line).matches();
    }

    /** Is the line a section header? */
    static boolean isHeader(@NotNull String line) {
        String text = line.strip();
        return text.startsWith("[") && text.endsWith("]");
    }

    /** The name of the section, lower case. */
    static @NotNull String sectionName(@NotNull String header) {
        return writtenName(header).toLowerCase(Locale.ROOT);
    }

    /** The name of the section as written. */
    private static @NotNull String writtenName(@NotNull String header) {
        String text = header.strip();
        return text.substring(1, text.length() - 1).strip();
    }

    /** The sections without explanations; the first part holds the lines before the first section. */
    private static @NotNull List<Part> parse(@NotNull String text) {
        List<Part> parts = new ArrayList<>(List.of(new Part(null)));
        String body = text.startsWith("\uFEFF") ? text.substring(1) : text;
        for (String line : body.split("\\r\\n|\\n|\\r", -1)) {
            if (isExplanation(line)) continue;
            if (isHeader(line)) {
                parts.add(new Part(line));
            } else {
                parts.get(parts.size() - 1).lines.add(line);
            }
        }
        return parts;
    }

    /** A copy without empty lines at the start and at the end. */
    private static @NotNull List<String> trimmed(@NotNull List<String> lines) {
        int start = 0;
        int end = lines.size();
        while (start < end && lines.get(start).isBlank()) start++;
        while (end > start && lines.get(end - 1).isBlank()) end--;
        return new ArrayList<>(lines.subList(start, end));
    }

    /** Appends a block of lines, separated from the text before by an empty line. */
    private static void append(@NotNull StringBuilder result, @NotNull List<String> lines) {
        if (lines.isEmpty()) return;
        if (!result.isEmpty()) result.append('\n');
        for (String line : lines) result.append(line).append('\n');
    }

    /** The result with the line separator and the byte order mark of the original text. */
    private static @NotNull String finish(@NotNull String original, @NotNull StringBuilder result) {
        return (original.startsWith("\uFEFF") ? "\uFEFF" : "")
                + result.toString().replace("\n", AhkSettingsEdit.separator(original));
    }
}
