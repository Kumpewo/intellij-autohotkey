package de.kumpewo.autohotkey.parser;

import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.DebugUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.ParsingTestCase;
import de.kumpewo.autohotkey.psi.AhkConcatExpr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Parser tests: tree and errors for small inputs. Every input runs with a timeout, because an "empty" Pratt
 * operator once froze the IDE (endless loop in the parser).
 */
public class AhkParsingTest extends ParsingTestCase {

    private static final long TIMEOUT_SECONDS = 20;

    public AhkParsingTest() {
        super("", "ahk", new AhkParserDefinition());
    }

    @Override
    protected String getTestDataPath() {
        return "src/test/testData";
    }

    // ---------------------------------------------------------------- incomplete: errors allowed, but no hang

    public void testOpenParenthesis() { parse("a := ("); }
    public void testOpenBracket() { parse("a := ["); }
    public void testOpenPercent() { parse("a := %"); }
    public void testOpenParenthesisWithOperand() { parse("a := (b"); }
    public void testEmptyParentheses() { parse("a := ()"); }
    public void testConcatenationWithOpenParenthesis() { parse("f ("); }

    // ---------------------------------------------------------------- concatenation across line breaks in ( )

    public void testConcatenationInArgument() {
        assertConcat(assertNoErrors("x := foo(\"a\"\n    \"b\"\n    c)"));
    }

    public void testConcatenationInParentheses() {
        assertConcat(assertNoErrors("y := (a\n    b)"));
    }

    public void testClosingParenthesisOnNextLine() {
        assertNoErrors("z := foo(a\n)");
    }

    // ---------------------------------------------------------------- valid code

    public void testHeadIsConcatenation() { assertNoErrors("if (a) b := 0\n    c := 1"); }

    public void testElseChain() {
        assertNoErrors("if (a) {\n} else if (b) {\n} else MsgBox \"x\"");
    }

    public void testTryOnSameLine() { assertNoErrors("try FileDelete(\"x\")"); }
    public void testDynRef() { assertNoErrors("v1 := a%b%"); }
    public void testDeref() { assertNoErrors("v2 := % \"a\" b %"); }
    public void testDynMember() { assertNoErrors("v3 := obj.%name%"); }

    public void testClassAsIdentifier() {
        assertNoErrors("Class.prototype.defineProp(\"ToString\", { call: Class_ToString })");
    }

    public void testStarParameter() { assertNoErrors("f(*) { }"); }
    public void testStarLambda() { assertNoErrors("g := (*) => 1"); }
    public void testSpread() { assertNoErrors("h(args*)"); }

    public void testOptionalParameter() {
        assertNoErrors("__new(Options := \"\", Title?) {\n    super.__new(\"+resize \" Options, Title?)\n}");
    }

    public void testObjectLiteral() { assertNoErrors("d := { Buttons: \"Ja|Nein\" }"); }

    public void testObjectLiteralWithNumberAsKey() {
        Result r = assertNoErrors("d := { 1: \"a\", 5: \"b\", x: 2, \"y\": 3 }\ne := {\n    10: [1, 2],\n    20: \"c\"\n}");
        assertEquals(r.tree, 6, r.tree.split("PROPERTY").length - 1);
    }

    /** Inside ( ) a binary operator or the dot of a member access may start the next line (also after comments). */
    /** Default values of parameters may continue across line breaks, as in ( ) (AutoHotkey accepts this). */
    public void testParameterDefaultAcrossLines() {
        String[][] cases = {
                {"f(a := \"x\"\n\"y\") {\n    return a\n}", "AhkFuncImpl"},
                {"f(a := \"x\"\n    \"y\", b := 1\n    + 2) {\n}", "AhkFuncImpl"},
                {"f(a := 1\n    + 2) => a", "AhkFuncImpl"},
                {"g := (a := \"x\"\n\"y\") => a", "AhkLambdaExprImpl"},
                {"class C {\n    M(a := \"x\"\n        \"y\") {\n    }\n}", "AhkFuncImpl"},
                {"f(\n    a := 1,\n    b := 2\n) {\n}", "AhkFuncImpl"},
        };
        for (String[] c : cases) {
            Result r = assertNoErrors(c[0]);
            assertTrue(c[0] + "\n" + r.tree, r.tree.contains(c[1]));
        }
        // one parameter with a concatenated default, not two parameters
        Result r = assertNoErrors("f(a := \"x\"\n\"y\") {\n}");
        assertEquals(r.tree, 1, r.tree.split("AhkParameterImpl").length - 1);
        // still a call (no block): the arguments of a call already allowed this
        assertTrue(assertNoErrors("f(a\n\"y\")").tree.contains("AhkArgListImpl"));
    }

