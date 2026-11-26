package de.kumpewo.autohotkey.install;

import com.intellij.CommonBundle;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.filters.TextConsoleBuilder;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.compile.AhkCompiler;
import de.kumpewo.autohotkey.include.AhkLibraryRootsProvider;
import de.kumpewo.autohotkey.run.AhkErrorFilter;
import de.kumpewo.autohotkey.settings.AhkSettings;
import de.kumpewo.autohotkey.validate.AhkValidator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.awt.Font;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * "Deploy to Local Target...": copies the tool of the project (one tool = one project) as described by the
 * {@link AhkDeployConfig settings.ahk-v2-plugin.ini} in the project root ({@link AhkProjectRoot}). With
 * {@code Compile} (default) every executable named in a copy line (without wildcards) that has a script of the same
 * name next to it is compiled from that script first, e.g. a GUI and a console variant. No commit and no tag (that is
 * "Set Version...", {@link AhkSetVersion}); uncommitted changes of the project are only a warning. Order: read and check everything ({@link #prepare}, in the background) → confirmation
 * ({@link AhkDeployDialog}, always) → with files to remove ({@code [Cleanup]}) a second confirmation with each file
 * ({@link AhkCleanupDialog}: remove, deploy without removing, cancel) → compile each script ({@link AhkCompiler#run})
 * → move the files of {@code [Cleanup]} to the Recycle Bin → copy ({@link AhkDeployCopier}) → bring the changed
 * files into the VFS ({@link #refreshFiles}) and report a new Lib folder ({@link AhkLibraryRootsProvider}) → report in
 * a console of the Run tool window. Every problem found before the start is an error and nothing happens; a failure later
 * stops the remaining steps (no rollback).
 */
public final class AhkDeployer {

    /** Green text in the console for success (same color as Validate/Compile). */
    private static final ConsoleViewContentType SUCCESS = new ConsoleViewContentType("AutoHotkey.InstallSuccess",
            new TextAttributes(new JBColor(new Color(0x067D17), new Color(0x6AAB73)), null, null, null, Font.PLAIN));

    /** Console of the last deployment in the project, reused by the next one. */
    private static final Key<RunContentDescriptor> CONSOLE = Key.create("AhkDeployer.console");

    /** Project roots being deployed (a second start for the same project is ignored). */
    private static final Set<String> RUNNING = ConcurrentHashMap.newKeySet();

    /** {@code ;@Ahk2Exe-ExeName path} (output file of Ahk2Exe). */
    private static final Pattern EXE_NAME = Pattern.compile("(?im)^\\uFEFF?[ \\t]*;@Ahk2Exe-ExeName[ \\t]+(.+?)[ \\t]*$");

    private AhkDeployer() {
    }

    /**
     * Everything checked before the start.
     *
     * @param root        the project root
     * @param config      the read {@code settings.ahk-v2-plugin.ini}
     * @param plan        the resolved copies (checked again after compiling)
     * @param errors      problems that prevent the deployment (all are shown)
     * @param warnings    warnings shown in the confirmation (red)
     * @param hints       hints shown in the confirmation (gray, nothing wrong)
     * @param uncommitted the project root has uncommitted changes (Git)
     * @param overview    the overview for the confirmation ({@code GenerateOverview}): compile steps, then the lines
     *                    of {@code [Install]} and {@code [InstallIfMissing]} without comments
     * @param compile     the scripts to compile ({@code Compile}), in the order of the copy lines,
     *                    each only once
     * @param willExist   the executables the compilation will create (as far as known beforehand)
     */
    public record Prepared(@NotNull Path root, @NotNull AhkDeployConfig config,
                           @NotNull AhkDeployPlan plan, @NotNull List<String> errors, @NotNull List<String> warnings,
                           @NotNull List<String> hints, boolean uncommitted, @NotNull List<String> overview,
                           @NotNull List<Path> compile, @NotNull List<Path> willExist) {
    }

    /**
     * Reads the settings file of the project root ({@link AhkDeployConfig#file}: {@code settings.ahk-v2-plugin.ini},
     * otherwise the {@code settings.ahk-plugin.ini} of version 2.1) and checks everything (not on the EDT: runs Git).
     * If both files exist, the old one is ignored with a warning.
     *
     * @param root       the project root
     * @param env        the variables ({@link AhkDeployPlan#variables()})
     * @param gitEnabled false = behave as if Git were missing (tests)
     */
    public static @NotNull Prepared prepare(@NotNull Path root, @NotNull Function<String, String> env,
                                            boolean gitEnabled) throws IOException {
        Path dir = root.toAbsolutePath().normalize();
        Path file = AhkDeployConfig.file(dir);
        if (file == null) throw new NoSuchFileException(dir.resolve(AhkDeployConfig.FILE_NAME).toString());
        AhkDeployConfig config = AhkDeployConfig.load(file);
        List<String> errors = new ArrayList<>(config.errors());
        List<String> warnings = new ArrayList<>();
        if (!config.legacy() && Files.isRegularFile(dir.resolve(AhkDeployConfig.LEGACY_FILE_NAME))) {
            warnings.add(AhkBundle.message("deploy.warning.legacy.ignored"));
        }
        List<Path> willExist = new ArrayList<>();
        List<Path> compile = new ArrayList<>();
        for (AhkDeployConfig.Entry entry : config.entries()) {
            Path exe = AhkDeployPlan.executable(entry.source(), dir);
            Path script = exe == null ? null : scriptOf(exe);
            if (script == null || !Files.isRegularFile(script)) continue;
            script = withRealName(script);
            if (!config.compile()) {
                if (Files.isRegularFile(exe) && newerSources(script, exe)
                        && warnings.stream().noneMatch(w -> w.contains(exe.getFileName().toString()))) {
                    warnings.add(AhkBundle.message("deploy.warning.exe.older", relative(dir, exe)));
                }
                continue;
            }
            willExist.add(exe);
            if (contains(compile, script)) continue;
            String where = AhkBundle.message("deploy.where", entry.line(), entry.source());
            String text = readText(script);
            if (!AhkCompiler.hasBaseDirective(text)) {
                errors.add(AhkBundle.message("deploy.compile.no.base", where, relative(dir, script)));
            }
            Path output = compiledExe(script, text);
            if (output != null && !samePath(output, exe)) {
                errors.add(AhkBundle.message("deploy.compile.other.exe", where, relative(dir, script),
                        relative(dir, output)));
            }
            compile.add(script);
        }
        if (!compile.isEmpty() && AhkSettings.getInstance().findAhk2Exe() == null) {
            errors.add(AhkBundle.message("compile.ahk2exe.not.found"));
        }
        AhkDeployPlan plan = AhkDeployPlan.create(config, dir, env, willExist);
        errors.addAll(plan.errors());
        warnings.addAll(plan.warnings());
        if (plan.steps().isEmpty() && errors.isEmpty()) errors.add(AhkBundle.message("deploy.nothing"));
        boolean uncommitted = false;
        if (gitEnabled) {
            AhkDeployGit git = new AhkDeployGit(dir);
            uncommitted = git.inRepository() && git.hasChanges();
        }
        List<String> hints = new ArrayList<>();
        if (config.legacy()) hints.add(AhkBundle.message("deploy.hint.legacy"));
        hints.addAll(plan.hints());
        return new Prepared(dir, config, plan, errors, warnings, hints, uncommitted,
                overview(config, dir, compile), compile, willExist);
    }

    /**
     * "Update Settings" in the confirmation of a {@code settings.ahk-plugin.ini} of version 2.1: writes its text with
     * the new names ({@link AhkSettingsFormat#updated}; encoding, byte order mark and line separators stay) as
     * {@code settings.ahk-v2-plugin.ini} and removes the old file, so Git sees a rename; then brings both into the
     * VFS (the documents were saved before the deployment). Nothing happens if the new file exists already.
     *
     * @return {@code null} on success, otherwise the reason
     */
    static @Nullable String updateSettings(@NotNull Path root) {
        Path old = root.resolve(AhkDeployConfig.LEGACY_FILE_NAME);
        Path file = root.resolve(AhkDeployConfig.FILE_NAME);
        try {
            if (Files.exists(file)) return null;
            AhkSettingsEdit.Stored stored = AhkSettingsEdit.read(old);
            stored.write(file, AhkSettingsFormat.updated(stored.text()));
            Files.delete(old);
        } catch (IOException | RuntimeException e) {
            return e.getMessage() != null ? e.getMessage() : e.toString();
        } finally {
            refreshFiles(List.of(old, file));
        }
        return null;
    }

    /** The script from which the executable is compiled: same folder, same name, extension {@code .ahk}. */
    static @NotNull Path scriptOf(@NotNull Path exe) {
        String name = exe.getFileName().toString();
        return exe.resolveSibling(name.substring(0, name.length() - ".exe".length()) + ".ahk");
    }

    /** The existing file with its name as written on disk (the copy line may use another case). */
    private static @NotNull Path withRealName(@NotNull Path file) {
        try {
            return file.resolveSibling(file.toRealPath().getFileName().toString());
        } catch (IOException | RuntimeException e) {
            return file;
        }
    }

    /** Same file, ignoring case (as on Windows)? Both paths absolute and normalized. */
    private static boolean samePath(@NotNull Path a, @NotNull Path b) {
        return a.toString().equalsIgnoreCase(b.toString());
    }

    /** Is the file among the paths (ignoring case)? */
    private static boolean contains(@NotNull List<Path> paths, @NotNull Path file) {
        return paths.stream().anyMatch(p -> samePath(p, file));
    }

    /**
     * The overview: "compile tool.ahk" for each script to compile (relative to the project root), then each copy
     * section with its lines {@code source=target} as written (without comments and empty lines), then the lines of
     * {@code [Cleanup]}, then the lines of {@code [AddBanner]} if there is a {@code Banner}.
     */
    static @NotNull List<String> overview(@NotNull AhkDeployConfig config, @NotNull Path root,
                                          @NotNull List<Path> compile) {
        List<String> overview = new ArrayList<>();
        for (Path file : compile) overview.add(AhkBundle.message("deploy.overview.compile", relative(root, file)));
        for (boolean ifMissing : new boolean[]{false, true}) {
            boolean header = false;
            for (AhkDeployConfig.Entry entry : config.entries()) {
                if (entry.ifMissing() != ifMissing) continue;
                if (!header) {
                    overview.add(ifMissing ? "[InstallIfMissing]" : "[Install]");
                    header = true;
                }
                overview.add(entry.source() + "=" + entry.target());
            }
        }
        if (!config.cleanup().isEmpty()) {
            overview.add("[Cleanup]");
            for (AhkDeployConfig.CleanupEntry entry : config.cleanup()) {
                overview.add(entry.selector() + "=" + entry.folder());
            }
        }
        if (config.banner() != null && !config.bannerRules().isEmpty()) {
            overview.add("[AddBanner]");
            for (AhkFileRules.Rule rule : config.bannerRules()) overview.add(rule.pattern().text() + "=" + rule.value());
        }
        return overview;
    }

    /**
     * Output file of Ahk2Exe as far as it can be known without compiling: {@code ;@Ahk2Exe-ExeName} (relative to the
     * script folder, {@code .exe} added without extension), otherwise the script name with {@code .exe};
     * {@code null} if the directive uses variables ({@code %…%}).
     */
    public static @Nullable Path compiledExe(@NotNull Path script, @NotNull CharSequence scriptText) {
        Path dir = script.toAbsolutePath().getParent();
        Matcher m = EXE_NAME.matcher(scriptText);
        String name;
        if (m.find()) {
            name = m.group(1);
            if (name.contains("%")) return null;
            int slash = Math.max(name.lastIndexOf('\\'), name.lastIndexOf('/'));
            if (name.indexOf('.', slash + 1) < 0) name += ".exe";
        } else {
            String file = script.getFileName().toString();
            int dot = file.lastIndexOf('.');
            name = (dot > 0 ? file.substring(0, dot) : file) + ".exe";
        }
        try {
            return dir.resolve(name).normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The file relative to the project root (as in the settings), the absolute path if that is not possible. */
    static @NotNull String relative(@NotNull Path root, @NotNull Path file) {
        try {
            return root.toAbsolutePath().normalize().relativize(file).toString();
        } catch (IllegalArgumentException e) {
            return file.toString();
        }
    }

    /** Is the script or another {@code .ahk} file of its folder newer than the executable? */
    static boolean newerSources(@NotNull Path script, @NotNull Path exe) {
        try {
            FileTime built = Files.getLastModifiedTime(exe);
            if (Files.getLastModifiedTime(script).compareTo(built) > 0) return true;
            try (Stream<Path> files = Files.list(script.toAbsolutePath().getParent())) {
                return files.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".ahk"))
                        .anyMatch(p -> {
                            try {
                                return Files.getLastModifiedTime(p).compareTo(built) > 0;
                            } catch (IOException e) {
                                return false;
                            }
                        });
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Text of a file: UTF-16 with byte order mark, otherwise UTF-8. */
    static @NotNull String readText(@NotNull Path file) throws IOException {
        byte[] content = Files.readAllBytes(file);
        if (content.length >= 2 && (content[0] & 0xFF) == 0xFF && (content[1] & 0xFF) == 0xFE) {
            return new String(content, 2, content.length - 2, StandardCharsets.UTF_16LE);
        }
        if (content.length >= 2 && (content[0] & 0xFF) == 0xFE && (content[1] & 0xFF) == 0xFF) {
            return new String(content, 2, content.length - 2, StandardCharsets.UTF_16BE);
        }
        return new String(content, StandardCharsets.UTF_8);
    }

    /**
     * Deploys the project (call on the EDT): without a {@code settings.ahk-v2-plugin.ini} in the project root offers to
     * create it; otherwise saves all modified documents, checks everything in the background, asks for
     * confirmation, then runs the steps in the background and shows the report.
     */
    public static void deploy(@NotNull Project project) {
        Path root = AhkProjectRoot.path(project);
        if (root == null) return;
        String title = AhkBundle.message("deploy.title", project.getName());
        if (!AhkProjectRoot.hasSettings(root)) {
            // offer the documented default file
            if (Messages.showYesNoDialog(project, AhkBundle.message("deploy.ini.missing", AhkDeployConfig.FILE_NAME,
                    root), title, AhkBundle.message("deploy.ini.create"), Messages.getCancelButton(),
                    Messages.getQuestionIcon()) == Messages.YES) {
                AhkSettingsFile.create(project);
            }
            return;
        }
        if (!AhkValidator.saveAllDocuments(project, title)) return;
        String key = root.toString();
        if (!RUNNING.add(key)) return;
        new Task.Backgroundable(project, AhkBundle.message("deploy.progress.check", project.getName()), true) {
            private Prepared prepared;
            private String failure;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    prepared = prepare(root, AhkDeployPlan.variables(), true);
                } catch (IOException e) {
                    failure = e.toString();
                }
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
                    String message = AhkBundle.message("deploy.errors", text);
                    if (failure != null || !prepared.config().legacy()) {
                        Messages.showErrorDialog(project, message, title);
                    } else if (Messages.showDialog(project,
                            message + "\n\n" + AhkBundle.message("deploy.errors.legacy"), title,
                            new String[]{AhkBundle.message("deploy.dialog.update"), CommonBundle.getCloseButtonText()},
                            1, Messages.getErrorIcon()) == 0) {
                        // an old file with errors: "Update Settings" is offered here, too
                        update(project, root, title);
                    }
                    return;
                }
                AhkDeployDialog dialog = new AhkDeployDialog(project, title, project.getName(), prepared);
                if (!dialog.showAndGet()) {
                    RUNNING.remove(key);
                    if (dialog.updateRequested()) update(project, root, title);
                    return;
                }
                boolean remove = false;
                if (!prepared.plan().removals().isEmpty()) {
                    AhkCleanupDialog cleanup = new AhkCleanupDialog(project, title, prepared.plan().removals());
                    cleanup.show();
                    if (cleanup.choice() == AhkCleanupDialog.Choice.CANCEL) {
                        RUNNING.remove(key);
                        return;
                    }
                    remove = cleanup.choice() == AhkCleanupDialog.Choice.REMOVE;
                }
                start(project, title, prepared, remove);
            }
        }.queue();
    }

    /**
     * "Update Settings" (confirmation or error dialog of a {@code settings.ahk-plugin.ini} of version 2.1): renames the
     * file with the new names ({@link #updateSettings}), then checks again, so the confirmation or the remaining errors
     * appear with the new names.
     */
    private static void update(@NotNull Project project, @NotNull Path root, @NotNull String title) {
        String problem = updateSettings(root);
        if (problem != null) {
            Messages.showErrorDialog(project, AhkBundle.message("deploy.update.failed", problem), title);
        } else {
            deploy(project);
        }
    }

    /**
     * Runs the confirmed steps in the background and shows the report.
     *
     * @param remove move the files of {@code [Cleanup]} to the Recycle Bin (confirmed by the user)
     */
    private static void start(@NotNull Project project, @NotNull String title, @NotNull Prepared prepared,
                              boolean remove) {
        new Task.Backgroundable(project, AhkBundle.message("deploy.progress", project.getName()), false) {
            private final List<Line> report = new ArrayList<>();

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    execute(prepared, remove, report, indicator);
                } finally {
                    // also after a failure: some files may have been written or removed already
                    List<Path> changed = new ArrayList<>();
                    for (AhkDeployPlan.Step step : prepared.plan().steps()) changed.add(step.target());
                    if (remove) {
                        for (AhkDeployPlan.Removal removal : prepared.plan().removals()) changed.add(removal.file());
                    }
                    refreshFiles(changed);
                }
            }

            @Override
            public void onFinished() {
                RUNNING.remove(prepared.root().toString());
                ApplicationManager.getApplication().invokeLater(() -> {
                    // a Lib folder created by this deployment: report it as a new library root
                    AhkLibraryRootsProvider.changeRoots(() -> { });
                    // new copies of a library project: read-only and banner at once
                    AhkLibraryCopyNotification.refreshAll();
                    if (!project.isDisposed()) show(project, title, report);
                });
            }
        }.queue();
    }

    /**
     * Brings files written or removed outside the IDE into the VFS at once (synchronous refresh, no read action
     * allowed): the copier writes with plain file IO, and until the file watcher reports it, a new copy has no
     * {@code VirtualFile}; navigation to {@code #Include <Name>} would not find it. Existing files (new ones with new
     * folders, too) are found by a refresh, then every known file is refreshed (changed content, removed files).
     */
    static void refreshFiles(@NotNull List<Path> files) {
        LocalFileSystem fs = LocalFileSystem.getInstance();
        Set<VirtualFile> known = new LinkedHashSet<>();
        for (Path file : files) {
            VirtualFile virtualFile = Files.exists(file) ? fs.refreshAndFindFileByNioFile(file)
                    : fs.findFileByNioFile(file);
            if (virtualFile != null) known.add(virtualFile);
        }
        if (!known.isEmpty()) VfsUtil.markDirtyAndRefresh(false, false, false, known.toArray(VirtualFile.EMPTY_ARRAY));
    }

    /** One line of the report. */
    record Line(@NotNull String text, @NotNull ConsoleViewContentType type) {
    }

    /** Compile each script, then copy, without removing the files of {@code [Cleanup]}. */
    static void execute(@NotNull Prepared prepared, @NotNull List<Line> report, @NotNull ProgressIndicator indicator) {
        execute(prepared, false, report, indicator);
    }

    /**
     * Compile each script, then (with {@code remove}) move the confirmed files of {@code [Cleanup]} to the Recycle
     * Bin, then copy; stops at the first failure. Appends the report lines. With {@code RequiresAdmin} removing and
     * copying run in one elevated process.
     */
    static void execute(@NotNull Prepared prepared, boolean remove, @NotNull List<Line> report,
                        @NotNull ProgressIndicator indicator) {
        AhkDeployPlan plan = prepared.plan();
        List<AhkDeployPlan.Removal> removals = remove ? prepared.plan().removals() : List.of();
        for (Path file : prepared.compile()) {
            String name = relative(prepared.root(), file);
            String ahk2Exe = AhkSettings.getInstance().findAhk2Exe();
            ProcessOutput output;
            try {
                output = ahk2Exe == null ? null : AhkCompiler.run(ahk2Exe, file.toString(),
                        AhkSettings.getInstance().getCompression(), indicator);
            } catch (ExecutionException e) {
                fail(report, AhkBundle.message("compile.failed", e.getMessage()));
                return;
            }
            if (output == null) {
                fail(report, AhkBundle.message("compile.ahk2exe.not.found"));
                return;
            }
            String out = (output.getStdout() + "\n" + output.getStderr()).strip();
            if (!out.isEmpty()) report.add(new Line(out, ConsoleViewContentType.NORMAL_OUTPUT));
            if (output.isTimeout() || output.isCancelled() || output.getExitCode() != 0) {
                fail(report, AhkBundle.message("deploy.report.compile.failed", name));
                return;
            }
            report.add(new Line(AhkBundle.message("deploy.report.compiled", name),
                    ConsoleViewContentType.NORMAL_OUTPUT));
        }
        if (!prepared.compile().isEmpty()) {
            // the compiled files exist now: resolve the copies again
            plan = AhkDeployPlan.create(prepared.config(), prepared.root(), AhkDeployPlan.variables(),
                    List.of());
            if (!plan.errors().isEmpty()) {
                fail(report, AhkBundle.message("deploy.errors", "- " + String.join("\n- ", plan.errors())));
                return;
            }
        }
        List<AhkDeployCopier.RemovalResult> removed;
        List<AhkDeployCopier.StepResult> results;
        if (prepared.config().requiresAdmin()) {
            AhkDeployCopier.ElevatedResult elevated = AhkDeployCopier.copyElevated(removals, plan.steps());
            removed = elevated.removals();
            results = elevated.steps();
        } else {
            removed = AhkDeployCopier.recycle(removals);
            results = removed.size() == removals.size() && removed.stream().allMatch(r -> r.error() == null)
                    ? AhkDeployCopier.copy(plan.steps()) : List.of();
        }
        for (AhkDeployCopier.RemovalResult result : removed) {
            if (result.error() != null) {
                fail(report, AhkBundle.message("deploy.report.remove.failed", result.removal().file(),
                        result.error()));
                return;
            }
            report.add(new Line(AhkBundle.message("deploy.report.removed", result.removal().file()),
                    ConsoleViewContentType.NORMAL_OUTPUT));
        }
        if (removed.size() < removals.size()) {
            fail(report, AhkBundle.message("deploy.report.incomplete"));
            return;
        }
        int copied = 0;
        int skipped = 0;
        for (AhkDeployCopier.StepResult result : results) {
            String source = prepared.root().relativize(result.step().source()).toString();
            switch (result.outcome()) {
                case COPIED -> {
                    copied++;
                    report.add(new Line(AhkBundle.message("deploy.report.copied", source, result.step().target()),
                            ConsoleViewContentType.NORMAL_OUTPUT));
                }
                case SKIPPED -> {
                    skipped++;
                    report.add(new Line(AhkBundle.message("deploy.report.skipped", source, result.step().target()),
                            ConsoleViewContentType.NORMAL_OUTPUT));
                }
                case FAILED -> {
                    fail(report, AhkBundle.message("deploy.report.copy.failed", source, result.step().target(),
                            String.valueOf(result.error())));
                    return;
                }
            }
        }
        if (results.size() < plan.steps().size()) {
            fail(report, AhkBundle.message("deploy.report.incomplete"));
            return;
        }
        report.add(new Line(removed.isEmpty() ? AhkBundle.message("deploy.report.done", copied, skipped)
                : AhkBundle.message("deploy.report.done.removed", removed.size(), copied, skipped), SUCCESS));
    }

    private static void fail(List<Line> report, String text) {
        report.add(new Line(text, ConsoleViewContentType.ERROR_OUTPUT));
        report.add(new Line(AhkBundle.message("deploy.report.stopped"), ConsoleViewContentType.ERROR_OUTPUT));
    }

    /** Shows the report in a console of the Run tool window (paths of compile errors clickable). */
    private static void show(@NotNull Project project, @NotNull String title, @NotNull List<Line> report) {
        TextConsoleBuilder builder = TextConsoleBuilderFactory.getInstance().createBuilder(project);
        builder.addFilter(new AhkErrorFilter(project));
        ConsoleView console = builder.getConsole();
        console.print(title + "\n", ConsoleViewContentType.SYSTEM_OUTPUT);
        for (Line line : report) console.print(line.text() + "\n", line.type());
        RunContentDescriptor descriptor = new RunContentDescriptor(console, null, console.getComponent(), title);
        RunContentManager.getInstance(project).showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor,
                project.getUserData(CONSOLE));
        project.putUserData(CONSOLE, descriptor);
    }
}
