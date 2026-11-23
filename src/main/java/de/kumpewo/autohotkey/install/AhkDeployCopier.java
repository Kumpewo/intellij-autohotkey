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
 * Copies the steps of an {@link AhkDeployPlan} in order and stops at the first error (no rollback).
 * <ul>
 *   <li>Copying writes the content into the target (created or truncated), so a new file inherits the permissions
 *       of its folder and an overwritten file keeps its own; the modification time of the source is taken over.
 *       Missing target folders are created. A step with a banner ({@code Banner}) writes the banner lines and then
 *       the content of the source ({@link AhkBanner#apply}); a step with an origin writes the executable and then the
 *       origin ({@link AhkExeOrigin#append}).</li>
 *   <li>A target of {@code [Install]} gets the read-only attribute after copying (silently left out where the file
 *       system refuses it); an existing read-only target is made writable first and overwritten without asking
 *       (usually an earlier deployment set the attribute). A target of {@code [InstallIfMissing]} never gets it, and
 *       {@code [Cleanup]} removes the attribute before moving a file to the Recycle Bin.</li>
 *   <li>{@code RequiresAdmin=yes}: all copies run in one elevated PowerShell process ({@code Start-Process -Verb
 *       RunAs}, one UAC prompt) from a script in {@code <temp>/intellij-autohotkey/}; it writes one line per step
 *       into a log file, which is read afterwards. The paths are already resolved in the IDE process, so the
 *       elevated process never resolves environment variables (another administrator account would have another
 *       {@code %APPDATA%}). The content of a copy with banner or origin is prepared in the temporary folder first.</li>
 * </ul>
 */
public final class AhkDeployCopier {

    /** What happened to one step. */
    public enum Outcome { COPIED, SKIPPED, FAILED }

    /**
     * Result of one step.
     *
     * @param step    the step
     * @param outcome copied, skipped (target existed, {@code [InstallIfMissing]}) or failed
     * @param error   the error message if failed
     */
    public record StepResult(@NotNull AhkDeployPlan.Step step, @NotNull Outcome outcome, @Nullable String error) {
    }

    /**
     * Result of one removal of {@code [Cleanup]}.
     *
     * @param removal the file
     * @param error   {@code null} if it is in the Recycle Bin now (or was gone already), otherwise the reason
     */
    public record RemovalResult(@NotNull AhkDeployPlan.Removal removal, @Nullable String error) {
    }

    /**
     * Results of the elevated process.
     *
     * @param removals the removals done, ending with the first failed one
     * @param steps    the copies done, ending with the first failed one (none after a failed removal)
     */
    public record ElevatedResult(@NotNull List<RemovalResult> removals, @NotNull List<StepResult> steps) {
    }

    /** Marker of the elevated process for a file on a drive without Recycle Bin. */
    static final String NO_RECYCLE_BIN = "NO_RECYCLE_BIN";

    /** Maximum run time of the elevated process (the UAC prompt waits for the user). */
    static final int ELEVATED_TIMEOUT_MS = 10 * 60_000;

    private AhkDeployCopier() {
    }

    /**
     * Moves the files to the Recycle Bin in this process; the results end with the first failure. A file that is gone
     * already counts as removed. A file on a drive without Recycle Bin (only local fixed drives have one) is a failure:
     * it is never deleted for good.
     */
    public static @NotNull List<RemovalResult> recycle(@NotNull List<AhkDeployPlan.Removal> removals) {
        List<RemovalResult> results = new ArrayList<>();
        for (AhkDeployPlan.Removal removal : removals) {
            String error = null;
            try {
                Path file = removal.file();
                if (Files.exists(file)) {
                    if (!hasRecycleBin(file)) {
                        error = AhkBundle.message("deploy.cleanup.no.recycle.bin");
                    } else {
                        clearReadOnlyQuietly(file);
                        com.sun.jna.platform.FileUtils.getInstance().moveToTrash(file.toFile());
                        if (Files.exists(file)) error = AhkBundle.message("deploy.cleanup.still.there");
                    }
                }
            } catch (IOException | RuntimeException e) {
                error = e.toString();
            }
            results.add(new RemovalResult(removal, error));
            if (error != null) break;
        }
        return results;
    }

    /** Is the file on a local fixed drive (the only drives with a Recycle Bin)? */
    static boolean hasRecycleBin(@NotNull Path file) {
        Path root = file.toAbsolutePath().getRoot();
        if (root == null || !com.sun.jna.platform.FileUtils.getInstance().hasTrash()) return false;
        try {
            return com.sun.jna.platform.win32.Kernel32.INSTANCE.GetDriveType(root.toString())
                    == com.sun.jna.platform.win32.WinNT.DRIVE_FIXED;
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            return false;
        }
    }

    /** Copies in this process; the results end with the first failed step. */
    public static @NotNull List<StepResult> copy(@NotNull List<AhkDeployPlan.Step> steps) {
        List<StepResult> results = new ArrayList<>();
        for (AhkDeployPlan.Step step : steps) {
            try {
                if (step.ifMissing() && Files.exists(step.target())) {
                    results.add(new StepResult(step, Outcome.SKIPPED, null));
                    continue;
                }
                if (!step.ifMissing()) clearReadOnly(step.target());
                copyFile(step, step.target());
                if (!step.ifMissing()) setReadOnlyQuietly(step.target());
                results.add(new StepResult(step, Outcome.COPIED, null));
            } catch (IOException | RuntimeException e) {
                results.add(new StepResult(step, Outcome.FAILED, e.toString()));
                break;
            }
        }
        return results;
    }

    /**
     * Writes the content of the source of the step (with the banner lines in front, if any, without the line
     * {@code dropLine}, and with the origin at the end, if any) into the target and takes over the modification time.
     */
    private static void copyFile(@NotNull AhkDeployPlan.Step step, @NotNull Path target) throws IOException {
        Path source = step.source();
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        if (isPlain(step)) {
            try (InputStream in = Files.newInputStream(source); OutputStream out = Files.newOutputStream(target)) {
                in.transferTo(out);
            }
        } else {
            byte[] content = Files.readAllBytes(source);
            if (step.banner() != null) content = AhkBanner.apply(content, step.banner(), step.dropLine());
            if (step.origin() != null) content = AhkExeOrigin.append(content, step.origin());
            Files.write(target, content);
        }
        Files.setLastModifiedTime(target, Files.getLastModifiedTime(source));
    }

    /** Is the content of the copy exactly that of the source? */
    private static boolean isPlain(@NotNull AhkDeployPlan.Step step) {
        return step.banner() == null && step.origin() == null;
    }

    /**
     * Removes the read-only attribute of the file if it has one, so that it can be overwritten or removed; a file
     * that does not exist, or a file system without this attribute, is left alone. Fails if the attribute cannot be
     * removed.
     */
    static void clearReadOnly(@NotNull Path file) throws IOException {
        if (!Files.exists(file)) return;
        try {
            if (Boolean.TRUE.equals(Files.getAttribute(file, "dos:readonly"))) {
                Files.setAttribute(file, "dos:readonly", false);
            }
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            // no DOS attributes on this file system: nothing to remove
        }
    }

    /**
     * Sets the read-only attribute of a target of {@code [Install]}, as a hint not to edit the copy; a file system
     * that refuses it (e.g. some network drives) is no error and no warning.
     */
    static void setReadOnlyQuietly(@NotNull Path file) {
        setReadOnly(file, true);
    }

    /**
     * Removes the read-only attribute if possible and ignores a failure: for a file that goes to the Recycle Bin
     * (it is writable again when restored) or a new file of the project (copied from a read-only library copy).
     */
    static void clearReadOnlyQuietly(@NotNull Path file) {
        setReadOnly(file, false);
    }

    /** Sets or removes the read-only attribute and ignores a failure. */
    private static void setReadOnly(@NotNull Path file, boolean readOnly) {
        try {
            Files.setAttribute(file, "dos:readonly", readOnly);
        } catch (IOException | RuntimeException e) {
            // the attribute is only a hint
        }
    }

    /**
     * Copies in one elevated process (waits for it; not on the EDT). If the elevation is refused or the process
     * fails before the first step, the first step is reported as failed with that reason.
     */
    public static @NotNull List<StepResult> copyElevated(@NotNull List<AhkDeployPlan.Step> steps) {
        return copyElevated(List.of(), steps).steps();
    }

    /**
     * Moves the files to the Recycle Bin and then copies, all in one elevated process (waits for it; not on the
     * EDT). If the elevation is refused or the process fails before its first action, that action is reported as
     * failed with that reason. After a failed removal nothing is copied.
     */
    public static @NotNull ElevatedResult copyElevated(@NotNull List<AhkDeployPlan.Removal> removals,
                                                       @NotNull List<AhkDeployPlan.Step> steps) {
        List<StepResult> results = new ArrayList<>();
        List<RemovalResult> removed = new ArrayList<>();
        ElevatedResult result = new ElevatedResult(removed, results);
        if (steps.isEmpty() && removals.isEmpty()) return result;
        Path script = null;
        Path log = null;
        List<Path> prepared = new ArrayList<>();
        try {
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), AhkExternalHelp.TEMP_FOLDER);
            Files.createDirectories(dir);
            script = Files.createTempFile(dir, "deploy-", ".ps1");
            log = Files.createTempFile(dir, "deploy-", ".log");
            // a copy with banner or origin: the elevated process copies the prepared content (same modification time)
            List<AhkDeployPlan.Step> physical = new ArrayList<>();
            for (AhkDeployPlan.Step step : steps) {
                if (isPlain(step)) {
                    physical.add(step);
                    continue;
                }
                Path content = Files.createTempFile(dir, "deploy-", ".tmp");
                prepared.add(content);
                copyFile(step, content);
                physical.add(new AhkDeployPlan.Step(content, step.target(), step.ifMissing()));
            }
            // UTF-8 with byte order mark: Windows PowerShell 5.1 reads a script without it in the ANSI code page
            Files.write(script, ("﻿" + elevatedScript(removals, physical, log)).getBytes(StandardCharsets.UTF_8));
            String outer = "$p = Start-Process -FilePath powershell.exe -Verb RunAs -Wait -PassThru -WindowStyle Hidden"
                    + " -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File'," + quote("\"" + script + "\"")
                    + "; exit $p.ExitCode";
            GeneralCommandLine line = new GeneralCommandLine("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-EncodedCommand", encode(outer));
            ProcessOutput output = new CapturingProcessHandler(line).runProcess(ELEVATED_TIMEOUT_MS);
            List<String> logLines = Files.readAllLines(log, StandardCharsets.UTF_8);
            removed.addAll(readRemovals(removals, logLines));
            results.addAll(readLog(steps, logLines));
            String reason = output.isTimeout() ? "timeout" : (output.getStderr() + output.getStdout()).strip();
            if (reason.isEmpty()) reason = AhkBundle.message("deploy.elevation.failed", output.getExitCode());
            if (!removed.isEmpty() && removed.get(removed.size() - 1).error() != null) return result;
            if (removed.size() < removals.size()) {
                removed.add(new RemovalResult(removals.get(removed.size()), reason));
                return result;
            }
            if (!steps.isEmpty() && (results.isEmpty() || (results.size() < steps.size()
                    && results.get(results.size() - 1).outcome() != Outcome.FAILED))) {
                results.add(new StepResult(steps.get(results.size()), Outcome.FAILED, reason));
            }
        } catch (IOException | ExecutionException | RuntimeException e) {
            if (removed.size() < removals.size()) {
                removed.add(new RemovalResult(removals.get(removed.size()), e.toString()));
            } else if (results.size() < steps.size()) {
                results.add(new StepResult(steps.get(results.size()), Outcome.FAILED, e.toString()));
            }
        } finally {
            deleteQuietly(script);
            deleteQuietly(log);
            for (Path file : prepared) deleteQuietly(file);
        }
        return result;
    }

    /**
     * The PowerShell script of the elevated process: one line {@code OK|SKIP|ERROR<TAB>index[<TAB>message]} per
     * step in the log, stops at the first error (exit code 1).
     */
    static @NotNull String elevatedScript(@NotNull List<AhkDeployPlan.Step> steps, @NotNull Path log) {
        return elevatedScript(List.of(), steps, log);
    }

    /**
     * The PowerShell script of the elevated process: first the removals (one line
     * {@code REMOVED|NOTREMOVED<TAB>index[<TAB>message]} each; only on a local fixed drive, into the Recycle Bin with
     * {@code Microsoft.VisualBasic.FileIO.FileSystem.DeleteFile}), then the copies as in
     * {@link #elevatedScript(List, Path)}; stops at the first error (exit code 1).
     */
    static @NotNull String elevatedScript(@NotNull List<AhkDeployPlan.Removal> removals,
                                          @NotNull List<AhkDeployPlan.Step> steps, @NotNull Path log) {
        StringBuilder s = new StringBuilder();
        s.append("$ErrorActionPreference = 'Stop'\r\n");
        s.append("$log = ").append(quote(log.toString())).append("\r\n");
        if (!removals.isEmpty()) {
            s.append("$removals = @(\r\n");
            for (AhkDeployPlan.Removal removal : removals) {
                s.append("  ,").append(quote(removal.file().toString())).append("\r\n");
            }
            s.append(")\r\n");
            s.append("""
                    Add-Type -AssemblyName Microsoft.VisualBasic
                    $r = 0
                    foreach ($f in $removals) {
                      try {
                        if ([IO.File]::Exists($f)) {
                          $d = New-Object IO.DriveInfo([IO.Path]::GetPathRoot($f))
                          if ($d.DriveType -ne [IO.DriveType]::Fixed) {
                            [IO.File]::AppendAllText($log, "NOTREMOVED`t$r`t%s`r`n")
                            exit 1
                          }
                          try { $a = New-Object IO.FileInfo($f); if ($a.IsReadOnly) { $a.IsReadOnly = $false } } catch { }
                          [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile($f, 'OnlyErrorDialogs', 'SendToRecycleBin')
                          if ([IO.File]::Exists($f)) { throw 'still there' }
                        }
                        [IO.File]::AppendAllText($log, "REMOVED`t$r`r`n")
                      } catch {
                        $m = $_.Exception.Message -replace "[`r`n`t]+", ' '
                        [IO.File]::AppendAllText($log, "NOTREMOVED`t$r`t$m`r`n")
                        exit 1
                      }
                      $r++
                    }
                    """.formatted(NO_RECYCLE_BIN).replace("\n", "\r\n"));
        }
        s.append("$steps = @(\r\n");
        for (int i = 0; i < steps.size(); i++) {
            AhkDeployPlan.Step step = steps.get(i);
            s.append("  ,@(").append(quote(step.source().toString())).append(", ")
                    .append(quote(step.target().toString())).append(", ")
                    .append(step.ifMissing() ? "$true" : "$false").append(")\r\n");
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
                      $t = New-Object IO.FileInfo($s[1])
                      if (-not $s[2] -and $t.Exists -and $t.IsReadOnly) { $t.IsReadOnly = $false }
                      $in = [IO.File]::OpenRead($s[0])
                      try {
                        $out = [IO.File]::Open($s[1], [IO.FileMode]::Create, [IO.FileAccess]::Write)
                        try { $in.CopyTo($out) } finally { $out.Dispose() }
                      } finally { $in.Dispose() }
                      [IO.File]::SetLastWriteTimeUtc($s[1], [IO.File]::GetLastWriteTimeUtc($s[0]))
                      if (-not $s[2]) { try { (New-Object IO.FileInfo($s[1])).IsReadOnly = $true } catch { } }
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
    static @NotNull List<StepResult> readLog(@NotNull List<AhkDeployPlan.Step> steps, @NotNull List<String> lines) {
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
            AhkDeployPlan.Step step = steps.get(index);
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

    /** Results of the removals from the lines of the log of the elevated process. */
    static @NotNull List<RemovalResult> readRemovals(@NotNull List<AhkDeployPlan.Removal> removals,
                                                     @NotNull List<String> lines) {
        List<RemovalResult> results = new ArrayList<>();
        for (String line : lines) {
            String[] parts = line.strip().replace("﻿", "").split("\t", 3);
            if (parts.length < 2) continue;
            int index;
            try {
                index = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                continue;
            }
            if (index < 0 || index >= removals.size()) continue;
            switch (parts[0]) {
                case "REMOVED" -> results.add(new RemovalResult(removals.get(index), null));
                case "NOTREMOVED" -> {
                    String error = parts.length > 2 ? parts[2] : "";
                    results.add(new RemovalResult(removals.get(index), NO_RECYCLE_BIN.equals(error)
                            ? AhkBundle.message("deploy.cleanup.no.recycle.bin") : error));
                }
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
