package de.kumpewo.autohotkey.formatter;

import com.intellij.formatting.Block;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.formatter.FormattingDocumentModelImpl;
import com.intellij.psi.formatter.PsiBasedFormattingModel;
import com.intellij.psi.impl.source.tree.LeafElement;
import com.intellij.psi.impl.source.tree.TreeUtil;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Formatting model that keeps the {@code CRLF} tokens when the formatter changes whitespace through the PSI
 * ({@code CodeStyleManager.reformat(PsiElement)}: previews of the code style page, refactorings).
 * <p>
 * A line break is a real token ({@code CRLF}, the end of a statement), but no formatter block, so the whitespace
 * between two blocks can contain {@code CRLF} tokens. The platform's PSI-based replacement only knows
 * {@code WHITE_SPACE} tokens: it inserted the new whitespace (with its line breaks) and left the {@code CRLF} tokens
 * in place, which doubled every line break and broke the indentation. Here the {@code CRLF} tokens of the range stay
 * (one per line break of the new whitespace) and only the whitespace pieces between them get the new text. The text
 * path ("Reformat Code") works on the document and is not affected.
 */
final class AhkFormattingModel extends PsiBasedFormattingModel {

    private final PsiFile file;

    AhkFormattingModel(@NotNull PsiFile file, @NotNull Block rootBlock) {
        super(file, rootBlock, FormattingDocumentModelImpl.createOn(file));
        this.file = file;
    }

    /** Replaces the whitespace of the range; with {@code CRLF} tokens in it by {@link #replaceKeepingLineBreaks}. */
    @Override
    protected String replaceWithPsiInLeaf(TextRange range, String whiteSpace, ASTNode leaf) {
        List<ASTNode> leaves = leavesIn(range);
        if (leaves == null || leaves.stream().noneMatch(AhkFormattingModel::isLineBreak)
                || whiteSpace.indexOf('\n') < 0) {
            return super.replaceWithPsiInLeaf(range, whiteSpace, leaf);
        }
        CodeStyleManager.getInstance(file.getProject())
                .performActionWithFormatterDisabled((Runnable) () -> replaceKeepingLineBreaks(leaves, whiteSpace));
        return whiteSpace;
    }

    /**
     * The leaves that make up the range exactly, if they are all whitespace or {@code CRLF}; otherwise null (then the
     * platform's replacement applies).
     */
    private @Nullable List<ASTNode> leavesIn(TextRange range) {
        if (range.isEmpty()) return null;
        ASTNode root = file.getNode();
        ASTNode leaf = root.findLeafElementAt(range.getStartOffset());
        if (leaf == null || leaf.getStartOffset() != range.getStartOffset()) return null;
        List<ASTNode> result = new ArrayList<>();
        while (leaf != null && leaf.getStartOffset() < range.getEndOffset()) {
            if (leaf.getElementType() != TokenType.WHITE_SPACE && !isLineBreak(leaf)) return null;
            if (leaf.getStartOffset() + leaf.getTextLength() > range.getEndOffset()) return null;
            result.add(leaf);
            leaf = TreeUtil.nextLeaf(leaf);
        }
        return result;
    }

    /** A {@code CRLF} token. */
    private static boolean isLineBreak(ASTNode node) {
        return node.getElementType() == AhkTypes.CRLF;
    }

    /** A token of the new whitespace: a run of line breaks ({@code CRLF}) or of spaces and tabs. */
    private record Piece(boolean lineBreak, String text) {
    }

    /** The new whitespace split like the lexer splits it: runs of line breaks and runs of other whitespace. */
    private static List<Piece> pieces(String whiteSpace) {
        List<Piece> result = new ArrayList<>();
        int i = 0;
        while (i < whiteSpace.length()) {
            boolean lineBreak = isBreakChar(whiteSpace.charAt(i));
            int end = i;
            while (end < whiteSpace.length() && isBreakChar(whiteSpace.charAt(end)) == lineBreak) end++;
            result.add(new Piece(lineBreak, whiteSpace.substring(i, end)));
            i = end;
        }
        return result;
    }

