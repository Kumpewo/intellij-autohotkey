package de.kumpewo.autohotkey.install;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.validate.AhkValidator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Set Version...": sets the version of the tool of the project (one tool = one project) in its files, then optionally
 * commits all changes of the project and sets the annotated tag {@code v<version>} on the new commit, so that the tag
 * marks the sources of the deployed tool. Which files ({@link #prepare}, lines of {@code [SetVersion]} as in
 * {@link AhkFileRules}, the last matching line wins):
 * <ul>
 *   <li>every AutoHotkey file unless a line excludes it, and every other file that a line includes: its version line
 *       ({@link AhkVersionDirective}) is changed, or added if it has none (in AutoHotkey files below the first
 *       {@code #Requires} line, otherwise {@code <comment> @SetVersion x} in the comment style of the line or of the
 *       file type as the first line, after a first line {@code <?xml}, {@code #!}, registry header or
 *       {@code @echo off}); in an AutoHotkey file its keyword becomes that of
 *       {@link AhkVersionDirective#keyword}: the compiler directive {@code ;@Ahk2Exe-SetVersion} with
 *       {@code ;@Ahk2Exe-Base}, otherwise the comment {@code ;@SetVersion}; a line read with another comment
 *       start ({@code REM} in a batch file) is written in the style ({@code ::}, {@link #otherMarker}); a line at the
 *       start right before such a first line moves behind it ({@link #moveBehindHeader});</li>
 *   <li>binary files never (warning), excluded and other files not at all.</li>
 * </ul>
 * Commit and tag are only offered if every file of the project is either versioned or ignored (Git). Order: check (in
 * the background, runs Git) → dialog ({@link AhkSetVersionDialog}) → change the lines in the documents (undoable) and
 * save → commit → tag (background) → notification. Needs no {@code settings.ahk-v2-plugin.ini}; only mistakes of the file
 * itself or of {@code [SetVersion]} stop. Works without Git (then only the lines are changed). Excluded files still
 * count for commit and tag (Git ignores files only through {@code .gitignore}). Without any file to change, a
 * hint ({@link Missing}) offers to create or edit the settings.
 */
public final class AhkSetVersion {

    /** Project roots whose version is being set (a second start for the same project is ignored). */
    private static final Set<String> RUNNING = ConcurrentHashMap.newKeySet();

    private AhkSetVersion() {
    }

    /** The comment style of the version line in AutoHotkey files. */
    static final AhkFileRules.Style AUTOHOTKEY_STYLE = new AhkFileRules.Style(";", null, false);

    /** Why no file gets the version ({@link Prepared#missingText}). */
    public enum Missing {
        /** The project root has no {@code settings.ahk-v2-plugin.ini} (offers "Create Settings"). */
        SETTINGS,
        /** {@code [SetVersion]} has no line with {@code yes} (offers to edit the settings). */
        NO_INCLUDE,
        /** The lines with {@code yes} in {@code [SetVersion]} match no file (offers to edit the settings). */
        NO_MATCH
    }

    /**
     * A file that gets the new version.
     *
     * @param file  the file
     * @param name  its path relative to the project root (for messages)
     * @param line  its version line, {@code null} if the line is added
     * @param style its comment style (for finding and adding the line)
     */
    public record Script(@NotNull Path file, @NotNull String name, @Nullable AhkVersionDirective.Line line,
                         @NotNull AhkFileRules.Style style) {
        /** A script whose line is changed (AutoHotkey style). */
        public Script(@NotNull Path file, @NotNull String name, @NotNull AhkVersionDirective.Line line) {
            this(file, name, line, AUTOHOTKEY_STYLE);
        }

        /** Is the version line added (the file has none yet)? */
        public boolean added() {
            return line == null;
        }
    }

    /**
     * Everything checked before the dialog.
     *
     * @param root       the project root
     * @param tool       name of the tool (the project name), for messages
     * @param scripts    the scripts with a version line, sorted by path
     * @param errors     problems that prevent setting the version
     * @param warnings   hints shown in the dialog (binary files)
     * @param repository Git is installed and the project root lies in a repository
     * @param untracked  files of the project that are neither versioned nor ignored (then no commit is possible)
     * @param changed    versioned files of the project with uncommitted changes (they are committed with the version)
     * @param tags       existing version tags {@code v*} (case-insensitive)
     * @param missing    why no file gets the version, {@code null} if one does or {@code errors} is not empty
     */
    public record Prepared(@NotNull Path root, @NotNull String tool, @NotNull List<Script> scripts,
                           @NotNull List<String> errors, @NotNull List<String> warnings, boolean repository,
                           @NotNull List<String> untracked, @NotNull List<String> changed, @NotNull Set<String> tags,
                           @Nullable Missing missing) {

        /** The hint for {@link #missing} (the state of the settings); empty if nothing is missing. */
        public @NotNull String missingText() {
            if (missing == null) return "";
            return AhkBundle.message(switch (missing) {
                case SETTINGS -> "version.missing.settings";
                case NO_INCLUDE -> "version.missing.include";
                case NO_MATCH -> "version.missing.match";
            });
        }

        /** The current version: the highest of the scripts with a line, empty without one. */
        public @NotNull String current() {
            String current = "";
            for (Script script : scripts) {
                if (script.line() == null) continue;
                String value = script.line().value();
                if (current.isEmpty() || AhkVersionDirective.compare(value, current) > 0) current = value;
            }
            return current;
        }

        /** The scripts with their versions ({@code name version}) if they differ, otherwise empty. */
        public @NotNull List<String> differentVersions() {
            Set<String> versions = new LinkedHashSet<>();
            List<String> each = new ArrayList<>();
            for (Script script : scripts) {
                if (script.line() == null) continue;
                versions.add(script.line().value());
                each.add(script.name() + " " + script.line().value());
            }
            return versions.size() > 1 ? each : List.of();
        }

        /** Can the version be committed and tagged (Git repository, no untracked files)? */
        public boolean canCommit() {
            return repository && untracked.isEmpty();
        }

        /** Does the tag exist already? */
        public boolean tagExists(@NotNull String tag) {
            return tags.contains(tag);
        }

        /** Proposed commit and tag message for the version. */
        public @NotNull String message(@NotNull String version) {
            return AhkBundle.message("version.commit.message", tool, AhkVersionDirective.tagName(version));
        }
    }

    /**
     * What the user chose in the dialog.
     *
     * @param version the new version
     * @param commit  commit the project and set the tag {@link #tag}
     * @param tag     the tag {@code v<version>}
     * @param message commit and tag message
     */
    public record Choice(@NotNull String version, boolean commit, @NotNull String tag, @NotNull String message) {
    }

    /**
     * The settings file of the root ({@link AhkDeployConfig#file}, also one of version 2.1), empty without one; read
     * mistakes go to the errors.
     */
    static @NotNull AhkDeployConfig readConfig(@NotNull Path root, @NotNull List<String> errors) {
        Path ini = AhkDeployConfig.file(root);
        if (ini == null) return AhkDeployConfig.parse("");
        try {
            return AhkDeployConfig.load(ini);
        } catch (IOException e) {
            errors.add(e.toString());
            return AhkDeployConfig.parse("");
        }
    }

    /**
     * Of the files of the project, those that "Set Version" looks at: AutoHotkey files not excluded, and files that a
     * line of {@code [SetVersion]} includes (also binary ones, for the warning).
     */
    static @NotNull List<Path> candidates(@NotNull Path root, @NotNull AhkDeployConfig config,
                                          @NotNull List<Path> files) {
        List<Path> result = new ArrayList<>();
        for (Path file : files) {
            String name = AhkDeployer.relative(root, file.toAbsolutePath().normalize());
            AhkFileRules.Rule rule = AhkFileRules.find(config.versionRules(), name);
            if (rule != null ? rule.include() : AhkFileRules.isAutoHotkey(name)) result.add(file);
        }
        return result;
    }

    /**
     * Reads the version lines of the files as described in the class comment and asks Git (not on the EDT).
     *
     * @param root       the project root
     * @param tool       name of the tool (the project name)
     * @param texts      the saved texts of the files to look at ({@link #candidates}; a binary file may map to "")
     * @param gitEnabled false = behave as if Git were missing (tests)
     */
    public static @NotNull Prepared prepare(@NotNull Path root, @NotNull String tool, @NotNull Map<Path, String> texts,
                                            boolean gitEnabled) {
        Path dir = root.toAbsolutePath().normalize();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<Script> scripts = new ArrayList<>();
        AhkDeployConfig config = readConfig(dir, errors);
        errors.addAll(config.versionErrors());
        Map<Path, String> sorted = new java.util.TreeMap<>(texts);
        for (Map.Entry<Path, String> entry : sorted.entrySet()) {
            String name = AhkDeployer.relative(dir, entry.getKey().toAbsolutePath().normalize());
            AhkFileRules.Rule rule = AhkFileRules.find(config.versionRules(), name);
            boolean autoHotkey = AhkFileRules.isAutoHotkey(name);
            if (rule != null ? !rule.include() : !autoHotkey) continue;
            if (AhkFileRules.isBinary(name)) {
                warnings.add(AhkBundle.message("version.binary", name));
                continue;
            }
            AhkFileRules.Style style = autoHotkey ? AUTOHOTKEY_STYLE : AhkFileRules.style(rule, name);
            if (style == null) {
                errors.add(AhkBundle.message("version.style.unknown", name));
                continue;
            }
            List<AhkVersionDirective.Line> lines = AhkVersionDirective.findIn(name, entry.getValue(), style);
            if (lines.size() > 1) {
                List<String> numbers = new ArrayList<>();
                for (AhkVersionDirective.Line l : lines) numbers.add(String.valueOf(l.line() + 1));
                errors.add(AhkBundle.message("version.line.several", name, String.join(", ", numbers)));
            } else if (lines.size() == 1 && lines.get(0).computed()) {
                errors.add(AhkBundle.message("version.line.computed", name, lines.get(0).line() + 1));
            } else if (lines.size() == 1) {
                scripts.add(new Script(entry.getKey(), name, lines.get(0), style));
            } else {
                // an AutoHotkey file or an included file without a version line: the line is added
                scripts.add(new Script(entry.getKey(), name, null, style));
            }
        }
        Missing missing = null;
        if (scripts.isEmpty() && errors.isEmpty()) {
            if (AhkDeployConfig.file(dir) == null) missing = Missing.SETTINGS;
            else if (config.versionRules().stream().anyMatch(AhkFileRules.Rule::include)) missing = Missing.NO_MATCH;
            else missing = Missing.NO_INCLUDE;
        }
        AhkDeployGit git = new AhkDeployGit(dir);
        boolean repository = gitEnabled && git.inRepository();
        List<String> untracked = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        Set<String> tags = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (repository) {
            for (String line : git.status()) {
                if (line.length() < 4) continue;
                (line.startsWith("??") ? untracked : changed).add(line.substring(3));
            }
            AhkDeployGit.Result result = git.run("tag", "-l", "v*");
            if (result.ok()) {
                for (String tag : result.stdout().split("\\R")) {
                    if (!tag.isBlank()) tags.add(tag.strip());
                }
            }
        }
        return new Prepared(dir, tool, scripts, errors, warnings, repository, untracked, changed, tags, missing);
    }

    /**
     * Commits all changes of the project and sets the tag as chosen (the new version is already saved); {@code null}
     * on success, otherwise the error. The tag is only checked with Git here ({@code git check-ref-format}).
     */
    static @Nullable String commitAndTag(@NotNull Prepared prepared, @NotNull Choice choice) {
        if (!choice.commit()) return null;
        AhkDeployGit git = new AhkDeployGit(prepared.root());
        if (!git.isValidTag(choice.tag())) return AhkBundle.message("version.tag.invalid", choice.tag());
        String error = git.commitAll(choice.message());
        if (error != null) return AhkBundle.message("version.commit.failed", error);
        error = git.tag(choice.tag(), choice.message());
        if (error != null) return AhkBundle.message("version.tag.failed", choice.tag(), error);
        return null;
    }

    /**
     * The only version line of the text; {@code null} if there is not exactly one or its value is not the expected one
     * (the script was changed meanwhile).
     */
    static @Nullable AhkVersionDirective.Line currentLine(@NotNull CharSequence text, @NotNull String expected) {
        return currentLine("x.ahk", text, expected, AUTOHOTKEY_STYLE);
    }

    /** As {@link #currentLine(CharSequence, String)} for a file of any type with its comment style. */
    static @Nullable AhkVersionDirective.Line currentLine(@NotNull String name, @NotNull CharSequence text,
                                                         @NotNull String expected, @NotNull AhkFileRules.Style style) {
        List<AhkVersionDirective.Line> lines = AhkVersionDirective.findIn(name, text, style);
        return lines.size() == 1 && lines.get(0).value().equals(expected) ? lines.get(0) : null;
    }

    /**
     * Moving a version line behind the header line ({@link #moveBehindHeader}); offsets in the original text.
     *
     * @param insertAt    where the new line is inserted
     * @param inserted    the new line with its line break
     * @param removeStart start of the old line
     * @param removeEnd   end of the old line, after its line break
     */
    record Move(int insertAt, @NotNull String inserted, int removeStart, int removeEnd) {
    }

    /**
     * How the version line of a file other than an AutoHotkey file moves behind the header line (e.g.
     * {@code @echo off}) when it stands right before it, at the start of the file: the line is written anew in the
     * style of the file; {@code null} if it stays where it is.
     */
    static @Nullable Move moveBehindHeader(@NotNull String name, @NotNull CharSequence text,
                                          @NotNull AhkVersionDirective.Line line, @NotNull AhkFileRules.Style style,
                                          @NotNull String version) {
        if (AhkFileRules.isAutoHotkey(name)) return null;
        int lineStart = line.valueStart();
        while (lineStart > 0 && text.charAt(lineStart - 1) != '\n' && text.charAt(lineStart - 1) != '\r') lineStart--;
        int bom = text.length() > 0 && text.charAt(0) == '﻿' ? 1 : 0;
        if (lineStart == 0) lineStart = bom;
        if (lineStart != bom) return null;
        int breakAt = line.valueEnd();
        while (breakAt < text.length() && text.charAt(breakAt) != '\n' && text.charAt(breakAt) != '\r') breakAt++;
        if (breakAt >= text.length()) return null;
        int lineEnd = breakAt + AhkBanner.indexOfLineEnd(text.subSequence(breakAt, text.length()));
        String lineBreak = text.subSequence(breakAt, lineEnd).toString();
        CharSequence rest = text.subSequence(lineEnd, text.length());
        if (!AhkBanner.hasHeaderLine(rest)) return null;
        String newLine = versionLine(name, text, style, version);
        int header = AhkBanner.indexOfLineEnd(rest);
        if (header < 0) return new Move(text.length(), lineBreak + newLine, lineStart, lineEnd);
        return new Move(lineEnd + header, newLine + lineBreak, lineStart, lineEnd);
    }

    /**
     * Start and end of the comment start of the version line if it is not the start of the style (e.g. {@code REM}
     * in a batch file, whose style writes {@code ::}, {@link AhkFileRules#readStarts}); {@code null} otherwise. The
     * line is then written in the style of the file.
     */
    static int @Nullable [] otherMarker(@NotNull CharSequence text, @NotNull AhkVersionDirective.Line line,
                                        @NotNull AhkFileRules.Style style) {
        int lineStart = line.valueStart();
        while (lineStart > 0 && text.charAt(lineStart - 1) != '\n' && text.charAt(lineStart - 1) != '\r') lineStart--;
        int start = lineStart;
        while (start < line.valueStart() && (text.charAt(start) == ' ' || text.charAt(start) == '\t'
                || text.charAt(start) == '﻿')) start++;
        String rest = text.subSequence(start, line.valueStart()).toString();
        if (rest.startsWith(style.start())) return null;
        for (String other : AhkFileRules.readStarts(style)) {
            if (rest.regionMatches(true, 0, other, 0, other.length())) return new int[]{start, start + other.length()};
        }
        return null;
    }

    /**
     * The version line to add to a file with this text: in an AutoHotkey file with
     * {@link AhkVersionDirective#keyword}, otherwise {@code <start> @SetVersion x [<end>]}.
     */
    static @NotNull String versionLine(@NotNull String name, @NotNull CharSequence text,
                                       @NotNull AhkFileRules.Style style, @NotNull String version) {
        if (AhkFileRules.isAutoHotkey(name)) return AhkVersionDirective.keyword(text) + " " + version;
        return style.start() + " @SetVersion " + version + (style.end() == null ? "" : " " + style.end());
    }

    /**
     * Where the version line is added: in an AutoHotkey file below the first {@code #Requires} line (as the head of
     * Ahk2Exe directives), otherwise at the start, after a first line {@code <?xml}, {@code #!}, registry header or {@code @echo off}
     * ({@code breakBefore[0]} is set if the line before has no line break).
     */
    static int insertionOffset(@NotNull String name, @NotNull CharSequence text, boolean @NotNull [] breakBefore) {
        int start = text.length() > 0 && text.charAt(0) == '\uFEFF' ? 1 : 0;
        breakBefore[0] = false;
        if (AhkFileRules.isAutoHotkey(name)) {
            int offset = de.kumpewo.autohotkey.compile.AhkAhk2ExeHeader.insertionOffset(text);
            if (offset > 0) {
                breakBefore[0] = de.kumpewo.autohotkey.compile.AhkAhk2ExeHeader.needsLineBreakBefore(text, offset);
                return offset;
            }
        }
        if (!AhkBanner.hasHeaderLine(text.subSequence(start, text.length()))) return start;
        int end = AhkBanner.indexOfLineEnd(text.subSequence(start, text.length()));
        if (end >= 0) return start + end;
        breakBefore[0] = true;
        return text.length();
    }

    /**
     * The files of the project under the root (content, without excluded folders), call in a read action.
     */
    static @NotNull List<Path> projectScripts(@NotNull Project project, @NotNull Path root) {
        List<Path> result = new ArrayList<>();
        ProjectFileIndex.getInstance(project).iterateContent(file -> {
            if (!file.isDirectory() && file.isInLocalFileSystem()) {
                try {
                    Path path = file.toNioPath().toAbsolutePath().normalize();
                    if (path.startsWith(root)) result.add(path);
                } catch (UnsupportedOperationException | InvalidPathException ignored) {
                    // not a local file
                }
            }
            return true;
        });
        return result;
    }

    /**
     * Sets the version of the project (call on the EDT): saves all modified documents, checks in the background,
     * shows the dialog, changes the lines, then commits and tags in the background.
     */
    public static void run(@NotNull Project project) {
        Path root = AhkProjectRoot.path(project);
        if (root == null) return;
        Path dir = root.toAbsolutePath().normalize();
        String tool = project.getName();
        String title = AhkBundle.message("version.title", tool);
        if (!AhkValidator.saveAllDocuments(project, title)) return;
        String key = dir.toString();
        if (!RUNNING.add(key)) return;
        new Task.Backgroundable(project, AhkBundle.message("version.progress.check", tool), true) {
            private Prepared prepared;
            private String failure;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                List<Path> files = ReadAction.compute(() -> project.isDisposed() ? List.of()
                        : projectScripts(project, dir));
                files = candidates(dir, readConfig(dir, new ArrayList<>()), files);
                Map<Path, String> texts = new LinkedHashMap<>();
                try {
                    for (Path file : files) {
                        texts.put(file, AhkFileRules.isBinary(file.toString()) ? "" : AhkDeployer.readText(file));
                    }
                } catch (IOException e) {
                    failure = e.toString();
                    return;
                }
                prepared = prepare(dir, tool, texts, true);
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                RUNNING.remove(key);
                super.onThrowable(error);
            }

            @Override
            public void onCancel() {
                RUNNING.remove(key);
            }

            @Override
            public void onSuccess() {
                if (project.isDisposed()) {
                    RUNNING.remove(key);
                    return;
                }
                if (failure != null || !prepared.errors().isEmpty()) {
                    RUNNING.remove(key);
                    String text = failure != null ? failure : "- " + String.join("\n- ",
                            AhkDialogs.limit(prepared.errors(), AhkDialogs.MAX_ERRORS));
                    Messages.showErrorDialog(project, AhkBundle.message("version.errors", text), title);
                    return;
                }
                if (prepared.missing() != null) {
                    RUNNING.remove(key);
                    offerSettings(project, dir, prepared, title);
                    return;
                }
                AhkSetVersionDialog dialog = new AhkSetVersionDialog(project, title, prepared);
                if (!dialog.showAndGet()) {
                    RUNNING.remove(key);
                    return;
                }
                Choice choice = dialog.choice();
                String changed = write(project, prepared, choice.version());
                if (changed != null) {
                    RUNNING.remove(key);
                    Messages.showErrorDialog(project, AhkBundle.message("version.line.changed", changed), title);
                    return;
                }
                if (!choice.commit()) {
                    RUNNING.remove(key);
                    AhkSetVersion.notify(project, AhkBundle.message("version.done", tool, choice.version(),
                            prepared.scripts().size()), NotificationType.INFORMATION);
                    return;
                }
                finish(project, prepared, choice);
            }
        }.queue();
    }

    /**
     * Shows why no file gets the version and offers "Create Settings" (no {@code settings.ahk-v2-plugin.ini}) or "Edit
     * Settings" (opens the file at {@code [SetVersion]}) (call on the EDT).
     */
    private static void offerSettings(@NotNull Project project, @NotNull Path dir, @NotNull Prepared prepared,
                                      @NotNull String title) {
        boolean create = prepared.missing() == Missing.SETTINGS;
        String button = AhkBundle.message(create ? "version.missing.create" : "version.missing.edit");
        if (Messages.showYesNoDialog(project, prepared.missingText(), title, button, Messages.getCancelButton(),
                Messages.getWarningIcon()) != Messages.YES) {
            return;
        }
        if (create) {
            AhkSettingsFile.create(project, SECTION);
            return;
        }
        Path found = AhkDeployConfig.file(dir);
        VirtualFile ini = found == null ? null : LocalFileSystem.getInstance().refreshAndFindFileByNioFile(found);
        if (ini != null) AhkSettingsFile.open(project, ini, SECTION);
    }

    /** The section of the settings file that "Set Version" reads. */
    private static final String SECTION = "SetVersion";

    /** The line of the header {@code [section]} (ignoring case), -1 without one. */
    static int sectionLine(@NotNull String text, @NotNull String section) {
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].replace(String.valueOf((char) 0xFEFF), "").strip();
            if (line.equalsIgnoreCase("[" + section + "]")) return i;
        }
        return -1;
    }

    /**
     * Writes the new version into the documents of all scripts (one undoable command) and saves them; nothing is
     * changed and the name of the script is returned if one of the lines changed meanwhile or is read-only.
     */
    private static @Nullable String write(@NotNull Project project, @NotNull Prepared prepared,
                                          @NotNull String version) {
        List<Document> documents = new ArrayList<>();
        List<Runnable> changes = new ArrayList<>();
        for (Script script : prepared.scripts()) {
            VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(script.file());
            Document document = file == null ? null : FileDocumentManager.getInstance().getDocument(file);
            if (document == null || !document.isWritable()) return script.name();
            CharSequence text = document.getCharsSequence();
            if (script.line() == null) {
                // add the line, if the file still has none
                if (!AhkVersionDirective.findIn(script.name(), text, script.style()).isEmpty()) return script.name();
                boolean[] breakBefore = new boolean[1];
                int offset = insertionOffset(script.name(), text, breakBefore);
                String line = versionLine(script.name(), text, script.style(), version);
                changes.add(() -> document.insertString(offset, breakBefore[0] ? "\n" + line + "\n" : line + "\n"));
            } else {
                AhkVersionDirective.Line line = currentLine(script.name(), text, script.line().value(), script.style());
                if (line == null) return script.name();
                Move move = moveBehindHeader(script.name(), text, line, script.style(), version);
                if (move != null) {
                    // insert behind the header first (later in the text), then remove the old line
                    changes.add(() -> {
                        document.insertString(move.insertAt(), move.inserted());
                        document.deleteString(move.removeStart(), move.removeEnd());
                    });
                    documents.add(document);
                    continue;
                }
                boolean autoHotkey = AhkFileRules.isAutoHotkey(script.name());
                int[] marker = autoHotkey ? AhkVersionDirective.keywordRange(text, line)
                        : otherMarker(text, line, script.style());
                String start = autoHotkey ? AhkVersionDirective.keyword(text) : script.style().start();
                changes.add(() -> {
                    // an empty value directly behind the keyword needs a space
                    boolean space = autoHotkey && marker[1] == line.valueStart();
                    document.replaceString(line.valueStart(), line.valueEnd(), space ? " " + version : version);
                    // the value comes after the marker: its offsets stay valid
                    if (marker != null) document.replaceString(marker[0], marker[1], start);
                });
            }
            documents.add(document);
        }
        WriteCommandAction.runWriteCommandAction(project, AhkBundle.message("version.command"), null, () -> {
            for (Runnable change : changes) change.run();
        });
        for (Document document : documents) FileDocumentManager.getInstance().saveDocument(document);
        return null;
    }

    /** Commits and tags in the background, then reports in a notification. */
    private static void finish(@NotNull Project project, @NotNull Prepared prepared, @NotNull Choice choice) {
        new Task.Backgroundable(project, AhkBundle.message("version.progress", prepared.tool()), false) {
            private String error;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                error = commitAndTag(prepared, choice);
            }

            @Override
            public void onFinished() {
                RUNNING.remove(prepared.root().toString());
            }

            @Override
            public void onSuccess() {
                if (project.isDisposed()) return;
                if (error != null) {
                    AhkSetVersion.notify(project, AhkBundle.message("version.done.failed", prepared.tool(),
                            choice.version(), error), NotificationType.ERROR);
                } else {
                    AhkSetVersion.notify(project, AhkBundle.message("version.done.tagged", prepared.tool(),
                            choice.version(), choice.tag()), NotificationType.INFORMATION);
                }
            }
        }.queue();
    }

    private static void notify(@NotNull Project project, @NotNull String text, @NotNull NotificationType type) {
        NotificationGroupManager.getInstance().getNotificationGroup("AutoHotkey").createNotification(text, type)
                .notify(project);
    }
}
