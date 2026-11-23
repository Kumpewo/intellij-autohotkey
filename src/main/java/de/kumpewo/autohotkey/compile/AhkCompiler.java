package de.kumpewo.autohotkey.compile;

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.execution.ExecutionException;
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
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
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
import com.intellij.ui.JBColor;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.run.AhkErrorFilter;
import de.kumpewo.autohotkey.run.AhkRunState;
import de.kumpewo.autohotkey.settings.AhkSettings;
import de.kumpewo.autohotkey.validate.AhkValidator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.awt.Font;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiles a script into an executable with Ahk2Exe, the compiler of the AutoHotkey installation, and shows its
 * output in a console in the Run tool window (errors clickable through {@link AhkErrorFilter}).
 * <p>
 * Compiled is the script of the selected AutoHotkey run configuration, otherwise the current file (the same
 * {@link AhkValidator#target} as the validation). Only prepared scripts are compiled: the script must choose its
 * base file itself with a line {@code ;@Ahk2Exe-Base …} ({@link #hasBaseDirective}); without it Ahk2Exe would use
 * the base file chosen last in its own window, possibly one of AutoHotkey v1. Otherwise only a notification is shown,
 * whose action inserts a head of directives ({@link AhkAhk2ExeHeader}).
 * The output file, icon, version information and so on come from the {@code ;@Ahk2Exe-…} directives of the script;
 * the plugin passes only the script and the compression from the settings. Command line:
 * {@code Ahk2Exe.exe /in script /compress n /silent verbose} in the directory of the script.
 * <p>
 * With {@code /silent} Ahk2Exe shows no dialogs: status lines and the result go to stdout, errors to stderr
 * (exit code 0 = success). Syntax errors have the format of {@code /ErrorStdOut}
 * ({@code C:\Path\Script.ahk (12) : ==> Missing ")"}).
 */
public final class AhkCompiler {

    /** Maximum run time of Ahk2Exe (compression with UPX may take a while). */
    public static final int TIMEOUT_MS = 120_000;

    /** Directive that selects the base file; {@code Bin} is the older name of the same directive. */
    private static final Pattern BASE_DIRECTIVE =
            Pattern.compile("(?im)^\\uFEFF?[ \\t]*;@Ahk2Exe-(?:Base|Bin)[ \\t]+\\S");

    /** Success message of Ahk2Exe: {@code Successfully compiled as:} followed by the quoted path. */
    private static final Pattern COMPILED_AS = Pattern.compile("Successfully compiled as:\\s*\"([^\"]+)\"");

    /** Green text in the console for success (same color as the validation). */
    private static final ConsoleViewContentType SUCCESS = new ConsoleViewContentType("AutoHotkey.CompileSuccess",
            new TextAttributes(new JBColor(new Color(0x067D17), new Color(0x6AAB73)), null, null, null, Font.PLAIN));

    /** Console of the last compilation in the project, reused by the next one. */
    private static final Key<RunContentDescriptor> CONSOLE = Key.create("AhkCompiler.console");

    /** Paths of the scripts being compiled (a second start for the same script is ignored). */
    private static final Set<String> RUNNING = ConcurrentHashMap.newKeySet();

    private AhkCompiler() {
    }

    /** True if the script text contains a line {@code ;@Ahk2Exe-Base path} (or the older {@code ;@Ahk2Exe-Bin}). */
    public static boolean hasBaseDirective(@NotNull CharSequence text) {
        return BASE_DIRECTIVE.matcher(text).find();
    }

    /**
     * True if the saved script contains a line {@code ;@Ahk2Exe-Base path}; read as UTF-16 with a byte order mark,
     * otherwise byte by byte (the directive is ASCII). Unreadable file = false.
     */
    public static boolean hasBaseDirective(@NotNull Path script) {
        String text = readDirectives(script);
        return text != null && hasBaseDirective(text);
    }

    /**
     * Text of a saved script for looking up ASCII directives ({@code ;@Ahk2Exe-…}, {@code #Requires}): UTF-16 with
     * a byte order mark, otherwise byte by byte (fits UTF-8 and ANSI for ASCII text). {@code null} if unreadable.
     */
    public static @Nullable String readDirectives(@NotNull Path script) {
        try {
            byte[] content = Files.readAllBytes(script);
            Charset charset = StandardCharsets.ISO_8859_1;
            if (content.length >= 2 && (content[0] & 0xFF) == 0xFF && (content[1] & 0xFF) == 0xFE) {
                charset = StandardCharsets.UTF_16LE;
            } else if (content.length >= 2 && (content[0] & 0xFF) == 0xFE && (content[1] & 0xFF) == 0xFF) {
                charset = StandardCharsets.UTF_16BE;
            }
            return new String(content, charset);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Runs Ahk2Exe for the script and waits for it (not on the EDT); stopped after {@link #TIMEOUT_MS} or when the
     * indicator is cancelled. Also used by the run configuration ("compile first").
     */
    public static @NotNull ProcessOutput run(@NotNull String ahk2Exe, @NotNull String script,
                                             @NotNull AhkSettings.Compression compression,
                                             @NotNull ProgressIndicator indicator) throws ExecutionException {
        return new CapturingProcessHandler(createCommandLine(ahk2Exe, script, compression))
                .runProcessWithProgressIndicator(indicator, TIMEOUT_MS, true);
    }

    /** Path of the executable from the output of Ahk2Exe ({@code Successfully compiled as: "…"}), or {@code null}. */
    public static @Nullable String compiledFile(@NotNull String output) {
        Matcher matcher = COMPILED_AS.matcher(output);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Command line {@code Ahk2Exe.exe /in script /compress n /silent verbose} in the directory of the script.
     * Ahk2Exe writes in the ANSI code page, like AutoHotkey.
     */
    public static @NotNull GeneralCommandLine createCommandLine(@NotNull String ahk2Exe, @NotNull String script,
                                                                @NotNull AhkSettings.Compression compression) {
        GeneralCommandLine commandLine = new GeneralCommandLine(FileUtil.toSystemDependentName(ahk2Exe));
        String path = FileUtil.toSystemDependentName(script);
        commandLine.addParameters("/in", path, "/compress", compression.argument, "/silent", "verbose");
        String dir = new java.io.File(path).getParent();
        if (dir != null) commandLine.setWorkDirectory(dir);
        commandLine.setCharset(AhkRunState.systemCharset());
        return commandLine;
    }

    /**
     * Compiles the target of {@code file} (call on the EDT): saves all modified documents, checks the base
     * directive and runs Ahk2Exe in the background. Without base directive a notification offers to insert one
     * ({@link #notifyNoBase}); without Ahk2Exe a hint is shown in the editor or, without an editor, a message dialog.
     */
    public static void compile(@NotNull Project project, @Nullable VirtualFile file, @Nullable Editor editor) {
        if (project.isDisposed()) return;
        AhkValidator.Target target = AhkValidator.target(project, file);
        if (target == null) return;
        VirtualFile script = target.script();
        if (!AhkValidator.saveAllDocuments(project, AhkBundle.message("compile.title", script.getName()))) return;
        Document document = FileDocumentManager.getInstance().getDocument(script);
        if (document == null) return;
        if (!hasBaseDirective(document.getCharsSequence())) {
            notifyNoBase(project, script);
            return;
        }
        String ahk2Exe = AhkSettings.getInstance().findAhk2Exe();
        if (ahk2Exe == null) {
            showHint(project, editor, script, AhkBundle.message("compile.ahk2exe.not.found"));
            return;
        }
        String path = script.getPath();
        if (!RUNNING.add(path)) return;
        AhkSettings.Compression compression = AhkSettings.getInstance().getCompression();
        new Task.Backgroundable(project, AhkBundle.message("compile.progress", script.getName()), true) {
            private ProcessOutput output;
            private String failure;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    output = AhkCompiler.run(ahk2Exe, path, compression, indicator);
                } catch (ExecutionException e) {
                    failure = e.getMessage();
                }
            }

            @Override
            public void onFinished() {
                RUNNING.remove(path);
            }

            @Override
            public void onSuccess() {
                if (!project.isDisposed()) report(project, target, output, failure);
            }
        }.queue();
    }

    /**
     * The script has no base directive: balloon notification with the action "Add Ahk2Exe directives", which opens
     * the script and inserts the head of {@link AhkAhk2ExeHeader}.
     */
    private static void notifyNoBase(@NotNull Project project, @NotNull VirtualFile script) {
        com.intellij.notification.NotificationGroupManager.getInstance().getNotificationGroup("AutoHotkey")
                .createNotification(AhkBundle.message("compile.no.base", script.getName()),
                        com.intellij.notification.NotificationType.INFORMATION)
                .addAction(com.intellij.notification.NotificationAction.createSimpleExpiring(
                        AhkBundle.message("compile.no.base.action"), () -> AhkAhk2ExeHeader.insert(project, script)))
                .notify(project);
    }

    /** Shows the message as a hint in the editor of the action or, without an editor, as a message dialog. */
    private static void showHint(@NotNull Project project, @Nullable Editor editor, @NotNull VirtualFile script,
                                 @NotNull String message) {
        if (editor != null && !editor.isDisposed()) {
            HintManager.getInstance().showInformationHint(editor, message);
        } else {
            Messages.showInfoMessage(project, message, AhkBundle.message("compile.title", script.getName()));
        }
    }

    /** Shows the output and a summary in the console; refreshes the folder of the new executable. */
    private static void report(@NotNull Project project, @NotNull AhkValidator.Target target,
                               @Nullable ProcessOutput output, @Nullable String failure) {
        String summary;
        ConsoleViewContentType type = ConsoleViewContentType.ERROR_OUTPUT;
        if (failure != null) {
            summary = AhkBundle.message("compile.failed", failure);
        } else if (output.isCancelled()) {
            summary = AhkBundle.message("compile.cancelled");
        } else if (output.isTimeout()) {
            summary = AhkBundle.message("compile.timeout", TIMEOUT_MS / 1000);
        } else if (output.getExitCode() == 0) {
            String exe = compiledFile(output.getStdout());
            summary = AhkBundle.message("compile.ok", exe == null ? target.script().getName() : exe);
            type = SUCCESS;
            if (exe != null) LocalFileSystem.getInstance().refreshAndFindFileByPath(FileUtil.toSystemIndependentName(exe));
        } else {
            summary = AhkBundle.message("compile.error", output.getExitCode());
        }
        TextConsoleBuilder builder = TextConsoleBuilderFactory.getInstance().createBuilder(project);
        builder.addFilter(new AhkErrorFilter(project));
        ConsoleView console = builder.getConsole();
        String name = target.script().getName();
        console.print((target.configuration() == null ? AhkBundle.message("compile.start", name)
                : AhkBundle.message("compile.start.configuration", name, target.configuration())) + "\n",
                ConsoleViewContentType.SYSTEM_OUTPUT);
        if (output != null) {
            printLines(console, output.getStdout(), ConsoleViewContentType.NORMAL_OUTPUT);
            printLines(console, output.getStderr(), ConsoleViewContentType.ERROR_OUTPUT);
        }
        console.print("\n" + summary + "\n", type);
        RunContentDescriptor descriptor = new RunContentDescriptor(console, null, console.getComponent(),
                AhkBundle.message("compile.title", name));
        RunContentManager.getInstance(project).showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor,
                project.getUserData(CONSOLE));
        project.putUserData(CONSOLE, descriptor);
    }

    /** Prints the text (if any) so that it ends with a line break. */
    private static void printLines(@NotNull ConsoleView console, @NotNull String text,
                                   @NotNull ConsoleViewContentType type) {
        if (text.isEmpty()) return;
        console.print(text.endsWith("\n") ? text : text + "\n", type);
    }
}