    public void testOperatorAtStartOfLineInParentheses() {
        String[][] cases = {
                {"x := (a\n    + b)", "AhkAddExprImpl"},
                {"x := (a\n    - b * c)", "AhkAddExprImpl"},
                {"x := (a\n    * b)", "AhkMulExprImpl"},
                {"x := (a\n    / b\n    // c)", "AhkMulExprImpl"},
                {"x := (a\n    ** 2)", "AhkPowerExprImpl"},
                {"x := (a\n    = b)", "AhkCompareExprImpl"},
                {"x := (a\n    != b)", "AhkCompareExprImpl"},
                {"x := (a\n    < b)", "AhkCompareExprImpl"},
                {"x := (a\n    >= b)", "AhkCompareExprImpl"},
                {"x := (a\n    ~= \"x\")", "AhkCompareExprImpl"},
                {"x := (a\n    | b)", "AhkBitOrExprImpl"},
                {"x := (a\n    & b)", "AhkBitAndExprImpl"},
                {"x := (a\n    ^ b)", "AhkBitXorExprImpl"},
                {"x := (a\n    << 2)", "AhkShiftExprImpl"},
                {"x := (a\n    := 1)", "AhkAssignExprImpl"},
                {"foo(a\n    .b())", "AhkMemberExprImpl"},
                {"foo(obj\n    .First()\n    .Second())", "AhkMemberExprImpl"},
                {"MsgBox(\"Sum: \"\n    . a\n    + b)", "AhkAddExprImpl"},
                {"x := (a\n    ; comment\n\n    + b)", "AhkAddExprImpl"},
                {"if (a\n    = 1\n    || b)\n    y := 1", "AhkCompareExprImpl"},
                {"f(1,\n    a\n    * 2,\n    3)", "AhkMulExprImpl"},
        };
        for (String[] c : cases) {
            Result r = assertNoErrors(c[0]);
            assertTrue(c[0] + "\n" + r.tree, r.tree.contains(c[1]));
        }
        // the result is ONE expression: a + b, not a statement "a" plus a stray "+ b"
        Result r = assertNoErrors("x := (a\n    + b)");
        assertEquals(r.tree, 1, r.tree.split("AhkAddExprImpl").length - 1);
        // outside of ( ) the end of a line still ends the statement
        assertFalse(parse("x := a\n+ b").errors.isEmpty() && parse("x := a\n* b").errors.isEmpty());
        Result outside = parse("x := a\n* b");
        assertFalse(outside.tree, outside.tree.contains("AhkMulExprImpl"));
        // ". " at the start of a line is a concatenation, also without indentation (not a member access)
        for (String text : new String[]{"x := \"a\"\n. b", "x := \"a\"\n    . b", "MsgBox(\"a\"\n. b\n+ c)"}) {
            Result c = assertNoErrors(text);
            assertTrue(text + "\n" + c.tree, c.tree.contains("AhkTokenType.CONCAT_DOT"));
            assertFalse(text + "\n" + c.tree, c.tree.contains("AhkMemberExprImpl"));
        }
        // ++ and -- at the start of the line are no binary operators
        assertFalse(parse("x := (a\n    ++b)").tree.contains("AhkAddExprImpl"));
    }

    /** A float without leading zero (.5) is a number; directly after an operand the dot stays member access. */
    public void testFloatWithLeadingDot() {
        for (String text : new String[]{"x := .5", "f(.25, -.5)", "y := [.1, .2]", "z := 2 * .5 + .75",
                "return .5", "w := (.5)", "v := a .5"}) {
            Result r = assertNoErrors(text);
            assertFalse(text + "\n" + r.tree, r.tree.contains("AhkTokenType.DOT"));
        }
        Result r = assertNoErrors("x := .5");
        assertTrue(r.tree, r.tree.contains("PsiElement(AhkTokenType.NUMBER)('.5')"));
        // member access with a digit after the dot: unchanged (DOT, then NUMBER)
        for (String text : new String[]{"x := a.5", "x := f().5", "x := 1.5.5", "x := arr[1].5"}) {
            assertFalse(text + "\n" + parse(text).tree, parse(text).tree.contains("PsiElement(AhkTokenType.NUMBER)('.5')"));
        }
    }

    /** Scientific notation with and without decimal point is one number token; not for hexadecimal numbers. */
    public void testScientificNotation() {
        for (String number : new String[]{"1e4", "1E4", "1.0e4", "2.1E-4", "3e+2", ".5e3"}) {
            Result r = assertNoErrors("x := " + number);
            assertTrue(number + "\n" + r.tree, r.tree.contains("PsiElement(AhkTokenType.NUMBER)('" + number + "')"));
        }
        assertNoErrors("y := -2.1E-4 * 1e3");
        // 0x1e3 is the hexadecimal number 0x1E3
        assertTrue(parse("x := 0x1e3").tree.contains("PsiElement(AhkTokenType.NUMBER)('0x1e3')"));
        // without digits after "e" it stays a number followed by a name
        assertFalse(parse("x := 1e").tree.contains("NUMBER)('1e')"));
    }

    public void testNestedBaseClass() {
        assertNoErrors("class MyEdit extends Gui.Edit {\n}\n");
        assertNoErrors("class X extends Outer.Inner.Deep\n{\n}\n");
        // the class name stays the first identifier
        Result r = assertNoErrors("class MyEdit extends Gui.Edit {\n}\n");
        assertTrue(r.tree, r.tree.contains("AhkClassImpl"));
        // whitespace around the dot is not a member access
        assertFalse(parse("class X extends Gui . Edit {\n}\n").errors.isEmpty());
    }

    public void testClass() {
        assertNoErrors("""
            class Win {
                __Item[key] { }
                Visible {
                    get => WinGetStyle(this) & WinAPI.WS_VISIBLE ? true : false
                    set {
                        if value
                            this.Show()
                        else
                            this.Hide()
                    }
                }
            }""");
    }

    public void testContinuationSection() {
        assertNoErrors("x := \"\n(\ntext ) \"q\"\n)\"");
    }

    // ---------------------------------------------------------------- comments, hotkeys, continuation lines

    public void testBlockCommentInMiddleOfLine() {
        Result r = assertNoErrors("f() { /* */\n}");
        assertTrue(r.tree, r.tree.contains("BAD_COMMENT)('/* */')"));
    }

    public void testBlockCommentInMiddleOfLineMultiline() {
        Result r = assertNoErrors("x := 1 /* a\nb */\ny := 2");
        assertTrue(r.tree, r.tree.contains("BAD_COMMENT"));
    }

    public void testBlockCommentAtLineStart() {
        Result r = assertNoErrors("/* a\n*/\n    /* b */\nx := 1");
        assertFalse(r.tree, r.tree.contains("BAD_COMMENT"));
    }

    public void testColonInCommentIsNoHotkey() {
        Result r = assertNoErrors("f() { ; ::\n}");
        assertFalse(r.tree, r.tree.contains("HOTKEY"));
    }

    public void testHotkeyWithComment() {
        Result r = assertNoErrors("F1:: ; Kommentar ::\n    MsgBox \"x\"");
        assertTrue(r.tree, r.tree.contains("HOTKEY)('F1::')"));
    }

