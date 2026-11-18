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
 * run in the project root: the warning about uncommitted changes of the installer ({@link AhkInstaller}) and commit and
 * tag of "Set Version" ({@link AhkSetVersion}). A commit concerns all versioned files under the project root
 * ({@code git commit -m <message> -- .}), so that staged changes outside of it are not committed; the tag is
 * annotated and set on HEAD.
 */
public final class AhkInstallGit {

    /** Maximum run time of one Git command. */
    static final int TIMEOUT_MS = 60_000;

    private final Path folder;

    /** Git commands in the folder (the project root). */
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

    /** Runs {@code git <args>} in the folder; a program that cannot be started gives exit code -1. */
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

    /** Is Git installed and the folder inside a working tree? */
    public boolean inRepository() {
        Result result = run("rev-parse", "--is-inside-work-tree");
        return result.ok() && result.stdout().strip().equals("true");
    }

    /** Does the folder have uncommitted changes (also untracked files)? */
    public boolean hasChanges() {
        return !status().isEmpty();
    }

    /**
     * The lines of {@code git status --porcelain} for the folder, untracked files one by one ({@code ?? path});
     * ignored files are not listed. Empty if Git fails.
     */
    public @NotNull List<String> status() {
        Result result = run("status", "--porcelain", "--untracked-files=all", "--", ".");
        List<String> lines = new ArrayList<>();
        if (!result.ok()) return lines;
        for (String line : result.stdout().split("\\R")) {
            if (!line.isBlank()) lines.add(line);
        }
        return lines;
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
     * Commits all changes of versioned files under the folder with the message; staged changes outside of it stay
     * staged, untracked files are not added. Returns {@code null} on success, otherwise the error text of Git.
     */
    public @Nullable String commitAll(@NotNull String message) {
        Result commit = run("commit", "-m", message, "--", ".");
        return commit.ok() ? null : commit.text();
    }

    /** Sets the annotated tag on HEAD; {@code null} on success, otherwise the error text of Git. */
    public @Nullable String tag(@NotNull String tag, @NotNull String message) {
        Result result = run("tag", "-a", tag, "-m", message);
        return result.ok() ? null : result.text();
    }
}