    /** Character of a CRLF token ({@code [\r\n]+} in Ahk.flex). */
    private static boolean isBreakChar(char c) {
        return c == '\n' || c == '\r';
    }

    /**
     * Puts the new whitespace into the leaves so that the tree equals a fresh parse: its runs of line breaks reuse the
     * {@code CRLF} tokens of the range in order (surplus ones are removed, missing ones added after the previous
     * piece), its other runs reuse the whitespace token of the same gap between two {@code CRLF} tokens (or a new one
     * is added); whitespace tokens that are no longer needed are removed.
     */
    private static void replaceKeepingLineBreaks(List<ASTNode> leaves, String whiteSpace) {
        // the original leaves: CRLF tokens and the whitespace tokens in the gaps before, between and after them
        List<ASTNode> breaks = new ArrayList<>();
        List<List<ASTNode>> gaps = new ArrayList<>();
        gaps.add(new ArrayList<>());
        for (ASTNode leaf : leaves) {
            if (isLineBreak(leaf)) {
                breaks.add(leaf);
                gaps.add(new ArrayList<>());
            } else {
                gaps.get(gaps.size() - 1).add(leaf);
            }
        }
        ASTNode firstLeaf = leaves.get(0);
        ASTNode anchor = null;   // the node of the last placed piece
        int nextBreak = 0;       // next original CRLF token to reuse
        int gap = 0;             // gap of the original leaves we are in
        for (Piece piece : pieces(whiteSpace)) {
            if (piece.lineBreak()) {
                if (nextBreak < breaks.size()) {
                    removeAll(gaps.get(gap));   // whitespace of this gap that was not reused
                    ASTNode lineBreak = breaks.get(nextBreak++);
                    anchor = setText(lineBreak, piece.text());
                    gap++;
                } else {
                    anchor = insertAfter(anchor, AhkTypes.CRLF, piece.text());
                }
            } else {
                List<ASTNode> spaces = gaps.get(Math.min(gap, gaps.size() - 1));
                if (gap < gaps.size() && !spaces.isEmpty()) {
                    anchor = setText(spaces.remove(0), piece.text());
                } else if (anchor != null) {
                    anchor = insertAfter(anchor, TokenType.WHITE_SPACE, piece.text());
                } else {
                    // nothing placed yet and no whitespace token at the start: insert before the first leaf
                    firstLeaf.getTreeParent().addLeaf(TokenType.WHITE_SPACE, piece.text(), firstLeaf);
                    anchor = firstLeaf.getTreePrev();
                }
            }
        }
        // what is left: whitespace of the remaining gaps and surplus CRLF tokens
        for (int i = gap; i < gaps.size(); i++) removeAll(gaps.get(i));
        for (int i = nextBreak; i < breaks.size(); i++) remove(breaks.get(i));
    }

    /** Gives the leaf the text (a new leaf of the same type if it differs) and returns the leaf now in the tree. */
    private static ASTNode setText(ASTNode leaf, String text) {
        return leaf.getText().equals(text) ? leaf : ((LeafElement) leaf).replaceWithText(text);
    }

    /** Adds a leaf directly after the anchor (in the anchor's parent) and returns it. */
    private static ASTNode insertAfter(ASTNode anchor, com.intellij.psi.tree.IElementType type, String text) {
        ASTNode parent = anchor.getTreeParent();
        parent.addLeaf(type, text, anchor.getTreeNext());
        return anchor.getTreeNext();
    }

    /** Removes the leaves from the tree. */
    private static void removeAll(List<ASTNode> nodes) {
        for (ASTNode node : nodes) remove(node);
        nodes.clear();
    }

    /** Removes one leaf from the tree (if it is still there). */
    private static void remove(ASTNode node) {
        ASTNode parent = node.getTreeParent();
        if (parent != null) parent.removeChild(node);
    }
}