    public void testHotkeyWithSemicolonWithoutWhitespace() {
        Result r = assertNoErrors("`;::MsgBox \"x\"");
        assertTrue(r.tree, r.tree.contains("HOTKEY)('`;::')"));
    }

    public void testCommentLineBeforeContinuationLine() {
        assertNoErrors("f(a\n; comment\n, b)");
        assertNoErrors("f(a\n    ; comment\n\n    ; noch einer\n    , b)");
        assertNoErrors("if a\n; c\n    or b\n    c()");
    }

    public void testCommentLineAfterOperatorAtLineEnd() {
        assertNoErrors("x := a +\n; comment\n\n    b");
        assertNoErrors("f(a,\n; comment\n    b)");
    }

    public void testCommentLineWithoutContinuationStaysStatementEnd() {
        Result r = assertNoErrors("a := 1\n; comment\nb := 2");
        assertEquals(r.tree, 2, r.tree.split("EXPR_STMT").length - 1);
    }

    public void testElseForAllLoops() {
        Result r = assertNoErrors("""
            if a
                f()
            else
                b()
            for a in b
                c()
            else b_is_empty
            loop length {
                do()
            } else {
                length_is_null
            }
            while (false) {
            }
            else always_false()""");
        assertEquals(r.tree, 4, r.tree.split("ELSE_BRANCH").length - 1);
    }

    public void testSwitch() {
        Result r = assertNoErrors("""
            switch b {
            case 1: msgbox ("eins")
            case 2: msgbox ("zwei")
            default: msgbox ("---")
            }""");
        assertEquals(r.tree, 3, r.tree.split("CASE_CLAUSE").length - 1);
    }

    public void testSwitchVariants() {
        Result r = assertNoErrors("""
            switch
            {
            case a > 1, b = 2:
                x := 1
                y := 2

            case "s":
            default:
                if x
                    y()
                default := 3
            }
            switch x, true {
            case 1: switch y {
                case 2: z()
                }
            }""");
        assertEquals(r.tree, 5, r.tree.split("CASE_CLAUSE").length - 1);
    }

    public void testSwitchWithErrorKeepsTheRest() {
        Result r = parse("switch b {\nfoo()\ncase 1: x()\n}\ny := 1");
        assertFalse(r.errors.isEmpty());
        assertTrue(r.tree, r.tree.contains("CASE_CLAUSE"));
        assertTrue(r.tree, r.tree.contains("PsiElement(AhkTokenType.IDENTIFIER)('y')"));
    }

    // incomplete: errors allowed, but no hang
    public void testSwitchIncomplete() {
        parse("switch b {\n)\n}");
        parse("switch b {\ncase");
        parse("switch b {\ncase 1");
        parse("switch b {\ncase 1:");
        parse("switch");
        parse("switch {");
        parse("goto");
        parse("f() { /*");
    }

    public void testDefaultIsNormalName() { assertNoErrors("default := 1\nMsgBox default"); }

    public void testGoto() {
        Result r = assertNoErrors("goto \"Ziel\"\ngoto(ziel)\ngoto ziel . \"x\"");
        assertEquals(r.tree, 3, r.tree.split("GOTO_STMT").length - 1);
    }

    public void testKeywordAsMemberName() { assertNoErrors("x := a.switch + a.case + a.goto"); }

    // ---------------------------------------------------------------- else, switch, goto, labels

    public void testTryElse() {
        Result r = assertNoErrors("""
            try
                a()
            catch Error as e
                b()
            else
                c()
            finally
                d()
            try {
                a()
            } catch {
                b()
            } else {
                c()
            }
            try a()
            else c()""");
        assertEquals(r.tree, 3, r.tree.split("ELSE_BRANCH").length - 1);
        assertEquals(r.tree, 3, r.tree.split("TRY_STMT").length - 1);
    }

    public void testLabel() {
        Result r = assertNoErrors("""
            Anfang:
            goto Ende
            f() {
                Aussen: ; Kommentar
                loop {
                    break Aussen
                }
            }
            Ende:""");
        assertEquals(r.tree, 3, r.tree.split("AhkLabelImpl").length - 1);
    }

    public void testNoLabel() {
        // default: stays a branch in a switch, "a: 1" is not a label, "a::" is a hotkey
        Result r = assertNoErrors("""
            switch x {
            case 1:
            default:
                y := 1
            }
            o := {
                a: 1
            }
            a::b()""");
        assertFalse(r.tree, r.tree.contains("AhkLabelImpl"));
        assertEquals(r.tree, 2, r.tree.split("CASE_CLAUSE").length - 1);
    }

    public void testLabelWithStatementIsError() {
        assertFalse(parse("Ende: x := 1").errors.isEmpty());
    }

    public void testCommentInHotstring() {
        Result r = assertNoErrors("::btw::by the way ; Kommentar\n::a;b::x;y `; z\n::c:: ;nur Kommentar");
        assertTrue(r.tree, r.tree.contains("HOTSTRING_TEXT)('by the way')"));
        assertTrue(r.tree, r.tree.contains("LINE_COMMENT)(' ; Kommentar')"));
        assertTrue(r.tree, r.tree.contains("HOTSTRING_TEXT)('x;y `; z')"));
        assertTrue(r.tree, r.tree.contains("LINE_COMMENT)(' ;nur Kommentar')"));
    }

    // ---------------------------------------------------------------- block comment at the start of a line (also indented)

    public void testBlockCommentLooksLikeHotkey() {
        Result r = assertNoErrors("/* a::b */\n    /* c::d\n*/\nx := 1");
        assertFalse(r.tree, r.tree.contains("HOTKEY"));
        assertFalse(r.tree, r.tree.contains("BAD_COMMENT"));
    }

    public void testBlockCommentAfterHotkeyIsNotAtLineStart() {
        Result r = parse("F1:: /* x */\n    MsgBox \"x\"");
        assertTrue(r.tree, r.tree.contains("BAD_COMMENT"));
    }

