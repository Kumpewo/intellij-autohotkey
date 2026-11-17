package de.kumpewo.autohotkey.formatter;

import com.intellij.formatting.*;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.TokenType;
import com.intellij.psi.formatter.common.AbstractBlock;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Formatter block: one block per AST node (without whitespace and line breaks). Decides the indent of a line and
 * the indent after Enter. The names of the constants correspond to the rules in Ahk.bnf.
 *
 * Three kinds of indent:
 *  1. Content between brackets (BRACKET_CONTAINERS)            -> normal   (for "foo(a,<break>b)": continuation)
 *  2. Body of a control structure without a block (BODY)       -> normal
 *  3. Continuation line inside an expression/statement         -> continuation (CONTINUATION_PARENTS)
 * Continuation indents never add up: all continuation lines of a statement get the same indent, also for nested
 * expressions (a +<break>b *<break>c).
 */
public class AhkBlock extends AbstractBlock {

    private static final TokenSet OPENERS = TokenSet.create(
            AhkTypes.L_BRACE, AhkTypes.L_PAREN, AhkTypes.L_BRACKET);
    private static final TokenSet CLOSERS = TokenSet.create(
            AhkTypes.R_BRACE, AhkTypes.R_PAREN, AhkTypes.R_BRACKET);

    /** Elements that have their content between brackets. The brackets belong to the element itself. */
    private static final TokenSet BRACKET_CONTAINERS = TokenSet.create(
            AhkTypes.BLOCK, AhkTypes.PROPERTY_BLOCK, AhkTypes.ARG_LIST, AhkTypes.INDEX_LIST, AhkTypes.PARAMETER_LIST,
            AhkTypes.INDEX_PARAMETER_LIST, AhkTypes.PAREN_EXPR, AhkTypes.ARRAY_EXPR, AhkTypes.OBJECT_EXPR,
            AhkTypes.IMPORT_LIST);

    /**
     * Elements whose second and following children can be a continuation line.
     * Deliberately NOT included: FUNC and CLASS (there the next line holds a block, not an expression).
     */
    private static final TokenSet CONTINUATION_PARENTS = TokenSet.create(
            AhkTypes.ASSIGN_EXPR, AhkTypes.TERNARY_EXPR, AhkTypes.COALESCE_EXPR,
            AhkTypes.OR_EXPR, AhkTypes.AND_EXPR, AhkTypes.COMPARE_EXPR,
            AhkTypes.BIT_OR_EXPR, AhkTypes.BIT_XOR_EXPR, AhkTypes.BIT_AND_EXPR, AhkTypes.SHIFT_EXPR,
            AhkTypes.ADD_EXPR, AhkTypes.MUL_EXPR, AhkTypes.POWER_EXPR, AhkTypes.CONCAT_EXPR,
            AhkTypes.MEMBER_EXPR, AhkTypes.LAMBDA_EXPR, AhkTypes.PROPERTY,
            AhkTypes.EXPR_STMT, AhkTypes.COMMAND_STMT, AhkTypes.DECL_STMT, AhkTypes.DECL_ITEM,
            AhkTypes.RETURN_STMT, AhkTypes.TYPED_DECL_STMT, AhkTypes.TYPED_PROPERTY);

    /** Control structures with a BODY. */
    private static final TokenSet CONTROL_STATEMENTS = TokenSet.create(
            AhkTypes.IF_STMT, AhkTypes.WHILE_STMT, AhkTypes.LOOP_STMT, AhkTypes.FOR_STMT,
            AhkTypes.TRY_STMT, AhkTypes.CATCH_CLAUSE, AhkTypes.FINALLY_CLAUSE, AhkTypes.ELSE_BRANCH);

    private static final TokenSet COMMENTS = TokenSet.create(
            AhkTypes.LINE_COMMENT, AhkTypes.BLOCK_COMMENT, AhkTypes.BAD_COMMENT, AhkTypes.CONTINUATION_OPTIONS);

    private static final TokenSet SKIPPED = TokenSet.create(
            TokenType.WHITE_SPACE, AhkTypes.CRLF, AhkTypes.LINE_COMMENT, AhkTypes.BLOCK_COMMENT, AhkTypes.BAD_COMMENT,
            AhkTypes.CONTINUATION_OPTIONS);

    private final SpacingBuilder spacingBuilder;

