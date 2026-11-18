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
import de.kumpewo.autohotkey.AhkFileType;
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
 * "Set Version...": sets the version of the tool of the project (one tool = one project) in every AutoHotkey file of
 * the project that has a line {@code ;@Ahk2Exe-SetVersion} ({@link AhkVersionDirective}), then optionally commits all
 * changes of the project and sets the annotated tag {@code v<version>} on the new commit, so that the tag marks the
 * sources of the deployed tool. Commit and tag are only offered if every file of the project is either versioned or
 * ignored (Git). Order: check ({@link #prepare}, in the background, runs Git) → dialog ({@link AhkSetVersionDialog})
 * → change the lines in the documents (undoable) and save → commit → tag (background) → notification. Needs no
 * {@code settings.ahk-plugin.ini}; if there is one, the files named in its section {@code [SetVersion]} are left out
 * silently ({@link AhkInstallConfig#excludes}), and only mistakes of the file itself or of that section stop. Works
 * without Git (then only the lines are changed). Excluded files still count for commit and tag (Git ignores files
 * only through {@code .gitignore}).
 */
public final class AhkSetVersion {

    /** Project roots whose version is being set (a second start for the same project is ignored). */
    private static final Set<String> RUNNING = ConcurrentHashMap.newKeySet();

    private AhkSetVersion() {
    }

    /**
     * A script with a version line.
     *
     * @param file the script
     * @param name its path relative to the project root (for messages)
     * @param line its version line
     */
    public record Script(@NotNull Path file, @NotNull String name, @NotNull AhkVersionDirective.Line line) {
    }

    /**
     * Everything checked before the dialog.
     *
     * @param root       the project root
     * @param tool       name of the tool (the project name), for messages
     * @param scripts    the scripts with a version line, sorted by path
     * @param errors     problems that prevent setting the version
     * @param warnings   hints shown in the dialog (e.g. different versions)
     * @param repository Git is installed and the project root lies in a repository
     * @param untracked  files of the project that are neither versioned nor ignored (then no commit is possible)
     * @param changed    versioned files of the project with uncommitted changes (they are committed with the version)
     * @param tags       existing version tags {@code v*} (case-insensitive)
     */
    public record Prepared(@NotNull Path root, @NotNull String tool, @NotNull List<Script> scripts,
                           @NotNull List<String> errors, @NotNull List<String> warnings, boolean repository,
                           @NotNull List<String> untracked, @NotNull List<String> changed, @NotNull Set<String> tags) {

        /** The current version: the highest of the scripts, empty without scripts. */
        public @NotNull String current() {
            String current = "";
            for (Script script : scripts) {
                String value = script.line().value();
                if (current.isEmpty() || AhkVersionDirective.compare(value, current) > 0) current = value;
            }
            return current;
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
     * Reads the version lines (without the files excluded in {@code settings.ahk-plugin.ini}, if there is one in the
     * root) and asks Git (not on the EDT).
     *
     * @param root       the project root
     * @param tool       name of the tool (the project name)
     * @param texts      the saved texts of the AutoHotkey files of the project
     * @param gitEnabled false = behave as if Git were missing (tests)
     */
    public static @NotNull Prepared prepare(@NotNull Path root, @NotNull String tool, @NotNull Map<Path, String> texts,
                                            boolean gitEnabled) {
        Path dir = root.toAbsolutePath().normalize();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<Script> scripts = new ArrayList<>();
        AhkInstallConfig config = AhkInstallConfig.parse("");
        Path ini = dir.resolve(AhkInstallConfig.FILE_NAME);
        if (Files.isRegularFile(ini)) {
            try {
                config = AhkInstallConfig.parse(AhkInstaller.readText(ini));
                errors.addAll(config.versionErrors());
            } catch (IOException e) {
                errors.add(e.toString());
            }
        }
        Map<Path, String> sorted = new java.util.TreeMap<>(texts);
        for (Map.Entry<Path, String> entry : sorted.entrySet()) {
            String name = AhkInstaller.relative(dir, entry.getKey().toAbsolutePath().normalize());
            if (config.excludes(name)) continue;
            List<AhkVersionDirective.Line> lines = AhkVersionDirective.find(entry.getValue());
            if (lines.isEmpty()) continue;
            if (lines.size() > 1) {
                List<String> numbers = new ArrayList<>();
                for (AhkVersionDirective.Line l : lines) numbers.add(String.valueOf(l.line() + 1));
                errors.add(AhkBundle.message("version.line.several", name, String.join(", ", numbers)));
            } else if (lines.get(0).computed()) {
                errors.add(AhkBundle.message("version.line.computed", name, lines.get(0).line() + 1));
            } else {
                scripts.add(new Script(entry.getKey(), name, lines.get(0)));
            }
        }
        if (scripts.isEmpty() && errors.isEmpty()) errors.add(AhkBundle.message("version.line.missing"));
        Set<String> versions = new LinkedHashSet<>();
        for (Script script : scripts) versions.add(script.line().value());
        if (versions.size() > 1) {
            List<String> each = new ArrayList<>();
            for (Script script : scripts) each.add(script.name() + " " + script.line().value());
            warnings.add(AhkBundle.message("version.dialog.different", String.join(", ", each)));
        }
        AhkInstallGit git = new AhkInstallGit(dir);
        boolean repository = gitEnabled && git.inRepository();
        List<String> untracked = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        Set<String> tags = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (repository) {
            for (String line : git.status()) {
                if (line.length() < 4) continue;
                (line.startsWith("??") ? untracked : changed).add(line.substring(3));
            }
            AhkInstallGit.Result result = git.run("tag", "-l", "v*");
            if (result.ok()) {
                for (String tag : result.stdout().split("\\R")) {
                    if (!tag.isBlank()) tags.add(tag.strip());
                }
            }
        }
        return new Prepared(dir, tool, scripts, errors, warnings, repository, untracked, changed, tags);
    }

    /**
     * Commits all changes of the project and sets the tag as chosen (the new version is already saved); {@code null}
     * on success, otherwise the error. The tag is only checked with Git here ({@code git check-ref-format}).
     */
    static @Nullable String commitAndTag(@NotNull Prepared prepared, @NotNull Choice choice) {
        if (!choice.commit()) return null;
        AhkInstallGit git = new AhkInstallGit(prepared.root());
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
        List<AhkVersionDirective.Line> lines = AhkVersionDirective.find(text);
        return lines.size() == 1 && lines.get(0).value().equals(expected) ? lines.get(0) : null;
    }

    /**
     * The AutoHotkey files of the project under the root (content, without excluded folders), call in a read action.
     */
    static @NotNull List<Path> projectScripts(@NotNull Project project, @NotNull Path root) {
        List<Path> result = new ArrayList<>();
        ProjectFileIndex.getInstance(project).iterateContent(file -> {
            if (!file.isDirectory() && file.isInLocalFileSystem() && file.getFileType() == AhkFileType.INSTANCE) {
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
        AhkValidator.saveAllDocuments();
        String key = dir.toString();
        if (!RUNNING.add(key)) return;
        new Task.Backgroundable(project, AhkBundle.message("version.progress.check", tool), true) {
            private Prepared prepared;
            private String failure;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                List<Path> files = ReadAction.compute(() -> project.isDisposed() ? List.of()
                        : projectScripts(project, dir));
                Map<Path, String> texts = new LinkedHashMap<>();
                try {
                    for (Path file : files) texts.put(file, AhkInstaller.readText(file));
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
                    String text = failure != null ? failure : "- " + String.join("\n- ", prepared.errors());
                    Messages.showErrorDialog(project, AhkBundle.message("version.errors", text), title);
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
     * Writes the new version into the documents of all scripts (one undoable command) and saves them; nothing is
     * changed and the name of the script is returned if one of the lines changed meanwhile or is read-only.
     */
    private static @Nullable String write(@NotNull Project project, @NotNull Prepared prepared,
                                          @NotNull String version) {
        List<Document> documents = new ArrayList<>();
        List<AhkVersionDirective.Line> lines = new ArrayList<>();
        for (Script script : prepared.scripts()) {
            VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(script.file());
            Document document = file == null ? null : FileDocumentManager.getInstance().getDocument(file);
            AhkVersionDirective.Line line = document == null || !document.isWritable() ? null
                    : currentLine(document.getCharsSequence(), script.line().value());
            if (line == null) return script.name();
            documents.add(document);
            lines.add(line);
        }
        WriteCommandAction.runWriteCommandAction(project, AhkBundle.message("version.command"), null, () -> {
            for (int i = 0; i < documents.size(); i++) {
                documents.get(i).replaceString(lines.get(i).valueStart(), lines.get(i).valueEnd(), version);
            }
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