    public void testGlobalWithoutName() {
        Result r = assertNoErrors("f() {\n    global\n    x := 1\n}");
        assertTrue(r.tree, r.tree.contains("DECL_STMT"));
        assertFalse(r.tree, r.tree.contains("DECL_ITEM"));
    }

    public void testRemapToSemicolon() {
        Result r = assertNoErrors("a::;\nb::;  ; Kommentar\nc::;\t");
        assertEquals(r.tree, 3, r.tree.split("REMAP_KEY").length - 1);
        assertTrue(r.tree, r.tree.contains("LINE_COMMENT)('  ; Kommentar')"));
        r = assertNoErrors("d::;");
        assertTrue(r.tree, r.tree.contains("REMAP_KEY)(';')"));
    }

    public void testOmittedArguments() {
        Result r = assertNoErrors("MouseClick \"Left\",,, 1\nMouseClick(\"Left\",,, 1)");
        assertEquals(r.tree, 1, r.tree.split("COMMAND_STMT").length - 1);
        assertEquals(r.tree, 1, r.tree.split("CALL_EXPR").length - 1);
    }

    public void testHotkeyWithCommentDirectlyAfterColons() {
        Result r = assertNoErrors("F1::;x\na::b\nF2:: /* x */ MsgBox 1");
        assertTrue(r.tree, r.tree.contains("LINE_COMMENT)(';x')"));
        assertTrue(r.tree, r.tree.contains("BAD_COMMENT)('/* x */')"));
        assertFalse(r.tree, r.tree.contains("BLOCK_COMMENT"));
    }

    public void testBlockCommentBetweenContinuationLines() {
        Result r = assertNoErrors("x := a +\n    /* c\n    */\n    b\nf(a\n    /* c */\n    , b)");
        assertFalse(r.tree, r.tree.contains("BAD_COMMENT"));
        assertTrue(r.tree, r.tree.contains("BLOCK_COMMENT"));
    }

    // ---------------------------------------------------------------- #Requires AutoHotkey

    public void testRequiresVersion2IsParsed() {
        for (String req : List.of("v2", "v2.0", "2", "2.0.18", ">=2.0- <2.1", ">2", "v2.0-rc.2 64-bit", "=v2.1",
                "v2.1-alpha.3", "64-bit", "v3")) {
            Result r = assertNoErrors("#Requires AutoHotkey " + req + "\nx := 1");
            assertFalse(req + "\n" + r.tree, r.tree.contains("OTHER_VERSION_CODE"));
            assertTrue(req + "\n" + r.tree, r.tree.contains("EXPR_STMT"));
        }
        // without #Requires, commented out or not for AutoHotkey: parse normally
        for (String text : List.of("x := 1", "; #Requires AutoHotkey v1.1\nx := 1", "/*\n#Requires AutoHotkey v1.1\n*/\nx := 1",
                "#requires Foo v1\nx := 1")) {
            Result r = assertNoErrors(text);
            assertFalse(text + "\n" + r.tree, r.tree.contains("OTHER_VERSION_CODE"));
        }
    }

    public void testRequiresOtherVersionIsSwallowed() {
        for (String req : List.of("v1.1", "v1.1.33 64-bit", ">1.1", "<2", ">=1.1", "1.1.37.02")) {
            Result r = assertNoErrors("x := 1\n#requires autohotkey " + req + " ; v1\nMsgBox, Hallo %name\nif (x) {");
            assertTrue(req + "\n" + r.tree, r.tree.contains("OTHER_VERSION_CODE"));
            assertFalse(req + "\n" + r.tree, r.tree.contains("EXPR_STMT"));
        }
        // the first #Requires with a version decides
        assertTrue(parse("#Requires AutoHotkey v1.1\n#Requires AutoHotkey v2").tree.contains("OTHER_VERSION_CODE"));
        assertFalse(parse("#Requires AutoHotkey v2\n#Requires AutoHotkey v1.1").tree.contains("OTHER_VERSION_CODE"));
    }

    public void testSurplusBraceKeepsTheRest() {
        Result r = parse("class X extends %Y% {\n    a() {\n    }\n}\nc() {\n}\n}\nd := 1");
        assertEquals(r.errors.toString(), 3, r.errors.size());   // extends %Y%, two surplus "}"
        assertEquals(r.tree, 2, r.tree.split("AhkFuncImpl").length - 1);
        assertTrue(r.tree, r.tree.contains("PsiElement(AhkTokenType.IDENTIFIER)('d')"));
    }

    // ---------------------------------------------------------------- v2.1 syntax

    private static final String V21 = "#Requires AutoHotkey v2.1-alpha.39\n";
    private static final String V20 = "#Requires AutoHotkey v2.0\n";

    /** The error of a v2.1 construct in a v2.0 file, exactly once. */
    private void assertRequiresV21(String code) {
        assertNoErrors(V21 + code);
        Result r = parse(V20 + code);
        String message = de.kumpewo.autohotkey.AhkBundle.message("parser.requires.v21");
        assertEquals(r.errors.toString(), 1, r.errors.size());
        assertTrue(r.errors.toString(), r.errors.get(0).endsWith(message));
    }

    public void testRegexNotMatch() {
        assertRequiresV21("if a !~= \"^x\"\n    b := 1\n");
        Result r = assertNoErrors(V21 + "c := (a\n    !~= b)\n");
        assertTrue(r.tree, r.tree.contains("AhkCompareExprImpl"));
        // without #Requires: the default v2.0
        assertFalse(parse("x := a !~= b").errors.isEmpty());
    }

    public void testMaybeAssign() {
        assertRequiresV21("x ??= 1\n");
        assertRequiresV21("f() {\n    static s ??= 1\n}\n");
        assertNoErrors(V20 + "x := 1, y += 2\n");
    }

