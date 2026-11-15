package de.kumpewo.autohotkey.highlighting;

import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/** Creates the {@link AhkSyntaxHighlighter} (extension {@code lang.syntaxHighlighterFactory}). */
final class AhkSyntaxHighlighterFactory extends SyntaxHighlighterFactory {

    /** Same highlighter for every file. */
    @NotNull
    @Override
    public SyntaxHighlighter getSyntaxHighlighter(Project project, VirtualFile virtualFile) {
        return new AhkSyntaxHighlighter();
    }

}
