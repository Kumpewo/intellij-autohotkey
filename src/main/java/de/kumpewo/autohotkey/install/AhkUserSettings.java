package de.kumpewo.autohotkey.install;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.settings.AhkSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The user settings: a file in the format of {@code settings.ahk-v2-plugin.ini} with the defaults of the user, whose
 * name is set on the settings page ({@link AhkSettings#getUserSettingsName}). It is searched from the project root
 * upwards, the nearest one counts ({@link #find}). Its lines are inserted into new settings files and, by the link
 * "Apply User Settings" of the bar ({@link AhkSettingsNotification}), into existing ones; Deploy itself never reads it.
 * <p>
 * Precedence: active lines of the project, then the user settings, then the system (the template). A line is a system
 * value as long as it is written exactly as in the template; an active option without a value counts as one, too.
 * <ul>
 *   <li>{@code [Deploy]}: an active user line replaces the system value (active or commented out) at its place, never
 *       another active line. Commented-out user lines replace only a commented-out system value and are left out beside
 *       an active line.</li>
 *   <li>{@code [AddBanner]}, {@code [SetVersion]}: an active user line replaces the commented-out line with the same
 *       selector at its place; the others come before the first active line of the project, so that the project wins
 *       (the last matching line counts). The user lines keep their order: where the place of the commented-out line
 *       would change it, that line is removed.</li>
 *   <li>{@code [Install]}, {@code [InstallIfMissing]}, {@code [Cleanup]}: user lines are only inserted commented out;
 *       active ones give a warning.</li>
 * </ul>
 * Comments of the user file are ignored; unknown, invalid and duplicate active lines too, with a warning. Inserting a
 * second time changes nothing.
 */
public final class AhkUserSettings {

    /** The sections, in the order of the template. */
    private static final List<String> SECTIONS = List.of("Deploy", "Install", "InstallIfMissing", "Cleanup",
            "AddBanner", "SetVersion");

    /** The sections whose lines are only inserted commented out (lower case). */
    private static final Set<String> COMMENTED_ONLY = Set.of("install", "installifmissing", "cleanup");

    /** Options whose value keeps {@code {project-name}}, because Deploy resolves it (lower case). */
    private static final Set<String> KEEP_PLACEHOLDER = Set.of("banner", "message");

    /** The placeholder for the folder name of the project. */
    static final String PROJECT_NAME = "{project-name}";

    private AhkUserSettings() {
    }

    /**
     * A line of the user file.
     *
     * @param id     what the line is about, lower case: the option or selector, in {@code [Cleanup]} with the folder
     * @param value  the value
     * @param active not commented out
     * @param text   {@code key=value} as written
     */
    private record Line(@NotNull String id, @NotNull String value, boolean active, @NotNull String text) {
        /** The line as it is inserted. */
        String written() {
            return active ? text : ";" + text;
        }
    }

    /**
     * A settings file with the user settings.
     *
     * @param text     the new text
     * @param added    active lines that were inserted
     * @param replaced active lines that replaced another active line
     * @param kept     active lines of the project that won over a different line of the user file
     * @param comments number of commented-out lines that were inserted
     * @param warnings problems of the user file
     */
    record Result(@NotNull String text, @NotNull List<String> added, @NotNull List<String> replaced,
                  @NotNull List<String> kept, int comments, @NotNull List<String> warnings) {
    }

    /** Is the name valid for the settings page: empty (off) or a plain file name that is no project settings file? */
    public static boolean isValidName(@NotNull String name) {
        return name.isEmpty() || AhkBanner.isPlainName(name) && !AhkDeployConfig.isSettingsName(name);
    }

    /** The user settings for a project folder: the nearest file in it or above, {@code null} if there is none. */
    static @Nullable Path find(@Nullable Path folder) {
        String name = AhkSettings.getInstance().getUserSettingsName();
        return folder == null || name.isEmpty() || !isValidName(name) ? null : AhkBanner.find(folder, name);
    }

    /**
     * The text ({@code \n} as separator) with the user settings of the project folder; {@code null} if there is no user
     * file.
     *
     * @param folder  the folder of the settings file
     * @param library is it the settings file of a library project?
     */
    static @Nullable Result apply(@NotNull String text, @Nullable Path folder, boolean library) {
        Path file = find(folder);
        if (file == null) return null;
        Path name = folder.toAbsolutePath().normalize().getFileName();
        try {
            return merge(text, StringUtil.convertLineSeparators(AhkSettingsEdit.read(file).text()), library,
                    name == null ? "" : name.toString());
        } catch (IOException e) {
            return new Result(text, List.of(), List.of(), List.of(), 0,
                    List.of(AhkBundle.message("user.settings.unreadable", file, String.valueOf(e.getMessage()))));
        }
    }

    /**
     * Inserts the user settings (see class comment).
     *
     * @param text    text of the settings file ({@code \n} as separator)
     * @param user    text of the user file ({@code \n} as separator)
     * @param library is it the settings file of a library project?
     * @param project folder name of the project, for {@value #PROJECT_NAME}
     */
    static @NotNull Result merge(@NotNull String text, @NotNull String user, boolean library,
                                 @NotNull String project) {
        Merge merge = new Merge(text, library);
        Map<String, List<Line>> sections = read(user, project, merge.warnings);
        for (String section : SECTIONS) {
            List<Line> lines = sections.get(section.toLowerCase(Locale.ROOT));
            if (lines == null || lines.isEmpty()) continue;
            if (section.equals("Deploy")) {
                merge.options(lines);
            } else {
                merge.rules(section, lines);
            }
        }
        return new Result(String.join("\n", merge.lines), merge.added, merge.replaced, merge.kept, merge.comments,
                merge.warnings);
    }

    /** The lines of the user file by section (lower case). */
    private static @NotNull Map<String, List<Line>> read(@NotNull String user, @NotNull String project,
                                                         @NotNull List<String> warnings) {
        Map<String, List<Line>> result = new LinkedHashMap<>();
        String section = null;
        boolean known = false;
        String[] lines = user.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i].strip();
            if (raw.isEmpty()) continue;
            if (AhkSettingsExplain.isHeader(raw)) {
                section = AhkSettingsExplain.sectionName(raw);
                known = SECTIONS.stream().anyMatch(section(section));
                if (!known) warnings.add(AhkBundle.message("user.settings.ignored", i + 1, raw));
                continue;
            }
            // the lines of an unknown section are skipped without further warnings
            if (section != null && !known) continue;
            boolean active = !raw.startsWith(";");
            AhkSettingsExplain.Entry entry = AhkSettingsExplain.entry(raw);
            boolean valid = section != null && entry != null && isValid(section, active ? raw : raw.substring(1));
            if (!valid) {
                if (active) warnings.add(AhkBundle.message("user.settings.ignored", i + 1, raw));
                continue;
            }
            boolean keep = section.equals("deploy") && KEEP_PLACEHOLDER.contains(entry.key());
            String written = active ? raw : raw.substring(1);
            if (!keep) written = written.replace(PROJECT_NAME, project);
            entry = AhkSettingsExplain.entry(active ? written : ";" + written);
            if (entry == null) continue;
            if (active && COMMENTED_ONLY.contains(section)) {
                warnings.add(AhkBundle.message("user.settings.commented", i + 1, raw));
                active = false;
            }
            Line line = new Line(id(section, entry), entry.value(), active, written);
            List<Line> all = result.computeIfAbsent(section, k -> new ArrayList<>());
            if (active && all.stream().anyMatch(l -> l.active() && l.id().equals(line.id()))) {
                warnings.add(AhkBundle.message("user.settings.ignored", i + 1, raw));
            } else if (active || all.stream().noneMatch(l -> l.id().equals(line.id())
                    && l.value().equals(line.value()))) {
                all.add(line);
            }
        }
        // commented-out lines beside an active one have no use
        for (List<Line> all : result.values()) {
            Set<String> activeIds = new HashSet<>();
            for (Line line : all) if (line.active()) activeIds.add(line.id());
            all.removeIf(l -> !l.active() && activeIds.contains(l.id()));
        }
        return result;
    }

    /** Would the line (not commented out) be valid in the section? */
    private static boolean isValid(@NotNull String section, @NotNull String line) {
        AhkDeployConfig config = AhkDeployConfig.parse("[" + section + "]\n" + line);
        return config.errors().isEmpty() && config.versionErrors().isEmpty();
    }

    /** Compares names of sections in any case. */
    private static @NotNull Predicate<String> section(@NotNull String name) {
        return other -> other.equalsIgnoreCase(name);
    }

    /** What an entry of a section is about ({@link Line#id}). */
    private static @NotNull String id(@NotNull String section, @NotNull AhkSettingsExplain.Entry entry) {
        return section.equals("cleanup")
                ? entry.key() + "=" + entry.value().replace('/', '\\').toLowerCase(Locale.ROOT) : entry.key();
    }

    /** Reports what was inserted: warnings always, otherwise only for {@code always} (the link of the bar). */
    static void notify(@Nullable Project project, @NotNull Result result, boolean always) {
        List<String> lines = report(result, always);
        if (lines.isEmpty()) return;
        lines.replaceAll(StringUtil::escapeXmlEntities);
        NotificationGroupManager.getInstance().getNotificationGroup("AutoHotkey")
                .createNotification(AhkBundle.message("user.settings.title"), String.join("<br>", lines),
                        result.warnings().isEmpty() ? NotificationType.INFORMATION : NotificationType.WARNING)
                .notify(project);
    }

    /** The lines of {@link #notify}; hints for undo and for kept lines only where the user can act on them. */
    static @NotNull List<String> report(@NotNull Result result, boolean always) {
        List<String> lines = new ArrayList<>();
        if (always) {
            for (String line : result.added()) lines.add(AhkBundle.message("user.settings.added", line));
            for (String line : result.replaced()) lines.add(AhkBundle.message("user.settings.replaced", line));
            for (String line : result.kept()) lines.add(AhkBundle.message("user.settings.kept", line));
            if (result.comments() > 0) lines.add(AhkBundle.message("user.settings.comments", result.comments()));
            if (lines.isEmpty()) lines.add(AhkBundle.message("user.settings.nothing"));
            if (!result.kept().isEmpty()) lines.add(AhkBundle.message("user.settings.kept.hint"));
        }
        lines.addAll(result.warnings());
        // a new file cannot be undone in the editor
        if (always && !(result.added().isEmpty() && result.replaced().isEmpty() && result.comments() == 0)) {
            lines.add(AhkBundle.message("user.settings.undo"));
        }
        return lines;
    }

    /** One insertion: the lines of the settings file, changed step by step. */
    private static final class Merge {
        final List<String> lines;
        final boolean library;
        final List<String> added = new ArrayList<>();
        final List<String> replaced = new ArrayList<>();
        final List<String> kept = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        int comments;
        /** The options of the template in their order, lower case, with their line. */
        final Map<String, String> system = new LinkedHashMap<>();

        /** Reads the options of the template. */
        Merge(@NotNull String text, boolean library) {
            lines = new ArrayList<>(List.of(text.split("\n", -1)));
            this.library = library;
            boolean deploy = false;
            for (String line : AhkSettingsExplain.template(library ? AhkNewLibraryProject.TEMPLATE
                    : AhkSettingsFile.TEMPLATE).split("\n")) {
                if (AhkSettingsExplain.isHeader(line)) {
                    deploy = AhkSettingsExplain.sectionName(line).equals("deploy");
                } else if (deploy) {
                    AhkSettingsExplain.Entry entry = AhkSettingsExplain.entry(line);
                    if (entry != null) system.put(entry.key(), line);
                }
            }
        }

        /** The indexes of the lines of a section (also if it appears twice) that are entries with this id. */
        private @NotNull List<Integer> find(@NotNull String section, @NotNull String id) {
            List<Integer> result = new ArrayList<>();
            String current = null;
            for (int i = 0; i < lines.size(); i++) {
                if (AhkSettingsExplain.isHeader(lines.get(i))) {
                    current = AhkSettingsExplain.sectionName(lines.get(i));
                } else if (section.equals(current)) {
                    AhkSettingsExplain.Entry entry = AhkSettingsExplain.entry(lines.get(i));
                    if (entry != null && id(section, entry).equals(id)) result.add(i);
                }
            }
            return result;
        }

        /** The entry of a line that is one. */
        private @NotNull AhkSettingsExplain.Entry entry(int line) {
            AhkSettingsExplain.Entry entry = AhkSettingsExplain.entry(lines.get(line));
            if (entry == null) throw new IllegalStateException(lines.get(line));
            return entry;
        }

        /** Is the option line as the system writes it, or active without a value? */
        private boolean isSystem(int line) {
            AhkSettingsExplain.Entry entry = entry(line);
            if (entry.active() && entry.value().isEmpty()) return true;
            String written = system.get(entry.key());
            AhkSettingsExplain.Entry model = written == null ? null : AhkSettingsExplain.entry(written);
            return model != null && model.active() == entry.active()
                    && comparable(model).equals(comparable(entry));
        }

        /** The value for {@link #isSystem}: a separator at the end of {@code Destination} does not matter. */
        private static @NotNull String comparable(@NotNull AhkSettingsExplain.Entry entry) {
            return entry.key().equals("destination") ? entry.value().replaceFirst("[\\\\/]$", "") : entry.value();
        }

        /** Inserts the options. */
        void options(@NotNull List<Line> user) {
            Map<String, List<Line>> byOption = new LinkedHashMap<>();
            for (Line line : user) byOption.computeIfAbsent(line.id(), k -> new ArrayList<>()).add(line);
            for (Map.Entry<String, List<Line>> option : byOption.entrySet()) {
                List<Line> values = option.getValue();
                if (values.get(0).active()) {
                    activeOption(option.getKey(), values.get(0));
                } else {
                    commentedOption(option.getKey(), values);
                }
            }
        }

        /** An active option of the user file. */
        private void activeOption(@NotNull String key, @NotNull Line line) {
            List<Integer> found = find("deploy", key);
            int at = -1;
            int last = -1;
            boolean same = false;
            for (int i : found) {
                last = i;
                if (entry(i).active()) {
                    // the line of the project wins; nothing to do if it is the same
                    if (entry(i).value().equals(line.value())) return;
                    if (!isSystem(i)) {
                        keep(i, line);
                        return;
                    }
                    at = i;
                    break;
                }
                boolean equal = entry(i).value().equals(line.value());
                if (!same && (equal || at < 0 && isSystem(i))) {
                    at = i;
                    same = equal;
                }
            }
            if (at >= 0) {
                AhkSettingsExplain.Entry old = entry(at);
                // an empty option is not worth mentioning
                if (old.active() && !old.value().isEmpty()) {
                    replaced.add(lines.get(at).strip() + " -> " + line.text());
                } else {
                    added.add(line.text());
                }
                lines.set(at, line.text());
            } else {
                added.add(line.text());
                insertOption(key, last, line.text());
            }
            AhkSettingsExplain.Entry folder = AhkSettingsExplain.entry(line.text());
            AhkSettingsExplain.Entry model = AhkSettingsExplain.entry(system.get("destination"));
            if (library && key.equals("destination") && !comparable(folder).equals(comparable(model))) {
                warnings.add(AhkBundle.message("user.settings.destination", line.value()));
            }
        }

        /** Remembers that a line of the project won over the user line. */
        private void keep(int at, @NotNull Line line) {
            kept.add(lines.get(at).strip() + " (" + AhkBundle.message("user.settings.kept.user", line.text()) + ")");
        }

        /** Commented-out lines of an option of the user file. */
        private void commentedOption(@NotNull String key, @NotNull List<Line> values) {
            List<Integer> found = find("deploy", key);
            Set<String> existing = new HashSet<>();
            int at = -1;
            int last = -1;
            for (int i : found) {
                if (entry(i).active()) return;
                existing.add(entry(i).value());
                last = i;
                if (isSystem(i)) at = i;
            }
            if (at >= 0) {
                String old = entry(at).value();
                // the system value stays if it is one of the user, too
                if (values.stream().anyMatch(l -> l.value().equals(old))) at = -1;
            }
            for (Line line : values) {
                if (existing.contains(line.value())) continue;
                comments++;
                if (at >= 0) {
                    lines.set(at, line.written());
                    last = Math.max(last, at);
                    at = -1;
                } else {
                    last = insertOption(key, last, line.written());
                }
            }
        }

        /**
         * Inserts an option after the line {@code last} (the same option) or, with -1, after the option that precedes
         * it in the template; returns the index of the new line.
         */
        private int insertOption(@NotNull String key, int last, @NotNull String text) {
            int header = header("Deploy");
            int at = last;
            if (at < 0) {
                for (String other : system.keySet()) {
                    if (other.equals(key)) break;
                    for (int i : find("deploy", other)) at = Math.max(at, i);
                }
                if (at < 0) at = header;
                // in an explained file the options are separated by empty lines
                if (at + 1 < lines.size() && lines.get(at + 1).isBlank() && explained()) {
                    lines.add(++at, "");
                }
            }
            lines.add(at + 1, text);
            return at + 1;
        }

        /** Does the file contain explanations? */
        private boolean explained() {
            return lines.stream().anyMatch(AhkSettingsExplain::isExplanation);
        }

        /** Inserts the lines of a section other than {@code [Deploy]}. */
        void rules(@NotNull String name, @NotNull List<Line> user) {
            String section = name.toLowerCase(Locale.ROOT);
            List<Line> missing = new ArrayList<>();
            for (Line line : user) {
                List<Integer> found = find(section, line.id());
                // an active line of the project wins; a commented-out line counts as there
                if (found.stream().noneMatch(i -> entry(i).active() || !line.active())) missing.add(line);
                if (!line.active()) continue;
                List<Integer> own = found.stream().filter(i -> entry(i).active()).toList();
                if (!own.isEmpty() && own.stream().noneMatch(i -> entry(i).value().equals(line.value()))) {
                    keep(own.get(0), line);
                }
            }
            if (missing.isEmpty()) return;
            Set<String> userActive = new HashSet<>();
            for (Line line : user) if (line.active()) userActive.add(line.id() + "=" + line.value());
            int pos = header(name) + 1;
            for (Line line : user) {
                if (!line.active()) continue;
                int limit = limit(section, pos, userActive);
                List<Integer> found = find(section, line.id());
                if (!missing.contains(line)) {
                    // already active: the following lines come after it
                    for (int i : found) if (entry(i).active() && i < limit) pos = Math.max(pos, i + 1);
                    continue;
                }
                added.add(line.text());
                int start = pos;
                int place = found.stream().filter(i -> i >= start && i < limit).findFirst().orElse(-1);
                if (place >= 0) {
                    lines.set(place, line.text());
                    pos = place + 1;
                    continue;
                }
                int at = limit;
                // its place would change the order: the commented-out line goes
                for (int i = found.size() - 1; i >= 0; i--) {
                    int old = found.get(i);
                    if (!entry(old).value().equals(line.value()) && !isSystemRule(old)) continue;
                    lines.remove(old);
                    if (old < at) at--;
                }
                lines.add(at, line.text());
                pos = at + 1;
            }
            int end = end(section);
            for (Line line : missing) {
                if (line.active()) continue;
                lines.add(end++, line.written());
                comments++;
            }
        }

        /** Is the commented-out rule one of the template? */
        private boolean isSystemRule(int line) {
            return List.of(AhkSettingsExplain.template(AhkSettingsFile.TEMPLATE).split("\n"))
                    .contains(lines.get(line).strip());
        }

        /**
         * Where the user lines end: before the first active line from {@code pos} on that is not one of them (with the
         * comments directly above it), otherwise the end of the section.
         */
        private int limit(@NotNull String section, int pos, @NotNull Set<String> userActive) {
            int end = end(section);
            for (int i = pos; i < end; i++) {
                AhkSettingsExplain.Entry entry = AhkSettingsExplain.entry(lines.get(i));
                if (entry == null || !entry.active()) continue;
                if (userActive.contains(entry.key() + "=" + entry.value())) continue;
                int at = i;
                while (at > pos && lines.get(at - 1).stripLeading().startsWith(";")
                        && AhkSettingsExplain.entry(lines.get(at - 1)) == null
                        && !AhkSettingsExplain.isExplanation(lines.get(at - 1))) at--;
                return at;
            }
            return end;
        }

        /** The index after the last line of the section that is not empty (of the first one with this name). */
        private int end(@NotNull String section) {
            int start = header(section);
            int end = start + 1;
            for (int i = start + 1; i < lines.size() && !AhkSettingsExplain.isHeader(lines.get(i)); i++) {
                if (!lines.get(i).isBlank()) end = i + 1;
            }
            return end;
        }

        /** The index of the header of the section, which is added at its place in the template if it is missing. */
        private int header(@NotNull String name) {
            int following = -1;
            int order = order(name);
            for (int i = 0; i < lines.size(); i++) {
                if (!AhkSettingsExplain.isHeader(lines.get(i))) continue;
                String other = AhkSettingsExplain.sectionName(lines.get(i));
                if (other.equalsIgnoreCase(name)) return i;
                if (following < 0 && order(other) > order) following = i;
            }
            if (following >= 0) {
                lines.add(following, "[" + SECTIONS.get(order) + "]");
                lines.add(following + 1, "");
                return following;
            }
            while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) lines.remove(lines.size() - 1);
            if (!lines.isEmpty()) lines.add("");
            lines.add("[" + SECTIONS.get(order) + "]");
            lines.add("");
            return lines.size() - 2;
        }

        /** The position of a section in the template, -1 for an unknown one. */
        private static int order(@NotNull String name) {
            for (int i = 0; i < SECTIONS.size(); i++) {
                if (SECTIONS.get(i).equalsIgnoreCase(name)) return i;
            }
            return -1;
        }
    }
}