    public void testOptionalChain() {
        assertRequiresV21("y := x?.z\n");
        assertRequiresV21("y := f()?.z(1)\n");
        Result r = assertNoErrors(V21 + "y := (w ?? x?.y)?.z(), v := a[1]?.b.c\n");
        assertTrue(r.tree, r.tree.contains("AhkMemberExprImpl"));
        // the ternary and "?" before , ) stay as they are
        assertNoErrors(V21 + "y := a ? b : c, f(Title?), z := a ? .5 : 1\n");
        assertNoErrors(V20 + "y := a ? b : c, f(Title?), z := a ? .5 : 1\n");
        // AutoHotkey also accepts whitespace between "?" and "." (then still no concatenation " . ")
        assertRequiresV21("y := x? .z\n");
    }

    /** v2.1: Throw is a function in expressions; the statement stays as it is in both levels. */
    public void testThrowAsFunction() {
        assertRequiresV21("c := 1\nx := c ? 1 : throw(Error())\n");
        assertRequiresV21("f := Throw\n");
        assertRequiresV21("g := Throw.Bind(Error(\"x\"))\n");
        Result r = assertNoErrors(V21 + "x := 1 || throw(ValueError(\"x\", -1))\n");
        assertTrue(r.tree, r.tree.contains("AhkCallExprImpl"));
        for (String level : new String[]{V20, V21}) {
            r = assertNoErrors(level + "throw Error()\nthrow(Error())\ntry x := 1\ncatch\n    throw\n");
            assertEquals(r.tree, 3, r.tree.split("AhkThrowStmtImpl").length - 1);
        }
    }

    public void testStruct() {
        Result r = assertNoErrors(V21 + "struct POINT {\n    x := 0\n}\nstruct B extends POINT\n{\n}\n");
        assertTrue(r.tree, r.tree.contains("KEYWORD_STRUCT"));
        assertEquals(r.tree, 2, r.tree.split("AhkClassImpl").length - 1);
        // otherwise "struct" is a normal name (the class Struct)
        r = assertNoErrors(V21 + "P := Class(Struct), struct := 1, s := Struct.Prototype\nclass A extends Struct {\n}\n");
        assertFalse(r.tree, r.tree.contains("KEYWORD_STRUCT"));
        // in v2.0 "struct" is no keyword at all
        r = parse(V20 + "struct POINT {\n}\n");
        assertFalse(r.tree, r.tree.contains("KEYWORD_STRUCT"));
        assertFalse(r.tree, r.tree.contains("AhkClassImpl"));
    }

    public void testImport() {
        // forms accepted by AutoHotkey v2.1-alpha.39 (checked with /validate)
        for (String code : List.of(
                "#Import X\n", "#Import \"X\"\n", "#Import \"Lib\\x.ahk\" as Y\n", "#import x {calc AS k}\n",
                "#Import Export X as XX {*}\n", "#Import X as Y{*}\n", "#Import \"X\"{*}\n", "#Import X {}\n",
                "#Import X {*, Calc as K}\n", "#Import X {Calc,}\n", "#Import X {*} ; comment\n",
                "#Import X {\n    ; comment\n    Calc as C,  ; c2\n\n    /* block\n    */\n    Other\n}\nC()\n",
                "#Import X {Calc\n, Other}\n", "#Import X {\nCalc }\n", "#Import Export\n",
                "f() {\n    #Import X {Calc}\n    return Calc()\n}\n")) {
            Result r = assertNoErrors(V21 + code);
            assertTrue(code + "\n" + r.tree, r.tree.contains("AhkImportSpecImpl"));
            assertEquals(code + "\n" + r.tree, 1, r.tree.split("DIRECTIVE_ARGS").length - 1);   // only #Requires
        }
        Result r = assertNoErrors(V21 + "#Import Export X\n#Import Export\n");
        assertEquals(r.tree, 1, r.tree.split("KEYWORD_EXPORT").length - 1);
        // errors of AutoHotkey: missing alias, two aliases, missing comma, string as name, extra name
        for (String code : List.of("#Import X as\n", "#Import X {Calc as a as b}\n", "#Import X {Calc Other}\n",
                "#Import X {\"Calc\"}\n", "#Import X Y\n", "#Import X {Calc\n")) {
            assertFalse(code, parse(V21 + code).errors.isEmpty());
        }
        // the next line after the import is a normal statement again; other directives keep DIRECTIVE_ARGS
        r = assertNoErrors(V21 + "#Import X {Calc}\nx := Calc()\n#Module X\nCalc() => 1\n");
        assertTrue(r.tree, r.tree.contains("AhkAssignExprImpl"));
        assertEquals(r.tree, 2, r.tree.split("DIRECTIVE_ARGS").length - 1);
        // "{" on the next line does not belong to #Import: a normal block (as in AutoHotkey)
        r = assertNoErrors(V21 + "#Import X\n{\n}\n");
        assertFalse(r.tree, r.tree.contains("AhkImportListImpl"));
        assertTrue(r.tree, r.tree.contains("AhkBlockImpl"));
        // the syntax is the same in v2.0 (the error "requires v2.1" comes from AhkModuleAnnotator)
        assertNoErrors(V20 + "#Import X {\n    Calc\n}\n");
    }

    /** Number of function definition expressions in the tree. */
    private static int funcExprs(Result r) {
        return r.tree.split("AhkFuncExprImpl").length - 1;
    }

