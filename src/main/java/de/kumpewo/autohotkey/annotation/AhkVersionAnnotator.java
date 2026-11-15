package de.kumpewo.autohotkey.annotation;

import de.kumpewo.autohotkey.AhkBundle;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.lang.ASTNode;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiTreeUtil;
import de.kumpewo.autohotkey.parser.AhkVersion;
import de.kumpewo.autohotkey.psi.AhkOtherVersionCode;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * AutoHotkey version (#Requires, see AhkVersion and AhkParserUtil.otherVersion):
 *  * File for another version (element OTHER_VERSION_CODE): warning at the deciding #Requires line.
 *  * File without "#Requires AutoHotkey ..." with a version: weak warning in the first line, with a quick fix for
 *    "#Requires AutoHotkey v2.0". If the syntax detection of the AutoHotkey launcher (AhkVersionDetector, in the
 *    background) says v1: a warning instead, with the fix for "#Requires AutoHotkey v1.1" first.
 */
public class AhkVersionAnnotator implements Annotator {


    /** Warning for other versions, hint for a missing #Requires. */
    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (element instanceof AhkOtherVersionCode) {
            TextRange range = decidingRequires(element.getNode());
            if (range != null) {
                holder.newAnnotation(HighlightSeverity.WARNING, AhkBundle.message("annotator.other.version")).range(range).create();
            }
            return;
        }

        // Hint at the first element of the file (top level, only its first line)
        if (!(element.getParent() instanceof PsiFile)
                || element.getTextRange().getStartOffset() != firstLeafOffset(element)) {
            return;
        }
        PsiFile file = element.getContainingFile();
        if (file == null || hasVersionRequires(file)) return;
        String text = element.getText();
        int end = text.indexOf('\n');
        int start = element.getTextRange().getStartOffset();
        TextRange range = new TextRange(start, start + (end < 0 ? text.length() : end));
        // Syntax detection of the AutoHotkey launcher (in the background; until its result: the v2 hint)
        AhkVersionDetector.Result detected = AhkVersionDetector.resultOrRequest(file, file.getViewProvider().getContents());
        if (detected != null && detected.major() == 1) {
            holder.newAnnotation(HighlightSeverity.WARNING, AhkBundle.message("annotator.requires.v1", detected.marks()))
                    .range(range)
                    .withFix(new AhkAddRequiresFix(AhkAddRequiresFix.LINE_V1, PriorityAction.Priority.HIGH))
                    .withFix(new AhkAddRequiresFix(AhkAddRequiresFix.LINE, PriorityAction.Priority.NORMAL))
                    .create();
            return;
        }
        holder.newAnnotation(HighlightSeverity.WEAK_WARNING, AhkBundle.message("annotator.requires.missing"))
                .range(range)
                .withFix(new AhkAddRequiresFix())
                .create();
    }

    /** Offset of the first leaf of the file that is not whitespace/line break (-1: none). */
    private static int firstLeafOffset(PsiElement element) {
        PsiFile file = element.getContainingFile();
        if (file == null) return -1;
        return CachedValuesManager.getCachedValue(file, () -> {
            int offset = -1;
            for (PsiElement leaf = PsiTreeUtil.firstChild(file); leaf != null; leaf = PsiTreeUtil.nextLeaf(leaf)) {
                IElementType t = leaf.getNode().getElementType();
                if (t != TokenType.WHITE_SPACE && t != AhkTypes.CRLF && leaf.getTextLength() > 0) {
                    offset = leaf.getTextRange().getStartOffset();
                    break;
                }
            }
            return CachedValueProvider.Result.create(offset, file);
        });
    }

    /** Is there a #Requires directive with an AutoHotkey version? */
    private static boolean hasVersionRequires(PsiFile file) {
        return CachedValuesManager.getCachedValue(file, () -> {
            boolean found = false;
            for (PsiElement leaf = PsiTreeUtil.firstChild(file); leaf != null; leaf = PsiTreeUtil.nextLeaf(leaf)) {
                if (requiresArgs(leaf.getNode()) != null) {
                    found = true;
                    break;
                }
            }
            return CachedValueProvider.Result.create(found, file);
        });
    }

    /** First #Requires directive with a version in the swallowed range: from the name to the end of the arguments. */
    private static @Nullable TextRange decidingRequires(ASTNode code) {
        for (ASTNode n = code.getFirstChildNode(); n != null; n = n.getTreeNext()) {
            ASTNode args = requiresArgs(n);
            if (args != null) {
                int end = args.getStartOffset() + args.getText().stripTrailing().length();
                return new TextRange(n.getStartOffset(), end);
            }
        }
        return null;
    }

    /** Is n the name "#Requires" with an AutoHotkey version? Then its argument token, otherwise null. */
    private static @Nullable ASTNode requiresArgs(@Nullable ASTNode n) {
        if (n == null || n.getElementType() != AhkTypes.DIRECTIVE_NAME || !"#Requires".equalsIgnoreCase(n.getText())) {
            return null;
        }
        ASTNode args = n.getTreeNext();
        while (args != null && args.getElementType() == TokenType.WHITE_SPACE) args = args.getTreeNext();
        if (args == null || args.getElementType() != AhkTypes.DIRECTIVE_ARGS) return null;
        return AhkVersion.requiresV2(args.getText()) != null ? args : null;
    }
}
