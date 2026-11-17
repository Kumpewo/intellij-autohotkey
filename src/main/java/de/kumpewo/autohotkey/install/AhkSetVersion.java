package de.kumpewo.autohotkey.install;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.validate.AhkValidator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Set Version...": changes the version of a tool in its line {@code ;@Ahk2Exe-SetVersion} ({@link AhkVersionDirective}),
 * then optionally commits only this file and sets the annotated tag {@code <TagPrefix>/v<version>} on the new commit
 * (prefix from {@code [Version]} of {@link AhkInstallConfig settings.ahk-plugin.ini}). The script is the target of
 * Validate/Compile. Order: check ({@link #prepare}, in the background, runs Git) → dialog ({@link AhkSetVersionDialog})
 * → change the line in the document (undoable) and save → commit → tag (background) → notification. Works without Git
 * (then only the line is changed) and without {@code settings.ahk-plugin.ini} (then without tag).
 */
public final class AhkSetVersion {

    /** Paths of the scripts whose version is being set (a second start for the same script is ignored). */
    private static final Set<String> RUNNING = ConcurrentHashMap.newKeySet();

    private AhkSetVersion() {
    }

    /**
     * Everything checked before the dialog.
     *
     * @param script        the script
     * @param toolDir       its folder
     * @param line          the version line, {@code null} if there is none (then {@code errors} is not empty)
     * @param tagPrefix     {@code TagPrefix} of {@code [Version]}, {@code null} = no tag possible
     * @param repository    Git is installed and the tool folder lies in a repository
     * @param scriptChanged the script has uncommitted changes (they would be committed with the version)
     * @param otherChanges  other files of the tool folder have uncommitted changes (they stay uncommitted)
     * @param tags          existing tags with the prefix (case-insensitive)
     * @param errors        problems that prevent setting the version
     */
    public record Prepared(@NotNull Path script, @NotNull Path toolDir, @Nullable AhkVersionDirective.Line line,
                           @Nullable String tagPrefix, boolean repository, boolean scriptChanged, boolean otherChanges,
                           @NotNull Set<String> tags, @NotNull List<String> errors) {

        /** The current version, empty without a line. */
        public @NotNull String current() {
            return line == null ? "" : line.value();
        }

        /** The file name of the script. */
        public @NotNull String fileName() {
            return script.getFileName().toString();
        }

        /** Does the tag exist already? */
        public boolean tagExists(@NotNull String tag) {
            return tags.contains(tag);
        }

        /** Proposed commit and tag message for the version. */
        public @NotNull String message(@NotNull String version) {
            return tagPrefix != null
                    ? AhkBundle.message("version.commit.message.release", tagPrefix,
                            AhkVersionDirective.tagName(tagPrefix, version).substring(tagPrefix.length() + 1))
                    : AhkBundle.message("version.commit.message", version, fileName());
        }
    }

    /**
     * What the user chose in the dialog.
     *
     * @param version the new version
     * @param commit  commit the script
     * @param tag     the tag to set, {@code null} = none (only with {@code commit})
     * @param message commit and tag message
     */
    public record Choice(@NotNull String version, boolean commit, @Nullable String tag, @NotNull String message) {
    }

    /**
     * Reads the version line and the settings and asks Git (not on the EDT).
     *
     * @param script     the script
     * @param scriptText its saved text
     * @param gitEnabled false = behave as if Git were missing (tests)
     */
    public static @NotNull Prepared prepare(@NotNull Path script, @NotNull String scriptText, boolean gitEnabled)
            throws IOException {
        Path toolDir = script.toAbsolutePath().getParent();
        List<String> errors = new ArrayList<>();
        String tagPrefix = null;
        Path ini = toolDir.resolve(AhkInstallConfig.FILE_NAME);
        if (Files.isRegularFile(ini)) {
            AhkInstallConfig config = AhkInstallConfig.parse(AhkInstaller.readText(ini));
            errors.addAll(config.errors());
            tagPrefix = config.tagPrefix();
        }
        String fileName = script.getFileName().toString();
        List<AhkVersionDirective.Line> lines = AhkVersionDirective.find(scriptText);
        AhkVersionDirective.Line line = null;
        if (lines.isEmpty()) {
            errors.add(AhkBundle.message("version.line.missing", fileName));
        } else if (lines.size() > 1) {
            List<String> numbers = new ArrayList<>();
            for (AhkVersionDirective.Line l : lines) numbers.add(String.valueOf(l.line() + 1));
            errors.add(AhkBundle.message("version.line.several", fileName, String.join(", ", numbers)));
        } else if (lines.get(0).computed()) {
            errors.add(AhkBundle.message("version.line.computed", fileName, lines.get(0).line() + 1));
        } else {
            line = lines.get(0);
        }
        AhkInstallGit git = new AhkInstallGit(toolDir);
        boolean repository = gitEnabled && git.inRepository();
        boolean scriptChanged = repository && git.hasChanges(fileName);
        boolean otherChanges = repository && git.hasOtherChanges(fileName);
        Set<String> tags = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (repository && tagPrefix != null) {
            AhkInstallGit.Result result = git.run("tag", "-l", tagPrefix + "/*");
            if (result.ok()) {
                for (String tag : result.stdout().split("\\R")) {
                    if (!tag.isBlank()) tags.add(tag.strip());
                }
            }
        }
        return new Prepared(script, toolDir, line, tagPrefix, repository, scriptChanged, otherChanges, tags, errors);
    }

    /**
     * Commits the script and sets the tag as chosen (the new version is already saved); {@code null} on success,
     * otherwise the error. The tag is only checked with Git here ({@code git check-ref-format}).
     */
    static @Nullable String commitAndTag(@NotNull Prepared prepared, @NotNull Choice choice) {
        AhkInstallGit git = new AhkInstallGit(prepared.toolDir());
        if (choice.tag() != null && !git.isValidTag(choice.tag())) {
            return AhkBundle.message("version.tag.invalid", choice.tag());
        }
        if (choice.commit()) {
            String error = git.commitFile(prepared.fileName(), choice.message());
            if (error != null) return AhkBundle.message("version.commit.failed", error);
        }
        if (choice.tag() != null) {
            String error = git.tag(choice.tag(), choice.message());
            if (error != null) return AhkBundle.message("version.tag.failed", choice.tag(), error);
        }
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
     * Sets the version of the target of {@code file} (call on the EDT): saves the AutoHotkey documents, checks in the
     * background, shows the dialog, changes the line, then commits and tags in the background.
     */
    public static void run(@NotNull Project project, @Nullable VirtualFile file) {
        if (project.isDisposed()) return;
        AhkValidator.Target target = AhkValidator.target(project, file);
        if (target == null) return;
        VirtualFile scriptFile = target.script();
        Path script = Path.of(scriptFile.getPath());
        String title = AhkBundle.message("version.title", scriptFile.getName());
        AhkValidator.saveAhkDocuments();
        if (!RUNNING.add(script.toString())) return;
        new Task.Backgroundable(project, AhkBundle.message("version.progress.check", scriptFile.getName()), true) {
            private Prepared prepared;
            private String failure;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    prepared = prepare(script, AhkInstaller.readText(script), true);
                } catch (IOException e) {
                    failure = e.toString();
                }
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                RUNNING.remove(script.toString());
                super.onThrowable(error);
            }

            @Override
            public void onCancel() {
                RUNNING.remove(script.toString());
            }

            @Override
            public void onSuccess() {
                if (project.isDisposed()) {
                    RUNNING.remove(script.toString());
                    return;
                }
                if (failure != null || !prepared.errors().isEmpty()) {
                    RUNNING.remove(script.toString());
                    String text = failure != null ? failure : "- " + String.join("\n- ", prepared.errors());
                    Messages.showErrorDialog(project, AhkBundle.message("version.errors", text), title);
                    return;
                }
                AhkSetVersionDialog dialog = new AhkSetVersionDialog(project, title, prepared);
                if (!dialog.showAndGet()) {
                    RUNNING.remove(script.toString());
                    return;
                }
                Choice choice = dialog.choice();
                if (!write(project, scriptFile, prepared, choice.version())) {
                    RUNNING.remove(script.toString());
                    Messages.showErrorDialog(project, AhkBundle.message("version.line.changed",
                            scriptFile.getName()), title);
                    return;
                }
                if (!choice.commit()) {
                    RUNNING.remove(script.toString());
                    AhkSetVersion.notify(project, AhkBundle.message("version.done", scriptFile.getName(), choice.version()),
                            NotificationType.INFORMATION);
                    return;
                }
                finish(project, prepared, choice);
            }
        }.queue();
    }

    /** Writes the new version into the document of the script (undoable) and saves it; false if the line changed. */
    private static boolean write(@NotNull Project project, @NotNull VirtualFile scriptFile, @NotNull Prepared prepared,
                                 @NotNull String version) {
        Document document = FileDocumentManager.getInstance().getDocument(scriptFile);
        if (document == null || !document.isWritable()) return false;
        AhkVersionDirective.Line line = currentLine(document.getCharsSequence(), prepared.current());
        if (line == null) return false;
        WriteCommandAction.runWriteCommandAction(project, AhkBundle.message("version.command"), null,
                () -> document.replaceString(line.valueStart(), line.valueEnd(), version));
        FileDocumentManager.getInstance().saveDocument(document);
        return true;
    }

    /** Commits and tags in the background, then reports in a notification. */
    private static void finish(@NotNull Project project, @NotNull Prepared prepared, @NotNull Choice choice) {
        new Task.Backgroundable(project, AhkBundle.message("version.progress", prepared.fileName()), false) {
            private String error;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                error = commitAndTag(prepared, choice);
            }

            @Override
            public void onFinished() {
                RUNNING.remove(prepared.script().toString());
            }

            @Override
            public void onSuccess() {
                if (project.isDisposed()) return;
                if (error != null) {
                    AhkSetVersion.notify(project, AhkBundle.message("version.done.failed", prepared.fileName(),
                            choice.version(), error), NotificationType.ERROR);
                } else {
                    AhkSetVersion.notify(project, choice.tag() != null
                            ? AhkBundle.message("version.done.tagged", prepared.fileName(), choice.version(), choice.tag())
                            : AhkBundle.message("version.done.committed", prepared.fileName(), choice.version()),
                            NotificationType.INFORMATION);
                }
            }
        }.queue();
    }

    private static void notify(@NotNull Project project, @NotNull String text, @NotNull NotificationType type) {
        NotificationGroupManager.getInstance().getNotificationGroup("AutoHotkey").createNotification(text, type)
                .notify(project);
    }
}
