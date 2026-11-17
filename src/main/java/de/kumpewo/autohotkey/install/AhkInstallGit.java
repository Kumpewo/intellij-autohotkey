package de.kumpewo.autohotkey.install;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Git on the command line ({@code git} from the PATH; no Git plugin, the plugin only depends on platform modules),
 * run in the tool folder: the warning about uncommitted changes of the installer ({@link AhkInstaller}) and commit and
 * tag of "Set Version" ({@link AhkSetVersion}). A commit only concerns the given file
 * ({@code git commit -m <message> -- <file>}), so that other staged changes are not committed; the tag is annotated
 * and set on HEAD.
 */
public final class AhkInstallGit {

    /** Maximum run time of one Git command. */
    static final int TIMEOUT_MS = 60_000;

    private final Path folder;

    /** Git commands in the tool folder. */
    public AhkInstallGit(@NotNull Path folder) {
        this.folder = folder;
    }

    /** Result of one Git command. */
    public record Result(int exitCode, @NotNull String stdout, @NotNull String stderr) {
        /** Exit code 0. */
        public boolean ok() {
            return exitCode == 0;
        }

        /** Stderr, otherwise stdout, trimmed (for messages). */
        public @NotNull String text() {
            return (stderr.isBlank() ? stdout : stderr).strip();
        }
    }

    /** Runs {@code git <args>} in the tool folder; a program that cannot be started gives exit code -1. */
    public @NotNull Result run(@NotNull String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        GeneralCommandLine line = new GeneralCommandLine(command).withWorkDirectory(folder.toFile())
                .withCharset(StandardCharsets.UTF_8);
        try {
            ProcessOutput output = new CapturingProcessHandler(line).runProcess(TIMEOUT_MS);
            if (output.isTimeout()) return new Result(-1, "", "timeout");
            return new Result(output.getExitCode(), output.getStdout(), output.getStderr());
        } catch (ExecutionException e) {
            return new Result(-1, "", String.valueOf(e.getMessage()));
        }
    }

    /** Is Git installed and the tool folder inside a working tree? */
    public boolean inRepository() {
        Result result = run("rev-parse", "--is-inside-work-tree");
        return result.ok() && result.stdout().strip().equals("true");
    }

    /** Does the tool folder have uncommitted changes (also untracked files)? */
    public boolean hasChanges() {
        Result result = run("status", "--porcelain", "--", ".");
        return result.ok() && !result.stdout().isBlank();
    }

    /** Does the file of the tool folder have uncommitted changes (or is it untracked)? */
    public boolean hasChanges(@NotNull String fileName) {
        Result result = run("status", "--porcelain", "--", fileName);
        return result.ok() && !result.stdout().isBlank();
    }

    /** Does the tool folder have uncommitted changes in other files than the given one? */
    public boolean hasOtherChanges(@NotNull String fileName) {
        Result result = run("status", "--porcelain", "--", ".", ":(exclude)" + fileName);
        return result.ok() && !result.stdout().isBlank();
    }

    /** Is the tag name valid for Git ({@code git check-ref-format})? */
    public boolean isValidTag(@NotNull String tag) {
        return run("check-ref-format", "refs/tags/" + tag).ok();
    }

    /** Does the tag exist already? */
    public boolean tagExists(@NotNull String tag) {
        return run("rev-parse", "-q", "--verify", "refs/tags/" + tag).ok();
    }

    /**
     * Commits only the file of the tool folder (also if it was untracked) with the message; other staged changes stay
     * staged. Nothing to commit is no error. Returns {@code null} on success, otherwise the error text of Git.
     */
    public @Nullable String commitFile(@NotNull String fileName, @NotNull String message) {
        Result add = run("add", "--", fileName);
        if (!add.ok()) return add.text();
        if (run("diff", "--cached", "--quiet", "--", fileName).ok()) return null;
        Result commit = run("commit", "-m", message, "--only", "--", fileName);
        return commit.ok() ? null : commit.text();
    }

    /** Sets the annotated tag on HEAD; {@code null} on success, otherwise the error text of Git. */
    public @Nullable String tag(@NotNull String tag, @NotNull String message) {
        Result result = run("tag", "-a", tag, "-m", message);
        return result.ok() ? null : result.text();
    }
}
