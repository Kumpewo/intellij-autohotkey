package de.kumpewo.autohotkey.annotation;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.util.concurrency.AppExecutorUtil;
import de.kumpewo.autohotkey.settings.AhkSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Detects the AutoHotkey version of a script without {@code #Requires} with the syntax detection of the AutoHotkey
 * launcher ({@code IdentifyBySyntax} in {@code UX\inc\identify.ahk} of the installation), for the hint of
 * {@link AhkVersionAnnotator}.
 * <p>
 * The launcher's own {@code /Which} is not used: when it cannot determine a version it ends with a runtime error
 * dialog ({@code ExitApp} with an empty value, observed 2026-11-14), and {@code /ErrorStdOut} does not help for
 * runtime errors. Instead a small helper script includes {@code identify.ahk} and prints the result; it runs with
 * {@code AutoHotkeyUX.exe} (a v2 interpreter) next to {@code inc}.
 * <p>
 * The detection runs in the background, once per text (debounced by {@link #DELAY_MS}); the result is cached by the
 * text and the highlighting of the file is restarted afterwards. Without an AutoHotkey installation with
 * {@code identify.ahk} the result stays unknown. Off in unit tests unless enabled ({@link #enableForTest}).
 */
public final class AhkVersionDetector {

    private static final Logger LOG = Logger.getInstance(AhkVersionDetector.class);

    /** Pause after the last change before the detection starts. */
    static final int DELAY_MS = 1000;
    /** Time limit of the helper process. */
    static final int TIMEOUT_MS = 10_000;

    /**
     * Result of the detection: {@code major} 1 or 2, 0 = unknown; {@code marks} = the tell-tale constructs the launcher
     * found (e.g. {@code v1-cmd v1-ass}) or its reason ({@code no tell-tale matches}).
     */
    public record Result(int major, @NotNull String marks) {
        /** Unknown version (no installation, error, no clear tell-tale). */
        static final Result UNKNOWN = new Result(0, "");
    }

    /** Results by text (the same text gives the same result), the 200 most recent ones. */
    private static final Map<String, Result> CACHE = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Result> eldest) {
            return size() > 200;
        }
    });

    /** Texts whose detection is scheduled or running. */
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();

    private static volatile boolean enabledInTests;

    private AhkVersionDetector() {
    }

    /**
     * The cached result for the text, or {@code null} if there is none yet; in that case the detection is scheduled
     * (once per text) and the highlighting of {@code file} is restarted when it is done.
     */
    public static @Nullable Result resultOrRequest(@NotNull PsiFile file, @NotNull CharSequence text) {
        if (ApplicationManager.getApplication().isUnitTestMode() && !enabledInTests) return Result.UNKNOWN;
        String key = key(text);
        Result cached = CACHE.get(key);
        if (cached != null || !PENDING.add(key)) return cached;
        Project project = file.getProject();
        SmartPsiElementPointer<PsiFile> pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(file);
        AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> run(project, pointer, key), DELAY_MS,
                TimeUnit.MILLISECONDS);
        return null;
    }

    /** Runs the detection if the file still has the text, then restarts its highlighting. */
    private static void run(Project project, SmartPsiElementPointer<PsiFile> pointer, String key) {
        try {
            String text = ReadAction.compute(() -> {
                if (project.isDisposed()) return null;
                PsiFile file = pointer.getElement();
                Document document = file == null ? null
                        : PsiDocumentManager.getInstance(project).getDocument(file);
                return document == null ? null : document.getText();
            });
            if (text == null || !key(text).equals(key)) return;   // changed meanwhile: a new request follows
            CACHE.put(key, detect(text));
            ApplicationManager.getApplication().runReadAction(() -> {
                PsiFile file = project.isDisposed() ? null : pointer.getElement();
                if (file != null) DaemonCodeAnalyzer.getInstance(project).restart(file, "AutoHotkey version detected");
            });
        } finally {
            PENDING.remove(key);
        }
    }

    /** Runs the helper script on the text (blocking, not on the EDT); {@link Result#UNKNOWN} if that is not possible. */
    public static @NotNull Result detect(@NotNull String text) {
        Path inc = identifyDirectory();
        if (inc == null) return Result.UNKNOWN;
        Path exe = inc.getParent().resolve(AhkSettings.LAUNCHER_EXE);
        if (!Files.isRegularFile(exe)) return Result.UNKNOWN;
        Path dir = null;
        try {
            dir = Files.createTempDirectory("ahk-version");
            Path helper = dir.resolve("identify.ahk");
            Path script = dir.resolve("script.ahk");
            Files.writeString(helper, helperScript(inc), StandardCharsets.UTF_8);
            Files.writeString(script, text, StandardCharsets.UTF_8);
            GeneralCommandLine commandLine = new GeneralCommandLine(exe.toString(), "/ErrorStdOut", helper.toString(),
                    script.toString()).withCharset(StandardCharsets.UTF_8).withWorkDirectory(dir.toFile());
            ProcessOutput output = new CapturingProcessHandler(commandLine).runProcess(TIMEOUT_MS, true);
            if (output.isTimeout() || output.getExitCode() != 0) {
                LOG.info("AutoHotkey version detection failed: " + output.getExitCode() + " " + output.getStderr());
                return Result.UNKNOWN;
            }
            return parse(output.getStdout());
        } catch (Exception e) {
            LOG.info("AutoHotkey version detection failed", e);
            return Result.UNKNOWN;
        } finally {
            if (dir != null) com.intellij.openapi.util.io.FileUtil.delete(dir.toFile());
        }
    }

    /** The helper script: includes identify.ahk of the installation and prints the version and the marks. */
    public static @NotNull String helperScript(@NotNull Path inc) {
        return "#NoTrayIcon\n"
                + "#Include " + inc + "\\\n"
                + "#Include identify.ahk\n"
                + "i := IdentifyBySyntax(FileRead(A_Args[1], \"UTF-8\"))\n"
                + "FileAppend i.v \"`n\" i.r \"`n\", \"*\", \"UTF-8-RAW\"\n";
    }

    /** First line = version (1, 2, 0 or empty), second line = marks or reason. */
    public static @NotNull Result parse(@NotNull String stdout) {
        String[] lines = stdout.split("\\R", -1);
        int major;
        try {
            major = Integer.parseInt(lines[0].trim());
        } catch (NumberFormatException e) {
            major = 0;
        }
        String marks = lines.length > 1 ? lines[1].trim() : "";
        return new Result(major == 1 || major == 2 ? major : 0, marks);
    }

    /**
     * {@code UX\inc} with {@code identify.ahk}: next to the AutoHotkey program from the settings (launcher:
     * {@code UX\inc}; interpreter in {@code v2\} or the main folder: {@code ..\UX\inc}), otherwise of the detected
     * installation; {@code null} if there is none.
     */
    public static @Nullable Path identifyDirectory() {
        List<Path> programs = new ArrayList<>();
        String configured = AhkSettings.getInstance().findAutoHotkey();
        try {
            if (configured != null) programs.add(Path.of(configured));
        } catch (java.nio.file.InvalidPathException ignored) {
            // invalid path in the settings: only the detected installation
        }
        Path detected = AhkSettings.detectAutoHotkey();
        if (detected != null) programs.add(detected);
        for (Path program : programs) {
            Path dir = program.toAbsolutePath().getParent();
            for (int level = 0; level < 3 && dir != null; level++, dir = dir.getParent()) {   // at most three levels up
                for (Path inc : new Path[]{dir.resolve("inc"), dir.resolve("UX").resolve("inc")}) {
                    if (Files.isRegularFile(inc.resolve("identify.ahk"))) return inc;
                }
            }
        }
        return null;
    }

    /** Cache key: length and hash of the text (the text itself would keep large files in memory). */
    private static String key(CharSequence text) {
        return text.length() + ":" + text.toString().hashCode();
    }

    /** Enables the detection in unit tests until {@code disposable} is disposed, and clears the cache. */
    @TestOnly
    public static void enableForTest(@NotNull com.intellij.openapi.Disposable disposable) {
        enabledInTests = true;
        CACHE.clear();
        com.intellij.openapi.util.Disposer.register(disposable, () -> {
            enabledInTests = false;
            CACHE.clear();
        });
    }
}