    protected AhkBlock(@NotNull ASTNode node, @Nullable Wrap wrap, @Nullable Alignment alignment,
                       SpacingBuilder spacingBuilder) {
        super(node, wrap, alignment);
        this.spacingBuilder = spacingBuilder;
    }

    /** One child block per child node, except whitespace, line breaks and empty nodes. */
    @Override
    protected List<Block> buildChildren() {
        List<Block> blocks = new ArrayList<>();
        // File for another AHK version: leave unchanged (v2 rules would corrupt the code)
        if (myNode.getElementType() == AhkTypes.OTHER_VERSION_CODE) return blocks;
        // Continuation section: its content stays exactly as written (the indentation can be part of strings)
        if (isContinuationSection(myNode)) return blocks;
        ASTNode child = myNode.getFirstChildNode();
        while (child != null) {
            IElementType type = child.getElementType();
            if (type != TokenType.WHITE_SPACE && type != AhkTypes.CRLF && child.getTextLength() > 0) {
                blocks.add(new AhkBlock(child, null, null, spacingBuilder));
            }
            child = child.getTreeNext();
        }
        return blocks;
    }

    // ------------------------------------------------------------------ Indent

    /** Indent of this block relative to its parent (see class comment). */
    @Override
    public @NotNull Indent getIndent() {
        IElementType type = myNode.getElementType();
        ASTNode parent = myNode.getTreeParent();
        if (parent == null) return Indent.getNoneIndent();
        IElementType parentType = parent.getElementType();

        // The brackets themselves: never indented
        if (OPENERS.contains(type) || CLOSERS.contains(type)) {
            return Indent.getNoneIndent();
        }

        // Content between brackets
        if (BRACKET_CONTAINERS.contains(parentType)) {
            return bracketContentIndent(parent);
        }

        // Body without a block: one level (only takes effect if it starts on a new line).
        // If the body starts with a block, the brackets handle everything themselves.
        if (type == AhkTypes.BODY) {
            ASTNode first = myNode.getFirstChildNode();
            if (first == null || first.getElementType() == AhkTypes.BLOCK) {
                return Indent.getNoneIndent();
            }
            // Body on ONE line with its head ("else if", "else Foo()", "try Foo()"): no extra level.
            // Otherwise every further else if would add one more indent, because the body indents of the
            // nested if statements add up.
            if (!startsOnNewLine(myNode)) {
                return Indent.getNoneIndent();
            }
            return Indent.getNormalIndent(false);
        }

        // switch: "case"/"default" at the level of "switch" (style of the AHK docs, SWITCH_BLOCK without indent),
        // statements on following lines after "case x:" one level deeper.
        if (parentType == AhkTypes.CASE_CLAUSE) {
            if (afterColon(myNode) && startsOnNewLine(myNode)) return Indent.getNormalIndent(false);
            return Indent.getNoneIndent();
        }

        // Continuation line inside an expression: children from the second on.
        // relativeToDirectParent=false: relative to the line where the expression starts - so the indents
        // of chains (a + b + c) don't add up.
        // One uniform continuation indent: if the expression itself already starts a continuation line (and is
        // indented by it), its own continuation lines stay at that level instead of adding another one
        // (MsgBox(a<break>+ b<break>* c): "* c" below "+ b", although b * c is a nested expression).
        if (CONTINUATION_PARENTS.contains(parentType) && parent.getFirstChildNode() != myNode) {
            if (startsContinuationLine(parent) && hasContinuationIndent(parent)) return Indent.getNoneIndent();
            return Indent.getContinuationIndent(false);
        }

        return Indent.getNoneIndent();
    }

    /**
     * True if the node (or an ancestor starting at the same position, as its first child) gets a continuation indent:
     * a later child of an expression with continuation lines, or the content of brackets that does not start on the
     * line after the opening bracket ({@link #bracketContentIndent}).
     */
    private static boolean hasContinuationIndent(ASTNode node) {
        for (ASTNode n = node; n != null; n = n.getTreeParent()) {
            ASTNode p = n.getTreeParent();
            if (p == null) return false;
            IElementType pt = p.getElementType();
            if (CONTINUATION_PARENTS.contains(pt) && p.getFirstChildNode() != n) return true;
            if (BRACKET_CONTAINERS.contains(pt)) {
                return !OPENERS.contains(n.getElementType()) && !CLOSERS.contains(n.getElementType())
                        && pt != AhkTypes.BLOCK && pt != AhkTypes.PROPERTY_BLOCK && !lineBreakAfterOpener(p);
            }
            if (p.getFirstChildNode() != n) return false;
        }
        return false;
    }

