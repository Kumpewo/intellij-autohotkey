package de.kumpewo.autohotkey.structure;

import com.intellij.lang.ASTNode;
import com.intellij.lang.folding.FoldingBuilderEx;
import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.FoldingGroup;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import de.kumpewo.autohotkey.include.AhkInclude;
import de.kumpewo.autohotkey.psi.AhkDirective;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Code folding (lang.foldingBuilder). Folded are, if they span more than one line:
 * <ul>
 *   <li>everything between a pair of braces: class, function, method, property and control blocks, hotkey and
 *       hotstring blocks, switch blocks, multi-line object literals; placeholder {@code {...}}</li>
 *   <li>block comments ({@code /*...*}{@code /}) and continuation sections (multi-line strings,
 *       placeholder {@code "(...)"})</li>
 *   <li>runs of at least two whole-line comments (placeholder: the first comment line)</li>
 *   <li>runs of at least two {@code #Include}/{@code #IncludeAgain} lines (placeholder {@code #Include ...})</li>
 *   <li>custom regions between {@code ;region [name]} and {@code ;endregion}, and between {@code ;{ [name]} and
 *       {@code ;}} (SciTE4AutoHotkey style); placeholder: the name, otherwise {@code ...}. The platform's custom
 *       folding providers don't know the comment character {@code ;}, so they are recognized here.</li>
 * </ul>
 * Nothing is collapsed by default. Works on the syntax tree only, so also in dumb mode.
 */
public class AhkFoldingBuilder extends FoldingBuilderEx implements DumbAware {

    /** Patterns for the region markers (comment text without leading whitespace). */
    private static final class Tables {
        static final Pattern REGION_START = Pattern.compile("(?i);\\s*region\\b(.*)");
        static final Pattern REGION_END = Pattern.compile("(?i);\\s*endregion\\b.*");
        static final Pattern BRACE_START = Pattern.compile(";\\{(.*)");
        static final Pattern BRACE_END = Pattern.compile(";}.*");
        /** Longest placeholder taken from a comment. */
        static final int MAX_PLACEHOLDER = 60;
    }

    /** Collects the fold regions of the file. */
    @Override
    public FoldingDescriptor @NotNull [] buildFoldRegions(@NotNull PsiElement root, @NotNull Document document,
                                                          boolean quick) {
        List<FoldingDescriptor> result = new ArrayList<>();
        Regions regions = new Regions();
        collect(root.getNode(), document, result, regions);
        commentRuns(root.getNode(), document, result);
        includeRuns(root.getNode(), document, result);
        return result.toArray(FoldingDescriptor.EMPTY_ARRAY);
    }

    /** Braces, block comments, continuation sections and region markers (recursively, in document order). */
    private static void collect(ASTNode node, Document document, List<FoldingDescriptor> result, Regions regions) {
        for (ASTNode child = node.getFirstChildNode(); child != null; child = child.getTreeNext()) {
            IElementType type = child.getElementType();
            if (type == AhkTypes.L_BRACE) {
                ASTNode closing = closingBrace(child);
                if (closing != null) {
                    add(result, node, new TextRange(child.getStartOffset(), closing.getTextRange().getEndOffset()),
                            "{...}", document);
                }
            } else if (type == AhkTypes.BLOCK_COMMENT) {
                // the lexer returns a block comment in pieces ("/*", text, "*", "*/"): fold the whole sequence
                ASTNode previous = child.getTreePrev();
                if (previous == null || previous.getElementType() != AhkTypes.BLOCK_COMMENT) {
                    ASTNode last = child;
                    while (last.getTreeNext() != null && last.getTreeNext().getElementType() == AhkTypes.BLOCK_COMMENT) {
                        last = last.getTreeNext();
                    }
                    add(result, child, new TextRange(child.getStartOffset(), last.getTextRange().getEndOffset()),
                            "/*...*/", document);
                }
            } else if (type == AhkTypes.STRING) {
                add(result, child, child.getTextRange(), continuationPlaceholder(child), document);
            } else if (type == AhkTypes.LINE_COMMENT) {
                regions.comment(child, document, result);
            }
            if (child.getFirstChildNode() != null) collect(child, document, result, regions);
        }
    }

    /** The R_BRACE among the later siblings of {@code open} (same parent), {@code null} if it is missing. */
    private static @Nullable ASTNode closingBrace(ASTNode open) {
        ASTNode closing = null;
        for (ASTNode next = open.getTreeNext(); next != null; next = next.getTreeNext()) {
            if (next.getElementType() == AhkTypes.R_BRACE) closing = next;
        }
        return closing;
    }

    /** Placeholder of a continuation section: the quote character with "(...)". */
    private static String continuationPlaceholder(ASTNode string) {
        char quote = string.getText().isEmpty() ? '"' : string.getText().charAt(0);
        return quote + "(...)" + quote;
    }

    /** Runs of at least two comments that each fill a line of their own. */
    private static void commentRuns(ASTNode root, Document document, List<FoldingDescriptor> result) {
        List<ASTNode> run = new ArrayList<>();
        for (ASTNode leaf = firstLeaf(root); leaf != null; leaf = nextLeaf(leaf)) {
            IElementType type = leaf.getElementType();
            if (type == AhkTypes.LINE_COMMENT && isWholeLine(leaf, document) && !isRegionMarker(leaf)) {
                if (!run.isEmpty() && lineOf(leaf, document) != lineOf(run.get(run.size() - 1), document) + 1) {
                    commentRun(run, document, result);
                }
                run.add(leaf);
            } else if (type != TokenType.WHITE_SPACE && type != AhkTypes.CRLF) {
                commentRun(run, document, result);
            }
        }
        commentRun(run, document, result);
    }

    /** Fold for a finished run of comments (at least two lines); empties the list. */
    private static void commentRun(List<ASTNode> run, Document document, List<FoldingDescriptor> result) {
        if (run.size() >= 2) {
            ASTNode first = run.get(0);
            TextRange range = new TextRange(first.getStartOffset() + leadingSpace(first.getText()),
                    run.get(run.size() - 1).getTextRange().getEndOffset());
            add(result, first, range, shorten(first.getText().trim()) + " ...", document);
        }
        run.clear();
    }

    /** Runs of at least two {@code #Include} lines (only line breaks and whitespace in between). */
    private static void includeRuns(ASTNode root, Document document, List<FoldingDescriptor> result) {
        List<ASTNode> run = new ArrayList<>();
        for (ASTNode child = root.getFirstChildNode(); child != null; child = child.getTreeNext()) {
            IElementType type = child.getElementType();
            if (child.getPsi() instanceof AhkDirective directive && AhkInclude.isInclude(directive)) {
                run.add(child);
            } else if (type != TokenType.WHITE_SPACE && type != AhkTypes.CRLF) {
                includeRun(run, document, result);
            }
        }
        includeRun(run, document, result);
    }

    /** Fold for a finished run of include lines; empties the list. */
    private static void includeRun(List<ASTNode> run, Document document, List<FoldingDescriptor> result) {
        if (run.size() >= 2) {
            ASTNode first = run.get(0);
            String name = first.getText().trim().split("\\s+", 2)[0];
            add(result, first, new TextRange(first.getStartOffset(), run.get(run.size() - 1).getTextRange().getEndOffset()),
                    name + " ...", document);
        }
        run.clear();
    }

    /** Open region markers; pairs them with their end markers in document order. */
    private static final class Regions {
        private final Deque<ASTNode> regions = new ArrayDeque<>();
        private final Deque<ASTNode> braces = new ArrayDeque<>();

        /** Handles a line comment: start or end of a region, otherwise nothing. */
        void comment(ASTNode comment, Document document, List<FoldingDescriptor> result) {
            String text = comment.getText().trim();
            if (Tables.REGION_END.matcher(text).matches()) {
                close(regions, comment, Tables.REGION_START, document, result);
            } else if (Tables.REGION_START.matcher(text).matches()) {
                regions.push(comment);
            } else if (Tables.BRACE_END.matcher(text).matches()) {
                close(braces, comment, Tables.BRACE_START, document, result);
            } else if (Tables.BRACE_START.matcher(text).matches()) {
                braces.push(comment);
            }
        }

        /** Region from the last open start marker to {@code end}. */
        private static void close(Deque<ASTNode> open, ASTNode end, Pattern start, Document document,
                                  List<FoldingDescriptor> result) {
            if (open.isEmpty()) return;
            ASTNode first = open.pop();
            Matcher matcher = start.matcher(first.getText().trim());
            String name = matcher.matches() ? matcher.group(1).trim() : "";
            TextRange range = new TextRange(first.getStartOffset() + leadingSpace(first.getText()),
                    end.getTextRange().getEndOffset());
            add(result, first, range, name.isEmpty() ? "..." : shorten(name), document);
        }
    }

    /** Is the comment a region marker (start or end)? */
    private static boolean isRegionMarker(ASTNode comment) {
        String text = comment.getText().trim();
        return Tables.REGION_START.matcher(text).matches() || Tables.REGION_END.matcher(text).matches()
                || Tables.BRACE_START.matcher(text).matches() || Tables.BRACE_END.matcher(text).matches();
    }

    /** Adds a fold if the range spans more than one line. */
    private static void add(List<FoldingDescriptor> result, ASTNode node, TextRange range, String placeholder,
                            Document document) {
        if (range.getEndOffset() > document.getTextLength()) return;
        if (document.getLineNumber(range.getStartOffset()) == document.getLineNumber(range.getEndOffset())) return;
        result.add(new FoldingDescriptor(node, range, (FoldingGroup) null, placeholder));
    }

    /** Only whitespace before the comment in its line (comments behind code start with their whitespace). */
    private static boolean isWholeLine(ASTNode comment, Document document) {
        int start = comment.getStartOffset() + leadingSpace(comment.getText());
        int lineStart = document.getLineStartOffset(document.getLineNumber(start));
        return document.getCharsSequence().subSequence(lineStart, start).toString().isBlank();
    }

    /** Line of the start of the node. */
    private static int lineOf(ASTNode node, Document document) {
        return document.getLineNumber(node.getStartOffset() + leadingSpace(node.getText()));
    }

    /** Number of whitespace characters at the start of the text. */
    private static int leadingSpace(String text) {
        int i = 0;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;
        return i;
    }

    /** Text cut to the maximum placeholder length. */
    private static String shorten(String text) {
        return text.length() <= Tables.MAX_PLACEHOLDER ? text : text.substring(0, Tables.MAX_PLACEHOLDER) + "...";
    }

    /** First leaf below the node. */
    private static @Nullable ASTNode firstLeaf(ASTNode node) {
        ASTNode leaf = node;
        while (leaf != null && leaf.getFirstChildNode() != null) leaf = leaf.getFirstChildNode();
        return leaf == node ? null : leaf;
    }

    /** Next leaf in document order. */
    private static @Nullable ASTNode nextLeaf(ASTNode leaf) {
        ASTNode node = leaf;
        while (node != null && node.getTreeNext() == null) node = node.getTreeParent();
        if (node == null) return null;
        node = node.getTreeNext();
        while (node.getFirstChildNode() != null) node = node.getFirstChildNode();
        return node;
    }

    /** Placeholder for descriptors without their own text (not used: every descriptor has one). */
    @Override
    public @Nullable String getPlaceholderText(@NotNull ASTNode node) {
        return "...";
    }

    /** Nothing is collapsed when a file is opened. */
    @Override
    public boolean isCollapsedByDefault(@NotNull ASTNode node) {
        return false;
    }
}
