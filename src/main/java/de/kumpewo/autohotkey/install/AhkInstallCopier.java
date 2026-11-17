package de.kumpewo.autohotkey.install;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.documentation.AhkExternalHelp;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Copies the steps of an {@link AhkInstallPlan} in order and stops at the first error (no rollback).
 * <ul>
 *   <li>Copying writes the content into the target (created or truncated), so a new file inherits the permissions
 *       of its folder and an overwritten file keeps its own; the modification time of the source is taken over.
 *       Missing target folders are created.</li>
 *   <li>{@code RequiresAdmin=true}: all copies run in one elevated PowerShell process ({@code Start-Process -Verb
 *       RunAs}, one UAC prompt) from a script in {@code <temp>/intellij-autohotkey/}; it writes one line per step
 *       into a log file, which is read afterwards. The paths are already resolved in the IDE process, so the
 *       elevated process never resolves environment variables (another administrator account would have another
 *       {@code %APPDATA%}).</li>
 * </ul>
 */
public final class AhkInstallCopier {

    /** What happened to one step. */
    public enum Outcome { COPIED, SKIPPED, FAILED }

    /**
     * Result of one step.
     *
     * @param step    the step
     * @param outcome copied, skipped (target existed, {@code [KeepExisting]}) or failed
     * @param error   the error message if failed
     */
    public record StepResult(@NotNull AhkInstallPlan.Step step, @NotNull Outcome outcome, @Nullable String error) {
    }

    /** Maximum run time of the elevated process (the UAC prompt waits for the user). */
    static final int ELEVATED_TIMEOUT_MS = 10 * 60_000;

    private AhkInstallCopier() {
    }

    /** Copies in this process; the results end with the first failed step. */
    public static @NotNull List<StepResult> copy(@NotNull List<AhkInstallPlan.Step> steps) {
        List<StepResult> results = new ArrayList<>();
        for (AhkInstallPlan.Step step : steps) {
            try {
                if (step.keepExisting() && Files.exists(step.target())) {
                    results.add(new StepResult(step, Outcome.SKIPPED, null));
                    continue;
                }
                copyFile(step.source(), step.target());
                results.add(new StepResult(step, Outcome.COPIED, null));
            } catch (IOException | RuntimeException e) {
                results.add(new StepResult(step, Outcome.FAILED, e.toString()));
                break;
            }
        }
        return results;
    }

    /** Writes the content of the source into the target and takes over the modification time. */
    static void copyFile(@NotNull Path source, @NotNull Path target) throws IOException {
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (InputStream in = Files.newInputStream(source); OutputStream out = Files.newOutputStream(target)) {
            in.transferTo(out);
        }
        Files.setLastModifiedTime(target, Files.getLastModifiedTime(source));
    }