    /**
     * Does the node begin a line, possibly behind the operator that starts the line ({@code + b * c} in
     * {@code (a<break>+ b * c)}): before it in its parent only whitespace and tokens back to a line break, or
     * nothing before it in its parent and it begins the line itself.
     */
    private static boolean startsContinuationLine(ASTNode node) {
        for (ASTNode p = node.getTreePrev(); p != null; p = p.getTreePrev()) {
            IElementType t = p.getElementType();
            if (t == AhkTypes.CRLF) return true;
            if (t == TokenType.WHITE_SPACE) {
                if (p.getText().indexOf('\n') >= 0) return true;
                continue;
            }
            if (p.getFirstChildNode() == null && !COMMENTS.contains(t)) continue;   // the operator
            return false;
        }
        return startsLine(node);
    }

    /** Is the node the first code on its line (only whitespace before it since the last line break)? */
    private static boolean startsLine(ASTNode node) {
        for (ASTNode leaf = com.intellij.psi.impl.source.tree.TreeUtil.prevLeaf(node); leaf != null;
             leaf = com.intellij.psi.impl.source.tree.TreeUtil.prevLeaf(leaf)) {
            IElementType t = leaf.getElementType();
            if (t == AhkTypes.CRLF) return true;
            if (t == TokenType.WHITE_SPACE) {
                if (leaf.getText().indexOf('\n') >= 0) return true;
                continue;
            }
            return false;
        }
        return true;
    }

    /**
     * Continuation section outside a string (Ahk.flex, CS_OPEN_LINE): a parenthesized expression whose "(" starts
     * a line and is followed by a line break (options and a comment may be in between).
     */
    static boolean isContinuationSection(ASTNode node) {
        return node.getElementType() == AhkTypes.PAREN_EXPR && startsLine(node) && lineBreakAfterOpener(node);
    }

    /** Is the node after the ":" of its case/default? */
    private static boolean afterColon(ASTNode node) {
        for (ASTNode p = node.getTreePrev(); p != null; p = p.getTreePrev()) {
            if (p.getElementType() == AhkTypes.COLON) return true;
        }
        return false;
    }

    /** Is there a line break before the node (separated only by whitespace/comments)? */
    private static boolean startsOnNewLine(ASTNode node) {
        for (ASTNode p = node.getTreePrev(); p != null; p = p.getTreePrev()) {
            IElementType t = p.getElementType();
            if (t == AhkTypes.CRLF) return true;
            if (t == TokenType.WHITE_SPACE) {
                if (p.getText().indexOf('\n') >= 0) return true;
                continue;
            }
            if (COMMENTS.contains(t)) continue;
            return false;
        }
        return false;
    }

    /**
     * foo(<break> a, b <break>)  -> normal        (bracket at the end of the line)
     * foo(a,<break> b)           -> continuation  (as in Java)
     * Blocks are always normal.
     */
    private static Indent bracketContentIndent(ASTNode container) {
        if (container.getElementType() == AhkTypes.BLOCK || container.getElementType() == AhkTypes.PROPERTY_BLOCK
                || lineBreakAfterOpener(container)) {
            return Indent.getNormalIndent(false);
        }
        return Indent.getContinuationIndent(false);
    }

    /** Does a line break follow the opening bracket (only whitespace/comments in between)? */
    private static boolean lineBreakAfterOpener(ASTNode container) {
        ASTNode opener = container.getFirstChildNode();
        if (opener == null) return false;
        for (ASTNode n = opener.getTreeNext(); n != null; n = n.getTreeNext()) {
            IElementType t = n.getElementType();
            if (t == AhkTypes.CRLF) return true;
            if (t == TokenType.WHITE_SPACE) {
                if (n.getText().indexOf('\n') >= 0) return true;
                continue;
            }
            if (COMMENTS.contains(t)) continue;
            return false;
        }
        return false;
    }

    // ------------------------------------------------------------------ Enter / new lines

