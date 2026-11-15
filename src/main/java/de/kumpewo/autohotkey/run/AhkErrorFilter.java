package de.kumpewo.autohotkey.run;

import com.intellij.execution.filters.Filter;
import com.intellij.execution.filters.OpenFileHyperlinkInfo;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes error messages of {@code /ErrorStdOut} clickable in the console. Format (AutoHotkey v2):
 * {@code C:\Path\Script.ahk (12) : ==> Missing ")"}; {@code Path (Line)} becomes the link.
 */
public final class AhkErrorFilter implements Filter, DumbAware {

    private final Pattern pattern = Pattern.compile("^\\s*(.+?) \\((\\d+)\\) : ==> ");
    private final Project project;

    public AhkErrorFilter(@NotNull Project project) {
        this.project = project;
    }

    /** A link to the file and line, or {@code null} if the line is not an error message of an existing file. */
    @Override
    public @Nullable Result applyFilter(@NotNull String line, int entireLength) {
        Matcher matcher = pattern.matcher(line);
        if (!matcher.find()) return null;
        VirtualFile file = LocalFileSystem.getInstance()
                .findFileByPath(FileUtil.toSystemIndependentName(matcher.group(1)));
        if (file == null || file.isDirectory()) return null;
        int lineNumber;
        try {
            lineNumber = Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException e) {
            return null;
        }
        int lineStart = entireLength - line.length();
        return new Result(lineStart + matcher.start(1), lineStart + matcher.end(2) + 1,
                new OpenFileHyperlinkInfo(project, file, Math.max(0, lineNumber - 1)));
    }
}
