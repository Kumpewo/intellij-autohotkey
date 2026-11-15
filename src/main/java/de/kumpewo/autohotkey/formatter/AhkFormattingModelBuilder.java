package de.kumpewo.autohotkey.formatter;

import com.intellij.formatting.*;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import de.kumpewo.autohotkey.AhkLanguage;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;

/**
 * Entry point of the formatter: the block tree ({@link AhkBlock}) and the spacing rules.
 *
 * Rules: the FIRST matching rule wins, so special rules come before general ones.
 * All spaces()/spaceIf()/none() keep line breaks (KEEP_LINE_BREAKS is on) - important because the end of a line
 * ends a statement in AHK.
 */
public class AhkFormattingModelBuilder implements FormattingModelBuilder {

    /**
     * Formatting model for the file with the root block and the spacing rules. {@link AhkFormattingModel} keeps the
     * CRLF tokens when whitespace is changed through the PSI (Z.25).
     */
    @Override
    public @NotNull FormattingModel createModel(@NotNull FormattingContext context) {
        CodeStyleSettings settings = context.getCodeStyleSettings();
        AhkBlock root = new AhkBlock(context.getNode(), null, null, createSpacingBuilder(settings));
        return new AhkFormattingModel(context.getContainingFile(), root);
    }

    private static final IElementType[] CONTROL = {
            AhkTypes.IF_STMT, AhkTypes.WHILE_STMT, AhkTypes.LOOP_STMT, AhkTypes.FOR_STMT,
            AhkTypes.TRY_STMT, AhkTypes.CATCH_CLAUSE, AhkTypes.FINALLY_CLAUSE, AhkTypes.ELSE_BRANCH};

    private static final IElementType[] UNARY_OPERATORS = {
            AhkTypes.PLUS, AhkTypes.MINUS, AhkTypes.BANG, AhkTypes.TILDE, AhkTypes.AMP, AhkTypes.INCREMENT_OP};