    /** Indent of a new line inserted at {@code newChildIndex} (Enter). */
    @Override
    public @Nullable ChildAttributes getChildAttributes(int newChildIndex) {
        IElementType t = myNode.getElementType();
        if (BRACKET_CONTAINERS.contains(t)) {
            return new ChildAttributes(bracketContentIndent(myNode), null);
        }
        if (t == AhkTypes.SWITCH_BLOCK) {
            // Enter directly after "case x:" without a body: body one level deeper (also for live templates);
            // otherwise the next case at the level of switch
            List<Block> blocks = getSubBlocks();
            if (newChildIndex > 0 && newChildIndex <= blocks.size()
                    && blocks.get(newChildIndex - 1) instanceof AhkBlock previous
                    && previous.getNode().getElementType() == AhkTypes.CASE_CLAUSE) {
                ASTNode last = previous.getNode().getLastChildNode();
                if (last != null && last.getElementType() == AhkTypes.COLON) {
                    return new ChildAttributes(Indent.getNormalIndent(false), null);
                }
            }
            return new ChildAttributes(Indent.getNoneIndent(), null);
        }
        if (t == AhkTypes.CASE_CLAUSE) {
            return new ChildAttributes(Indent.getNormalIndent(false), null);
        }
        if (CONTROL_STATEMENTS.contains(t)) {
            // Enter after "if (x)" without a body: the body is indented
            return new ChildAttributes(Indent.getNormalIndent(false), null);
        }
        if (CONTINUATION_PARENTS.contains(t)) {
            // Enter after an operator without a right operand
            return new ChildAttributes(Indent.getContinuationIndent(false), null);
        }
        return new ChildAttributes(Indent.getNoneIndent(), null);
    }

    /** Incomplete blocks receive the new line after Enter (missing bracket, operand or body). */
    @Override
    public boolean isIncomplete() {
        IElementType t = myNode.getElementType();
        ASTNode last = myNode.getLastChildNode();

        if (BRACKET_CONTAINERS.contains(t) || t == AhkTypes.SWITCH_BLOCK) {
            // closing bracket still missing
            return last == null || !CLOSERS.contains(last.getElementType());
        }
        if (CONTINUATION_PARENTS.contains(t)) {
            // "x := a +" - the parser has an empty error element at the end for the missing operand
            return last != null && last.getElementType() == TokenType.ERROR_ELEMENT;
        }
        if (CONTROL_STATEMENTS.contains(t)) {
            ASTNode m = lastMeaningfulChild();
            if (m == null) return true;
            IElementType mt = m.getElementType();
            if (mt == AhkTypes.BODY || mt == AhkTypes.CATCH_CLAUSE || mt == AhkTypes.FINALLY_CLAUSE
                    || mt == AhkTypes.ELSE_BRANCH) {
                return false;
            }
            // "loop ... until <expression>" is complete (simplified check)
            return myNode.findChildByType(AhkTypes.KEYWORD_UNTIL) == null;
        }
        return false;
    }

    /** Last child that is not whitespace, a line break, a comment or an error element. */
    private @Nullable ASTNode lastMeaningfulChild() {
        for (ASTNode n = myNode.getLastChildNode(); n != null; n = n.getTreePrev()) {
            if (SKIPPED.contains(n.getElementType()) || n.getElementType() == TokenType.ERROR_ELEMENT) continue;
            return n;
        }
        return null;
    }

    // ------------------------------------------------------------------ Rest

    /**
     * Spacing between two children, from the rules of AhkFormattingModelBuilder. The whitespace before a
     * continuation section is never changed: moving its "(" line would move the whole section, and the indentation
     * of its lines can be part of strings.
     */
    @Override
    public @Nullable Spacing getSpacing(@Nullable Block child1, @NotNull Block child2) {
        if (child2 instanceof AhkBlock block && startsWithContinuationSection(block.getNode())) {
            return Spacing.getReadOnlySpacing();
        }
        return spacingBuilder.getSpacing(this, child1, child2);
    }

    /** The node is a continuation section or begins with one (as its first descendant). */
    private static boolean startsWithContinuationSection(@Nullable ASTNode node) {
        for (ASTNode n = node; n != null; n = n.getFirstChildNode()) {
            if (isContinuationSection(n)) return true;
        }
        return false;
    }

    /** Range of the node. */
    @Override
    public @NotNull TextRange getTextRange() {
        return myNode.getTextRange();
    }

    /**
     * Leaves: tokens, code for another AHK version and continuation sections (formatted as a whole, i.e. not at
     * all).
     */
    @Override
    public boolean isLeaf() {
        return myNode.getFirstChildNode() == null || myNode.getElementType() == AhkTypes.OTHER_VERSION_CODE
                || isContinuationSection(myNode);
    }
}
