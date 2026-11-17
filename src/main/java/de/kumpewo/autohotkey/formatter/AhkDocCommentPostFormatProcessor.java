package de.kumpewo.autohotkey.formatter;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.impl.source.codeStyle.PostFormatProcessor;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import de.kumpewo.autohotkey.AhkLanguage;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * "Reformat Code" for doc comments (only those at the start of a line and completely inside the range):
 * <ul>
 *   <li>always: the formatter only moves the first line ({@code /**}), the following lines are text inside
 *       the comment. Every following line that starts with {@code *} gets the indentation of the {@code /**} line
 *       plus one space, so that the stars stand below each other. Lines without a star and plain block comments
 *       (starting with a single star) stay as they are;</li>
 *   <li>with the main switch DOC_FORMAT (tab "Doc Comments"): the whole comment is rewritten by
 *       {@link AhkDocCommentFormatter} (alignment, blank lines, wrapping at the right margin, tags) and replaces its
 *       tokens.</li>
 * </ul>
 */
public final class AhkDocCommentPostFormatProcessor implements PostFormatProcessor {

    /** Aligns the doc comments inside an element (PSI-based formatting). */
    @Override
    public @NotNull PsiElement processElement(@NotNull PsiElement source, @NotNull CodeStyleSettings settings) {
        if (source.getLanguage() == AhkLanguage.INSTANCE) process(source, source.getTextRange(), settings);
        return source;
    }

    /** Aligns the doc comments inside a text range (the action "Reformat Code"); returns the changed range. */
    @Override
    public @NotNull TextRange processText(@NotNull PsiFile source, @NotNull TextRange range,
                                          @NotNull CodeStyleSettings settings) {
        if (source.getLanguage() != AhkLanguage.INSTANCE) return range;
        int delta = process(source, range, settings);
        return TextRange.create(range.getStartOffset(), Math.max(range.getStartOffset(), range.getEndOffset() + delta));
    }

    /** Formats (main switch on) or only aligns the doc comments; returns the change in length. */
    private static int process(PsiElement root, TextRange range, CodeStyleSettings settings) {
        AhkCodeStyleSettings custom = settings.getCustomSettings(AhkCodeStyleSettings.class);
        return custom.DOC_FORMAT ? format(root, range, settings, AhkDocCommentFormatter.Options.of(custom))
                : align(root, range);
    }

    /** A doc comment to rewrite: its tokens and the new text. */
    private record Replacement(List<LeafPsiElement> tokens, String text) {
    }

    /**
     * Rewrites every doc comment with {@link AhkDocCommentFormatter}: collects the replacements first, then replaces
     * them from the end of the file (the first token gets the whole new text, the others are removed). Returns the
     * change in length.
     */
    private static int format(PsiElement root, TextRange range, CodeStyleSettings settings,
                              AhkDocCommentFormatter.Options options) {
        PsiFile file = root.getContainingFile();
        if (file == null) return 0;
        String text = file.getText();
        int margin = settings.getRightMargin(AhkLanguage.INSTANCE);
        List<Replacement> replacements = new ArrayList<>();
        PsiTreeUtil.processElements(root, element -> {
            if (!(element instanceof LeafPsiElement start) || !isDocStart(start) || !range.contains(start.getTextRange())) {
                return true;
            }
            int offset = start.getTextRange().getStartOffset();
            int lineStart = text.lastIndexOf('\n', offset - 1) + 1;
            String indent = text.substring(lineStart, offset);
            if (!indent.isBlank()) return true;   // not at the start of a line
            List<LeafPsiElement> tokens = new ArrayList<>();
            StringBuilder comment = new StringBuilder();
            PsiElement end = null;
            for (PsiElement leaf = start; leaf instanceof LeafPsiElement token
                    && token.getNode().getElementType() == AhkTypes.BLOCK_COMMENT; leaf = PsiTreeUtil.nextLeaf(leaf)) {
                if (!range.contains(token.getTextRange())) return true;   // only comments completely inside the range
                tokens.add(token);
                comment.append(token.getText());
                if (token != start && token.getText().endsWith("*/")) {
                    end = token;
                    break;
                }
            }
            if (end == null) return true;   // not closed
            PsiElement definition = de.kumpewo.autohotkey.documentation.AhkDocComment.documentedDefinition(end);
            List<String> parameters = definition instanceof de.kumpewo.autohotkey.psi.AhkFunc func
                    ? de.kumpewo.autohotkey.documentation.AhkDocComment.parameterNames(func) : null;
            String formatted = AhkDocCommentFormatter.format(comment.toString(), indent, margin, parameters, options);
            if (!formatted.contentEquals(comment)) replacements.add(new Replacement(tokens, formatted));
            return true;
        });
        int[] delta = {0};
        com.intellij.psi.impl.source.PostprocessReformattingAspect.getInstance(file.getProject())
                .disablePostprocessFormattingInside(() -> {
                    for (int i = replacements.size() - 1; i >= 0; i--) {
                        Replacement replacement = replacements.get(i);
                        LeafPsiElement first = replacement.tokens().get(0);
                        if (!replacement.tokens().stream().allMatch(PsiElement::isValid)) continue;
                        int oldLength = replacement.tokens().stream().mapToInt(PsiElement::getTextLength).sum();
                        for (LeafPsiElement token : replacement.tokens().subList(1, replacement.tokens().size())) {
                            token.getNode().getTreeParent().removeChild(token.getNode());
                        }
                        // the new comment in the tokens the lexer would produce (the tree equals a fresh parse)
                        List<String> parts = commentTokens(replacement.text());
                        com.intellij.lang.ASTNode node = first.replaceWithText(parts.get(0));
                        for (int k = 1; k < parts.size(); k++) {
                            node.getTreeParent().addLeaf(AhkTypes.BLOCK_COMMENT, parts.get(k), node.getTreeNext());
                            node = node.getTreeNext();
                        }
                        delta[0] += replacement.text().length() - oldLength;
                    }
                });
        return delta[0];
    }

    /**
     * A block comment split like the lexer splits it (state IN_COMMENT in Ahk.flex): {@code /*}, then single
     * {@code *}, runs without {@code *}, and the closing {@code *}{@code /}.
     */
    static List<String> commentTokens(String comment) {
        List<String> tokens = new ArrayList<>();
        tokens.add(comment.substring(0, 2));
        int i = 2;
        while (i < comment.length()) {
            if (comment.startsWith("*/", i)) {
                tokens.add("*/");
                i += 2;
            } else if (comment.charAt(i) == '*') {
                tokens.add("*");
                i++;
            } else {
                int end = comment.indexOf('*', i);
                if (end < 0) end = comment.length();
                tokens.add(comment.substring(i, end));
                i = end;
            }
        }
        return tokens;
    }

    /**
     * Collects the new texts of the comment tokens first, then replaces them from the end of the file (offsets of
     * earlier tokens stay valid). Only doc comments that lie completely in the range. Returns the change in length.
     */
    private static int align(PsiElement root, TextRange range) {
        PsiFile file = root.getContainingFile();
        if (file == null) return 0;
        String text = file.getText();
        List<LeafPsiElement> leaves = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        PsiTreeUtil.processElements(root, element -> {
            if (element instanceof LeafPsiElement leaf && isDocStart(leaf) && range.contains(leaf.getTextRange())) {
                collect(leaf, text, range, leaves, texts);
            }
            return true;
        });
        int[] delta = {0};
        // without the platform's postponed formatting: it would shift the lines of a replaced multi-line token again
        com.intellij.psi.impl.source.PostprocessReformattingAspect.getInstance(file.getProject())
                .disablePostprocessFormattingInside(() -> {
                    for (int i = leaves.size() - 1; i >= 0; i--) {
                        LeafPsiElement leaf = leaves.get(i);
                        if (!leaf.isValid()) continue;
                        delta[0] += texts.get(i).length() - leaf.getTextLength();
                        leaf.replaceWithText(texts.get(i));
                    }
                });
        return delta[0];
    }

    /** {@code /*} of a doc comment: followed by a token {@code *} (the lexer splits {@code /**}), not {@code *}{@code /}. */
    private static boolean isDocStart(LeafPsiElement leaf) {
        if (leaf.getNode().getElementType() != AhkTypes.BLOCK_COMMENT || !leaf.getText().equals("/*")) return false;
        PsiElement next = PsiTreeUtil.nextLeaf(leaf);
        return next != null && next.getNode().getElementType() == AhkTypes.BLOCK_COMMENT
                && next.getText().startsWith("*") && !next.getText().startsWith("*/");
    }

    /**
     * New texts for the tokens of the comment starting at {@code start}: in a text token, the indentation after its
     * last line break becomes the indentation of the {@code /**} line plus one space if the next token is a star.
     */
    private static void collect(LeafPsiElement start, String fileText, TextRange range, List<LeafPsiElement> leaves,
                                List<String> texts) {
        int offset = start.getTextRange().getStartOffset();
        int lineStart = fileText.lastIndexOf('\n', offset - 1) + 1;
        String before = fileText.substring(lineStart, offset);
        if (!before.isBlank()) return;   // not at the start of a line
        String indent = before + " ";
        List<LeafPsiElement> own = new ArrayList<>();
        List<String> ownTexts = new ArrayList<>();
        for (PsiElement leaf = PsiTreeUtil.nextLeaf(start); leaf instanceof LeafPsiElement token
                && token.getNode().getElementType() == AhkTypes.BLOCK_COMMENT; leaf = PsiTreeUtil.nextLeaf(leaf)) {
            if (!range.contains(token.getTextRange())) return;   // only comments completely inside the range
            if (token.getText().endsWith("*/")) break;
            String aligned = aligned(token.getText(), PsiTreeUtil.nextLeaf(token), indent);
            if (aligned != null) {
                own.add(token);
                ownTexts.add(aligned);
            }
        }
        leaves.addAll(own);
        texts.addAll(ownTexts);
    }

    /** The text with the new indentation before a following star, {@code null} if nothing changes. */
    private static @Nullable String aligned(String text, @Nullable PsiElement next, String indent) {
        boolean starFollows = next != null && next.getNode().getElementType() == AhkTypes.BLOCK_COMMENT
                && next.getText().startsWith("*");
        if (!starFollows) return null;
        int last = text.lastIndexOf('\n');
        // the star must be the first character of its line (only spaces and tabs after the last line break)
        if (last < 0 || !text.substring(last + 1).chars().allMatch(c -> c == ' ' || c == '\t')) return null;
        String result = text.substring(0, last + 1) + indent;
        return result.equals(text) ? null : result;
    }
}