    /** The spacing rules, controlled by the code style options. */
    static SpacingBuilder createSpacingBuilder(CodeStyleSettings settings) {
        CommonCodeStyleSettings c = settings.getCommonSettings(AhkLanguage.INSTANCE);
        SpacingBuilder b = new SpacingBuilder(settings, AhkLanguage.INSTANCE);

        // ---- empty brackets: never spaces (must come before the "within" rules)
        b.between(AhkTypes.L_PAREN, AhkTypes.R_PAREN).none();
        b.between(AhkTypes.L_BRACKET, AhkTypes.R_BRACKET).none();
        b.between(AhkTypes.L_BRACE, AhkTypes.R_BRACE).none();

        // ---- comma
        // Comma chain for omitted arguments (foo(a,,, b)) like one comma: never spaces inside,
        // before/after like a single comma (must come before before/after).
        b.between(AhkTypes.COMMA, AhkTypes.COMMA).none();
        b.before(AhkTypes.COMMA).spaceIf(c.SPACE_BEFORE_COMMA);
        b.after(AhkTypes.COMMA).spaceIf(c.SPACE_AFTER_COMMA);

        // ---- assignment, arrow
        b.around(AhkTypes.ASSIGN_OP).spaceIf(c.SPACE_AROUND_ASSIGNMENT_OPERATORS);
        b.around(AhkTypes.FAT_ARROW).spaceIf(c.SPACE_AROUND_LAMBDA_ARROW);

        // ---- word operators and keywords: ALWAYS need a space (not configurable!)
        b.around(TokenSet.create(
                AhkTypes.KEYWORD_AND, AhkTypes.KEYWORD_OR, AhkTypes.KEYWORD_IS, AhkTypes.KEYWORD_IN,
                AhkTypes.KEYWORD_CONTAINS, AhkTypes.KEYWORD_AS, AhkTypes.KEYWORD_ELSE,
                AhkTypes.KEYWORD_EXTENDS, AhkTypes.KEYWORD_UNTIL)).spaces(1);
        b.after(TokenSet.create(
                AhkTypes.KEYWORD_NOT, AhkTypes.KEYWORD_IF, AhkTypes.KEYWORD_WHILE, AhkTypes.KEYWORD_FOR,
                AhkTypes.KEYWORD_LOOP, AhkTypes.KEYWORD_RETURN, AhkTypes.KEYWORD_THROW, AhkTypes.KEYWORD_CATCH,
                AhkTypes.KEYWORD_DECL, AhkTypes.KEYWORD_CLASS,
                AhkTypes.KEYWORD_SWITCH, AhkTypes.KEYWORD_CASE, AhkTypes.KEYWORD_GOTO)).spaces(1);
        b.afterInside(AhkTypes.IDENTIFIER, AhkTypes.COMMAND_STMT).spaces(1);   // MsgBox "x"

        // ---- symbolic operators
        // PLUS/MINUS/STAR/AMP/QUESTION/COLON are ambiguous (unary, variadic, parameter, property)
        // and are therefore only handled INSIDE their binary element.
        b.around(TokenSet.create(AhkTypes.AND_AND, AhkTypes.OR_OR, AhkTypes.COALESCE))
                .spaceIf(c.SPACE_AROUND_LOGICAL_OPERATORS);
        b.around(TokenSet.create(AhkTypes.EQ, AhkTypes.EQ_EQ, AhkTypes.NOT_EQ))
                .spaceIf(c.SPACE_AROUND_EQUALITY_OPERATORS);
        b.around(TokenSet.create(AhkTypes.LT, AhkTypes.GT, AhkTypes.LE, AhkTypes.GE, AhkTypes.REGEX_MATCH))
                .spaceIf(c.SPACE_AROUND_RELATIONAL_OPERATORS);
        b.around(AhkTypes.SHIFT_OP).spaceIf(c.SPACE_AROUND_SHIFT_OPERATORS);
        b.around(TokenSet.create(AhkTypes.BAR, AhkTypes.CARET)).spaceIf(c.SPACE_AROUND_BITWISE_OPERATORS);
        b.aroundInside(AhkTypes.AMP, AhkTypes.BIT_AND_EXPR).spaceIf(c.SPACE_AROUND_BITWISE_OPERATORS);
        b.aroundInside(AhkTypes.PLUS, AhkTypes.ADD_EXPR).spaceIf(c.SPACE_AROUND_ADDITIVE_OPERATORS);
        b.aroundInside(AhkTypes.MINUS, AhkTypes.ADD_EXPR).spaceIf(c.SPACE_AROUND_ADDITIVE_OPERATORS);
        b.aroundInside(AhkTypes.STAR, AhkTypes.MUL_EXPR).spaceIf(c.SPACE_AROUND_MULTIPLICATIVE_OPERATORS);
        b.around(TokenSet.create(AhkTypes.SLASH, AhkTypes.FLOOR_DIV, AhkTypes.POW))
                .spaceIf(c.SPACE_AROUND_MULTIPLICATIVE_OPERATORS);

        // ---- unary operators and x++
        for (IElementType op : UNARY_OPERATORS) {
            b.afterInside(op, AhkTypes.UNARY_EXPR).spaceIf(c.SPACE_AROUND_UNARY_OPERATOR);
        }
        b.beforeInside(AhkTypes.INCREMENT_OP, AhkTypes.POSTFIX_EXPR).none();

        // ---- ternary a ? b : c
        b.beforeInside(AhkTypes.QUESTION, AhkTypes.TERNARY_EXPR).spaceIf(c.SPACE_BEFORE_QUEST);
        b.afterInside(AhkTypes.QUESTION, AhkTypes.TERNARY_EXPR).spaceIf(c.SPACE_AFTER_QUEST);
        b.beforeInside(AhkTypes.COLON, AhkTypes.TERNARY_EXPR).spaceIf(c.SPACE_BEFORE_COLON);
        b.afterInside(AhkTypes.COLON, AhkTypes.TERNARY_EXPR).spaceIf(c.SPACE_AFTER_COLON);

        // ---- object property {a: 1}, member access a.b, parameter markers, deref
        b.beforeInside(AhkTypes.COLON, AhkTypes.PROPERTY).none();
        b.afterInside(AhkTypes.COLON, AhkTypes.PROPERTY).spaces(1);
        b.aroundInside(AhkTypes.DOT, AhkTypes.MEMBER_EXPR).none();
        b.aroundInside(AhkTypes.CONCAT_DOT, AhkTypes.CONCAT_EXPR).spaces(1);   // a . b: the spaces are required
        b.beforeInside(AhkTypes.STAR, AhkTypes.PARAMETER).none();      // b*
        b.beforeInside(AhkTypes.QUESTION, AhkTypes.PARAMETER).none();  // b?
        b.beforeInside(AhkTypes.STAR, AhkTypes.SPREAD_EXPR).none();        // f(args*)
        b.beforeInside(AhkTypes.QUESTION, AhkTypes.OPTIONAL_EXPR).none();  // f(Title?)
        b.afterInside(AhkTypes.AMP, AhkTypes.PARAMETER).none();        // &b
        b.afterInside(AhkTypes.PERCENT, AhkTypes.DEREF_EXPR).none();   // %b%
        b.beforeInside(AhkTypes.PERCENT, AhkTypes.DEREF_EXPR).none();

        // ---- brackets
        // No rule for the space BEFORE ( or [ in calls, declarations and index access: in AHK v2 a space there is
        // either a syntax error or means something else (implicit concatenation). A rule here would silently
        // change the meaning of the code. So deliberately NO rule (the formatter leaves it as it is).

        b.afterInside(AhkTypes.L_PAREN, AhkTypes.ARG_LIST).spaceIf(c.SPACE_WITHIN_METHOD_CALL_PARENTHESES);
        b.beforeInside(AhkTypes.R_PAREN, AhkTypes.ARG_LIST).spaceIf(c.SPACE_WITHIN_METHOD_CALL_PARENTHESES);
        b.afterInside(AhkTypes.L_PAREN, AhkTypes.PARAMETER_LIST).spaceIf(c.SPACE_WITHIN_METHOD_PARENTHESES);
        b.beforeInside(AhkTypes.R_PAREN, AhkTypes.PARAMETER_LIST).spaceIf(c.SPACE_WITHIN_METHOD_PARENTHESES);
        b.afterInside(AhkTypes.L_PAREN, AhkTypes.PAREN_EXPR).spaceIf(c.SPACE_WITHIN_PARENTHESES);
        b.beforeInside(AhkTypes.R_PAREN, AhkTypes.PAREN_EXPR).spaceIf(c.SPACE_WITHIN_PARENTHESES);

        // Array literal [ a, b ] and index access a[1] separately: ARRAY_EXPR and INDEX_LIST
        boolean spaceInArray = c.SPACE_WITHIN_BRACKETS;
        boolean spaceInIndex = settings.getCustomSettings(AhkCodeStyleSettings.class).SPACE_WITHIN_INDEX_BRACKETS;
        b.afterInside(AhkTypes.L_BRACKET, AhkTypes.ARRAY_EXPR).spaceIf(spaceInArray);
        b.beforeInside(AhkTypes.R_BRACKET, AhkTypes.ARRAY_EXPR).spaceIf(spaceInArray);
        b.afterInside(AhkTypes.L_BRACKET, AhkTypes.INDEX_LIST).spaceIf(spaceInIndex);
        b.beforeInside(AhkTypes.R_BRACKET, AhkTypes.INDEX_LIST).spaceIf(spaceInIndex);
        b.afterInside(AhkTypes.L_BRACKET, AhkTypes.INDEX_PARAMETER_LIST).spaceIf(spaceInIndex);   // __Item[key]
        b.beforeInside(AhkTypes.R_BRACKET, AhkTypes.INDEX_PARAMETER_LIST).spaceIf(spaceInIndex);
        b.afterInside(AhkTypes.L_BRACE, AhkTypes.OBJECT_EXPR).spaceIf(c.SPACE_WITHIN_BRACES);
        b.beforeInside(AhkTypes.R_BRACE, AhkTypes.OBJECT_EXPR).spaceIf(c.SPACE_WITHIN_BRACES);

        // ---- before the body / before { (K&R or Allman stays as the user wrote it)
        b.beforeInside(AhkTypes.BLOCK, AhkTypes.FUNC).spaceIf(c.SPACE_BEFORE_METHOD_LBRACE);
        b.beforeInside(AhkTypes.BLOCK, AhkTypes.CLASS).spaceIf(c.SPACE_BEFORE_CLASS_LBRACE);
        b.beforeInside(AhkTypes.PROPERTY_BLOCK, AhkTypes.CLASS_PROPERTY).spaceIf(c.SPACE_BEFORE_METHOD_LBRACE);
        b.beforeInside(AhkTypes.BLOCK, AhkTypes.ACCESSOR).spaceIf(c.SPACE_BEFORE_METHOD_LBRACE);
        for (IElementType control : CONTROL) {
            b.beforeInside(AhkTypes.BODY, control).spaces(1);
        }
        b.before(AhkTypes.CATCH_CLAUSE).spaces(1);
        b.before(AhkTypes.FINALLY_CLAUSE).spaces(1);
        b.before(AhkTypes.ELSE_BRANCH).spaces(1);   // "} else"

        // ---- switch / case
        b.beforeInside(AhkTypes.SWITCH_BLOCK, AhkTypes.SWITCH_STMT).spaceIf(c.SPACE_BEFORE_SWITCH_LBRACE);
        b.beforeInside(AhkTypes.COLON, AhkTypes.CASE_CLAUSE).none();
        b.afterInside(AhkTypes.COLON, AhkTypes.CASE_CLAUSE).spaces(1);

        return b;
    }
}