    /**
     * Copies in one elevated process (waits for it; not on the EDT). If the elevation is refused or the process
     * fails before the first step, the first step is reported as failed with that reason.
     */
    public static @NotNull List<StepResult> copyElevated(@NotNull List<AhkInstallPlan.Step> steps) {
        List<StepResult> results = new ArrayList<>();
        if (steps.isEmpty()) return results;
        Path script = null;
        Path log = null;
        try {
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), AhkExternalHelp.TEMP_FOLDER);
            Files.createDirectories(dir);
            script = Files.createTempFile(dir, "install-", ".ps1");
            log = Files.createTempFile(dir, "install-", ".log");
            // UTF-8 with byte order mark: Windows PowerShell 5.1 reads a script without it in the ANSI code page
            Files.write(script, ("﻿" + elevatedScript(steps, log)).getBytes(StandardCharsets.UTF_8));
            String outer = "$p = Start-Process -FilePath powershell.exe -Verb RunAs -Wait -PassThru -WindowStyle Hidden"
                    + " -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File'," + quote("\"" + script + "\"")
                    + "; exit $p.ExitCode";
            GeneralCommandLine line = new GeneralCommandLine("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-EncodedCommand", encode(outer));
            ProcessOutput output = new CapturingProcessHandler(line).runProcess(ELEVATED_TIMEOUT_MS);
            results.addAll(readLog(steps, Files.readAllLines(log, StandardCharsets.UTF_8)));
            if (results.isEmpty() || (results.size() < steps.size()
                    && results.get(results.size() - 1).outcome() != Outcome.FAILED)) {
                String reason = output.isTimeout() ? "timeout" : (output.getStderr() + output.getStdout()).strip();
                if (reason.isEmpty()) reason = AhkBundle.message("install.elevation.failed", output.getExitCode());
                results.add(new StepResult(steps.get(results.size()), Outcome.FAILED, reason));
            }
        } catch (IOException | ExecutionException | RuntimeException e) {
            results.add(new StepResult(steps.get(results.size()), Outcome.FAILED, e.toString()));
        } finally {
            deleteQuietly(script);
            deleteQuietly(log);
        }
        return results;
    }

    /**
     * The PowerShell script of the elevated process: one line {@code OK|SKIP|ERROR<TAB>index[<TAB>message]} per
     * step in the log, stops at the first error (exit code 1).
     */
    static @NotNull String elevatedScript(@NotNull List<AhkInstallPlan.Step> steps, @NotNull Path log) {
        StringBuilder s = new StringBuilder();
        s.append("$ErrorActionPreference = 'Stop'\r\n");
        s.append("$log = ").append(quote(log.toString())).append("\r\n");
        s.append("$steps = @(\r\n");
        for (int i = 0; i < steps.size(); i++) {
            AhkInstallPlan.Step step = steps.get(i);
            s.append("  ,@(").append(quote(step.source().toString())).append(", ")
                    .append(quote(step.target().toString())).append(", ")
                    .append(step.keepExisting() ? "$true" : "$false").append(")\r\n");
        }
        s.append(")\r\n");
        s.append("""
                $i = 0
                foreach ($s in $steps) {
                  try {
                    if ($s[2] -and [IO.File]::Exists($s[1])) {
                      [IO.File]::AppendAllText($log, "SKIP`t$i`r`n")
                    } else {
                      $dir = [IO.Path]::GetDirectoryName($s[1])
                      if ($dir) { [void][IO.Directory]::CreateDirectory($dir) }
                      $in = [IO.File]::OpenRead($s[0])
                      try {
                        $out = [IO.File]::Open($s[1], [IO.FileMode]::Create, [IO.FileAccess]::Write)
                        try { $in.CopyTo($out) } finally { $out.Dispose() }
                      } finally { $in.Dispose() }
                      [IO.File]::SetLastWriteTimeUtc($s[1], [IO.File]::GetLastWriteTimeUtc($s[0]))
                      [IO.File]::AppendAllText($log, "OK`t$i`r`n")
                    }
                  } catch {
                    $m = $_.Exception.Message -replace "[`r`n`t]+", ' '
                    [IO.File]::AppendAllText($log, "ERROR`t$i`t$m`r`n")
                    exit 1
                  }
                  $i++
                }
                exit 0
                """.replace("\n", "\r\n"));
        return s.toString();
    }

    /** Results from the lines of the log of the elevated process. */
    static @NotNull List<StepResult> readLog(@NotNull List<AhkInstallPlan.Step> steps, @NotNull List<String> lines) {
        List<StepResult> results = new ArrayList<>();
        for (String line : lines) {
            String[] parts = line.strip().replace("﻿", "").split("\t", 3);
            if (parts.length < 2) continue;
            int index;
            try {
                index = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                continue;
            }
            if (index < 0 || index >= steps.size()) continue;
            AhkInstallPlan.Step step = steps.get(index);
            switch (parts[0]) {
                case "OK" -> results.add(new StepResult(step, Outcome.COPIED, null));
                case "SKIP" -> results.add(new StepResult(step, Outcome.SKIPPED, null));
                case "ERROR" -> results.add(new StepResult(step, Outcome.FAILED, parts.length > 2 ? parts[2] : ""));
                default -> {
                }
            }
        }
        return results;
    }

    /** PowerShell string literal in single quotes. */
    static @NotNull String quote(@NotNull String text) {
        return "'" + text.replace("'", "''") + "'";
    }

    /** Base64 of the UTF-16LE text, for {@code -EncodedCommand}. */
    private static String encode(String command) {
        return Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_16LE));
    }

    private static void deleteQuietly(@Nullable Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // a leftover file in the temporary folder does no harm
        }
    }
}
