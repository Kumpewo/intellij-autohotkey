package de.kumpewo.autohotkey.validate;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.filters.TextConsoleBuilder;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.ui.JBColor;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.AhkFileType;
import de.kumpewo.autohotkey.annotation.AhkIgnoredLines;
import de.kumpewo.autohotkey.include.AhkInclude;
import de.kumpewo.autohotkey.run.AhkErrorFilter;
import de.kumpewo.autohotkey.run.AhkRunConfiguration;
import de.kumpewo.autohotkey.run.AhkRunState;
import de.kumpewo.autohotkey.settings.AhkSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.awt.Font;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates a script with AutoHotkey itself (the script is loaded, not run), stores the result in
 * {@link AhkValidation} and shows the output in a console like the run configuration (errors clickable through
 * {@link AhkErrorFilter}).
 * <p>
 * Validated is the script of the selected AutoHotkey run configuration (the main script; an included file alone
 * usually fails), otherwise the file itself ({@link #target}). All modified AutoHotkey documents are saved first.
 * AutoHotkey gets a temporary copy of the script next to the original ({@link #copyPath}) with the line
 * {@code #Warn All, StdOut} appended ({@link #withWarnToStdOut}): it overrides every other {@code #Warn} mode, so no
 * warning opens a dialog, and the line numbers stay the same. The path of the copy is replaced by the original one
 * in the output ({@link #restorePaths}). Command line:
 * {@code AutoHotkey.exe [launcher.ahk] /ErrorStdOut /validate copy} (program chosen for the script by
 * {@link AhkSettings#findAutoHotkey(String)}: v2 interpreter instead of the launcher for {@code #Requires} v2.0).
 * <p>
 * AutoHotkey reports only the first load error; warnings ({@code ==> Warning: …}) are shown but do not count as
 * errors. If AutoHotkey still waits for a dialog, it is stopped after {@link #TIMEOUT_MS}. Started manually
 * (AhkValidateAction) the console is always shown, started automatically (AhkValidateTriggers) only if AutoHotkey
 * reports an error.
 */
public final class AhkValidator {

    private static final Logger LOG = Logger.getInstance(AhkValidator.class);

    /** Maximum run time of AutoHotkey; longer means it waits for a dialog. */
    public static final int TIMEOUT_MS = 15_000;

    /** Directive appended to the copy of the script. */
    public static final String WARN_TO_STDOUT = "#Warn All, StdOut";

    /** Error message of {@code /ErrorStdOut}: {@code C:\Path\Script.ahk (12) : ==> Missing ")"}. */
    private static final Pattern MESSAGE = Pattern.compile("(?m)^\\s*(.+?) \\((\\d+)\\) : ==> (.*?)\\s*$");

    /** Prefix of a warning message (after {@code ==> }). */
    private static final String WARNING = "Warning:";

    /** Green text in the console for "no errors" (green of strings in the default light/dark schemes). */
    private static final ConsoleViewContentType SUCCESS = new ConsoleViewContentType("AutoHotkey.ValidationSuccess",
            new TextAttributes(new JBColor(new Color(0x067D17), new Color(0x6AAB73)), null, null, null, Font.PLAIN));

    /** Console of the last validation in the project, reused by the next one. */
    private static final Key<RunContentDescriptor> CONSOLE = Key.create("AhkValidator.console");

    /** True while the validator saves documents (EDT only), so that AhkValidateTriggers does not start again. */
    private static boolean saving;

    /** First error in the output of AutoHotkey: file path as printed, line (1-based), message. */
    public record Error(@NotNull String path, int line, @NotNull String message) {
    }

    /**
     * What is validated.
     *
     * @param script        the script passed to AutoHotkey
     * @param configuration name of the run configuration the script comes from, {@code null} = the file itself
     */
    public record Target(@NotNull VirtualFile script, @Nullable String configuration) {
    }

    private AhkValidator() {
    }

    /** True while the validator itself saves documents. */
    static boolean isSaving() {
        return saving;
    }

    /** True for an AutoHotkey file on the local file system (only those can be passed to AutoHotkey). */
    static boolean canValidate(@Nullable VirtualFile file) {
        return file != null && !file.isDirectory() && file.isInLocalFileSystem()
                && file.getFileType() == AhkFileType.INSTANCE;
    }

    /**
     * The script of the selected run configuration, if it is an AutoHotkey run configuration whose script exists;
     * otherwise {@code null}.
     */
    public static @Nullable Target mainScript(@NotNull Project project) {
        RunnerAndConfigurationSettings selected = RunManager.getInstance(project).getSelectedConfiguration();
        if (selected == null || !(selected.getConfiguration() instanceof AhkRunConfiguration configuration)) {
            return null;
        }
        String path = configuration.getScriptPath();
        if (path.isEmpty()) return null;
        VirtualFile script = LocalFileSystem.getInstance().findFileByPath(path);
        return canValidate(script) ? new Target(script, selected.getName()) : null;
    }

    /** Is there anything to validate or compile for {@code file} ({@link #target} != null), without reading files? */
    public static boolean hasTarget(@NotNull Project project, @Nullable VirtualFile file) {
        return canValidate(file) || mainScript(project) != null;
    }

    /**
     * What to validate (or compile) for {@code file}: the main script of the selected run configuration if
     * {@code file} belongs to it (it is the script itself or is included by it, also indirectly) or is no AutoHotkey
     * file (e.g. nothing selected); otherwise the file itself. So in a project that collects many small scripts, the
     * action applies to the current script, not to the one whose run configuration was selected last.
     */
    public static @Nullable Target target(@NotNull Project project, @Nullable VirtualFile file) {
        Target main = mainScript(project);
        if (!canValidate(file)) return main;
        if (main != null && (file.equals(main.script()) || includes(project, main.script(), file))) return main;
        return new Target(file, null);
    }

    /**
     * Saves the AutoHotkey documents and validates the target of {@code file} (call on the EDT). Without an
     * AutoHotkey executable only a manual start shows a message. A validation of the same script that is still
     * running is not started again.
     */
    public static void validate(@NotNull Project project, @Nullable VirtualFile file, boolean manual) {
        if (project.isDisposed()) return;
        Target target = target(project, file);
        if (target == null) return;
        VirtualFile script = target.script();
        String exe = AhkSettings.getInstance().findAutoHotkey();
        if (exe == null) {
            if (manual) {
                Messages.showErrorDialog(project, AhkBundle.message("run.autohotkey.not.found"),
                        AhkBundle.message("validate.title", script.getName()));
            }
            return;
        }
        saveAhkDocuments();
        Map<VirtualFile, Long> stamps = new HashMap<>();
        FileDocumentManager documents = FileDocumentManager.getInstance();
        for (VirtualFile checked : file == null || file.equals(script) ? List.of(script) : List.of(script, file)) {
            Document document = documents.getDocument(checked);
            if (document != null) stamps.put(checked, document.getModificationStamp());
        }
        if (!stamps.containsKey(script)) return;
        AhkValidation validation = AhkValidation.getInstance(project);
        if (!validation.start(script)) return;
        VirtualFile requested = file != null && canValidate(file) ? file : script;
        new Task.Backgroundable(project, AhkBundle.message("validate.progress", script.getName()), false) {
            private ProcessOutput output;
            private String failure;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                Path original;
                try {
                    original = script.toNioPath();
                } catch (UnsupportedOperationException | InvalidPathException e) {
                    failure = e.getMessage();
                    return;
                }
                Path copy = copyPath(original);
                try {
                    Files.write(copy, withWarnToStdOut(Files.readAllBytes(original)));
                    Path dir = original.getParent();
                    // the saved script decides between launcher and v2 interpreter (#Requires)
                    String program = AhkSettings.getInstance().findAutoHotkey(original.toString());
                    GeneralCommandLine commandLine = AhkRunState.createCommandLine(program == null ? exe : program,
                            List.of("/ErrorStdOut", "/validate"), copy.toString(), "",
                            dir == null ? "" : dir.toString());
                    ProcessOutput raw = new CapturingProcessHandler(commandLine).runProcess(TIMEOUT_MS, true);
                    output = new ProcessOutput(restorePaths(raw.getStdout(), copy, original),
                            restorePaths(raw.getStderr(), copy, original), raw.getExitCode(), raw.isTimeout(),
                            raw.isCancelled());
                } catch (IOException | ExecutionException e) {
                    failure = e.getMessage();
                } finally {
                    try {
                        Files.deleteIfExists(copy);
                    } catch (IOException e) {
                        LOG.warn("Cannot delete the copy of the validated script: " + copy, e);
                    }
                }
            }

            @Override
            public void onFinished() {
                validation.finish(script);
            }

            @Override
            public void onSuccess() {
                if (!project.isDisposed()) report(project, target, requested, stamps, output, failure, manual);
            }
        }.queue();
    }

    /**
     * Saves all modified AutoHotkey documents (the script and the files it includes are read from disk). Also used
     * by the compilation (AhkCompiler); while it saves, AhkValidateTriggers does not start a validation.
     */
    public static void saveAhkDocuments() {
        FileDocumentManager documents = FileDocumentManager.getInstance();
        saving = true;
        try {
            for (Document document : documents.getUnsavedDocuments()) {
                VirtualFile file = documents.getFile(document);
                if (file != null && file.getFileType() == AhkFileType.INSTANCE) documents.saveDocument(document);
            }
        } finally {
            saving = false;
        }
    }

    /** Path of the temporary copy: next to the script (same {@code A_ScriptDir}, Lib folder and relative includes). */
    public static @NotNull Path copyPath(@NotNull Path script) {
        String name = script.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return script.resolveSibling(stem + ".~validate.ahk");
    }

    /**
     * The content of the script with {@link #WARN_TO_STDOUT} as an additional last line, in the encoding of the file
     * (UTF-16 by its byte order mark, otherwise ASCII bytes, which fit UTF-8 and ANSI) and with its line separator.
     */
    public static byte @NotNull [] withWarnToStdOut(byte @NotNull [] content) {
        Charset charset = StandardCharsets.ISO_8859_1;
        if (content.length >= 2 && (content[0] & 0xFF) == 0xFF && (content[1] & 0xFF) == 0xFE) {
            charset = StandardCharsets.UTF_16LE;
        } else if (content.length >= 2 && (content[0] & 0xFF) == 0xFE && (content[1] & 0xFF) == 0xFF) {
            charset = StandardCharsets.UTF_16BE;
        }
        String text = new String(content, charset);
        String separator = text.contains("\r\n") || !text.contains("\n") ? "\r\n" : "\n";
        boolean endsWithLineBreak = text.endsWith("\n") || text.endsWith("\r");
        String appended = (endsWithLineBreak || text.isEmpty() ? "" : separator)
                + WARN_TO_STDOUT + separator;
        ByteArrayOutputStream result = new ByteArrayOutputStream(content.length + 64);
        result.writeBytes(content);
        result.writeBytes(appended.getBytes(charset));
        return result.toByteArray();
    }

    /**
     * Output of AutoHotkey for the copy as if the original had been validated: the path (and file name) of the copy
     * becomes the original one; the appended {@link #WARN_TO_STDOUT}, which AutoHotkey may quote at the end of a
     * "Specifically:" line (open bracket in the last line), is removed.
     */
    public static @NotNull String restorePaths(@NotNull String output, @NotNull Path copy, @NotNull Path original) {
        String result = output.replace(copy.toString(), original.toString());
        Path copyName = copy.getFileName();
        Path originalName = original.getFileName();
        if (copyName != null && originalName != null) {
            result = result.replace(copyName.toString(), originalName.toString());
        }
        return result.replaceAll("(?m)^(\\s*Specifically:.*?)\\s+" + Pattern.quote(WARN_TO_STDOUT) + "\\s*$", "$1");
    }

    /** Stores the result, refreshes the highlighting and shows the console if needed (EDT). */
    private static void report(@NotNull Project project, @NotNull Target target, @NotNull VirtualFile requested,
                               @NotNull Map<VirtualFile, Long> stamps, @Nullable ProcessOutput output,
                               @Nullable String failure, boolean manual) {
        VirtualFile script = target.script();
        boolean finished = failure == null && output != null && !output.isTimeout();
        String text = output == null ? "" : output.getStdout() + "\n" + output.getStderr();
        Error error = parse(text);
        boolean accepted = finished && output.getExitCode() == 0 && error == null;

        // results: the script, the requested file (if the script includes it) and the file with the error
        Map<VirtualFile, AhkValidation.Result> results = new HashMap<>();
        VirtualFile errorFile = error == null ? null
                : LocalFileSystem.getInstance().findFileByPath(FileUtil.toSystemIndependentName(error.path()));
        Set<VirtualFile> acceptedFiles = new HashSet<>();
        acceptedFiles.add(script);
        if (!requested.equals(script) && stamps.containsKey(requested) && includes(project, script, requested)) {
            acceptedFiles.add(requested);
        }
        for (VirtualFile file : acceptedFiles) {
            boolean errorHere = file.equals(errorFile);
            results.put(file, new AhkValidation.Result(script, stamps.get(file), accepted,
                    errorHere ? error.line() : 0, errorHere ? error.message() : ""));
        }
        FileDocumentManager documents = FileDocumentManager.getInstance();
        if (errorFile != null && !results.containsKey(errorFile) && !documents.isFileModified(errorFile)) {
            Document document = documents.getDocument(errorFile);
            if (document != null) {
                results.put(errorFile, new AhkValidation.Result(script, document.getModificationStamp(), false,
                        error.line(), error.message()));
            }
        }
        PsiManager psiManager = PsiManager.getInstance(project);
        for (VirtualFile changed : AhkValidation.getInstance(project).store(script, results)) {
            PsiFile psi = changed.isValid() ? psiManager.findFile(changed) : null;
            if (psi != null) DaemonCodeAnalyzer.getInstance(project).restart(psi, "AutoHotkey validation");
        }

        if (!manual && accepted) return;
        List<SummaryLine> summary = new ArrayList<>();
        if (failure != null) {
            summary.add(new SummaryLine(AhkBundle.message("validate.failed", failure), ConsoleViewContentType.ERROR_OUTPUT));
        } else if (output.isTimeout()) {
            summary.add(new SummaryLine(AhkBundle.message("validate.timeout", TIMEOUT_MS / 1000),
                    ConsoleViewContentType.ERROR_OUTPUT));
        } else if (accepted) {
            summary.add(new SummaryLine(AhkBundle.message("validate.ok", script.getName()), SUCCESS));
            int warnings = warnings(text);
            if (warnings > 0) {
                summary.add(new SummaryLine(AhkBundle.message("validate.ok.warnings", warnings),
                        ConsoleViewContentType.NORMAL_OUTPUT));
            }
            for (VirtualFile file : acceptedFiles) {
                int parserErrors = parserErrors(project, file);
                if (parserErrors > 0) {
                    summary.add(new SummaryLine(AhkBundle.message("validate.ok.parser.errors", parserErrors,
                            file.getName()), ConsoleViewContentType.NORMAL_OUTPUT));
                }
            }
        } else {
            summary.add(new SummaryLine(AhkBundle.message("validate.error", output.getExitCode()),
                    ConsoleViewContentType.ERROR_OUTPUT));
        }
        showConsole(project, target, output, summary);
    }

    /** Prints the text (if any) so that it ends with a line break. */
    private static void printLines(@NotNull ConsoleView console, @NotNull String text,
                                   @NotNull ConsoleViewContentType type) {
        if (text.isEmpty()) return;
        console.print(text.endsWith("\n") ? text : text + "\n", type);
    }

    /** One line of the summary at the end of the console, with its color. */
    private record SummaryLine(@NotNull String text, @NotNull ConsoleViewContentType type) {
    }

    /** True if {@code script} includes {@code file}, directly or through other included files. */
    private static boolean includes(@NotNull Project project, @NotNull VirtualFile script, @NotNull VirtualFile file) {
        PsiFile start = PsiManager.getInstance(project).findFile(script);
        if (start == null) return false;
        Set<PsiFile> visited = new HashSet<>();
        Deque<PsiFile> queue = new ArrayDeque<>(List.of(start));
        while (!queue.isEmpty()) {
            PsiFile current = queue.poll();
            if (!visited.add(current)) continue;
            for (PsiFile included : AhkInclude.includedFiles(current)) {
                if (file.equals(included.getVirtualFile())) return true;
                queue.add(included);
            }
        }
        return false;
    }

    /** The first error message in the output (warnings are skipped), or {@code null}. */
    public static @Nullable Error parse(@NotNull String output) {
        Matcher matcher = MESSAGE.matcher(output);
        while (matcher.find()) {
            String message = matcher.group(3);
            if (message.startsWith(WARNING)) continue;
            try {
                return new Error(matcher.group(1).trim(), Integer.parseInt(matcher.group(2)), message);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** Number of warnings ({@code ==> Warning: …}) in the output. */
    public static int warnings(@NotNull String output) {
        Matcher matcher = MESSAGE.matcher(output);
        int count = 0;
        while (matcher.find()) {
            if (matcher.group(3).startsWith(WARNING)) count++;
        }
        return count;
    }

    /** Number of parser errors in the file, outside lines marked with ";@ignore-next-line". */
    public static int parserErrors(@NotNull Project project, @NotNull VirtualFile file) {
        PsiFile psi = PsiManager.getInstance(project).findFile(file);
        if (psi == null) return 0;
        int count = 0;
        for (PsiErrorElement error : PsiTreeUtil.collectElementsOfType(psi, PsiErrorElement.class)) {
            if (!AhkIgnoredLines.isIgnored(error)) count++;
        }
        return count;
    }

    /**
     * Empties the validation console of the project (if there is one), so that no outdated output stays visible, e.g.
     * after "Reformat Code" (AhkValidateTriggers). The tab stays; the next validation replaces it as usual.
     */
    public static void clearConsole(@NotNull Project project) {
        RunContentDescriptor descriptor = project.getUserData(CONSOLE);
        if (descriptor == null) return;
        if (descriptor.getExecutionConsole() instanceof ConsoleView console) console.clear();
    }

    /**
     * Makes {@code console} the validation console of the project, as {@link #showConsole} does; {@code null} removes
     * it again (for tests).
     */
    @org.jetbrains.annotations.TestOnly
    public static void useConsoleForTest(@NotNull Project project, @Nullable ConsoleView console) {
        project.putUserData(CONSOLE, console == null ? null
                : new RunContentDescriptor(console, null, console.getComponent(), "test"));
    }

    /**
     * Shows what was validated, the output of AutoHotkey and the summary in the Run tool window (replaces the
     * previous validation console). The command line itself is not shown. Summary colors: no error = green
     * ({@link #SUCCESS}), error/timeout/failure = red (error output), warnings and accepted parser errors = normal.
     */
    private static void showConsole(@NotNull Project project, @NotNull Target target, @Nullable ProcessOutput output,
                                    @NotNull List<SummaryLine> summary) {
        TextConsoleBuilder builder = TextConsoleBuilderFactory.getInstance().createBuilder(project);
        builder.addFilter(new AhkErrorFilter(project));
        ConsoleView console = builder.getConsole();
        String name = target.script().getName();
        console.print((target.configuration() == null ? AhkBundle.message("validate.start", name)
                : AhkBundle.message("validate.start.configuration", name, target.configuration())) + "\n",
                ConsoleViewContentType.SYSTEM_OUTPUT);
        if (output != null) {
            printLines(console, output.getStdout(), ConsoleViewContentType.NORMAL_OUTPUT);
            printLines(console, output.getStderr(), ConsoleViewContentType.ERROR_OUTPUT);
        }
        // blank line between the output and the summary (the output of AutoHotkey may lack the final line break)
        console.print("\n", ConsoleViewContentType.NORMAL_OUTPUT);
        for (SummaryLine line : summary) console.print(line.text() + "\n", line.type());
        RunContentDescriptor descriptor = new RunContentDescriptor(console, null, console.getComponent(),
                AhkBundle.message("validate.title", name));
        RunContentManager.getInstance(project).showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor,
                project.getUserData(CONSOLE));
        project.putUserData(CONSOLE, descriptor);
    }
}
