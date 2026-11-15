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
import java.util.List;
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