    public void testFuncExpr() {
        // forms accepted by AutoHotkey v2.1-alpha.39 (checked with /validate): one function expression each
        for (String code : List.of(
                "x := f() {\n    return 1\n}\n", "x := () {\n    return 1\n}\n",
                "x := (f()\n{\n    return 1\n})\n", "x := [f()\n{\n}]\n", "x := {a: f()\n\n{\n}}\n",
                "if (f() {\n    return 1\n}) {\n}\n", "while (() {\n    return 0\n})() {\n}\n",
                "x := f(a, b := 2) {\n    return a + b\n}(1)\n",
                "outer() {\n    local x := 1\n    (inner(y) {\n        global x := y + 1\n    })(x)\n}\n",
                "SetTimer(() {\n    MsgBox 'x'\n    y := 1\n}, -1)\n", "SetTimer(()\n{\n    MsgBox 'x'\n}, -1)\n",
                "x := 1 f() {\n}\n", "x := f () {\n}\n", "MsgBox f() {\n}\n", "x := (a, b*) {\n}\n",
                "F1::x := () {\n}\n", "x := f() {\n}, y := 2\n", "x := f() {\n} . 'a'\n", "x := f() {\n} f\n",
                "if true\n    x := () {\n    }\n", "class C {\n    p := () {\n        return 1\n    }\n}\n",
                "x := f() {\n    y := 1\n    + 2\n}\n", "x := (f() {\n    y := 1\n    z := 2\n})\n",
                "x := f(a) {\n    return a\n}(\n    1)\n", "x := g := () {\n}\n", "x := (() {\n}).Name\n",
                "x := f() {\n}.Name\n", "x := f(*) {\n}\n", "x := f(&a) {\n}\n", "MsgBox(f() {\n    return 1\n}())\n",
                "x := { a: 1, b: () {\n    }\n}\n", "x := Map('a', () {\n    return 1\n}, 'b', 2)\n",
                "x := 1\nif x = 1 && (() {\n    return 1\n})()\n    y := 2\n", "F1::f(hk) {\n    return 1\n}\n",
                "x := %(() {\n    return 'a'\n})()%\n")) {
            Result r = assertNoErrors(V21 + code);
            assertEquals(code + "\n" + r.tree, 1, funcExprs(r));
        }
        // nested: the body gets a fresh state (line breaks in it end statements, also inside ( ))
        Result r = assertNoErrors(V21 + "x := (f() {\n    return [g() {\n        a := 1\n        b := 2\n    }]\n})\n");
        assertEquals(r.tree, 2, funcExprs(r));
        assertEquals(r.tree, 0, r.concats);
        // a function in a class method returns a closure
        r = assertNoErrors(V21 + "class C {\n    M() {\n        return () {\n            return this\n        }\n    }\n}\n");
        assertEquals(r.tree, 1, funcExprs(r));
        // not a function expression: head of a control statement (call and block), "{" on the next line outside of
        // brackets, a statement at the start of a line (function definition)
        for (String code : List.of("if f() {\n}\n", "for k in f() {\n}\n", "switch f() {\n}\n", "Loop f() {\n}\n",
                "while x := g() {\n}\n", "x := f()\n{\n}\n", "x := f() ; c\n{\n}\n", "f() {\n}\n",
                "try\n    x := 1\ncatch f() {\n}\n")) {
            r = assertNoErrors(V21 + code);
            assertEquals(code + "\n" + r.tree, 0, funcExprs(r));
        }
        // the same syntax in v2.0 (the error "requires v2.1" comes from AhkFuncExprAnnotator)
        assertEquals(1, funcExprs(assertNoErrors(V20 + "x := () {\n}\n")));
        // a call with a nested argument list is no function expression (and is not parsed twice)
        assertEquals(0, funcExprs(assertNoErrors(V21 + "x := f(g(h(1, i(2)), 3))\n")));
    }

    /** Number of typed properties in the tree. */
    private static int typedProperties(Result r) {
        return r.tree.split("AhkTypedPropertyImpl").length - 1;
    }

    public void testTypedProperty() {
        // forms accepted by AutoHotkey v2.1-alpha.39 (checked with /validate), number of typed properties
        Map<String, Integer> forms = new LinkedHashMap<>();
        forms.put("struct P {\n    x : Int32, y : Int32\n}\n", 2);
        forms.put("struct P {\n    x : Int32 := 5\n    y : UInt32 := this.Size\n}\n", 2);
        forms.put("class P {\n    x : Int32\n}\n", 1);
        forms.put("struct P {\n    x : Int32, y := 2\n}\n", 1);
        forms.put("struct P {\n    y := 2, x : Int32\n}\n", 1);
        forms.put("struct P {\n    a : Int32[10]\n    b : P2.Ptr\n    c : (Int32)\n    d : Int32.Ptr.Ptr\n}\n", 4);
        forms.put("struct P {\n    a : C(4)\n    b : C(4).Ptr\n    c : this.Ptr\n}\n", 3);
        forms.put("struct P {\n    a:Int32\n}\n", 1);
        forms.put("struct P {\n    a : Int32,\n    b : Int32\n}\n", 2);
        forms.put("struct P {\n    a : Int32 := 1, b : Int32 := 2\n}\n", 2);
        forms.put("struct P {\n    a : (Int32\n    )\n}\n", 1);
        forms.put("struct P {\n    a : Int32 ; comment\n}\n", 1);
        forms.put("struct P {\n    a : Int32 := 1 + 2, b : Int8 := (3)\n}\n", 2);
        forms.put("struct P {\n    union {\n        a : Int32\n        b : Int64\n    }\n}\n", 2);
        forms.put("struct P {\n    union\n    {\n        a : Int32 := 1, b : Int8\n    }\n}\n", 2);
        for (Map.Entry<String, Integer> form : forms.entrySet()) {
            Result r = assertNoErrors(V21 + form.getKey());
            assertEquals(form.getKey() + "\n" + r.tree, (int) form.getValue(), typedProperties(r));
        }
        // the initializer is no part of the type, the type no assignment
        Result r = assertNoErrors(V21 + "struct P {\n    x : Int32 := 1\n}\n");
        assertFalse(r.tree, r.tree.contains("AhkAssignExprImpl"));
        // a union block only with a typed property as the first item, otherwise a property named union
        r = assertNoErrors(V21 + "struct P {\n    union {\n        a : Int32\n    }\n}\n");
        assertTrue(r.tree, r.tree.contains("KEYWORD_UNION"));
        for (String code : List.of("class P {\n    union {\n    }\n}\n", "class P {\n    union {\n        get => 1\n    }\n}\n",
                "union := 1\n")) {
            r = assertNoErrors(V21 + code);
            assertFalse(code + "\n" + r.tree, r.tree.contains("KEYWORD_UNION"));
        }
        // no typed property: label, ternary, object literal, case
        for (String code : List.of("a:\n", "x := a ? b : c\n", "x := {a: 1, b: 2}\n", "f(a, b ? c : d)\n",
                "switch x {\ncase 1: y := 2\ndefault: y := 3\n}\n")) {
            assertEquals(code, 0, typedProperties(assertNoErrors(V21 + code)));
        }
        // the same syntax in v2.0 (the error "requires v2.1" comes from AhkTypedPropertyAnnotator)
        assertEquals(1, typedProperties(assertNoErrors(V20 + "class P {\n    x : Int32\n}\n")));
        // errors of AutoHotkey: binary operator, string, concatenation, other assignment than :=, outside of a class
        // body (also in a method or a function expression in a field initializer)
        for (String code : List.of("x : Int32\n", "f() {\n    x : Int32\n}\n", "Ende: x := 1\n",
                "class P {\n    M() {\n        x : Int32\n    }\n}\n", "class P {\n    p := () {\n        x : Int32\n    }\n}\n","struct P {\n    a : Int32 + 1\n}\n", "struct P {\n    a : \"Int32\"\n}\n",
                "struct P {\n    a : Int32 Int8\n}\n", "struct P {\n    a : Int32 += 1\n}\n")) {
            assertFalse(code, parse(V21 + code).errors.isEmpty());
        }
    }

