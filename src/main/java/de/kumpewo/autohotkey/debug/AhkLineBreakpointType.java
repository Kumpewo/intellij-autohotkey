package de.kumpewo.autohotkey.debug;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import com.intellij.xdebugger.breakpoints.XLineBreakpointType;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.AhkFileType;
import de.kumpewo.autohotkey.psi.AhkTokenSets;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Line breakpoints in AutoHotkey files, only on lines with code: not on empty lines, comment lines or lines of a
 * file for another AutoHotkey version ({@code OTHER_VERSION_CODE}). The engine itself would move a breakpoint on
 * such a line to the next line with code.
 */
public final class AhkLineBreakpointType extends XLineBreakpointType<XBreakpointProperties<?>> {

    /** Registered in plugin.xml ({@code xdebugger.breakpointType}). */
    public AhkLineBreakpointType() {
        super("autohotkey-line", AhkBundle.message("debug.breakpoint.type"));
    }

    /** No properties. */
    @Override
    public @Nullable XBreakpointProperties<?> createBreakpointProperties(@NotNull VirtualFile file, int line) {
        return null;
    }

    /** True for a line with code in an AutoHotkey file. */
    @Override
    public boolean canPutAt(@NotNull VirtualFile file, int line, @NotNull Project project) {
        if (file.getFileType() != AhkFileType.INSTANCE) return false;
        Document document = FileDocumentManager.getInstance().getDocument(file);
        if (document == null || line < 0 || line >= document.getLineCount()) return false;
        PsiFile psi = PsiManager.getInstance(project).findFile(file);
        return psi != null && hasCode(psi, document.getLineStartOffset(line), document.getLineEndOffset(line));
    }

    /** True if a leaf between the offsets is code (not whitespace, line break or comment). */
    static boolean hasCode(@NotNull PsiFile file, int start, int end) {
        int offset = start;
        while (offset < end) {
            PsiElement leaf = file.findElementAt(offset);
            if (leaf == null) return false;
            IElementType type = leaf.getNode().getElementType();
            if (type != TokenType.WHITE_SPACE && type != AhkTypes.CRLF && !AhkTokenSets.COMMENTS.contains(type)) {
                return PsiTreeUtil.findFirstParent(leaf,
                        e -> e.getNode() != null && e.getNode().getElementType() == AhkTypes.OTHER_VERSION_CODE) == null;
            }
            offset = Math.max(offset + 1, leaf.getTextRange().getEndOffset());
        }
        return false;
    }
}
