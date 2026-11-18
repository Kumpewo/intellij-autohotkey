package de.kumpewo.autohotkey.install;

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
import com.intellij.ui.JBColor;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.compile.AhkCompiler;
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
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * "Deploy to Local Target...": copies the tool of the project (one tool = one project) as described by the
 * {@link AhkInstallConfig settings.ahk-plugin.ini} in the project root ({@link AhkProjectRoot}). With
 * {@code CompileBeforeInstall} (default) every executable named in a copy line (without wildcards) that has a script of the same
 * name next to it is compiled from that script first, e.g. a GUI and a console variant. No commit and no tag (that is
 * "Set Version...", {@link AhkSetVersion}); uncommitted changes of the project are only a warning. Order: read and check everything ({@link #prepare}, in the background) → confirmation
 * ({@link AhkInstallDialog}, always) → compile each script ({@link AhkCompiler#run}) → copy ({@link AhkInstallCopier}) → report in
 * a console of the Run tool window. Every problem found before the start is an error and nothing happens; a failure later
 * stops the remaining steps (no rollback).
 */
public final class AhkInstaller {

    /** Green text in the console for success (same color as Validate/Compile). */
    private static final ConsoleViewContentType SUCCESS = new ConsoleViewContentType("AutoHotkey.InstallSuccess",
            new TextAttributes(new JBColor(new Color(0x067D17), new Color(0x6AAB73)), null, null, null, Font.PLAIN));

    /** Console of the last installation in the project, reused by the next one. */
    private static final Key<RunContentDescriptor> CONSOLE = Key.create("AhkInstaller.console");

    /** Project roots being deployed (a second start for the same project is ignored). */
    private static final Set<String> RUNNING = ConcurrentHashMap.newKeySet();

    /** {@code ;@Ahk2Exe-ExeName path} (output file of Ahk2Exe). */
    private static final Pattern EXE_NAME = Pattern.compile("(?im)^\\uFEFF?[ \\t]*;@Ahk2Exe-ExeName[ \\t]+(.+?)[ \\t]*$");

    private AhkInstaller() {
    }

    /**
     * Everything checked before the start.
     *
     * @param root        the project root
     * @param config      the read {@code settings.ahk-plugin.ini}
     * @param plan        the resolved copies (checked again after compiling)
     * @param errors      problems that prevent the installation (all are shown)
     * @param warnings    hints shown in the confirmation
     * @param uncommitted the project root has uncommitted changes (Git)
     * @param overview    the overview for the confirmation ({@code GenerateOverview}): compile steps, then the lines
     *                    of {@code [Overwrite]} and {@code [KeepExisting]} without comments
     * @param compile     the scripts to compile ({@code CompileBeforeInstall}), in the order of the copy lines,
     *                    each only once
     * @param willExist   the executables the compilation will create (as far as known beforehand)
     */
    public record Prepared(@NotNull Path root, @NotNull AhkInstallConfig config,
                           @NotNull AhkInstallPlan plan, @NotNull List<String> errors, @NotNull List<String> warnings,
                           boolean uncommitted, @NotNull List<String> overview, @NotNull List<Path> compile,
                           @NotNull List<Path> willExist) {
    }

    /**
     * Reads the {@code settings.ahk-plugin.ini} of the project root and checks everything (not on the EDT: runs Git).
     *
     * @param root       the project root
     * @param env        environment variables
     * @param gitEnabled false = behave as if Git were missing (tests)
     */
    public static @NotNull Prepared prepare(@NotNull Path root, @NotNull Function<String, String> env,
                                            boolean gitEnabled) throws IOException {
        Path dir = root.toAbsolutePath().normalize();
        AhkInstallConfig config = AhkInstallConfig.parse(readText(dir.resolve(AhkInstallConfig.FILE_NAME)));
        List<String> errors = new ArrayList<>(config.errors());
        List<String> warnings = new ArrayList<>();
        List<Path> willExist = new ArrayList<>();
        List<Path> compile = new ArrayList<>();
        for (AhkInstallConfig.Entry entry : config.entries()) {
            Path exe = AhkInstallPlan.executable(entry.source(), dir);
            Path script = exe == null ? null : scriptOf(exe);
            if (script == null || !Files.isRegularFile(script)) continue;
            script = withRealName(script);
            if (!config.compile()) {
                if (Files.isRegularFile(exe) && newerSources(script, exe)
                        && warnings.stream().noneMatch(w -> w.contains(exe.getFileName().toString()))) {
                    warnings.add(AhkBundle.message("install.warning.exe.older", relative(dir, exe)));
                }
                continue;
            }
            willExist.add(exe);
            if (contains(compile, script)) continue;
            String where = AhkBundle.message("install.where", entry.line(), entry.source());
            String text = readText(script);
            if (!AhkCompiler.hasBaseDirective(text)) {
                errors.add(AhkBundle.message("install.compile.no.base", where, relative(dir, script)));
            }
            Path output = compiledExe(script, text);
            if (output != null && !samePath(output, exe)) {
                errors.add(AhkBundle.message("install.compile.other.exe", where, relative(dir, script),
                        relative(dir, output)));
            }
            compile.add(script);
        }
        if (!compile.isEmpty() && AhkSettings.getInstance().findAhk2Exe() == null) {
            errors.add(AhkBundle.message("compile.ahk2exe.not.found"));
        }
        AhkInstallPlan plan = AhkInstallPlan.create(config, dir, env, willExist);
        errors.addAll(plan.errors());
        if (plan.steps().isEmpty() && errors.isEmpty()) errors.add(AhkBundle.message("install.nothing"));
        boolean uncommitted = false;
        if (gitEnabled) {
            AhkInstallGit git = new AhkInstallGit(dir);
            uncommitted = git.inRepository() && git.hasChanges();
        }
        return new Prepared(dir, config, plan, errors, warnings, uncommitted, overview(config, dir, compile), compile,
                willExist);
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
     * section with its lines {@code source=target} as written (without comments and empty lines).
     */
    static @NotNull List<String> overview(@NotNull AhkInstallConfig config, @NotNull Path root,
                                          @NotNull List<Path> compile) {
        List<String> overview = new ArrayList<>();
        for (Path file : compile) overview.add(AhkBundle.message("install.overview.compile", relative(root, file)));
        for (boolean keep : new boolean[]{false, true}) {
            boolean header = false;
            for (AhkInstallConfig.Entry entry : config.entries()) {
                if (entry.keepExisting() != keep) continue;
                if (!header) {
                    overview.add(keep ? "[KeepExisting]" : "[Overwrite]");
                    header = true;
                }
                overview.add(entry.source() + "=" + entry.target());
            }
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
     * Deploys the project (call on the EDT): without a {@code settings.ahk-plugin.ini} in the project root offers to
     * create it; otherwise saves all modified documents, checks everything in the background, asks for
     * confirmation, then runs the steps in the background and shows the report.
     */
    public static void install(@NotNull Project project) {
        Path root = AhkProjectRoot.path(project);
        if (root == null) return;
        String title = AhkBundle.message("install.title", project.getName());
        if (!AhkProjectRoot.hasSettings(root)) {
            // offer the documented default file
            if (Messages.showYesNoDialog(project, AhkBundle.message("install.ini.missing", AhkInstallConfig.FILE_NAME,
                    root), title, AhkBundle.message("install.ini.create"), Messages.getCancelButton(),
                    Messages.getQuestionIcon()) == Messages.YES) {
                AhkSettingsFile.create(project);
            }
            return;
        }
        AhkValidator.saveAllDocuments();
        String key = root.toString();
        if (!RUNNING.add(key)) return;
        new Task.Backgroundable(project, AhkBundle.message("install.progress.check", project.getName()), true) {
            private Prepared prepared;
            private String failure;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    prepared = prepare(root, AhkInstallPlan.processEnvironment(), true);
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
                    Messages.showErrorDialog(project, AhkBundle.message("install.errors", text), title);
                    return;
                }
                AhkInstallDialog dialog = new AhkInstallDialog(project, title, project.getName(), prepared);
                if (!dialog.showAndGet()) {
                    RUNNING.remove(key);
                    return;
                }
                start(project, title, prepared);
            }
        }.queue();
    }

    /** Runs the confirmed steps in the background and shows the report. */
    private static void start(@NotNull Project project, @NotNull String title, @NotNull Prepared prepared) {
        new Task.Backgroundable(project, AhkBundle.message("install.progress", project.getName()), false) {
            private final List<Line> report = new ArrayList<>();

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                execute(prepared, report, indicator);
            }

            @Override
            public void onFinished() {
                RUNNING.remove(prepared.root().toString());
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!project.isDisposed()) show(project, title, report);
                });
            }
        }.queue();
    }

    /** One line of the report. */
    record Line(@NotNull String text, @NotNull ConsoleViewContentType type) {
    }

    /** Compile each script, then copy; stops at the first failure. Appends the report lines. */
    static void execute(@NotNull Prepared prepared, @NotNull List<Line> report, @NotNull ProgressIndicator indicator) {
        AhkInstallPlan plan = prepared.plan();
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
                fail(report, AhkBundle.message("install.report.compile.failed", name));
                return;
            }
            report.add(new Line(AhkBundle.message("install.report.compiled", name),
                    ConsoleViewContentType.NORMAL_OUTPUT));
        }
        if (!prepared.compile().isEmpty()) {
            // the compiled files exist now: resolve the copies again
            plan = AhkInstallPlan.create(prepared.config(), prepared.root(), AhkInstallPlan.processEnvironment(),
                    List.of());
            if (!plan.errors().isEmpty()) {
                fail(report, AhkBundle.message("install.errors", "- " + String.join("\n- ", plan.errors())));
                return;
            }
        }
        List<AhkInstallCopier.StepResult> results = prepared.config().requiresAdmin()
                ? AhkInstallCopier.copyElevated(plan.steps()) : AhkInstallCopier.copy(plan.steps());
        int copied = 0;
        int skipped = 0;
        for (AhkInstallCopier.StepResult result : results) {
            String source = prepared.root().relativize(result.step().source()).toString();
            switch (result.outcome()) {
                case COPIED -> {
                    copied++;
                    report.add(new Line(AhkBundle.message("install.report.copied", source, result.step().target()),
                            ConsoleViewContentType.NORMAL_OUTPUT));
                }
                case SKIPPED -> {
                    skipped++;
                    report.add(new Line(AhkBundle.message("install.report.skipped", source, result.step().target()),
                            ConsoleViewContentType.NORMAL_OUTPUT));
                }
                case FAILED -> {
                    fail(report, AhkBundle.message("install.report.copy.failed", source, result.step().target(),
                            String.valueOf(result.error())));
                    return;
                }
            }
        }
        if (results.size() < plan.steps().size()) {
            fail(report, AhkBundle.message("install.report.incomplete"));
            return;
        }
        report.add(new Line(AhkBundle.message("install.report.done", copied, skipped), SUCCESS));
    }

    private static void fail(List<Line> report, String text) {
        report.add(new Line(text, ConsoleViewContentType.ERROR_OUTPUT));
        report.add(new Line(AhkBundle.message("install.report.stopped"), ConsoleViewContentType.ERROR_OUTPUT));
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