    // ---------------------------------------------------------------- forms found in the help examples

    /** Continuation section outside a string: strings may span lines inside it, options are skipped. */
    public void testContinuationSectionOutsideString() {
        Result r = assertNoErrors("x := 1\nMsgBox(\n(\n    \"a:\n    b: \" x \"\n    c\"\n))\n");
        assertTrue(r.tree, r.tree.contains("PsiElement(AhkTokenType.STRING)('\"a:\\n    b: \"')"));
        assertNoErrors("Var :=\n(\n\"Same as above.\nSpecify: \" Var \"\nA line.\"\n)\n");
        assertNoErrors("f() {\n    MsgBox\n    (\n        \"All: \" 1 \"\n        Two\"\n    )\n}\n");
        assertNoErrors("x := 2\nMsgBox\n(Join`s\n    \"a\" x\n    \"b\" . x\n    \"c\"\n    and x\n)\n");
        assertNoErrors("FileAppend \"   ; Comment.\n; Comment.\n( LTrim Join    ; Comment.\n    ;; literal\n)\", \"C:\\File.txt\"\n");
        // code after the closing ")" belongs to the statement again; a following line is a new statement
        r = assertNoErrors("x := \"a\"\n(\n\"b\nc\"\n) . \"d\"\nz := 2\n");
        assertEquals(r.tree, 2, r.tree.split("AhkExprStmtImpl").length - 1);
        r = assertNoErrors("MsgBox\n( LTrim Join\n    \"a\"\n)\n");
        assertTrue(r.tree, r.tree.contains("CONTINUATION_OPTIONS)('LTrim Join')"));
        // a line starting with "(" and another parenthesis is an expression, not a section
        assertNoErrors("x := 1\n(x) := 2\n");
        // inside a section a line starting with ")" ends it, a string never spans it
        assertTrue(parse("MsgBox(\n(\n    \"a\n)\n)\n").tree.contains("UNCLOSED_STRING)('\"a')"));
    }

    /** Continuation section as the replacement text of a hotstring: one HOTSTRING_TEXT token. */
    public void testHotstringContinuationSection() {
        Result r = assertNoErrors("::text1::\n(\nAny text (here) \"quoted\"\n  more ; not a comment\n)\nx := 1\n");
        assertTrue(r.tree, r.tree.contains("HOTSTRING_TEXT)('(\\nAny text (here)"));
        assertTrue(r.tree, r.tree.contains("PsiElement(AhkTokenType.IDENTIFIER)('x')"));
    }

    /** "this" as a parameter name (it stays a reserved word). */
    public void testThisAsParameterName() {
        assertNoErrors("f := (this) => this\ng := {get: (this, value) => 1}\nh(this) {\n}\nk := this => 1\n");
        // still an expression where no arrow follows
        Result r = assertNoErrors("x := (this)\n");
        assertTrue(r.tree, r.tree.contains("AhkParenExprImpl"));
    }

    /** Member names starting with a digit: m.0 (RegExMatch), obj.1a. */
    public void testMemberNameStartingWithDigit() {
        assertNoErrors("m := 1\nx := m.0\ny := m.1a\nz := m.0.Len\n");
    }

    /** Function call statement whose first argument starts with a unary operator or an object literal. */
    public void testCommandWithUnaryOperatorOrObjectArgument() {
        for (String line : new String[]{"SetCapsLockState !n", "ToolTip ++n", "WinActivate {Hwnd: n}",
                "WinGetClientPos &X, &Y,,, \"A\"", "f ~n"}) {
            Result r = assertNoErrors("n := 1\n" + line + "\n");
            assertTrue(line + "\n" + r.tree, r.tree.contains("AhkCommandStmtImpl"));
        }
        // still binary with whitespace on both sides, still a postfix without whitespace before
        assertFalse(assertNoErrors("a := 1\na & b\n").tree.contains("AhkCommandStmtImpl"));
        assertFalse(assertNoErrors("a := 1\na++\n").tree.contains("AhkCommandStmtImpl"));
        // a property block stays a property block
        assertTrue(assertNoErrors("class P {\n    q {\n        get => 1\n    }\n    r {\n    }\n}\n").tree
                .contains("AhkPropertyBlockImpl"));
    }

    /** Dynamic property name in an object literal. */
    public void testDynamicNameInObjectLiteral() {
        assertNoErrors("p := [\"key\", 1]\npair := {%p[1]%: p[2]}\nq := {a%p[1]%b: 1, c: 2}\n");
    }

    /** A block comment ends only with a closing mark at the start or at the end of a line. */
    public void testBlockCommentEndOnlyAtLineStartOrEnd() {
        Result r = assertNoErrors("/*\nMsgBox \"x\"\nMsgBox \"Common:\" */ \" not end\"\nMsgBox \"y\"\n*/\nMsgBox \"z\"\n"
                + "/* valid */\nMsgBox \"w\"\n");
        assertEquals(r.tree, 2, r.tree.split("AhkCommandStmtImpl").length - 1);
        // code after the closing mark at the start of a line
        r = assertNoErrors("/*\nx\n*/ y := 1\n");
        assertTrue(r.tree, r.tree.contains("IDENTIFIER)('y')"));
    }

    /** Code after the closing brace of a block on the same line. */
    public void testCodeAfterClosingBrace() {
        for (String text : new String[]{"if x {\n    a()\n} a()\n", "f() {\n    a()\n} a()\n", "class C {\n} a()\n",
                "F1:: {\n    a()\n} a()\n", "{\n    a()\n} y := 2\n", "f() {\n    if x {\n        a()\n    } a()\n}\n",
                "switch x {\n    case 1: a()\n} a()\n"}) {
            assertNoErrors("x := 1\n" + text);
        }
        // else after the brace stays part of the statement
        assertTrue(assertNoErrors("x := 1\nif x {\n} else {\n}\n").tree.contains("AhkElseBranchImpl"));
    }

    // ---------------------------------------------------------------- parser gaps from the help examples

    /** Ternary with "?" and ":" at the end of a line: the operands continue on the following lines. */
    public void testTernaryAcrossLines() {
        Result r = assertNoErrors("a := 1, b := 2, c := 3\nx := (a = 1) ?\n    b : ; Comment\n    c\ny := 1\n");
        assertTrue(r.tree, r.tree.contains("AhkTernaryExprImpl"));
        assertEquals(r.tree, 3, r.tree.split("AhkExprStmtImpl").length - 1);
        assertNoErrors("a := 1\nz := a = 1 ?\n  2 :\n\n  ; comment line\n  3\nf(a ?\n  1 : 0)\nf(v) => v\n");
        // a label and a case branch with ":" at the end of a line stay what they are
        assertTrue(assertNoErrors("Done:\nx := 1\n").tree.contains("AhkLabelImpl"));
        assertNoErrors("x := 1\nswitch x {\n    case 1:\n        x := x ? 1 :\n            2\n    default:\n}\n");
    }

    /** A line starting with ".Name" continues a method chain (member access), like ". " continues a concatenation. */
    public void testMethodChainAtLineStart() {
        Result r = assertNoErrors("wmi := 1, pid := 2\nr := wmi.ExecQuery(\"\"\n. \"Select x\" . pid)\n._NewEnum()\n");
        assertEquals(r.tree, 2, r.tree.split("AhkExprStmtImpl").length - 1);
        assertTrue(r.tree, r.tree.contains("PsiElement(AhkTokenType.IDENTIFIER)('_NewEnum')"));
        assertNoErrors("x := C\n.P\ny := [1, 2]\n    .Length\nC\n    .N()\n    ; comment\n    .M(\"b\")\nz := C\n.%name%\n");
        // ".5" at the start of a line is still a number
        assertTrue(parse("x := 1\n.5\n").tree.contains("NUMBER)('.5')"));
    }

    /** Hotkey for a quote key: ^+"::, '::, "::. */
    public void testQuoteHotkey() {
        Result r = assertNoErrors("^+\"::Send '\"\"{Left}'\n\"::MsgBox\n+'::MsgBox \"x\"\n  <^\"::x := 1\n");
        assertEquals(r.tree, 4, r.tree.split("AhkTokenType.HOTKEY\\)").length - 1);
        assertTrue(r.tree, r.tree.contains("HOTKEY)('^+\"::')"));
    }

    /** A named fat arrow function in an expression is a function (element FUNC), a call stays a call. */
    public void testNamedFatArrowInExpression() {
        Result r = assertNoErrors("sumfn := Sum(a, b) => a + b\nf := [G(x) => x * 2, H() => 1]\nMsgBox(K(*) => 0)\n");
        assertEquals(r.tree, 4, r.tree.split("AhkFuncImpl").length - 1);
        // as a statement it stays a function definition; calls stay calls
        r = assertNoErrors("Sum(a, b) => a + b\nx := Sum(1, 2)\ny := Sum(f(1), [2])\n");
        assertEquals(r.tree, 1, r.tree.split("AhkFuncImpl").length - 1);
        assertTrue(r.tree, r.tree.contains("AhkCallExprImpl"));
    }

    // ---------------------------------------------------------------- helpers

    private record Result(String tree, List<String> errors, int concats) { }

    private void assertConcat(Result r) {
        assertTrue("Kein CONCAT_EXPR im Baum:\n" + r.tree, r.concats > 0);
    }

    private Result assertNoErrors(String text) {
        Result r = parse(text);
        assertTrue("Fehlerelemente " + r.errors + " bei:\n" + text + "\n\n" + r.tree, r.errors.isEmpty());
        return r;
    }

    /** Parses in a separate daemon thread, so that an endless loop does not block the test run. */
    private Result parse(String text) {
        FutureTask<Result> task = new FutureTask<>(() -> {
            PsiFile file = createPsiFile("test", text);
            String tree = DebugUtil.psiToString(file, true, false);
            List<String> errors = new ArrayList<>();
            Collection<PsiErrorElement> elements = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement.class);
            for (PsiErrorElement e : elements) {
                errors.add("@" + e.getTextOffset() + ": " + e.getErrorDescription());
            }
            return new Result(tree, errors, PsiTreeUtil.findChildrenOfType(file, AhkConcatExpr.class).size());
        });
        Thread thread = new Thread(task, "ahk-parse");
        thread.setDaemon(true);
        thread.start();
        try {
            Result r = task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            System.out.println("=== " + getName() + "\n" + text + "\n--- Fehler: " + r.errors + "\n" + r.tree);
            return r;
        } catch (TimeoutException e) {
            fail("Timeout for:\n" + text + "\n" + Arrays.toString(thread.getStackTrace()));
            throw new AssertionError(e);
        } catch (ExecutionException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
