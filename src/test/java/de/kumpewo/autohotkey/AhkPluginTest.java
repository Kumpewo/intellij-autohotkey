package de.kumpewo.autohotkey;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.ide.structureView.StructureViewTreeElement;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import de.kumpewo.autohotkey.highlighting.AhkColors;
import de.kumpewo.autohotkey.types.AhkTypeDeclarations;
import com.intellij.ide.util.treeView.smartTree.TreeElement;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import de.kumpewo.autohotkey.psi.AhkLabel;
import de.kumpewo.autohotkey.structure.AhkStructureViewModel;
import java.util.ArrayList;
import java.util.List;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.application.options.CodeStyle;
import de.kumpewo.autohotkey.formatter.AhkCaseMode;
import de.kumpewo.autohotkey.formatter.AhkCodeStyleSettings;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * Tests with the whole plugin (annotators from plugin.xml, formatter, completion, navigation ...). Formatting
 * works like the action "Reformat Code" via reformatText (document-based). Note: CodeStyleManager.reformat(PsiElement)
 * inserts empty lines at CRLF tokens; that is a known bug not tested here.
 */
public class AhkPluginTest extends BasePlatformTestCase {

    // ---------------------------------------------------------------- matching brace

    public void testBracePairs() {
        assertCounterpart("x := f<caret>(a, [1, 2]§)\n");
        assertCounterpart("x := f§(a, [1, 2]<caret>)\n");
        assertCounterpart("x := f(a, <caret>[1, 2§])\n");
        assertCounterpart("f() <caret>{\n    x := {a: 1}\n§}\n");
        assertCounterpart("x := §{a: 1<caret>}\n");
    }

    public void testPercentPairs() {
        assertCounterpart("x := a<caret>%b§%c\n");
        assertCounterpart("x := a§%b<caret>%c\n");
        assertCounterpart("x := %a% . <caret>%b§%\n");
        assertCounterpart("x := %a%<caret>%b§%\n");
        assertCounterpart("x := \"%\" . <caret>%b§%\n");
        assertCounterpart("x := obj.<caret>%name§%\n");
    }

    public void testPercentWithoutCounterpartInLine() {
        assertNoCounterpart("x := <caret>%a\ny := b%\n");
        assertNoCounterpart("x := a\ny := b<caret>%\n");
    }

    /**
     * Caret before a brace ({@code <caret>}), expected counterpart directly after {@code §}.
     */
    private void assertCounterpart(String text) {
        String ohneCaret = text.replace("<caret>", "");
        int expected = ohneCaret.indexOf('§');
        assertEquals(text, expected, counterpart(text.replace("§", "")));
    }

    private void assertNoCounterpart(String text) {
        assertEquals(text, -1, counterpart(text));
    }

    private int counterpart(String text) {
        myFixture.configureByText("a.ahk", text);
        com.intellij.openapi.editor.Editor editor = myFixture.getEditor();
        CharSequence chars = editor.getDocument().getCharsSequence();
        com.intellij.openapi.fileTypes.FileType fileType = myFixture.getFile().getFileType();
        com.intellij.openapi.editor.highlighter.HighlighterIterator it =
                editor.getHighlighter().createIterator(editor.getCaretModel().getOffset());
        boolean links = com.intellij.codeInsight.highlighting.BraceMatchingUtil.isLBraceToken(it, chars, fileType);
        boolean rechts = com.intellij.codeInsight.highlighting.BraceMatchingUtil.isRBraceToken(it, chars, fileType);
        assertTrue("no brace at the caret: " + text, links != rechts);
        if (!com.intellij.codeInsight.highlighting.BraceMatchingUtil.matchBrace(chars, fileType, it, links)) {
            return -1;
        }
        return it.getStart();
    }

    // ---------------------------------------------------------------- completion

    /** Suggestions at {@code <caret>}; empty if there were none (or exactly one suggestion was inserted). */
    private List<String> suggestions(String text) {
        myFixture.configureByText("a.ahk", text);
        myFixture.completeBasic();
        List<String> list = myFixture.getLookupElementStrings();
        return list == null ? List.of() : list;
    }

    private void assertInserted(String text, String expected) {
        myFixture.configureByText("a.ahk", text);
        myFixture.completeBasic();
        com.intellij.openapi.editor.Editor editor = myFixture.getEditor();
        StringBuilder ist = new StringBuilder(editor.getDocument().getText());
        ist.insert(editor.getCaretModel().getOffset(), "<caret>");
        assertEquals("Vorschläge: " + myFixture.getLookupElementStrings(), expected, ist.toString());
    }

    public void testCompletionDirectives() {
        List<String> list = suggestions("#Incl<caret>\n");
        assertTrue(list.toString(), list.containsAll(List.of("#Include", "#IncludeAgain")));
        assertInserted("#singlei<caret>\n", "#SingleInstance<caret>\n");
    }

    public void testCompletionVariablesAndFunctions() {
        List<String> list = suggestions("x := A_Ti<caret>\n");
        assertTrue(list.toString(), list.containsAll(List.of("A_TickCount", "A_TimeIdle")));
        assertInserted("x := StrLe<caret>\n", "x := StrLen(<caret>)\n");
        assertInserted("x := strle<caret>(s)\n", "x := StrLen(<caret>s)\n");
        // methods only after the dot
        assertFalse(suggestions("x := Pus<caret>\n").contains("Push"));
    }

    public void testCompletionAfterDot() {
        List<String> list = suggestions("x := obj.Le<caret>\n");
        assertTrue(list.toString(), list.contains("Length"));
        assertFalse(list.toString(), list.contains("A_LineFile"));
        assertInserted("obj.Pus<caret>\n", "obj.Push(<caret>)\n");
        List<String> gui = suggestions("x := Gui.<caret>\n");
        assertTrue(gui.toString(), gui.containsAll(List.of("Button", "Edit", "ListView")));
        assertFalse(suggestions("x := ctrl.<caret>\n").contains("Button"));
    }

    public void testCompletionOwnDefinitions() {
        assertInserted("MeineFunktion(a) {\n}\nx := Meine<caret>\n",
                "MeineFunktion(a) {\n}\nx := MeineFunktion(<caret>)\n");
        assertInserted("class MeineKlasse {\n    Tuwas() {\n    }\n}\nx := obj.Tuw<caret>\n",
                "class MeineKlasse {\n    Tuwas() {\n    }\n}\nx := obj.Tuwas(<caret>)\n");
        List<String> list = suggestions("class MeineKlasse {\n}\nx := Meine<caret>\n");
        assertTrue(list.toString(), list.isEmpty() || list.contains("MeineKlasse"));
        assertInserted("class MeineKlasse {\n}\nx := MeineK<caret>\n", "class MeineKlasse {\n}\nx := MeineKlasse<caret>\n");
    }

    public void testCompletionKeywordsAndLoop() {
        assertTrue(suggestions("wh<caret>\n").contains("While"));
        assertFalse(suggestions("x := wh<caret>\n").contains("While"));
        List<String> loop = suggestions("Loop <caret>\n");
        assertTrue(loop.toString(), loop.containsAll(List.of("Files", "Parse", "Read", "Reg")));
        assertFalse(suggestions("x := <caret>\n").contains("Files"));
    }

    public void testCompletionLabelsAfterGoto() {
        assertInserted("Ende:\ngoto En<caret>\n", "Ende:\ngoto Ende<caret>\n");
    }

    public void testCompletionKeyNamesInSendString() {
        assertTrue(suggestions("Send \"{Ent<caret>\"\n").containsAll(List.of("Enter", "NumpadEnter")));
        assertInserted("Send \"{Backs<caret>\"\n", "Send \"{Backspace}<caret>\"\n");
        // "}" is already there: don't insert it twice
        assertInserted("SendInput(\"x{backsp<caret>}\")\n", "SendInput(\"x{Backspace}<caret>\")\n");
        assertTrue(suggestions("ControlSend \"{<caret>\", \"Edit1\"\n").contains("Escape"));
        assertEquals(List.of(), suggestions("MsgBox \"{Ent<caret>\"\n"));
        assertEquals(List.of(), suggestions("Send \"{Enter} Ent<caret>\"\n"));
    }

    public void testCompletionKeyNamesInHotkey() {
        assertTrue(suggestions("Ent<caret>::\n").containsAll(List.of("Enter", "NumpadEnter")));
        assertInserted("^!backsp<caret>::\n", "^!Backspace<caret>::\n");
        assertInserted("a & backsp<caret>::\n", "a & Backspace<caret>::\n");
        assertInserted("backsp<caret> up::\n", "Backspace<caret> up::\n");
        // only key names, no functions or variables
        assertFalse(suggestions("Ms<caret>::\n").contains("MsgBox"));
    }

    /** Spelling of the keywords from keyword-case.txt: flow control "as in list". */
    public void testKeywordLoopCapitalized() {
        List<String> list = suggestions("loo<caret>\n");
        assertTrue(list.toString(), list.contains("Loop") && !list.contains("loop"));
        assertTrue(suggestions("re<caret>\n").contains("Return"));
        assertTrue(suggestions("Loop Pa<caret>\n").contains("Parse"));
    }

    /** The group switch "lowercase" also applies to completion, independent of the main switch. */
    public void testKeywordLowercaseBySetting() {
        withCaseSettings(c -> c.setKeywordCase("Flow control", AhkCaseMode.LOWER), () -> {
            List<String> list = suggestions("loo<caret>\n");
            assertTrue(list.toString(), list.contains("loop") && !list.contains("Loop"));
        });
        withCaseSettings(c -> c.setKeywordCase("Flow control", AhkCaseMode.KEEP), () ->
                assertTrue(suggestions("loo<caret>\n").contains("Loop")));
    }

    /** Runs {@code action} with changed AHK code style settings (the old ones are restored afterwards). */
    private void withCaseSettings(java.util.function.Consumer<AhkCodeStyleSettings> setup, Runnable action) {
        CodeStyle.doWithTemporarySettings(getProject(), CodeStyle.getSettings(getProject()),
                (com.intellij.psi.codeStyle.CodeStyleSettings settings) -> {
                    setup.accept(settings.getCustomSettings(AhkCodeStyleSettings.class));
                    action.run();
                });
    }

    /** Main switch off (default) = the case stays. */
    public void testCaseAdjustmentOffByDefault() {
        assertFormatted("IF (x)\n    msgbox(a_index)\n", "IF (x)\n    msgbox(a_index)\n");
    }

    /** Keywords by group, built-in names by list, only in their colored role. */
    public void testCaseAdjustment() {
        withCaseSettings(c -> c.ADJUST_CASE = true, () -> assertFormatted("""
                #include <MyLib>
                #singleinstance Force
                CLASS Box EXTENDS gui.edit {
                    STATIC count := 0
                    length() {
                        RETURN THIS.LENGTH
                    }
                }
                msgbox(strlen(a_scriptdir), "msgbox if")   ; msgbox if
                LOOP PARSE, "a,b", ","
                    IF (a_loopfield = "a" AND NOT TRUE)
                        BREAK
                SWITCH x {
                CASE 1: RETURN
                DEFAULT: arr.PUSH(gui.button, ctrl.text, g.TEXT)
                }
                length := 1
                obj := {msgbox: 1, default: 2}
                x := obj.default . obj.IF
                """, """
                #Include <MyLib>
                #SingleInstance Force
                Class Box Extends Gui.Edit {
                    static count := 0
                    length() {
                        Return this.Length
                    }
                }
                MsgBox(StrLen(A_ScriptDir), "msgbox if")   ; msgbox if
                Loop Parse, "a,b", ","
                    If (A_LoopField = "a" and not true)
                        Break
                Switch x {
                Case 1: Return
                Default: arr.Push(Gui.Button, ctrl.Text, g.Text)
                }
                length := 1
                obj := {msgbox: 1, default: 2}
                x := obj.Default . obj.IF
                """));
    }

    /** Group switches "lowercase"/"keep", built-in switch, selection, ;@ignore-next-line. */
    public void testCaseAdjustmentSwitches() {
        withCaseSettings(c -> {
            c.ADJUST_CASE = true;
            c.ADJUST_BUILTIN_CASE = false;
            c.setKeywordCase("Flow control", AhkCaseMode.LOWER);
            c.setKeywordCase("Operators", AhkCaseMode.KEEP);
            c.setKeywordCase("Objects", AhkCaseMode.AS_LIST);
        }, () -> assertFormatted("""
                IF (x AND true)
                    msgbox(a_index)
                ;@ignore-next-line
                IF (x AND true)
                    msgbox(a_index)
                """, """
                if (x AND True)
                    msgbox(a_index)
                ;@ignore-next-line
                IF (x AND true)
                    msgbox(a_index)
                """));
        // only the selection (first line)
        withCaseSettings(c -> c.ADJUST_CASE = true, () -> {
            myFixture.configureByText("a.ahk", "IF (x)\n    msgbox(a_index)\n");
            WriteCommandAction.runWriteCommandAction(getProject(), (Runnable) () ->
                    CodeStyleManager.getInstance(getProject()).reformatText(myFixture.getFile(), 0, 6));
            assertEquals("If (x)\n    msgbox(a_index)\n", myFixture.getEditor().getDocument().getText());
        });
    }

    /** Another AHK version (OTHER_VERSION_CODE) stays unchanged. */
    public void testCaseAdjustmentNotForOtherVersion() {
        withCaseSettings(c -> c.ADJUST_CASE = true, () ->
                assertFormatted("#Requires AutoHotkey v1.1\nIF x\nmsgbox % a_index\n",
                        "#Requires AutoHotkey v1.1\nIF x\nmsgbox % a_index\n"));
    }

    public void testQuotesInPairs() {
        myFixture.configureByText("a.ahk", "x := <caret>\n");
        myFixture.type('"');
        myFixture.checkResult("x := \"<caret>\"\n");
        myFixture.type("abc\"");
        myFixture.checkResult("x := \"abc\"<caret>\n");
        myFixture.configureByText("a.ahk", "x := <caret>\n");
        myFixture.type('\'');
        myFixture.checkResult("x := '<caret>'\n");
        // not in a comment
        myFixture.configureByText("a.ahk", "x := 1 ; <caret>\n");
        myFixture.type('"');
        myFixture.checkResult("x := 1 ; \"<caret>\n");
    }

    public void testPercentInPairs() {
        myFixture.configureByText("a.ahk", "x := <caret>\n");
        myFixture.type('%');
        myFixture.checkResult("x := %<caret>%\n");
        myFixture.type("name%");
        myFixture.checkResult("x := %name%<caret>\n");
        // dynamic name: a%b%
        myFixture.configureByText("a.ahk", "x := a<caret>\n");
        myFixture.type("%b%c");
        myFixture.checkResult("x := a%b%c<caret>\n");
        // don't double a closing "%" if it is missing
        myFixture.configureByText("a.ahk", "x := %name<caret>\n");
        myFixture.type('%');
        myFixture.checkResult("x := %name%<caret>\n");
        // not in string and comment
        myFixture.configureByText("a.ahk", "x := \"<caret>\"\n");
        myFixture.type('%');
        myFixture.checkResult("x := \"%<caret>\"\n");
        myFixture.configureByText("a.ahk", "x := 1 ; <caret>\n");
        myFixture.type('%');
        myFixture.checkResult("x := 1 ; %<caret>\n");
    }

    // ---------------------------------------------------------------- parameter values and parameter info

    public void testParameterValuesInString() {
        List<String> eins = suggestions("CoordMode \"<caret>\"\n");
        assertTrue(eins.toString(), eins.containsAll(List.of("ToolTip", "Pixel", "Mouse", "Caret", "Menu")));
        List<String> zwei = suggestions("CoordMode(\"Mouse\", \"<caret>\")\n");
        assertEquals(List.of("Client", "Screen", "Window"), zwei.stream().sorted().toList());
        assertInserted("CoordMode \"Mouse\", \"scr<caret>\"\n", "CoordMode \"Mouse\", \"Screen<caret>\"\n");
        // only if the string is the whole argument
        assertFalse(suggestions("CoordMode \"a\" . \"<caret>\"\n").contains("Pixel"));
        // an omitted argument counts
        assertTrue(suggestions("MouseClick \"<caret>\"\n").contains("WheelUp"));
        assertTrue(suggestions("GetKeyState(\"a\", \"<caret>\")\n").containsAll(List.of("P", "T")));
    }

    public void testParameterValuesWithoutQuotes() {
        // the value is at the top (before PixelGetColor ...) and comes with quotes
        List<String> list = suggestions("CoordMode Pixe<caret>\n");
        assertEquals(list.toString(), "\"Pixel\"", list.get(0));
        myFixture.finishLookup(com.intellij.codeInsight.lookup.Lookup.NORMAL_SELECT_CHAR);
        myFixture.checkResult("CoordMode \"Pixel\"<caret>\n");
        assertInserted("SendMode(InputT<caret>)\n", "SendMode(\"InputThenPlay\"<caret>)\n");
    }

    public void testParameterValuesInDirectives() {
        assertInserted("#SingleInstance Fo<caret>\n", "#SingleInstance Force<caret>\n");
        List<String> list = suggestions("#Warn All, <caret>\n");
        assertTrue(list.toString(), list.containsAll(List.of("MsgBox", "StdOut", "OutputDebug", "Off")));
    }

    /** Parameter info at {@code <caret>}: "text of the signature | index of the current argument". */
    private String parameterInfo(String text) {
        myFixture.configureByText("a.ahk", text);
        return parameterInfoAmCursor();
    }

    /** Parameter info at the caret of the open file: "forms | current argument", null = none. */
    private String parameterInfoAmCursor() {
        de.kumpewo.autohotkey.completion.AhkParameterInfoHandler handler =
                new de.kumpewo.autohotkey.completion.AhkParameterInfoHandler();
        com.intellij.testFramework.utils.parameterInfo.MockCreateParameterInfoContext create =
                new com.intellij.testFramework.utils.parameterInfo.MockCreateParameterInfoContext(
                        myFixture.getEditor(), myFixture.getFile());
        PsiElement owner = handler.findElementForParameterInfo(create);
        if (owner == null) return null;
        com.intellij.testFramework.utils.parameterInfo.MockUpdateParameterInfoContext update =
                new com.intellij.testFramework.utils.parameterInfo.MockUpdateParameterInfoContext(
                        myFixture.getEditor(), myFixture.getFile(), create.getItemsToShow());
        handler.updateParameterInfo(owner, update);
        List<String> texte = new ArrayList<>();
        for (Object item : create.getItemsToShow()) {
            com.intellij.testFramework.utils.parameterInfo.MockParameterInfoUIContext<PsiElement> ui =
                    new com.intellij.testFramework.utils.parameterInfo.MockParameterInfoUIContext<>(owner);
            ui.setCurrentParameterIndex(update.getCurrentParameter());
            handler.updateUI((de.kumpewo.autohotkey.completion.AhkCommandInfo.Signature) item, ui);
            texte.add(ui.getText());
        }
        return String.join(" / ", texte) + " | " + update.getCurrentParameter();
    }

    public void testParameterInfo() {
        assertEquals("TargetType, [RelativeTo] | 0", parameterInfo("CoordMode(<caret>)\n"));
        assertEquals("TargetType, [RelativeTo] | 1", parameterInfo("CoordMode(\"Mouse\", <caret>)\n"));
        assertEquals("TargetType, [RelativeTo] | 1", parameterInfo("CoordMode \"Mouse\", <caret>\n"));
        assertEquals("[Text], [Title], [Options] | 0", parameterInfo("MsgBox <caret>\n"));
        // the innermost call counts, omitted arguments count
        assertEquals("Haystack, Needle, [ReplaceText], [CaseSense], [&OutputVarCount], [Limit] | 3",
                parameterInfo("x := StrReplace(a, StrLen(\"x\"),, <caret>)\n"));
        assertEquals("[Text], [Title], [Options] | 0", parameterInfo("MsgBox \"x\" <caret>\n"));
        assertEquals("[WarningType], [WarningMode] | 1", parameterInfo("#Warn All, <caret>\n"));
        assertEquals("OnOff / SendMouse / MouseMove | 0", parameterInfo("BlockInput <caret>\n"));
        assertNull(parameterInfo("MeineFunktion(<caret>)\n"));
    }

    /** IsSet is not a keyword, but a normal name (built-in function). */
    public void testIsSetIsNormalName() {
        myFixture.configureByText("a.ahk", "x := IsSet(a)\n");
        PsiElement leaf = myFixture.getFile().findElementAt(myFixture.getFile().getText().indexOf("IsSet"));
        assertEquals(de.kumpewo.autohotkey.psi.AhkTypes.IDENTIFIER, leaf.getNode().getElementType());
        assertEquals(de.kumpewo.autohotkey.psi.AhkTypes.CALL_EXPR,
                leaf.getParent().getParent().getNode().getElementType());
        assertEquals("Var | 0", parameterInfo("x := IsSet(<caret>)\n"));
        assertColor(AhkColors.AHK_BUILTIN_FUNCTION, "x := §IsSet(a)\n");
        assertTrue(suggestions("x := IsSe<caret>\n").containsAll(List.of("IsSet", "IsSetRef")));
    }

    public void testParameterInfoOwnFunctions() {
        String f = "MeineFunktion(a, &b, c?, d := \"x\", rest*) {\n}\n";
        assertEquals("a, &b, [c], [d := \"x\"], rest* | 1", parameterInfo(f + "MeineFunktion(1, <caret>)\n"));
        assertEquals("a, &b, [c], [d := \"x\"], rest* | 1", parameterInfo(f + "meinefunktion 1, <caret>\n"));
        assertEquals("a, b | 0", parameterInfo("F(a, b) => a\nF(<caret>)\n"));
        assertEquals("* | 0", parameterInfo("G(*) {\n}\nG(<caret>)\n"));
        assertEquals("<no parameters> | 0", parameterInfo("H() {\n}\nH(<caret>)\n"));
        // definition also after the call; an own function takes precedence over a built-in one
        assertEquals("x | 0", parameterInfo("y := StrLen(<caret>)\nStrLen(x) {\n}\n"));
        // methods are not functions: a free call M( does not find the method (methods see testParameterInfoMethods)
        assertNull(parameterInfo("class K {\n    M(a) {\n    }\n}\nM(<caret>)\n"));
    }

    /** Parameter info for methods (built-in from method-signatures.txt and own classes). */
    public void testParameterInfoMethods() {
        String setColor = "Menu.SetColor([ColorValue], [ApplyToSubmenus])";
        assertEquals(setColor + " | 0", parameterInfo("m.SetColor(<caret>)\n"));
        assertEquals(setColor + " | 1", parameterInfo("m.SetColor(\"red\", <caret>)\n"));
        // the grammar reads the command form obj.M "x", ... as a concatenation: no parameter info
        assertNull(parameterInfo("m.SetColor <caret>\n"));
        assertEquals(setColor + " | 0", parameterInfo("x := a.b.SetColor(<caret>)\n"));
        assertEquals("Gui.Destroy() | 0", parameterInfo("g.Destroy(<caret>)\n"));
        // without a type: all forms of all classes
        String add = parameterInfo("x.Add(<caret>)\n");
        assertNotNull(add);
        for (String form : List.of("Menu.Add(", "Gui.Add(", "Gui.Control.Add(", "Gui.ListView.Add(", "Gui.TreeView.Add(")) {
            assertTrue(add, add.contains(form));
        }
        // ;@declare-type: only the class, the derived one wins; inherited from the base class
        String decl = ";@declare-type lv as Gui.ListView\n";
        assertEquals("Gui.ListView.Add([Options], [Col1], [Col2], [...]) | 0", parameterInfo(decl + "lv.Add(<caret>)\n"));
        assertEquals("Gui.Control.Focus() | 0", parameterInfo(decl + "lv.Focus(<caret>)\n"));
        assertNull(parameterInfo(decl + "lv.SetColor(<caret>)\n"));
        // built-in class on the left
        assertEquals("Gui.Call([Options], [Title], [EventObj]) | 0", parameterInfo("g := Gui.Call(<caret>)\n"));
        assertEquals("Gui.ListView.Add([Options], [Col1], [Col2], [...]) | 0", parameterInfo("Gui.ListView.Add(<caret>)\n"));
        // own methods first, in addition to built-in; free functions of the same name not
        assertEquals("K.SetColor(a, [b]) / " + setColor + " | 0", parameterInfo(
                "SetColor(z) {\n}\nclass K {\n    SetColor(a, b?) {\n    }\n}\nm.SetColor(<caret>)\n"));
        assertEquals("K.Tuwas() | 0", parameterInfo("class K {\n    Tuwas() {\n    }\n}\nk.Tuwas(<caret>)\n"));
        // variadic: [...] applies to further arguments
        assertEquals("Array.Push([Value], [Value2], [...]) | 4", parameterInfo(
                ";@declare-type arr as Array\narr.Push(1, 2, 3, 4, <caret>)\n"));
        // dynamic name: no parameter info
        assertNull(parameterInfo("obj.%name%(<caret>)\n"));
        // parameter values (values.txt) for methods only with an entry Class.Method (none here)
        assertFalse(suggestions("obj.CoordMode(Mo<caret>)\n").contains("\"Mouse\""));
    }

    /** Gui.Add<ControlType> from gui.html#Add (AddButton, AddEdit ..., also AddDDL and AddPic). */
    public void testGuiAddControlType() {
        String decl = ";@declare-type g as Gui\n";
        List<String> list = suggestions(decl + "x := g.Add<caret>\n");
        assertTrue(list.toString(), list.containsAll(List.of("Add", "AddButton", "AddEdit", "AddDDL", "AddPic",
                "AddDropDownList", "AddPicture", "AddTab3", "AddUpDown")));
        assertEquals("Gui.AddEdit([Options], [Text]) | 1", parameterInfo(decl + "g.AddEdit(\"w200\", <caret>)\n"));
        assertEquals("Gui.AddDDL([Options], [Text]) | 0", parameterInfo(decl + "g.AddDDL(<caret>)\n"));
        assertColor(AhkColors.AHK_BUILTIN_METHOD, "x := g.§AddButton(\"Default\", \"OK\")\n");
    }

    /** Values for the first parameter of Gui.Add (values.txt "Gui.Add 1: ..."). */
    public void testOptionStrings() {
        // MsgBox: the word at the caret is the prefix, earlier options stay
        List<String> icons = suggestions("MsgBox \"x\",, \"YesNo Ic<caret>\"\n");
        assertTrue(icons.toString(), icons.containsAll(List.of("Iconx", "Icon?", "Icon!", "Iconi")));
        assertFalse(icons.toString(), icons.contains("YesNo"));
        assertTrue(suggestions("MsgBox(\"x\", \"t\", \"YesN<caret>\")\n").containsAll(List.of("YesNo", "YesNoCancel")));
        // not in other arguments
        assertFalse(suggestions("MsgBox(\"YesN<caret>\")\n").contains("YesNo"));
        // Gui(): window options, the sign stays
        assertInserted("g := Gui(\"+Resi<caret>\")\n", "g := Gui(\"+Resize<caret>\")\n");
        assertInserted("g := Gui(\"Resize -Capt<caret>\")\n", "g := Gui(\"Resize -Caption<caret>\")\n");
        String decl = ";@declare-type g as Gui, e as Gui.Edit, btn as Gui.Button, lv as Gui.ListView\n";
        assertInserted(decl + "g.Opt(\"+AlwaysOn<caret>\")\n", decl + "g.Opt(\"+AlwaysOnTop<caret>\")\n");
        // Gui.Add: options of the control type given as first argument; AddXxx
        assertInserted(decl + "g.Add(\"Edit\", \"w200 ReadO<caret>\")\n", decl + "g.Add(\"Edit\", \"w200 ReadOnly<caret>\")\n");
        assertFalse(suggestions(decl + "g.Add(\"Button\", \"Read<caret>\")\n").contains("ReadOnly"));
        assertInserted(decl + "g.AddButton(\"Defa<caret>\")\n", decl + "g.AddButton(\"Default<caret>\")\n");
        // an option with a value inserts only its prefix
        assertInserted(decl + "g.AddEdit(\"Passw<caret>\")\n", decl + "g.AddEdit(\"Password<caret>\")\n");
        assertInserted(decl + "g.Show(\"w300 xC<caret>\")\n", decl + "g.Show(\"w300 xCenter<caret>\")\n");
        // Gui.Control.Opt per control class
        assertInserted(decl + "e.Opt(\"-ReadO<caret>\")\n", decl + "e.Opt(\"-ReadOnly<caret>\")\n");
        // event names (values.txt): window events, control events per class
        List<String> windowEvents = suggestions(decl + "g.OnEvent(\"<caret>\")\n");
        assertTrue(windowEvents.toString(), windowEvents.containsAll(List.of("Close", "Escape", "Size")));
        assertFalse(windowEvents.toString(), windowEvents.contains("Click"));
        List<String> buttonEvents = suggestions(decl + "btn.OnEvent(\"<caret>\")\n");
        assertTrue(buttonEvents.toString(), buttonEvents.contains("Click"));
        assertFalse(buttonEvents.toString(), buttonEvents.contains("ItemCheck"));
        assertTrue(suggestions(decl + "lv.OnEvent(\"Item<caret>\")\n").contains("ItemCheck"));
    }

    public void testGuiAddValues() {
        String decl = ";@declare-type g as Gui\n";
        // in the string only the value, at a free position with quotes
        assertInserted(decl + "g.Add(\"Ed<caret>\")\n", decl + "g.Add(\"Edit<caret>\")\n");
        assertEquals("\"Edit\"", suggestions(decl + "g.Add(Ed<caret>)\n").get(0));
        List<String> t = suggestions(decl + "g.Add(\"T<caret>\")\n");
        assertTrue(t.toString(), t.containsAll(List.of("Tab", "Tab2", "Tab3", "Text", "TreeView")));
        assertTrue(suggestions(decl + "g.Add(\"D<caret>\")\n").containsAll(List.of("DDL", "DropDownList", "DateTime")));
        // without a type: values of all classes with this method
        assertInserted("myGui.Add(\"Ed<caret>\")\n", "myGui.Add(\"Edit<caret>\")\n");
        // classes with their own Add (Menu, Gui.ListView): no Gui values
        assertFalse(suggestions(";@declare-type m as Menu\nm.Add(\"<caret>\")\n").contains("Edit"));
        assertFalse(suggestions(";@declare-type lv as Gui.ListView\nlv.Add(\"<caret>\")\n").contains("Edit"));
        // only the first parameter
        assertFalse(suggestions(decl + "g.Add(\"Edit\", \"<caret>\")\n").contains("Edit"));
    }

    public void testParameterInfoFromIncludedFile() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("ahk").toRealPath();
        java.nio.file.Files.writeString(dir.resolve("a.ahk"), "#Include b.ahk\nLibFunktion(Wert, Anzahl := 1) {\n}\n");
        java.nio.file.Files.writeString(dir.resolve("b.ahk"), "LibTief(x) {\n}\n");
        for (String name : List.of("a.ahk", "b.ahk")) {
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir.resolve(name));
        }
        openInFile(dir, "#Include a.ahk\nLibFunktion(1, <caret>)\n");
        assertEquals("Wert, [Anzahl := 1] | 1", parameterInfoAmCursor());
        // not recursive
        openInFile(dir, "#Include a.ahk\nLibTief(<caret>)\n");
        assertNull(parameterInfoAmCursor());
    }

    public void testNoCompletionInCommentAndString() {
        assertEquals(List.of(), suggestions("x := 1 ; A_Ti<caret>\n"));
        assertEquals(List.of(), suggestions("x := \"A_Ti<caret>\"\n"));
    }

    // ---------------------------------------------------------------- Sticky Lines / Breadcrumbs

    private static final String STICKY_TEXT = String.join("\n",
            "class A extends Gui {",              // 0
            "    m(x) { ; Methode",               // 1
            "        if x > 1 {",                 // 2
            "            y := 1",                 // 3
            "        }",                          // 4
            "        else {",                     // 5
            "            z := 2",                 // 6
            "        }",                          // 7
            "    }",                              // 8
            "    Wert {",                         // 9
            "        get {",                      // 10
            "            return 1",               // 11
            "        }",                          // 12
            "    }",                              // 13
            "}",                                  // 14
            "F1:: {",                             // 15
            "    loop 3 {",                       // 16
            "        switch A_Index {",           // 17
            "        case 1:",                    // 18
            "            MsgBox",                 // 19
            "        }",                          // 20
            "    }",                              // 21
            "}",                                  // 22
            ":X:btw::",                           // 23
            "{",                                  // 24
            "    try {",                          // 25
            "        f()",                        // 26
            "    } catch as e {",                 // 27
            "        g()",                        // 28
            "    }",                              // 29
            "}",                                  // 30
            "F2::MsgBox \"einzeilig\"",           // 31
            "");

    public void testStickyLines() throws Exception {
        myFixture.configureByText("a.ahk", STICKY_TEXT);
        com.intellij.openapi.editor.Document doc = myFixture.getEditor().getDocument();
        com.intellij.openapi.vfs.VirtualFile file = myFixture.getFile().getVirtualFile();
        // collectLines must not run in the EDT (as in the IDE: background thread with read access)
        java.util.Collection<com.intellij.ui.components.breadcrumbs.StickyLineInfo> infos =
                com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(() ->
                        com.intellij.openapi.application.ReadAction.compute(() ->
                                new com.intellij.openapi.editor.impl.stickyLines.StickyLinesCollector(getProject(), doc)
                                        .collectLines(file, new com.intellij.openapi.progress.EmptyProgressIndicator())))
                        .get(30, java.util.concurrent.TimeUnit.SECONDS);
        java.util.TreeSet<Integer> zeilen = new java.util.TreeSet<>();
        for (com.intellij.ui.components.breadcrumbs.StickyLineInfo info : infos) {
            if (doc.getLineNumber(info.textOffset()) != doc.getLineNumber(info.endOffset())) {
                zeilen.add(doc.getLineNumber(info.textOffset()));
            }
        }
        assertEquals("[0, 1, 2, 5, 9, 10, 15, 16, 17, 18, 23, 25, 27]", zeilen.toString());
    }

    public void testBreadcrumbTexts() {
        myFixture.configureByText("a.ahk", STICKY_TEXT);
        de.kumpewo.autohotkey.structure.AhkBreadcrumbsProvider provider =
                new de.kumpewo.autohotkey.structure.AhkBreadcrumbsProvider();
        List<String> texte = new ArrayList<>();
        collectBreadcrumbs(myFixture.getFile(), provider, texte);
        assertEquals(List.of("class A extends Gui", "m(x)", "if x > 1", "else", "Wert", "get", "F1::", "loop 3",
                "switch A_Index", "case 1:", ":X:btw::", "try", "catch as e", "F2::"), texte);
    }

    private static void collectBreadcrumbs(PsiElement element,
                                          de.kumpewo.autohotkey.structure.AhkBreadcrumbsProvider provider,
                                          List<String> texte) {
        for (PsiElement kind : element.getChildren()) {
            if (provider.acceptElement(kind)) texte.add(provider.getElementInfo(kind));
            collectBreadcrumbs(kind, provider, texte);
        }
    }

    // ---------------------------------------------------------------- annotators

    public void testBlockCommentInMiddleOfLineIsError() {
        myFixture.configureByText("a.ahk",
                "f() { <error descr=\"Block comments are only allowed at the start of a line.\">/* */</error>\n}\n");
        myFixture.checkHighlighting(true, false, false);
    }

    public void testElseOnSameLineWarnsForAllLoops() {
        String w = "<warning descr=\"'else' should be followed by a line break or '{' "
                + "(AutoHotkey accepts this, but it is hard to read)\">";
        myFixture.configureByText("a.ahk", ""
                + "if a\n    f()\nelse " + w + "b()</warning>\n"
                + "for a in b\n    c()\nelse " + w + "b_is_empty</warning>\n"
                + "loop 3\n    d()\nelse " + w + "e()</warning>\n"
                + "while x\n    d()\nelse " + w + "e()</warning>\n"
                + "if a\n    f()\nelse if b\n    g()\nelse {\n}\n"
                + "while (false) {\n}\nelse\n    always_false()\n");
        myFixture.checkHighlighting(true, false, false);
    }

    public void testGlobalAlone() {
        String nichtErste = "<error descr=\"'global' without a variable name is only allowed as the first statement of a "
                + "function.\">global</error>";
        myFixture.configureByText("a.ahk", ""
                + "f() {\n    global\n    x := 1\n}\n"
                + "g() { ; Kommentar\n    ; noch einer\n    global ; Kommentar\n}\n"
                + "class K {\n    m() {\n        global\n    }\n}\n"
                + "h() {\n    x := 1\n    " + nichtErste + "\n}\n"
                + "i() {\n    if x {\n        " + nichtErste + "\n    }\n}\n"
                + "F1:: {\n    global\n}\n"
                + "F2::\n{\n    ; Kommentar\n    global\n}\n"
                + "F3:: {\n    x := 1\n    " + nichtErste + "\n}\n"
                + "F4::" + nichtErste + "\n"
                + "::btw::\n{\n    global\n}\n"
                + ":X:abc:: {\n    global\n}\n"
                + ":X:def:: {\n    x := 1\n    " + nichtErste + "\n}\n"
                + nichtErste + "\n"
                + "j() {\n    <error descr=\"'local' needs a variable name.\">local</error>\n"
                + "    <error descr=\"'static' needs a variable name.\">static</error>\n}\n"
                + "k() {\n    global x, y := 2\n}\n");
        myFixture.checkHighlighting(true, false, false);
    }

    public void testSwitchBraceWithStatementIsError() {
        myFixture.configureByText("a.ahk", ""
                + "switch x { <error descr=\"A line break must follow '{'\">case 1: a()</error>\n}\n"
                + "switch x { ; Kommentar\ncase 1: a()\n}\n"
                + "switch x\n{\ncase 1: a()\n}\n");
        myFixture.checkHighlighting(true, false, false);
    }

    // ---------------------------------------------------------------- highlighting (AhkAnnotator, AhkNames)

    public void testDirectiveIsColored() {
        assertColor(AhkColors.AHK_DIRECTIVE, "§#Include x.ahk\n");
        assertColor(AhkColors.AHK_DIRECTIVE, "§#singleinstance force\n");
    }

    public void testUnknownDirectiveWarns() {
        myFixture.configureByText("a.ahk", "#Requires AutoHotkey v2\n"
                + "<warning descr=\"Unknown directive.\">#Gibtsnicht</warning>\n");
        myFixture.checkHighlighting(true, false, false);
    }

    public void testKeywordsByTokenType() {
        assertColor(AhkColors.AHK_FLOWCONTROL, "§if x\n  a()\n");
        assertColor(AhkColors.AHK_FLOWCONTROL, "loop 3\n  §break\n");
        assertColor(AhkColors.AHK_RESERVED, "f() {\n  §static x := 1\n}\n");
        assertColor(AhkColors.AHK_RESERVED, "x := a §and b\n");
        assertColor(AhkColors.AHK_RESERVED, "§class A {\n}\n");
        // true, false, unset, this, super are KEYWORD_LITERAL (lexer) with their own color
        assertColor(AhkColors.AHK_RESERVED_VALUE, "a := §true\n");
        assertColor(AhkColors.AHK_RESERVED_VALUE, "a := §unset\n");
        assertColor(AhkColors.AHK_RESERVED_VALUE, "x := §this.a\n");
        // the new key inherits the reserved-word color, so existing schemes look the same
        assertEquals(AhkColors.AHK_RESERVED, AhkColors.AHK_RESERVED_VALUE.getFallbackAttributeKey());
    }

    public void testBuiltinOnlyOutsideMembers() {
        assertColor(AhkColors.AHK_BUILTIN_FUNCTION, "§MsgBox \"x\"\n");
        assertColor(AhkColors.AHK_BUILTIN_FUNCTION, "x := §StrLen(\"abc\")\n");
        assertColor(AhkColors.AHK_BUILTIN_VARIABLE, "x := §A_Index\n");
        assertColor(AhkColors.AHK_BUILTIN_CLASS, "m := §Map()\n");
        assertColor(AhkColors.AHK_BUILTIN_CLASS, "class X extends §Array {\n}\n");
        assertColor(AhkColors.AHK_BUILTIN_CLASS, "§Class.Prototype.x := 1\n");
        assertColor(null, "x := obj.§MsgBox\n");
        assertColor(null, "x := obj.§A_Index\n");
    }

    public void testBuiltinMemberOnlyAfterDot() {
        assertColor(AhkColors.AHK_BUILTIN_METHOD, "a.§Push(1)\n");
        assertColor(AhkColors.AHK_BUILTIN_PROPERTY, "x := a.§Length\n");
        assertColor(null, "x := §Length\n");
        // part of a dynamic name
        assertColor(null, "x := a.§Push%n%\n");
    }

    public void testNestedClassAfterDot() {
        assertColor(AhkColors.AHK_BUILTIN_CLASS, "x := Gui.§Button\n");
        assertColor(AhkColors.AHK_BUILTIN_CLASS, "x := gui.§listview.Prototype\n");
        // after Gui, Text is the class, otherwise the property
        assertColor(AhkColors.AHK_BUILTIN_CLASS, "x := Gui.§Text\n");
        assertColor(AhkColors.AHK_BUILTIN_PROPERTY, "x := ctrl.§Text\n");
        assertColor(null, "x := MyGui.§Button\n");
    }

    public void testNestedBaseClassIsColored() {
        assertColor(AhkColors.AHK_BUILTIN_CLASS, "class X extends §Gui.Edit {\n}\n");
        assertColor(AhkColors.AHK_BUILTIN_CLASS, "class X extends Gui.§Edit {\n}\n");
        assertColor(AhkColors.AHK_CLASS, "class §X extends Gui.Edit {\n}\n");
        assertColor(null, "class X extends Outer.§Edit {\n}\n");
    }

    public void testDefaultInSwitchAndAsProperty() {
        assertColor(AhkColors.AHK_FLOWCONTROL, "switch x {\n§default: a()\n}\n");
        assertColor(AhkColors.AHK_BUILTIN_PROPERTY, "m.§Default := 0\n");
        assertColor(null, "§default := 0\n");
    }

    public void testBuiltinFreeStandingEverywhere() {
        assertColor(AhkColors.AHK_BUILTIN_VARIABLE, "f(§A_Index) {\n}\n");
        assertColor(AhkColors.AHK_BUILTIN_VARIABLE, "f() {\n  local §A_Index := 1\n}\n");
        assertColor(AhkColors.AHK_BUILTIN_CLASS, "for §Map in x\n  a()\n");
        // methods/properties never when free-standing, not in parentheses either
        assertColor(null, "f(§Length) {\n}\n");
        assertColor(null, "x := (§Length)\n");
        // names of definitions and object keys are not free-standing
        assertColor(null, "x := {§MsgBox: 1}\n");
        assertColor(AhkColors.AHK_FUNC, "§MyFunc() {\n}\n");
    }

    public void testDeclarationInParenthesesIsError() {
        // "global A_Index" free-standing is valid (only colored), in parentheses it is not an expression
        myFixture.configureByText("a.ahk", "#Requires AutoHotkey v2\nf() {\n  global A_Index\n}\n");
        assertTrue(myFixture.doHighlighting(HighlightSeverity.ERROR).isEmpty());
        myFixture.configureByText("a.ahk", "#Requires AutoHotkey v2\nx := (global A_Index)\n");
        assertFalse(myFixture.doHighlighting(HighlightSeverity.ERROR).isEmpty());
    }

    public void testNameAsClassAndFunctionColoredAsClass() {
        // classes.txt before functions.txt; String is (also) callable
        assertColor(AhkColors.AHK_BUILTIN_CLASS, "x := §String(1)\n");
    }

    public void testKeywordAfterDotIsError() {
        myFixture.configureByText("a.ahk", "#Requires AutoHotkey v2\n"
                + "x := obj.<error descr=\"Keyword used as a name after the dot.\">if</error>\n"
                + "y := obj.<error descr=\"Keyword used as a name after the dot.\">class</error>.a\n");
        myFixture.checkHighlighting(true, false, false);
    }

    public void testEscapeSequencesInString() {
        assertColor(AhkColors.AHK_STRING_ESCAPE, "x := \"a§`nb\"\n");
        assertColor(AhkColors.AHK_STRING_ESCAPE, "x := \"a§`\"b\"\n");
        assertColor(AhkColors.AHK_STRING_ESCAPE, "x := 'a§``b'\n");
        // after `` no new sequence starts, unknown sequences stay uncolored
        assertColor(null, "x := \"``§`q\"\n");
        // no coloring in a comment
        assertColor(null, "x := 1 ; a§`nb\n");
    }

    public void testLoopSubcommand() {
        assertColor(AhkColors.AHK_FLOWCONTROL, "Loop §Read \"a.txt\"\n  a()\n");
        assertColor(AhkColors.AHK_FLOWCONTROL, "Loop §Reg \"HKCU\\\\Software\"\n  a()\n");
        assertColor(AhkColors.AHK_FLOWCONTROL, "Loop §Parse s, \",\"\n  a()\n");
        assertColor(AhkColors.AHK_FLOWCONTROL, "Loop §Files \"*.txt\"\n  a()\n");
        assertColor(null, "Loop x + §Parse\n  a()\n");
        assertColor(null, "§Parse := 1\n");
    }

    // ---------------------------------------------------------------- type declaration (;@declare-type)

    public void testDeclaredTypeIsColored() {
        assertColor(AhkColors.AHK_DECLARED_TYPE, ";@declare-type g as Gui\n§g := 1\n");
        // the pragma applies to the whole file, also if it comes further down; case-insensitive
        assertColor(AhkColors.AHK_DECLARED_TYPE, "§G := 1\n;@Declare-Type g AS gui\n");
        assertColor(AhkColors.AHK_DECLARED_TYPE, ";@declare-type *Obj as Object\nx := §myObj\n");
        assertColor(AhkColors.AHK_DECLARED_TYPE, ";@declare-type *Obj as Object\nx := §obj\n");
        assertColor(AhkColors.AHK_DECLARED_TYPE, ";@declare-type list* as Array\nx := §listA\n");
        assertColor(null, ";@declare-type list* as Array\nx := §alist\n");
        assertColor(null, "x := §myObj\n");
        // at all free-standing places: parameter, declaration, for variable
        assertColor(AhkColors.AHK_DECLARED_TYPE, ";@declare-type g as Gui\nf(§g) {\n}\n");
        assertColor(AhkColors.AHK_DECLARED_TYPE, ";@declare-type g as Gui\nf() {\n  local §g := 1\n}\n");
        assertColor(AhkColors.AHK_DECLARED_TYPE, ";@declare-type e as Error\nfor §e in x\n  a()\n");
        // not after the dot, not for definitions
        assertColor(null, ";@declare-type g as Gui\nx.§g := 1\n");
        assertColor(AhkColors.AHK_FUNC, ";@declare-type f as Gui\n§f() {\n}\n");
    }

    public void testDeclaredTypeAndBuiltinNames() {
        // explicitly declared beats built-in, via a wildcard it does not
        assertColor(AhkColors.AHK_DECLARED_TYPE, ";@declare-type MsgBox as Array\n§MsgBox \"x\"\n");
        assertColor(AhkColors.AHK_BUILTIN_FUNCTION, ";@declare-type MsgBox* as Array\n§MsgBox \"x\"\n");
        assertColor(AhkColors.AHK_DECLARED_TYPE,
                ";@declare-type Msgbox* as Array, MsgBox as Map\n§MsgBox \"x\"\n");
        assertColor(AhkColors.AHK_BUILTIN_CLASS, ";@declare-auto-type *\nx := §Gui()\n");
    }

    public void testAutomaticTypeDeclaration() {
        assertColor(AhkColors.AHK_DECLARED_TYPE, ";@declare-auto-type Gui*\nx := §nameEdit\n");
        assertColor(AhkColors.AHK_DECLARED_TYPE, ";@declare-auto-type *Box\nx := §myListBox\n");
        assertColor(null, ";@declare-auto-type *Box\nx := §myEdit\n");
        assertColor(AhkColors.AHK_DECLARED_TYPE, ";@declare-auto-type Gui.Edit\nx := §nameEdit\n");
        assertColor(AhkColors.AHK_DECLARED_TYPE, ";@declare-auto-type Array, Map\nx := §dataMap\n");
        assertColor(AhkColors.AHK_DECLARED_TYPE, ";@declare-auto-type *\nx := §lineNumber\n");
    }

    public void testTypeDeclarationWarnings() {
        myFixture.configureByText("a.ahk", ""
                + ";@declare-type <warning descr=\"Invalid pattern.\">*</warning> as Gui, "
                + "<warning descr=\"Invalid pattern.\">a*b</warning> as Gui\n"
                + ";@declare-type g <warning descr=\"'as' expected.\">ist</warning> Gui, "
                + "h as <warning descr=\"Unknown class.\">Gu</warning>\n"
                + ";@declare-type g <warning descr=\"Class expected.\">as</warning>\n"
                + ";@declare-type g as Gui <warning descr=\"',' expected.\">h</warning> as Gui\n"
                + ";@declare-type g as Gui<warning descr=\"Invalid pattern.\">,</warning>\n"
                + "<warning descr=\"Invalid pattern.\">;@declare-type</warning>\n"
                + ";@declare-auto-type <warning descr=\"Invalid pattern.\">G*i</warning>, "
                + "<warning descr=\"Unknown class.\">Xyz*</warning>\n"
                + ";@declare-type g as Gui, *Obj as Object, gx as Gui.Edit\n"
                + ";@declare-auto-type Gui*, *Box, Gui.Edit, *\n"
                + ";@declare-typen ist kein Pragma\n");
        myFixture.checkHighlighting(true, false, false);
    }

    public void testCompletionDeclaredType() {
        List<String> edit = suggestions(";@declare-type g as Gui.Edit\nx := g.<caret>\n");
        // own members of Gui.Control and inherited ones from Any, no foreign ones, no static ones
        assertTrue(edit.toString(), edit.containsAll(List.of("Value", "Focus", "HasProp")));
        assertFalse(edit.toString(), edit.contains("Push"));
        assertFalse(edit.toString(), edit.contains("Call"));
        List<String> beide = suggestions(";@declare-type g as Array, g as Map\nx := g.<caret>\n");
        assertTrue(beide.toString(), beide.containsAll(List.of("Push", "Clear", "Length", "Count")));
        List<String> eigene = suggestions(";@declare-type g as Array\nclass K {\n    Tuwas() {\n    }\n}\n"
                + "x := g.<caret>\n");
        assertTrue(eigene.toString(), eigene.containsAll(List.of("Push", "Tuwas")));
        assertFalse(eigene.toString(), eigene.contains("SetColor"));
        assertInserted(";@declare-type g as Array\ng.Pu<caret>\n", ";@declare-type g as Array\ng.Push(<caret>)\n");
        // without a declaration all members, as before
        assertTrue(suggestions("x := h.<caret>\n").containsAll(List.of("Push", "SetColor")));
    }

    /** Own classes as a type in ;@declare-type. */
    private static final String EIGENE_KLASSEN = String.join("\n",
            "class Basis extends Array {", "    BasisMethode() {", "    }", "}",
            "class MeinArray extends Basis {", "    Eigene(a, b?) {", "    }", "    Wert => 1",
            "    static Fabrik() {", "    }", "    static Zaehler := 0", "    class Innen {",
            "        InnenMethode(x) {", "        }", "    }", "}",
            "class Fremd {", "    FremdMethode() {", "    }", "}", "");

    public void testOwnClassAsType() {
        String decl = ";@declare-type k as meinarray, i as MeinArray.Innen\n";
        // colored as for built-in classes
        assertColor(AhkColors.AHK_DECLARED_TYPE, decl + EIGENE_KLASSEN + "x := §k\n");
        assertColor(AhkColors.AHK_DECLARED_TYPE, decl + EIGENE_KLASSEN + "x := §i\n");
        // completion: class with own and built-in base classes, without static/nested/foreign ones
        List<String> k = suggestions(decl + EIGENE_KLASSEN + "x := k.<caret>\n");
        assertTrue(k.toString(), k.containsAll(List.of("Eigene", "Wert", "BasisMethode", "Push", "Length", "HasProp")));
        for (String nicht : List.of("Fabrik", "Zaehler", "Innen", "FremdMethode", "InnenMethode", "SetColor")) {
            assertFalse(nicht + " in " + k, k.contains(nicht));
        }
        // order: own members of the class first, then per base class (Basis, Array, Object, Any)
        assertTrue(k.toString(), k.indexOf("Wert") < k.indexOf("BasisMethode"));
        assertTrue(k.toString(), k.indexOf("Eigene") < k.indexOf("BasisMethode"));
        assertTrue(k.toString(), k.indexOf("BasisMethode") < k.indexOf("Push"));
        assertTrue(k.toString(), k.indexOf("Push") < k.indexOf("DefineProp"));
        assertTrue(k.toString(), k.indexOf("DefineProp") < k.indexOf("HasProp"));
        assertEquals(List.of("Eigene", "Wert"), k.subList(0, 2));
        // also for built-in classes: Gui.Edit before Gui.Control
        List<String> edit = suggestions(";@declare-type g as Gui.Edit\nx := g.<caret>\n");
        assertTrue(edit.toString(), edit.indexOf("Value") < edit.indexOf("HasProp"));
        // nested class, derived from Object without extends
        List<String> i = suggestions(decl + EIGENE_KLASSEN + "x := i.<caret>\n");
        assertTrue(i.toString(), i.containsAll(List.of("InnenMethode", "HasProp", "DefineProp")));
        assertFalse(i.toString(), i.contains("Push"));
        assertFalse(i.toString(), i.contains("Eigene"));
        // parameter info: only forms of the class with base classes, the derived one wins
        assertEquals("MeinArray.Eigene(a, [b]) | 0", parameterInfo(decl + EIGENE_KLASSEN + "k.Eigene(<caret>)\n"));
        assertEquals("Basis.BasisMethode() | 0", parameterInfo(decl + EIGENE_KLASSEN + "k.BasisMethode(<caret>)\n"));
        assertEquals("Array.Push([Value], [Value2], [...]) | 0",
                parameterInfo(decl + EIGENE_KLASSEN + "k.Push(<caret>)\n"));
        assertEquals("MeinArray.Innen.InnenMethode(x) | 0",
                parameterInfo(decl + EIGENE_KLASSEN + "i.InnenMethode(<caret>)\n"));
        assertNull(parameterInfo(decl + EIGENE_KLASSEN + "k.FremdMethode(<caret>)\n"));
        // without a declaration still all own methods
        assertEquals("Fremd.FremdMethode() | 0", parameterInfo(EIGENE_KLASSEN + "y.FremdMethode(<caret>)\n"));
    }

    public void testOwnClassAsTypeWarnings() {
        myFixture.configureByText("a.ahk", ""
                + ";@declare-type k as MeinArray, i as MeinArray.Innen, "
                + "x as <warning descr=\"Unknown class.\">Innen</warning>, "
                + "y as <warning descr=\"Unknown class.\">MeinArray.Fremd</warning>\n"
                // ;@declare-auto-type only knows built-in classes
                + ";@declare-auto-type <warning descr=\"Unknown class.\">Mein*</warning>\n"
                + EIGENE_KLASSEN);
        myFixture.checkHighlighting(true, false, false);
    }

    public void testOwnClassFromIncludedFileAsType() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("ahk").toRealPath();
        java.nio.file.Files.writeString(dir.resolve("a.ahk"), "#Include b.ahk\nclass LibKlasse extends Tief {\n"
                + "    LibMethode() {\n    }\n}\n");
        java.nio.file.Files.writeString(dir.resolve("b.ahk"), "class Tief {\n    TiefMethode() {\n    }\n}\n"
                + "class NurTief {\n}\n");
        for (String name : List.of("a.ahk", "b.ahk")) {
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir.resolve(name));
        }
        // the base class Tief is only in the file of the class (a.ahk includes b.ahk)
        List<String> k = suggestionsInFile(dir, "#Include a.ahk\n;@declare-type k as LibKlasse\nx := k.<caret>\n");
        assertTrue(k.toString(), k.containsAll(List.of("LibMethode", "TiefMethode", "HasProp")));
        // b.ahk is only included via a.ahk: not known as a type (not recursive). Directly via lookup,
        // because no annotator runs in files outside of the project.
        openInFile(dir, "#Include a.ahk\n;@declare-type k as LibKlasse, n as NurTief\n");
        AhkTypeDeclarations.Declared declared = AhkTypeDeclarations.lookup(myFixture.getFile(), "k");
        assertNotNull(declared);
        assertEquals(List.of("LibKlasse"), declared.classes());
        assertNull(AhkTypeDeclarations.lookup(myFixture.getFile(), "n"));
    }

    public void testCompletionWithoutMetaMembers() {
        // never members starting with "__": built-in, declared type, own classes
        String eigene = "class K {\n    __New() {\n    }\n    __Item[k] {\n        get => 1\n    }\n}\n";
        for (String text : List.of("x := h._<caret>\n", ";@declare-type g as Array\nx := g._<caret>\n",
                eigene + "x := h._<caret>\n")) {
            List<String> list = suggestions(text);
            assertTrue(text + list, list.stream().noneMatch(n -> n.startsWith("__")));
        }
        List<String> array = suggestions(";@declare-type g as Array\nx := g.<caret>\n");
        assertTrue(array.toString(), array.contains("Push"));
        assertFalse(array.toString(), array.contains("__Item"));
        // still colored as a definition in the class
        assertColor(AhkColors.AHK_FUNC, "class K {\n    §__New() {\n    }\n}\n");
    }

    /** Color of the element at "§" (the character is removed), null = no color from an annotator. */
    /** All colors (INFORMATION annotations) that start at the marker "¦" in a.ahk. */
    private List<TextAttributesKey> colorsAt(String codeWithMarker) {
        int offset = codeWithMarker.indexOf('¦');
        assertTrue("marker missing", offset >= 0);
        myFixture.configureByText("a.ahk", codeWithMarker.replace("¦", ""));
        List<TextAttributesKey> keys = new ArrayList<>();
        for (HighlightInfo info : myFixture.doHighlighting()) {
            if (info.getStartOffset() == offset && info.getSeverity() == HighlightSeverity.INFORMATION
                    && info.forcedTextAttributesKey != null) {
                keys.add(info.forcedTextAttributesKey);
            }
        }
        return keys;
    }

    /** ;@declare-private: members (definitions, after the dot, fields) in the private color, by the rank of Z14. */
    public void testPrivateMembers() {
        TextAttributesKey priv = AhkColors.AHK_PRIVATE_MEMBER;
        String head = ";@declare-private _*, §*, *Impl, Push\n";
        // definitions in a class: field, static field, method, property
        assertEquals(List.of(priv), colorsAt(head + "class A {\n    ¦_count := 0\n}\n"));
        assertEquals(List.of(priv), colorsAt(head + "class A {\n    static ¦_n := 0\n}\n"));
        assertEquals(List.of(priv), colorsAt(head + "class A {\n    ¦_Helper() {\n    }\n}\n"));
        assertEquals(List.of(priv), colorsAt(head + "class A {\n    ¦DoImpl() => 1\n}\n"));
        assertEquals(List.of(priv), colorsAt(head + "class A {\n    ¦_Size => 1\n}\n"));
        assertEquals(List.of(priv), colorsAt(head + "class A {\n    ¦§count := 0\n}\n"));
        // after the dot
        assertEquals(List.of(priv), colorsAt(head + "x := this.¦_count\n"));
        assertEquals(List.of(priv), colorsAt(head + "x := obj.¦§count\n"));
        // built-in member: an explicit declaration wins, a wildcard does not
        assertEquals(List.of(priv), colorsAt(head + "x.¦Push(1)\n"));
        assertEquals(List.of(AhkColors.AHK_BUILTIN_METHOD), colorsAt(";@declare-private *ush\nx.¦Push(1)\n"));
        // not members: functions outside of classes, free-standing names, other files
        assertEquals(List.of(AhkColors.AHK_FUNC), colorsAt(head + "¦_Helper() {\n}\n"));
        assertEquals(List.of(), colorsAt(head + "¦_count := 1\n"));
        assertEquals(List.of(), colorsAt(head + "class A {\n    M() {\n        ¦_local := 1\n    }\n}\n"));
        assertEquals(List.of(AhkColors.AHK_FUNC), colorsAt("class A {\n    ¦_Helper() {\n    }\n}\n"));
    }

    /** Mistakes in ;@declare-private, and the pragma file pragmas.ahk: no "Unknown class." for own classes. */
    public void testPrivateMembersWarnings() {
        myFixture.configureByText("a.ahk", ";@declare-private _*, <warning descr=\"Invalid pattern.\">*a*</warning>\n"
                + ";@declare-private a <warning descr=\"',' expected.\">b</warning>\n"
                + "<warning descr=\"Invalid pattern.\">;@declare-private</warning>\n"
                + ";@declare-type x as <warning descr=\"Unknown class.\">MyOwnClass</warning>\n");
        myFixture.checkHighlighting(true, false, false);
        myFixture.configureByText("pragmas.ahk", ";@declare-type x as MyOwnClass\n;@declare-private §*\n");
        myFixture.checkHighlighting(true, false, false);
    }

    /** Pragmas of a directly included pragmas.ahk (also in a subfolder) apply in the including file. */
    public void testPragmasFromIncludedPragmaFile() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("ahk-pragmas");
        try {
            java.nio.file.Files.createDirectories(dir.resolve("shared"));
            java.nio.file.Files.writeString(dir.resolve("shared").resolve("Pragmas.ahk"),
                    ";@declare-private _*\n;@declare-type win as Gui, list as MyList\n");
            java.nio.file.Files.writeString(dir.resolve("other.ahk"), ";@declare-private x*\n");
            String code = "#Include shared\\Pragmas.ahk\n#Include other.ahk\nclass MyList {\n}\n"
                    + "a := obj._count\nb := obj.xValue\nwin := 1\nl := list\n";
            java.nio.file.Path main = java.nio.file.Files.writeString(dir.resolve("main.ahk"), code);
            var file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(main);
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir.resolve("shared").resolve("Pragmas.ahk"));
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir.resolve("other.ahk"));
            // annotators only run for files of the project (Falle 24): the folder becomes a content root for the test
            var root = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir);
            var entry = com.intellij.testFramework.PsiTestUtil.addContentRoot(myFixture.getModule(), root);
            com.intellij.openapi.util.Disposer.register(getTestRootDisposable(),
                    () -> com.intellij.testFramework.PsiTestUtil.removeContentEntry(myFixture.getModule(), entry.getFile()));
            myFixture.configureFromExistingVirtualFile(file);
            java.util.Map<String, TextAttributesKey> colors = new java.util.HashMap<>();
            for (HighlightInfo info : myFixture.doHighlighting()) {
                if (info.getSeverity() == HighlightSeverity.INFORMATION && info.forcedTextAttributesKey != null) {
                    colors.put(code.substring(info.getStartOffset(), info.getEndOffset()) + "@" + info.getStartOffset(),
                            info.forcedTextAttributesKey);
                }
            }
            assertEquals(AhkColors.AHK_PRIVATE_MEMBER, colors.get("_count@" + code.indexOf("_count")));
            assertNull("other.ahk is no pragma file", colors.get("xValue@" + code.indexOf("xValue")));
            assertEquals(AhkColors.AHK_DECLARED_TYPE, colors.get("win@" + code.indexOf("win :=")));
            // own class from the including file
            assertEquals(AhkColors.AHK_DECLARED_TYPE, colors.get("list@" + code.lastIndexOf("list")));
        } finally {
            com.intellij.openapi.util.io.FileUtil.delete(dir);
        }
    }

    /** "Non-ASCII characters" of the platform is suppressed for names, not for strings or other inspections. */
    public void testNonAsciiNamesAreAllowed() {
        myFixture.configureByText("a.ahk", "§count := \"Grüße\"\n");
        var suppressor = new de.kumpewo.autohotkey.annotation.AhkInspectionSuppressor();
        PsiElement name = myFixture.getFile().findElementAt(0);
        PsiElement string = myFixture.getFile().findElementAt(myFixture.getFile().getText().indexOf('"'));
        assertTrue(suppressor.isSuppressedFor(name, "NonAsciiCharacters"));
        assertTrue(suppressor.isSuppressedFor(name.getParent(), "NonAsciiCharacters"));
        assertFalse(suppressor.isSuppressedFor(string, "NonAsciiCharacters"));
        assertFalse(suppressor.isSuppressedFor(name, "SpellCheckingInspection"));
    }

    private void assertColor(TextAttributesKey expected, String codeWithMarker) {
        int offset = codeWithMarker.indexOf('§');
        assertTrue("marker missing", offset >= 0);
        myFixture.configureByText("a.ahk", codeWithMarker.replace("§", ""));
        TextAttributesKey actual = null;
        for (HighlightInfo info : myFixture.doHighlighting()) {
            // only colors (INFORMATION), not e.g. the hint "#Requires is missing" in the first line
            if (info.getStartOffset() == offset && info.getSeverity() == HighlightSeverity.INFORMATION
                    && info.forcedTextAttributesKey != null) {
                actual = info.forcedTextAttributesKey;
            }
        }
        assertEquals(codeWithMarker, expected, actual);
    }

    // ---------------------------------------------------------------- Labels

    public void testGotoLeadsToLabel() {
        myFixture.configureByText("a.ahk", "goto En<caret>de\nEnde:\n");
        assertLabel("Ende");
        myFixture.configureByText("a.ahk", "Ende:\ngoto(\"en<caret>de\")\n");
        assertLabel("Ende");
        myFixture.configureByText("a.ahk", "f() {\n    Aussen:\n    loop {\n        break Aus<caret>sen\n    }\n}\n");
        assertLabel("Aussen");
    }

    public void testLabelInOtherFunctionIsNoTarget() {
        myFixture.configureByText("a.ahk", "f() {\n    Ende:\n}\ng() {\n    goto En<caret>de\n}\n");
        PsiReference ref = myFixture.getFile().findReferenceAt(myFixture.getCaretOffset());
        assertNotNull(ref);
        assertNull(ref.resolve());
    }

    public void testLabelInStructureView() {
        myFixture.configureByText("a.ahk", "Start:\nf() {\n    Innen:\n}\n");
        StructureViewTreeElement root = new AhkStructureViewModel(myFixture.getFile()).getRoot();
        List<String> top = new ArrayList<>();
        for (TreeElement e : root.getChildren()) top.add(e.getPresentation().getPresentableText());
        assertEquals(List.of("Start:", "f()"), top);
        TreeElement f = root.getChildren()[1];
        assertEquals("Innen:", f.getChildren()[0].getPresentation().getPresentableText());
    }

    private void assertLabel(String name) {
        PsiReference ref = myFixture.getFile().findReferenceAt(myFixture.getCaretOffset());
        assertNotNull("no reference at the caret", ref);
        PsiElement target = ref.resolve();
        assertTrue(String.valueOf(target), target instanceof AhkLabel);
        assertEquals(name, ((AhkLabel) target).getName());
    }

    // ---------------------------------------------------------------- navigation

    public void testNavigationFreeStandingName() {
        assertEquals(List.of("foo()"), targets("x := f<caret>oo()\nfoo() {\n}\n"));
        assertEquals(List.of("foo()"), targets("Fo<caret>o 1\nfoo(x) {\n}\n"));
        assertEquals(List.of("foo()"), targets("f() {\n    g := fo<caret>o\n}\nfoo() => 1\n"));
        // function and class with the same name: chooser
        assertEquals(List.of("abc()", "Class Abc"), targets("x := Ab<caret>c()\nabc() {\n}\nclass Abc {\n}\n"));
        // methods and nested classes are no target for a free-standing name
        assertEquals(List.of(), targets("machW<caret>as()\nclass A {\n    machWas() {\n    }\n    class machWas {\n    }\n}\n"));
        assertEquals(List.of("Class A"), targets("x := <caret>A.machWas()\nclass A {\n    machWas() {\n    }\n}\n"));
    }

    public void testNavigationMember() {
        assertEquals(List.of("__New() in Class A", "__New() in Class B"), targets(
                "o.__N<caret>ew()\nclass A {\n    __New() {\n    }\n}\nclass B {\n    __New(x) {\n    }\n}\n"));
        assertEquals(List.of("Wert in Class A", "Class Wert in Class A"), targets(
                "o.we<caret>rt\nclass A {\n    Wert => 1\n    class Wert {\n    }\n}\nWert() {\n}\n"));
        assertEquals(List.of("Inhalt in Class A"), targets(
                "x := this.Inh<caret>alt\nclass A {\n    static Inhalt {\n        get => 1\n    }\n}\n"));
        // dynamic name after the dot: no reference
        myFixture.configureByText("a.ahk", "o.<caret>a%b%\n");
        assertNull(myFixture.getFile().findReferenceAt(myFixture.getCaretOffset()));
    }

    /** Fields of classes (count := 0, static count := 0) are members: navigation, completion, declared types. */
    public void testFieldsAreMembers() {
        String cls = "class Counter {\n    §count := 0\n    _step := 1, _max := 9\n    static _instances := 0\n"
                + "    Increment() {\n        x := this.";
        // completion after the dot: fields with non-ASCII and other names, also static ones
        List<String> list = suggestions(cls + "<caret>\n    }\n}\n");
        assertTrue(list.toString(), list.containsAll(List.of("§count", "_step", "_max", "_instances")));
        assertInserted(cls + "§co<caret>\n    }\n}\n", cls + "§count<caret>\n    }\n}\n");
        // a local variable in a method is no field
        assertFalse(suggestions("class A {\n    M() {\n        _local := 1\n        x := this._lo<caret>\n    }\n}\n")
                .contains("_local"));
        // declared own type: instance fields of the class, not static ones
        String typed = ";@declare-type c as Counter\nclass Counter {\n    _count := 0\n    static _instances := 0\n}\n";
        List<String> members = suggestions(typed + "c.<caret>\n");
        assertTrue(members.toString(), members.contains("_count"));
        assertFalse(members.toString(), members.contains("_instances"));
        // navigation: the field with its class as location, several classes = chooser
        assertEquals(List.of("_count in Class A", "_count in Class B"), targets(
                "o._co<caret>unt\nclass A {\n    _count := 0\n}\nclass B {\n    static _count := 1\n}\n"));
        myFixture.configureByText("a.ahk", "class A {\n    §count := 0\n}\nx := o.§co<caret>unt\n");
        PsiElement target = myFixture.getFile().findReferenceAt(myFixture.getCaretOffset()).resolve();
        assertNotNull(target);
        assertEquals(myFixture.getFile().getText().indexOf("§count"), target.getTextOffset());
        assertEquals("§count", target.getNavigationElement().getText());
    }

    public void testNavigationBaseClass() {
        assertEquals(List.of("Class AA"), targets("class B extends A<caret>a {\n}\nclass AA {\n    class Inner {\n    }\n}\n"));
        assertEquals(List.of("Class Inner in Class AA"), targets(
                "class B extends AA.In<caret>ner {\n}\nclass AA {\n    class Inner {\n    }\n}\nclass Inner {\n}\n"));
    }

    public void testNavigationBuiltinLeadsToHelp() {
        assertEquals(List.of("MsgBox (help)"), targets("Msg<caret>Box \"x\"\n"));
        assertEquals(List.of("msgbox (help)"), targets("x := msg<caret>box(\"x\")\n"));
        // an own definition takes precedence
        assertEquals(List.of("MsgBox()"), targets("MsgB<caret>ox \"x\"\nMsgBox(t) {\n}\n"));
    }

    public void testNavigationTargetIsTheName() {
        String text = "class A {\n    static mach() {\n    }\n    static Wert => 1\n}\nA.ma<caret>ch()\nA.We<caret>rt\n";
        myFixture.configureByText("a.ahk", text.replaceFirst("<caret>", ""));
        String plain = text.replace("<caret>", "");
        PsiElement target = myFixture.getFile().findReferenceAt(plain.indexOf("ach()\nA.W")).resolve();
        assertNotNull(target);
        assertEquals(plain.indexOf("mach("), target.getTextOffset());
        target = myFixture.getFile().findReferenceAt(plain.lastIndexOf("ert")).resolve();
        assertNotNull(target);
        assertEquals(plain.indexOf("Wert =>"), target.getTextOffset());
    }

    public void testCtrlBJumpsToTheName() {
        String text = "class A {\n    static mach() {\n    }\n}\nA.ma<caret>ch()\n";
        myFixture.configureByText("a.ahk", text);
        myFixture.performEditorAction(com.intellij.openapi.actionSystem.IdeActions.ACTION_GOTO_DECLARATION);
        assertEquals(text.indexOf("mach("), myFixture.getCaretOffset());
    }

    public void testGotoStaysWithLabel() {
        myFixture.configureByText("a.ahk", "goto En<caret>de\nEnde:\nEnde() {\n}\n");
        assertLabel("Ende");
    }

    // ---------------------------------------------------------------- open #Include

    public void testIncludeOpensFile() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("ahk").toRealPath();
        java.nio.file.Files.createDirectories(dir.resolve("sub"));
        java.nio.file.Files.createDirectories(dir.resolve("Lib"));
        java.nio.file.Path a = java.nio.file.Files.writeString(dir.resolve("sub").resolve("a.ahk"), "");
        java.nio.file.Path b = java.nio.file.Files.writeString(dir.resolve("sub").resolve("b.ahk"), "");
        java.nio.file.Path lib = java.nio.file.Files.writeString(dir.resolve("Lib").resolve("MyLib.ahk"), "");
        java.nio.file.Path c = java.nio.file.Files.writeString(dir.resolve("c.ahk"), "");
        java.nio.file.Path main = java.nio.file.Files.writeString(dir.resolve("main.ahk"), String.join("\n",
                "#Include \"sub\\a.ahk\" ; Kommentar",
                "#Include sub",
                "#include b.ahk",
                "#Include <MyLib_func>",
                "#Include %A_LineFile%\\..\\c.ahk",
                "#IncludeAgain '*i fehlt.ahk'",
                "#Include \"%A_ScriptDir%\"",
                "#Include %A_Unbekannt%\\x.ahk",
                "#Warn All", ""));
        myFixture.configureFromExistingVirtualFile(java.util.Objects.requireNonNull(
                com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(main)));
        List<de.kumpewo.autohotkey.psi.AhkDirective> directives = new ArrayList<>(
                com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(myFixture.getFile(),
                        de.kumpewo.autohotkey.psi.AhkDirective.class));
        assertEquals(9, directives.size());
        assertEquals(a.toString(), includeTarget(directives.get(0)));
        assertEquals("Verzeichnis " + dir.resolve("sub"), includeTarget(directives.get(1)));
        assertEquals(b.toString(), includeTarget(directives.get(2)));
        assertEquals(lib.toString(), includeTarget(directives.get(3)));
        assertEquals(c.toString(), includeTarget(directives.get(4)));
        assertNull(includeTarget(directives.get(5)));
        assertEquals("Verzeichnis " + dir, includeTarget(directives.get(6)));
        assertNull(includeTarget(directives.get(7)));
        assertNull(directives.get(8).getReference());
        // range of the reference: the argument without the comment
        assertEquals("\"sub\\a.ahk\"", directives.get(0).getReference().getRangeInElement()
                .substring(directives.get(0).getText()));
    }

    public void testCtrlBOnIncludeOpensFile() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("ahk").toRealPath();
        java.nio.file.Files.writeString(dir.resolve("teil.ahk"), "f() {\n}\n");
        java.nio.file.Path main = java.nio.file.Files.writeString(dir.resolve("main.ahk"), "#Include teil.ahk\n");
        myFixture.configureFromExistingVirtualFile(java.util.Objects.requireNonNull(
                com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(main)));
        myFixture.getEditor().getCaretModel().moveToOffset("#Include te".length());
        myFixture.performEditorAction(com.intellij.openapi.actionSystem.IdeActions.ACTION_GOTO_DECLARATION);
        com.intellij.openapi.vfs.VirtualFile[] selected =
                com.intellij.openapi.fileEditor.FileEditorManager.getInstance(getProject()).getSelectedFiles();
        assertEquals("teil.ahk", selected[0].getName());
    }

    public void testIncludeLibInSubdirectory() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("ahk").toRealPath();
        java.nio.file.Files.createDirectories(dir.resolve("Lib").resolve("v2"));
        java.nio.file.Path teil = java.nio.file.Files.writeString(dir.resolve("Lib").resolve("v2").resolve("Teil.ahk"), "");
        java.nio.file.Path main = java.nio.file.Files.writeString(dir.resolve("main.ahk"), "#Include <v2\\Teil>\n");
        myFixture.configureFromExistingVirtualFile(java.util.Objects.requireNonNull(
                com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(main)));
        de.kumpewo.autohotkey.psi.AhkDirective directive = com.intellij.psi.util.PsiTreeUtil.findChildOfType(
                myFixture.getFile(), de.kumpewo.autohotkey.psi.AhkDirective.class);
        assertEquals(teil.toString(), includeTarget(directive));
    }

    /** User Lib folder = Documents\AutoHotkey\Lib (FileSystemView returned no path here). */
    public void testIncludeUserLibFolder() {
        List<java.nio.file.Path> dirs = de.kumpewo.autohotkey.include.AhkInclude.libraryDirectories(
                java.nio.file.Path.of("C:\\x\\a.ahk"));
        assertEquals(java.nio.file.Path.of("C:\\x\\Lib"), dirs.get(0));
        java.nio.file.Path user = dirs.get(1);
        assertTrue(user.toString(), user.endsWith(java.nio.file.Path.of("AutoHotkey", "Lib")));
        assertTrue(user.toString(), java.nio.file.Files.isDirectory(user.getParent().getParent()));
    }

    /** Target of the #Include: path of the file, "Verzeichnis …" (directory) or null. */
    /**
     * Ctrl+B searches in the file itself and in the files included directly with #Include, not recursively; own
     * file first, then in the order of the #Include lines.
     */
    public void testNavigationInIncludedFiles() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("ahk").toRealPath();
        java.nio.file.Files.writeString(dir.resolve("a.ahk"), String.join("\n",
                "#Include b.ahk", "Tuwas() {", "}", "class K {", "    Zeige() {", "    }", "}", ""));
        java.nio.file.Files.writeString(dir.resolve("b.ahk"), "Tief() {\n}\n");
        java.nio.file.Files.writeString(dir.resolve("c.ahk"), "Tuwas() {\n}\n");
        java.nio.file.Path main = java.nio.file.Files.writeString(dir.resolve("main.ahk"), String.join("\n",
                "#Include a.ahk", "#Include c.ahk", "#Include a.ahk", "Tuwas() {", "}",
                "Tuwas()", "x := K()", "x.Zeige()", "Tief()", "class Mein extends K {", "}", ""));
        // bring all files into the VFS (includedFiles itself does no refresh)
        for (String name : List.of("a.ahk", "b.ahk", "c.ahk")) {
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir.resolve(name));
        }
        myFixture.configureFromExistingVirtualFile(java.util.Objects.requireNonNull(
                com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(main)));

        assertEquals(List.of("main.ahk:4", "a.ahk:2", "c.ahk:1"), targetsIn("Tuwas()\n", "Tuwas"));
        assertEquals(List.of("a.ahk:4"), targetsIn("x := K()", "K"));
        assertEquals(List.of("a.ahk:5"), targetsIn("x.Zeige()", "Zeige"));
        assertEquals(List.of("a.ahk:4"), targetsIn("extends K", "K"));
        // b.ahk is only included via a.ahk: not recursive, so no target
        assertEquals(List.of(), targetsIn("Tief()", "Tief"));
    }

    /** Completion also offers functions, classes and members of the directly included files. */
    public void testCompletionFromIncludedFiles() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("ahk").toRealPath();
        java.nio.file.Files.writeString(dir.resolve("a.ahk"), String.join("\n", "#Include b.ahk",
                "LibFunktion() {", "}", "class LibKlasse {", "    LibMethode() {", "    }", "}", ""));
        java.nio.file.Files.writeString(dir.resolve("b.ahk"), "LibTief() {\n}\n");
        for (String name : List.of("a.ahk", "b.ahk")) {
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir.resolve(name));
        }
        List<String> frei = suggestionsInFile(dir, "#Include a.ahk\nx := Lib<caret>\n");
        assertTrue(frei.toString(), frei.containsAll(List.of("LibFunktion", "LibKlasse")));
        // b.ahk is only included via a.ahk: not recursive
        assertFalse(frei.toString(), frei.contains("LibTief"));
        // without #Include nothing from a.ahk
        assertFalse(suggestionsInFile(dir, "x := Lib<caret>\n").contains("LibFunktion"));
        assertEquals("#Include a.ahk\nx := obj.LibMethode()\n",
                insertedInFile(dir, "#Include a.ahk\nx := obj.LibMe<caret>\n"));
    }

    public void testFindUsages() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("ahk").toRealPath();
        java.nio.file.Files.writeString(dir.resolve("lib.ahk"), String.join("\n",
                "Helper() {",
                "}",
                "class Box {",
                "    count := 0",
                "    Open() {",
                "        this.count += 1",
                "    }",
                "}",
                "Helper()",
                ""));
        java.nio.file.Files.writeString(dir.resolve("main.ahk"), String.join("\n",
                "#Include lib.ahk",
                "HELPER()",
                "b := Box()",
                "b.open()",
                "x := b.Count",
                "helper := 1",   // name-based like Ctrl+B: a free-standing name refers to the function
                ""));
        // does not include lib.ahk: its calls don't count
        java.nio.file.Files.writeString(dir.resolve("other.ahk"), "Helper()\nb.Open()\n");
        var root = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir);
        var entry = com.intellij.testFramework.PsiTestUtil.addContentRoot(myFixture.getModule(), root);
        com.intellij.openapi.util.Disposer.register(getTestRootDisposable(),
                () -> com.intellij.testFramework.PsiTestUtil.removeContentEntry(myFixture.getModule(), entry.getFile()));
        com.intellij.psi.PsiFile lib = com.intellij.psi.PsiManager.getInstance(getProject()).findFile(
                java.util.Objects.requireNonNull(root.findChild("lib.ahk")));
        assertNotNull(lib);
        java.util.function.Function<com.intellij.psi.PsiElement, List<String>> usages = target -> {
            List<String> out = new ArrayList<>();
            for (com.intellij.usageView.UsageInfo usage : myFixture.findUsages(target)) {
                com.intellij.psi.PsiFile file = usage.getFile();
                com.intellij.openapi.editor.Document document = com.intellij.psi.PsiDocumentManager
                        .getInstance(getProject()).getDocument(java.util.Objects.requireNonNull(file));
                int offset = java.util.Objects.requireNonNull(usage.getSegment()).getStartOffset();
                out.add(file.getName() + ":" + (java.util.Objects.requireNonNull(document).getLineNumber(offset) + 1));
            }
            java.util.Collections.sort(out);
            return out;
        };
        var funcs = new ArrayList<>(com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(lib, de.kumpewo.autohotkey.psi.AhkFunc.class));
        de.kumpewo.autohotkey.psi.AhkClass box = com.intellij.psi.util.PsiTreeUtil.findChildOfType(lib, de.kumpewo.autohotkey.psi.AhkClass.class);
        // function: in its own file and in main.ahk (includes lib.ahk), any case; not in other.ahk
        assertEquals(List.of("lib.ahk:9", "main.ahk:2", "main.ahk:6"), usages.apply(funcs.get(0)));
        // method and class
        assertEquals(List.of("main.ahk:4"), usages.apply(funcs.get(1)));
        assertEquals(List.of("main.ahk:3"), usages.apply(box));
        // field: the target object that navigation returns for b.Count
        com.intellij.psi.PsiFile main = com.intellij.psi.PsiManager.getInstance(getProject()).findFile(
                java.util.Objects.requireNonNull(root.findChild("main.ahk")));
        com.intellij.psi.PsiReference countRef = java.util.Objects.requireNonNull(main)
                .findReferenceAt(main.getText().indexOf("Count") + 1);
        com.intellij.psi.PsiElement field = ((com.intellij.psi.PsiPolyVariantReference)
                java.util.Objects.requireNonNull(countRef)).multiResolve(false)[0].getElement();
        assertTrue(String.valueOf(field), field instanceof de.kumpewo.autohotkey.navigation.AhkFieldTarget);
        assertEquals(List.of("lib.ahk:6", "main.ahk:5"), usages.apply(field));
    }

    /** Files for the rename tests in a new content root: lib.ahk, main.ahk (includes it), top.ahk, other.ahk. */
    private com.intellij.psi.PsiFile renameFixture() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("ahk").toRealPath();
        java.nio.file.Files.writeString(dir.resolve("lib.ahk"), String.join("\n",
                "Helper() {",                 // 1
                "}",                          // 2
                "class Box {",                // 3
                "    count := 0",             // 4
                "    Open() {",               // 5
                "        this.count += 1",    // 6
                "    }",                      // 7
                "}",                          // 8
                "Helper()",                   // 9
                "; calls Helper twice",       // 10
                "s := \"call Helper\"",       // 11
                "Retry:",                     // 12
                "goto Retry",                 // 13
                ""));
        java.nio.file.Files.writeString(dir.resolve("main.ahk"),
                "#Include lib.ahk\nHELPER()\nb := Box()\nb.open()\nx := b.Count\n");
        // includes lib.ahk only through main.ahk
        java.nio.file.Files.writeString(dir.resolve("top.ahk"), "#Include main.ahk\nHelper()\n");
        java.nio.file.Files.writeString(dir.resolve("other.ahk"), "Helper()\n");
        var root = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir);
        var entry = com.intellij.testFramework.PsiTestUtil.addContentRoot(myFixture.getModule(), root);
        com.intellij.openapi.util.Disposer.register(getTestRootDisposable(),
                () -> com.intellij.testFramework.PsiTestUtil.removeContentEntry(myFixture.getModule(), entry.getFile()));
        return com.intellij.psi.PsiManager.getInstance(getProject()).findFile(
                java.util.Objects.requireNonNull(java.util.Objects.requireNonNull(root).findChild("lib.ahk")));
    }

    /**
     * Text of a file of the rename fixture after the rename, from the PSI: a file without a loaded document is changed
     * in the PSI only, a document loaded afterwards would still show the text on disk.
     */
    private String renamedText(com.intellij.psi.PsiFile lib, String name) {
        com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        var file = java.util.Objects.requireNonNull(lib.getContainingDirectory().getVirtualFile().findChild(name));
        return java.util.Objects.requireNonNull(com.intellij.psi.PsiManager.getInstance(getProject()).findFile(file)).getText();
    }

    /** The function, method or class with this name in the file. */
    private static com.intellij.psi.PsiElement definitionNamed(com.intellij.psi.PsiFile file, String name) {
        for (de.kumpewo.autohotkey.psi.AhkNamedElement element : com.intellij.psi.util.PsiTreeUtil
                .findChildrenOfType(file, de.kumpewo.autohotkey.psi.AhkNamedElement.class)) {
            if (name.equals(element.getName())) return element;
        }
        throw new AssertionError("No definition " + name);
    }

    public void testRenameFunction() throws Exception {
        com.intellij.psi.PsiFile lib = renameFixture();
        myFixture.renameElement(definitionNamed(lib, "Helper"), "Assist", false, false);
        // definition and calls in any case, also in top.ahk (includes lib.ahk indirectly); not in other.ahk
        String text = renamedText(lib, "lib.ahk");
        assertTrue(text, text.startsWith("Assist() {\n"));
        assertTrue(text, text.contains("\nAssist()\n; calls Helper twice\ns := \"call Helper\"\n"));
        assertEquals("#Include lib.ahk\nAssist()\nb := Box()\nb.open()\nx := b.Count\n", renamedText(lib, "main.ahk"));
        assertEquals("#Include main.ahk\nAssist()\n", renamedText(lib, "top.ahk"));
        assertEquals("Helper()\n", renamedText(lib, "other.ahk"));
    }

    public void testRenameInCommentsAndStrings() throws Exception {
        com.intellij.psi.PsiFile lib = renameFixture();
        myFixture.renameElement(definitionNamed(lib, "Helper"), "Assist", true, false);
        String text = renamedText(lib, "lib.ahk");
        assertTrue(text, text.contains("\nAssist()\n; calls Assist twice\ns := \"call Assist\"\n"));
    }

    public void testRenameMembersAndLabels() throws Exception {
        com.intellij.psi.PsiFile lib = renameFixture();
        myFixture.renameElement(definitionNamed(lib, "Open"), "Launch", false, false);
        myFixture.renameElement(definitionNamed(lib, "Retry"), "Again", false, false);
        // field: the target object that navigation returns for b.Count
        com.intellij.psi.PsiFile main = com.intellij.psi.PsiManager.getInstance(getProject()).findFile(
                java.util.Objects.requireNonNull(lib.getContainingDirectory().getVirtualFile().findChild("main.ahk")));
        com.intellij.psi.PsiReference countRef = java.util.Objects.requireNonNull(main)
                .findReferenceAt(main.getText().indexOf("Count") + 1);
        com.intellij.psi.PsiElement field = ((com.intellij.psi.PsiPolyVariantReference)
                java.util.Objects.requireNonNull(countRef)).multiResolve(false)[0].getElement();
        myFixture.renameElement(java.util.Objects.requireNonNull(field), "amount", false, false);
        String text = renamedText(lib, "lib.ahk");
        assertTrue(text, text.contains("    amount := 0\n    Launch() {\n        this.amount += 1\n"));
        assertTrue(text, text.endsWith("Again:\ngoto Again\n"));
        assertEquals("#Include lib.ahk\nHELPER()\nb := Box()\nb.Launch()\nx := b.amount\n", renamedText(lib, "main.ahk"));
    }

    public void testRenameConflictsAndNames() throws Exception {
        com.intellij.psi.PsiFile lib = renameFixture();
        try {
            myFixture.renameElement(definitionNamed(lib, "Helper"), "box", false, false);
            fail("conflict expected");
        } catch (com.intellij.refactoring.BaseRefactoringProcessor.ConflictsInTestsException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("'box' already exists in lib.ahk"));
        }
        var validator = new de.kumpewo.autohotkey.navigation.AhkNamesValidator();
        assertTrue(validator.isIdentifier("Assist_2", getProject()));
        assertTrue(validator.isIdentifier("§name", getProject()));
        assertFalse(validator.isIdentifier("2nd", getProject()));
        assertFalse(validator.isIdentifier("if", getProject()));
        assertFalse(validator.isIdentifier("this", getProject()));
        assertFalse(validator.isIdentifier("a-b", getProject()));
        assertTrue(validator.isKeyword("Return", getProject()));
    }

    public void testSpellChecking() {
        // the inspection itself comes from the bundled Grazie plugin, which the tests don't load: check which text
        // the strategy passes to the spell checker, and the dictionary of built-in names
        myFixture.configureByText("a.ahk", String.join("\n",
                "; A comment about MsgBox",
                ";@declare-type myGui as Gui",
                "x := \"Line one`nline two\"",
                "::btw::by the way",
                ":X:zzq::Code()",
                "MyFunc() {",
                "}",
                ""));
        de.kumpewo.autohotkey.spellchecker.AhkSpellcheckingStrategy strategy =
                new de.kumpewo.autohotkey.spellchecker.AhkSpellcheckingStrategy();
        List<String> checked = new ArrayList<>();
        com.intellij.spellchecker.tokenizer.TokenConsumer consumer = new com.intellij.spellchecker.tokenizer.TokenConsumer() {
            @Override
            public void consumeToken(com.intellij.psi.PsiElement element, String text, boolean useRename, int offset,
                                     com.intellij.openapi.util.TextRange range,
                                     com.intellij.spellchecker.inspections.Splitter splitter) {
                checked.add(range.substring(text).trim());
            }
        };
        for (com.intellij.psi.PsiElement leaf = com.intellij.psi.util.PsiTreeUtil.firstChild(myFixture.getFile());
             leaf != null; leaf = com.intellij.psi.util.PsiTreeUtil.nextLeaf(leaf)) {
            //noinspection unchecked
            strategy.getTokenizer(leaf).tokenize(leaf, consumer);
        }
        // comment (not the pragma), string without quotes and escape sequence, hotstring text (not with X); no names
        assertEquals(List.of("; A comment about MsgBox", "Line one", "line two", "by the way"), checked);

        com.intellij.spellchecker.dictionary.Dictionary dictionary =
                new de.kumpewo.autohotkey.spellchecker.AhkDictionaryProvider().getDictionaries()[0];
        for (String word : List.of("MsgBox", "msg", "LoopField", "hotkeys", "AutoHotkey", "Gui", "NumpadEnter")) {
            assertEquals(word, com.intellij.spellchecker.dictionary.Dictionary.LookupStatus.Present, dictionary.lookup(word));
        }
        assertEquals(com.intellij.spellchecker.dictionary.Dictionary.LookupStatus.Absent, dictionary.lookup("speling"));
    }

    public void testCaseModeFileValuesDoNotDependOnTheBundle() {
        // while the IDE builds the searchable options, bundle texts carry markers: the values of
        // keyword-case.txt must be recognized without the bundle (buildSearchableOptions failed before)
        de.kumpewo.autohotkey.formatter.AhkCaseMode[] modes = de.kumpewo.autohotkey.formatter.AhkCaseMode.values();
        assertEquals(java.util.Arrays.asList(modes), java.util.Arrays.asList(
                de.kumpewo.autohotkey.formatter.AhkCaseMode.parse("as in list"),
                de.kumpewo.autohotkey.formatter.AhkCaseMode.parse("Lowercase"),
                de.kumpewo.autohotkey.formatter.AhkCaseMode.parse("keep")));
        assertEquals(de.kumpewo.autohotkey.formatter.AhkCaseMode.LOWER,
                de.kumpewo.autohotkey.formatter.AhkCaseMode.parse("klein"));
    }

    public void testFolding() {
        myFixture.configureByText("a.ahk", String.join("\n",
                "#Include a.ahk",                  // 1
                "#Include b.ahk",                  // 2
                "",
                "; first comment",                 // 4
                "; second comment",                // 5
                "class Foo",                       // 6
                "{",                               // 7
                "    Bar() {",                     // 8
                "        x := {a: 1,",             // 9
                "            b: 2}",               // 10
                "    }",                           // 11
                "}",                               // 12
                "/* block",                        // 13
                "   comment */",                   // 14
                "s := \"",                         // 15
                "(",                               // 16
                "text",                            // 17
                ")\"",                             // 18
                ";region Helpers",                 // 19
                "f() {",                           // 20
                "}",                               // 21
                ";endregion",                      // 22
                ";{",                              // 23
                "g() => 1",                        // 24
                ";}",                              // 25
                "if (x) {",                        // 26
                "}",                               // 27
                "y := 1 ; trailing",               // 28
                "; single",                        // 29
                "z := {a: 1}",                     // 30
                ""));
        com.intellij.openapi.editor.Document document = myFixture.getEditor().getDocument();
        List<String> folds = new ArrayList<>();
        com.intellij.lang.folding.FoldingDescriptor[] descriptors = new de.kumpewo.autohotkey.structure.AhkFoldingBuilder()
                .buildFoldRegions(myFixture.getFile(), document, false);
        java.util.Arrays.sort(descriptors, java.util.Comparator.comparingInt(d -> d.getRange().getStartOffset()));
        for (com.intellij.lang.folding.FoldingDescriptor d : descriptors) {
            folds.add(d.getPlaceholderText() + " " + (document.getLineNumber(d.getRange().getStartOffset()) + 1)
                    + "-" + (document.getLineNumber(d.getRange().getEndOffset()) + 1));
        }
        assertEquals(List.of(
                "#Include ... 1-2",
                "; first comment ... 4-5",
                "{...} 7-12",
                "{...} 8-11",
                "{...} 9-10",
                "/*...*/ 13-14",
                "\"(...)\" 15-18",
                "Helpers 19-22",
                "{...} 20-21",
                "... 23-25",
                "{...} 26-27"), folds);
    }

    public void testGotoSymbol() {
        myFixture.addFileToProject("lib.ahk", String.join("\n",
                "Helper(a) {",
                "}",
                "class Outer {",
                "    count := 0",
                "    static total := 1",
                "    Open() {",
                "    }",
                "    Size => 1",
                "    class Inner {",
                "        Open() {",
                "        }",
                "    }",
                "}",
                "F1::MsgBox 1",
                "^!a up::{",
                "}",
                ":X:btw::Send 'x'",
                "::sig::Kind regards",
                "Start:",
                "",
                ""));
        myFixture.addFileToProject("sub/other.ahk", "helper() {\n}\n");
        myFixture.addFileToProject("main.~validate.ahk", "Copied() {\n}\n");
        // project files only: "Include non-project items" (all scope) adds the AutoHotkey Lib of the machine
        var scope = com.intellij.psi.search.GlobalSearchScope.projectScope(getProject());
        var symbols = new AhkGotoSymbolContributorAccess();
        java.util.Set<String> names = new java.util.TreeSet<>();
        symbols.contributor.processNames(n -> names.add(n), scope, null);
        assertTrue(names.containsAll(List.of("Helper", "helper", "Outer", "Inner", "Open", "Size", "count", "total",
                "F1", "^!a up", "btw", "sig", "Start")));
        assertFalse(names.contains("Copied")); // validation copy
        assertEquals(List.of("Outer.Open() lib.ahk", "Outer.Inner.Open() lib.ahk"), symbols.items("Open"));
        assertEquals(List.of("Helper() lib.ahk"), symbols.items("Helper"));
        assertEquals(List.of("helper() sub\\other.ahk"), symbols.items("helper"));
        assertEquals(List.of("Outer.count lib.ahk"), symbols.items("count"));
        assertEquals(List.of("Outer.total lib.ahk"), symbols.items("total"));
        assertEquals(List.of("Outer.Size lib.ahk"), symbols.items("Size"));
        assertEquals(List.of("Outer.Inner lib.ahk"), symbols.items("Inner"));
        assertEquals(List.of("F1:: lib.ahk"), symbols.items("F1"));
        assertEquals(List.of("^!a up:: lib.ahk"), symbols.items("^!a up"));
        assertEquals(List.of(":X:btw:: lib.ahk"), symbols.items("btw"));
        assertEquals(List.of("::sig:: lib.ahk"), symbols.items("sig"));
        assertEquals(List.of("Start: lib.ahk"), symbols.items("Start"));
        assertEquals("Outer.Inner.Open", symbols.contributor.getQualifiedName(symbols.found("Open").get(1)));
        // the entry navigates to the name; a field stands for its own target (Find Usages works on it)
        var open = (de.kumpewo.autohotkey.navigation.AhkSymbolItem) symbols.found("Open").get(0);
        assertEquals("Open", open.getTargetElement().getContainingFile().findElementAt(open.getTargetElement().getTextOffset()).getText());
        assertTrue(((de.kumpewo.autohotkey.navigation.AhkSymbolItem) symbols.found("count").get(0)).getTargetElement()
                instanceof de.kumpewo.autohotkey.navigation.AhkFieldTarget);
        // Go to Class: only classes, also nested ones, found by the qualified name
        var classes = new de.kumpewo.autohotkey.navigation.AhkGotoClassContributor();
        List<String> found = new ArrayList<>();
        for (String name : List.of("Outer", "Inner", "Open")) {
            classes.processElementsWithName(name, item -> found.add(item.getPresentation().getPresentableText()),
                    new com.intellij.util.indexing.FindSymbolParameters(name, name, scope));
        }
        assertEquals(List.of("Outer", "Outer.Inner"), found);
        List<String> results = new ArrayList<>();
        for (Object item : myFixture.getGotoClassResults("Outer.Inner", false, null)) results.add(item.toString());
        assertEquals(List.of("Outer.Inner"), results);
    }

    /** Calls the Go to Symbol contributor for one name (test helper of {@link #testGotoSymbol}). */
    private final class AhkGotoSymbolContributorAccess {
        final de.kumpewo.autohotkey.navigation.AhkGotoSymbolContributor contributor =
                new de.kumpewo.autohotkey.navigation.AhkGotoSymbolContributor();

        /** Entries with this name. */
        List<com.intellij.navigation.NavigationItem> found(String name) {
            List<com.intellij.navigation.NavigationItem> result = new ArrayList<>();
            contributor.processElementsWithName(name, result::add, new com.intellij.util.indexing.FindSymbolParameters(
                    name, name, com.intellij.psi.search.GlobalSearchScope.projectScope(getProject())));
            return result;
        }

        /** "text location" of the entries with this name. */
        List<String> items(String name) {
            List<String> result = new ArrayList<>();
            for (var item : found(name)) {
                var presentation = java.util.Objects.requireNonNull(item.getPresentation());
                result.add(presentation.getPresentableText() + " " + presentation.getLocationString());
            }
            return result;
        }
    }

    /** Sample with doc comments (Z.22). */
    private static final String DOC_SAMPLE = String.join("\n",
            "/**",
            " * Opens the **box**.",
            " *",
            " * Second paragraph.",
            " * @param {Gui|Menu} name  name of the box,",
            " *     continued",
            " * @param force (optional) open even if locked",
            " * @returns {Integer} the new count",
            " * @throws Error if the box is missing",
            " * @example",
            " *   b := Box()",
            " *   b.Open(\"x\")",
            " * @see Helper",
            " * @see MsgBox shows a message",
            " * @see https://example.com/doc",
            " */",
            "Open(name, force := false) {",
            "    return 1",
            "}",
            "/** Helps. */",
            "Helper() => 1",
            "/* not a doc comment */",
            "Plain() {",
            "}",
            "/** separated by an empty line */",
            "",
            "Separated() {",
            "}",
            "/**",
            " * A box.",
            " * @deprecated use NewBox",
            " */",
            "class Box extends Array {",
            "    /** Opens it. */",
            "    Open(a) {",
            "    }",
            "}",
            "");

    public void testDocCommentParsing() {
        myFixture.configureByText("a.ahk", DOC_SAMPLE);
        java.util.Map<String, de.kumpewo.autohotkey.documentation.AhkDocComment> docs = new java.util.HashMap<>();
        for (PsiElement d : com.intellij.psi.util.PsiTreeUtil.findChildrenOfAnyType(myFixture.getFile(),
                de.kumpewo.autohotkey.psi.AhkFunc.class, de.kumpewo.autohotkey.psi.AhkClass.class)) {
            String key = (d instanceof de.kumpewo.autohotkey.psi.AhkClass ? "class " : "")
                    + ((com.intellij.psi.PsiNamedElement) d).getName() + "@" + d.getTextOffset();
            docs.put(key.replaceAll("@\\d+", "") + (docs.containsKey(key.replaceAll("@\\d+", "")) ? "2" : ""),
                    de.kumpewo.autohotkey.documentation.AhkDocComment.of(d));
        }
        var open = docs.get("Open");
        assertNotNull(open);
        assertEquals("Opens the **box**.\n\nSecond paragraph.", open.description());
        assertEquals(2, open.params().size());
        var name = open.params().get(0);
        assertEquals("name", name.name());
        assertEquals(List.of("Gui", "Menu"), name.types().stream().map(de.kumpewo.autohotkey.documentation.AhkDocComment.Type::name).toList());
        assertEquals("name of the box,\ncontinued", name.text());
        assertEquals("name", name.nameRange().substring(myFixture.getFile().getText()));
        assertEquals("Gui", name.types().get(0).range().substring(myFixture.getFile().getText()));
        assertEquals("(optional) open even if locked", open.param("FORCE").text());
        assertEquals("Integer", open.returns().types().get(0).name());
        assertEquals("the new count", open.returns().text());
        assertEquals(List.of("Error if the box is missing"), open.throwsList());
        assertEquals(List.of("  b := Box()\n  b.Open(\"x\")"), open.examples());
        assertEquals(List.of("Helper", "MsgBox shows a message", "https://example.com/doc"), open.sees());
        assertNull(open.deprecated());
        assertEquals("Helps.", docs.get("Helper").description());
        assertNull(docs.get("Plain"));       // /* ... */ is no doc comment
        assertNull(docs.get("Separated"));   // empty line in between
        assertEquals("use NewBox", docs.get("class Box").deprecated());
        assertEquals("Opens it.", docs.get("Open2").description());
    }

    public void testDocCommentQuickDoc() {
        de.kumpewo.autohotkey.documentation.AhkDocumentationProvider provider =
                new de.kumpewo.autohotkey.documentation.AhkDocumentationProvider();
        java.util.function.Function<String, String> doc = text -> {
            myFixture.configureByText("a.ahk", text);
            PsiElement leaf = myFixture.getFile().findElementAt(myFixture.getCaretOffset());
            return provider.generateDoc(leaf, leaf);
        };
        String open = doc.apply(DOC_SAMPLE + "x := Op<caret>en(1)\n");
        assertNotNull(open);
        assertTrue(open, open.contains("Open(name, [force := false]) → Integer"));
        assertTrue(open, open.contains("<b>box</b>") || open.contains("<strong>box</strong>"));
        assertTrue(open, open.contains("Second paragraph."));
        assertTrue(open, open.contains("Parameters:") && open.contains("<code>name</code> (Gui|Menu)"));
        // the description continues the line of the parameter (no paragraph of its own)
        assertTrue(open, open.contains("(Gui|Menu) – name of the box"));
        assertTrue(open, open.contains("Returns:") && open.contains("the new count"));
        assertTrue(open, open.contains("Throws:") && open.contains("Example:") && open.contains("b.Open(&quot;x&quot;)"));
        assertTrue(open, open.contains("File:") && open.contains("a.ahk"));
        // @see: own function, help page, url
        assertTrue(open, open.contains("psi_element://ahkdef:"));
        assertTrue(open, open.contains("psi_element://docs/lib/msgbox.htm"));
        assertTrue(open, open.contains("<a href=\"https://example.com/doc\">"));
        // the definition itself, without doc comment only the signature
        assertTrue(doc.apply(DOC_SAMPLE.replace("Plain() {", "Pla<caret>in() {")).contains("Plain()"));
        // method after the dot: several definitions (two Open, one function, one method) -> only the method
        String method = doc.apply(DOC_SAMPLE + "b := Box()\nb.Op<caret>en(1)\n");
        assertTrue(method, method.contains("Box.Open(a)") && method.contains("Opens it."));
        // class: signature with base class and deprecated section
        String cls = doc.apply(DOC_SAMPLE + "b := Bo<caret>x()\n");
        assertTrue(cls, cls.contains("class Box extends Array") && cls.contains("Deprecated:") && cls.contains("use NewBox"));
        // several matching definitions: overview with links that lead to the definitions
        String overview = doc.apply("/** One. */\nclass A {\n    Run() {\n    }\n}\n/** Two. */\nclass B {\n"
                + "    Run() {\n    }\n}\nx.R<caret>un()\n");
        assertTrue(overview, overview.contains("A.Run()") && overview.contains("B.Run()"));
        java.util.regex.Matcher link = java.util.regex.Pattern.compile("psi_element://(ahkdef:[^\"]+)\"").matcher(overview);
        assertTrue(overview, link.find());
        PsiElement target = provider.getDocumentationElementForLink(getPsiManager(), link.group(1), myFixture.getFile());
        assertNotNull(target);
        assertTrue(provider.generateDoc(target, null).contains("A.Run()"));
        // built-in names keep their help page; an own definition goes first
        assertTrue(doc.apply("x := StrL<caret>en(\"a\")\n").contains("StrLen"));
        String own = doc.apply("/** Mine. */\nStrLen(s) {\n}\nx := StrL<caret>en(\"a\")\n");
        assertTrue(own, own.contains("Mine.") && own.contains("StrLen(s)"));
        // Ctrl+hover: the signature
        myFixture.configureByText("a.ahk", DOC_SAMPLE + "Helper<caret>()\n");
        PsiElement leaf = myFixture.getFile().findElementAt(myFixture.getCaretOffset() - 1);
        assertEquals("Helper()", provider.getQuickNavigateInfo(leaf, leaf));
    }

    public void testDocCommentInCompletionAndParameterInfo() {
        // Ctrl+Q in the completion list: the lookup object is the definition
        de.kumpewo.autohotkey.documentation.AhkDocumentationProvider provider =
                new de.kumpewo.autohotkey.documentation.AhkDocumentationProvider();
        myFixture.configureByText("a.ahk", DOC_SAMPLE + "x := O<caret>\n");
        com.intellij.codeInsight.lookup.LookupElement[] items = myFixture.completeBasic();
        assertNotNull(items);
        PsiElement found = null;
        for (var item : items) {
            if (item.getLookupString().equals("Open")) {
                found = provider.getDocumentationElementForLookupItem(getPsiManager(), item.getObject(), null);
            }
        }
        assertTrue(found instanceof de.kumpewo.autohotkey.psi.AhkFunc);
        // parameter info: the @param text of the current parameter
        myFixture.configureByText("a.ahk", DOC_SAMPLE);
        var signature = de.kumpewo.autohotkey.completion.AhkCommandInfo.signatures(myFixture.getFile(), "Open").get(0);
        assertEquals("name of the box,\ncontinued", signature.parameters().get(0).description());
        assertEquals("(optional) open even if locked",
                de.kumpewo.autohotkey.completion.AhkParameterInfoHandler.currentDescription(signature.parameters(), 1));
        assertNull(de.kumpewo.autohotkey.completion.AhkParameterInfoHandler.currentDescription(signature.parameters(), 2));
    }

    public void testDocCommentParameterTypes() {
        String code = "/**\n * @param {Gui} g the window\n */\nShow(g, h) {\n    g.<caret>\n}\n";
        List<String> inside = suggestions(code);
        assertTrue(inside.toString(), inside.contains("AddButton"));
        assertFalse(inside.toString(), inside.contains("Push"));   // only the members of Gui
        assertTrue(suggestions(code.replace("g.<caret>", "h.<caret>")).contains("Push"));   // h: not declared
        assertTrue(suggestions(code + "x := g.<caret>\n").contains("Push"));    // outside of the function
        // an own class as type
        assertTrue(suggestions("class Box {\n    Lift() {\n    }\n}\n" + code.replace("{Gui}", "{Box}")).contains("Lift"));
        // color of the declared parameter
        myFixture.configureByText("a.ahk", code.replace("g.<caret>", "g.Show()"));
        boolean colored = false;
        for (var info : myFixture.doHighlighting()) {
            if (info.forcedTextAttributesKey == de.kumpewo.autohotkey.highlighting.AhkColors.AHK_DECLARED_TYPE
                    && "g".equals(myFixture.getFile().getText().substring(info.getStartOffset(), info.getEndOffset()))) colored = true;
        }
        assertTrue(colored);
    }

    public void testDocCommentWarnings() {
        myFixture.configureByText("a.ahk", String.join("\n",
                "/**",
                " * @param <warning descr=\"Unknown parameter 'x'.\">x</warning> nothing",
                " * @param {<warning descr=\"Unknown class.\">Gux</warning>|Gui} a",
                " * @returns {<warning descr=\"Unknown class.\">Nope</warning>}",
                " */",
                "F(a) {",
                "}",
                "/**",
                " * @deprecated use New",
                " */",
                "Old() {",
                "}",
                "/** @deprecated */",
                "class Gone {",
                "}",
                "<warning descr=\"'Old' is deprecated: use New\">Old</warning>()",
                "g := <warning descr=\"'Gone' is deprecated.\">Gone</warning>()",
                "F(1)",
                ""));
        myFixture.checkHighlighting(true, false, false);
        // only if all matching definitions are deprecated
        myFixture.configureByText("a.ahk", "class A {\n    /** @deprecated */\n    Run() {\n    }\n}\n"
                + "class B {\n    Run() {\n    }\n}\nx.Run()\n");
        myFixture.checkHighlighting(true, false, false);
    }

    /** Text of the editor with "<caret>" at the caret. */
    private String textWithCaret() {
        String text = myFixture.getEditor().getDocument().getText();
        int caret = myFixture.getCaretOffset();
        return text.substring(0, caret) + "<caret>" + text.substring(caret);
    }

    /** Text after typing Enter at the caret (test helper of the doc comment skeleton). */
    private String afterEnter(String text) {
        myFixture.configureByText("a.ahk", text);
        myFixture.type('\n');
        return textWithCaret();
    }

    public void testDocCommentEnter() {
        assertEquals("/**\n * <caret>\n * @param a\n * @param b\n * @param c\n * @param args\n * @returns\n */\n"
                        + "f(a, &b, c := 1, args*) {\n    return a\n}\n",
                afterEnter("/**<caret>\nf(a, &b, c := 1, args*) {\n    return a\n}\n"));
        // no return value, method indented
        assertEquals("class K {\n    /**\n     * <caret>\n     * @param x\n     */\n    M(x) {\n    }\n}\n",
                afterEnter("class K {\n    /**<caret>\n    M(x) {\n    }\n}\n"));
        // class and no definition: only the skeleton
        assertEquals("/**\n * <caret>\n */\nclass K {\n}\n", afterEnter("/**<caret>\nclass K {\n}\n"));
        assertEquals("x := 1\n/**\n * <caret>\n */\n", afterEnter("x := 1\n/**<caret>\n"));
        // inside a doc comment: " * " on the new line
        assertEquals("/**\n * Text.\n * <caret>\n */\nf() {\n}\n", afterEnter("/**\n * Text.<caret>\n */\nf() {\n}\n"));
        // a plain block comment stays as it is
        myFixture.configureByText("a.ahk", "/*\n Text.<caret>\n*/\n");
        myFixture.type('\n');
        assertFalse(myFixture.getEditor().getDocument().getText().contains("* \n"));
    }

    public void testHighlightUsages() {
        myFixture.configureByText("a.ahk", String.join("\n",
                "co<caret>unt := 0",
                "Count += 1",
                "MsgBox count",
                "s := \"count\" ; count",
                "f(count) {",
                "    return COUNT",
                "}",
                "obj.count := 2",
                "x := obj.count",
                "class count {",
                "}",
                "countdown := 1",
                ""));
        com.intellij.codeInsight.highlighting.HighlightUsagesHandlerBase<com.intellij.psi.PsiElement> handler =
                com.intellij.codeInsight.highlighting.HighlightUsagesHandler.createCustomHandler(
                        myFixture.getEditor(), myFixture.getFile());
        assertNotNull(handler);
        handler.computeUsages(handler.getTargets());
        com.intellij.openapi.editor.Document document = myFixture.getEditor().getDocument();
        java.util.function.Function<List<com.intellij.openapi.util.TextRange>, List<String>> lines = ranges -> {
            List<String> out = new ArrayList<>();
            for (com.intellij.openapi.util.TextRange r : ranges) {
                out.add((document.getLineNumber(r.getStartOffset()) + 1) + ":" + r.substring(document.getText()));
            }
            return out;
        };
        // definitions and assignment targets; not the parameter count of f (a local variable, Z.27)
        assertEquals(List.of("1:count", "2:Count", "8:count", "10:count"), lines.apply(handler.getWriteUsages()));
        // other occurrences, not in strings and comments, not other names, not the uses of the parameter
        assertEquals(List.of("3:count", "9:count"), lines.apply(handler.getReadUsages()));
        // no handler outside of names
        myFixture.configureByText("b.ahk", "s := \"te<caret>xt\"\n");
        assertNull(com.intellij.codeInsight.highlighting.HighlightUsagesHandler.createCustomHandler(
                myFixture.getEditor(), myFixture.getFile()));
    }

    // ---------------------------------------------------------------- local variables (Z.27)

    /**
     * The local variable named at {@code <caret>}: "line:name" of its first definition, or "-" if the name is no
     * local variable (no AhkLocalReference there).
     */
    private String localAt(String text) {
        myFixture.configureByText("a.ahk", text);
        PsiReference ref = myFixture.getFile().findReferenceAt(myFixture.getCaretOffset());
        if (!(ref instanceof de.kumpewo.autohotkey.navigation.AhkLocalReference)) return "-";
        var target = (de.kumpewo.autohotkey.navigation.AhkLocalVariable) ref.resolve();
        assertNotNull(text, target);
        com.intellij.psi.PsiElement definition = target.getNavigationElement();
        int line = myFixture.getEditor().getDocument().getLineNumber(definition.getTextOffset()) + 1;
        return line + ":" + definition.getText();
    }

    public void testLocalsByTheRulesOfAutoHotkey() {
        // parameters, assignments, declarations, for and catch variables, any case
        assertEquals("1:Count", localAt("f(Count) {\n    return <caret>COUNT\n}\n"));
        assertEquals("2:x", localAt("f() {\n    x := 1\n    MsgBox <caret>x\n}\n"));
        assertEquals("2:n", localAt("f() {\n    n++\n    return <caret>n\n}\n"));
        assertEquals("2:v", localAt("f() {\n    g(&v)\n    return <caret>v\n}\n"));
        assertEquals("3:x", localAt("f() {\n    if 1 {\n        static x := 0\n    }\n    return <caret>x\n}\n"));
        assertEquals("2:k", localAt("f(m) {\n    for k, v in m\n        MsgBox <caret>k\n}\n"));
        assertEquals("3:e", localAt("f() {\n    try g()\n    catch Error as e\n        MsgBox <caret>e.Message\n}\n"));
        // the definition itself carries the reference
        assertEquals("1:count", localAt("f(co<caret>unt) {\n}\n"));
        assertEquals("2:x", localAt("f() {\n    local <caret>x\n}\n"));
        // a command name can be a parameter
        assertEquals("1:fn", localAt("f(fn) {\n    <caret>fn \"x\"\n}\n"));
        // only read: may be a global variable; outside of functions: global
        assertEquals("-", localAt("g := 1\nf() {\n    MsgBox <caret>g\n}\n"));
        assertEquals("-", localAt("x := 1\nMsgBox <caret>x\n"));
        // global declaration, assume-global (local declarations stay local)
        assertEquals("-", localAt("f() {\n    global g\n    <caret>g := 1\n}\n"));
        assertEquals("-", localAt("f() {\n    global\n    <caret>g := 1\n}\n"));
        assertEquals("3:y", localAt("f() {\n    global\n    local y\n    <caret>y := 1\n}\n"));
        // functions, classes and built-in variables are not local through an assignment
        assertEquals("-", localAt("f() {\n    <caret>MsgBox := 1\n}\n"));
        assertEquals("-", localAt("f() {\n    <caret>Helper := 1\n}\nHelper() {\n}\n"));
        assertEquals("-", localAt("f() {\n    <caret>A_Clipboard := 1\n}\n"));
        // the name of a nested function stays a function
        assertEquals("-", localAt("f() {\n    inner() {\n    }\n    <caret>inner()\n}\n"));
        // methods, properties and accessors
        assertEquals("2:p", localAt("class A {\n    m(p) {\n        return <caret>p\n    }\n}\n"));
        assertEquals("2:i", localAt("class A {\n    P[i] {\n        get => <caret>i\n    }\n}\n"));
        assertEquals("-", localAt("class A {\n    count := 0\n    m() => <caret>count\n}\n"));
    }

    public void testLocalsInNestedFunctions() {
        // a nested function shares the parameters and assigned variables of the outer function
        assertEquals("1:a", localAt("outer(a) {\n    inner() {\n        return <caret>a\n    }\n}\n"));
        assertEquals("2:x", localAt("outer() {\n    x := 1\n    inner() {\n        <caret>x := 2\n    }\n}\n"));
        assertEquals("1:list", localAt("f(list) {\n    return (x) => <caret>list.Length + x\n}\n"));
        assertEquals("2:x", localAt("f(list) {\n    return (x) => <caret>x * 2\n}\n"));
        assertEquals("2:y", localAt("f() {\n    return y => <caret>y\n}\n"));
        // own declaration wins; assigned only in the nested function: local there
        assertEquals("4:x", localAt("outer() {\n    x := 1\n    inner() {\n        local x\n        <caret>x := 2\n    }\n}\n"));
        assertEquals("3:z", localAt("outer() {\n    inner() {\n        z := 1\n        return <caret>z\n    }\n}\n"));
        assertEquals("-", localAt("outer() {\n    inner() {\n        z := 1\n    }\n    return <caret>z\n}\n"));
        // a static nested function only sees static variables of the outer function
        assertEquals("4:x", localAt("outer() {\n    x := 1\n    static inner() {\n        <caret>x := 2\n    }\n}\n"));
        assertEquals("2:s", localAt("outer() {\n    static s := 0\n    static inner() {\n        return <caret>s\n    }\n}\n"));
        // a global declaration of the outer function applies to the nested function
        assertEquals("-", localAt("outer() {\n    global g\n    inner() {\n        <caret>g := 1\n    }\n}\n"));
    }

    public void testLocalsCompletion() {
        List<String> list = suggestions(String.join("\n",
                "alphaGlobal := 1",
                "f(alphaParam) {",
                "    alphaVar := 1",
                "    inner() {",
                "        alphaInner := 2",
                "        x := alph<caret>",
                "    }",
                "}",
                "g() {",
                "    alphaOther := 1",
                "}",
                ""));
        // own variables, then the shared ones of the outer function; not those of other functions or global ones
        assertEquals(List.of("alphaInner", "alphaParam", "alphaVar"), list.stream().filter(s -> s.startsWith("alpha")).sorted().toList());
    }

    public void testLocalsHighlightUsages() {
        myFixture.configureByText("a.ahk", String.join("\n",
                "count := 0",
                "f(count) {",
                "    count += 1",
                "    inner() => count",
                "    return co<caret>unt",
                "}",
                "g() {",
                "    count := 2",
                "}",
                ""));
        var handler = com.intellij.codeInsight.highlighting.HighlightUsagesHandler.createCustomHandler(
                myFixture.getEditor(), myFixture.getFile());
        assertNotNull(handler);
        handler.computeUsages(handler.getTargets());
        com.intellij.openapi.editor.Document document = myFixture.getEditor().getDocument();
        List<Integer> write = new ArrayList<>();
        for (var r : handler.getWriteUsages()) write.add(document.getLineNumber(r.getStartOffset()) + 1);
        List<Integer> read = new ArrayList<>();
        for (var r : handler.getReadUsages()) read.add(document.getLineNumber(r.getStartOffset()) + 1);
        // the parameter of f and its uses, also in the nested function; not the global count, not count of g
        assertEquals(List.of(2, 3), write);
        assertEquals(List.of(4, 5), read);
    }

    public void testLocalsFindUsagesAndNavigation() {
        myFixture.configureByText("a.ahk", String.join("\n",
                "f(value) {",
                "    x := val<caret>ue",
                "    inner() => value * 2",
                "}",
                "g(value) {",
                "    return value",
                "}",
                ""));
        com.intellij.psi.PsiElement target = myFixture.getElementAtCaret();
        assertTrue(String.valueOf(target), target instanceof de.kumpewo.autohotkey.navigation.AhkLocalVariable);
        // Ctrl+B: the parameter
        assertEquals(0, myFixture.getEditor().getDocument().getLineNumber(target.getNavigationElement().getTextOffset()));
        List<Integer> lines = new ArrayList<>();
        for (com.intellij.usageView.UsageInfo usage : myFixture.findUsages(target)) {
            lines.add(myFixture.getEditor().getDocument().getLineNumber(
                    java.util.Objects.requireNonNull(usage.getSegment()).getStartOffset()) + 1);
        }
        java.util.Collections.sort(lines);
        // definition and uses in f, also in the nested function; not in g
        assertEquals(List.of(1, 2, 3), lines);
    }

    public void testLocalsRenameInPlace() {
        com.intellij.codeInsight.template.impl.TemplateManagerImpl.setTemplateTesting(getTestRootDisposable());
        myFixture.configureByText("a.ahk", String.join("\n",
                "count := 0",
                "f(co<caret>unt) {",
                "    Count += 1",
                "    inner() => count",
                "    return count",
                "}",
                "g() {",
                "    count := 2",
                "}",
                ""));
        com.intellij.testFramework.fixtures.CodeInsightTestUtil.doInlineRename(
                new com.intellij.refactoring.rename.inplace.VariableInplaceRenameHandler(), "total", myFixture);
        assertEquals(String.join("\n",
                "count := 0",
                "f(total) {",
                "    total += 1",
                "    inner() => total",
                "    return total",
                "}",
                "g() {",
                "    count := 2",
                "}",
                ""), myFixture.getEditor().getDocument().getText());
    }

    public void testLocalsParameterInDocComment() {
        com.intellij.codeInsight.template.impl.TemplateManagerImpl.setTemplateTesting(getTestRootDisposable());
        String text = String.join("\n",
                "/**",
                " * Adds two numbers.",
                " * @param count the first number",
                " * @param {Integer} other the second number",
                " */",
                "f(co<caret>unt, other) {",
                "    return count + other",
                "}",
                "/**",
                " * @param count of another function",
                " */",
                "g(count) {",
                "}",
                "");
        // Find Usages and the highlighting of occurrences show the name after @param
        myFixture.configureByText("a.ahk", text);
        List<Integer> lines = new ArrayList<>();
        for (com.intellij.usageView.UsageInfo usage : myFixture.findUsages(myFixture.getElementAtCaret())) {
            lines.add(myFixture.getEditor().getDocument().getLineNumber(
                    java.util.Objects.requireNonNull(usage.getSegment()).getStartOffset()) + 1);
        }
        java.util.Collections.sort(lines);
        assertEquals(List.of(3, 6, 7), lines);
        var handler = com.intellij.codeInsight.highlighting.HighlightUsagesHandler.createCustomHandler(
                myFixture.getEditor(), myFixture.getFile());
        assertNotNull(handler);
        handler.computeUsages(handler.getTargets());
        assertEquals(3, handler.getReadUsages().size() + handler.getWriteUsages().size());
        // Ctrl+B on the name after @param leads to the parameter
        int inComment = myFixture.getFile().getText().indexOf("count the first") + 2;
        PsiReference ref = myFixture.getFile().findReferenceAt(inComment);
        assertNotNull(ref);
        com.intellij.psi.PsiElement target = ref.resolve();
        assertTrue(String.valueOf(target), target instanceof de.kumpewo.autohotkey.navigation.AhkLocalVariable);
        assertEquals(5, myFixture.getEditor().getDocument().getLineNumber(target.getNavigationElement().getTextOffset()));
        // rename in place: the parameter, its uses and its @param name; not the doc comment of g
        com.intellij.testFramework.fixtures.CodeInsightTestUtil.doInlineRename(
                new com.intellij.refactoring.rename.inplace.VariableInplaceRenameHandler(), "total", myFixture);
        assertEquals(text.replace("co<caret>unt", "total").replace("@param count the", "@param total the")
                .replace("return count", "return total"), myFixture.getEditor().getDocument().getText());
    }

    /**
     * What Shift+F6 does at {@code <caret>}: the hint of AhkRenameHintHandler, or the class name of the rename handler
     * the platform chooses otherwise ("none" without any).
     */
    private String renameHint(String text) {
        myFixture.configureByText("a.ahk", text);
        com.intellij.openapi.actionSystem.DataContext context =
                ((com.intellij.openapi.editor.ex.EditorEx) myFixture.getEditor()).getDataContext();
        com.intellij.refactoring.rename.RenameHandler handler =
                com.intellij.refactoring.rename.RenameHandlerRegistry.getInstance().getRenameHandler(context);
        if (!(handler instanceof de.kumpewo.autohotkey.navigation.AhkRenameHintHandler)) {
            return handler == null ? "none" : handler.getClass().getSimpleName();
        }
        try {
            handler.invoke(getProject(), myFixture.getEditor(), myFixture.getFile(), context);
        } catch (com.intellij.refactoring.util.CommonRefactoringUtil.RefactoringErrorHintException e) {
            return e.getMessage();
        }
        throw new AssertionError("no hint: " + text);
    }

    public void testRenameHintWhereNothingCanBeRenamed() {
        String global = AhkBundle.message("rename.hint.global", "x");
        String nothing = AhkBundle.message("rename.hint.nothing");
        // global variables: outside of functions, declared global, only read in a function; caret also at the end
        assertEquals(global, renameHint("x := 1\nMsgBox <caret>x\n"));
        assertEquals(global, renameHint("<caret>x := 1\n"));
        assertEquals(global, renameHint("x<caret> := 1\n"));
        assertEquals(global, renameHint("f() {\n    global x\n    <caret>x := 1\n}\n"));
        assertEquals(global, renameHint("f() {\n    return <caret>x\n}\n"));
        // built-in names
        assertEquals(AhkBundle.message("rename.hint.builtin", "MsgBox"), renameHint("<caret>MsgBox \"x\"\n"));
        assertEquals(AhkBundle.message("rename.hint.builtin", "A_Index"), renameHint("y := <caret>A_Index\n"));
        assertEquals(AhkBundle.message("rename.hint.builtin", "Length"), renameHint("y := arr.<caret>Length\n"));
        assertEquals(AhkBundle.message("rename.hint.builtin", "#SingleInstance"), renameHint("#Single<caret>Instance Force\n"));
        // anything else
        assertEquals(nothing, renameHint("y := obj.<caret>Unknown\n"));
        assertEquals(nothing, renameHint("y := 4<caret>2\n"));
        assertEquals(nothing, renameHint("<caret>if y\n    z := 1\n"));
        assertEquals(nothing, renameHint("; a com<caret>ment\n"));
        // something to rename: the hint handler stays out
        String function = renameHint("<caret>Helper()\nHelper() {\n}\n");
        assertFalse(function, function.equals(nothing) || function.equals("none"));
        String local = renameHint("f(<caret>p) {\n    return p\n}\n");
        assertFalse(local, local.equals(nothing) || local.equals("none"));
    }

    public void testColorSettingsPageGroups() {
        de.kumpewo.autohotkey.highlighting.AhkColorSettingsPage page = new de.kumpewo.autohotkey.highlighting.AhkColorSettingsPage();
        java.util.Set<String> groups = new java.util.TreeSet<>();
        java.util.Set<com.intellij.openapi.editor.colors.TextAttributesKey> keys = new java.util.HashSet<>();
        for (com.intellij.openapi.options.colors.AttributesDescriptor descriptor : page.getAttributeDescriptors()) {
            String[] parts = descriptor.getDisplayName().split("//");
            assertEquals(descriptor.getDisplayName(), 2, parts.length);
            groups.add(parts[0]);
            assertTrue("twice: " + descriptor.getKey(), keys.add(descriptor.getKey()));
        }
        // the IDE sorts groups and entries alphabetically by the whole display name (ColorOptionsTree)
        assertEquals(java.util.Set.of("Built-in elements", "Core", "Declaration", "Special"), groups);
        // every color of the preview can be configured
        assertTrue(keys.containsAll(java.util.Objects.requireNonNull(
                page.getAdditionalHighlightingTagToDescriptorMap()).values()));
        assertEquals(21, keys.size());
    }

    public void testCompletionIncludePaths() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("ahk").toRealPath();
        java.nio.file.Files.createDirectories(dir.resolve("sub"));
        java.nio.file.Files.createDirectories(dir.resolve("Lib").resolve("v2"));
        java.nio.file.Files.writeString(dir.resolve("helper.ahk"), "");
        java.nio.file.Files.writeString(dir.resolve("notes.txt"), "");
        java.nio.file.Files.writeString(dir.resolve("sub").resolve("part.ahk"), "");
        java.nio.file.Files.writeString(dir.resolve("sub").resolve("piece.ahk"), "");
        java.nio.file.Files.writeString(dir.resolve("Lib").resolve("ZqLib.ahk"), "");
        java.nio.file.Files.writeString(dir.resolve("Lib").resolve("v2").resolve("ZqTeil.ahk"), "");
        // a second entry starting with "v": with a single match the completion is inserted directly and the list
        // stays empty (on the CI runner there is no user library that adds further matches)
        java.nio.file.Files.writeString(dir.resolve("Lib").resolve("vZq.ahk"), "");
        com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir);

        // relative to the directory of the file: .ahk files and directories, not other files, not the file itself
        List<String> all = suggestionsInFile(dir, "#Include <caret>\n");
        assertTrue(all.toString(), all.containsAll(List.of("helper.ahk", "sub\\", "Lib\\")));
        assertFalse(all.toString(), all.contains("notes.txt") || all.contains("main.ahk"));
        assertEquals("#Include helper.ahk\n", insertedInFile(dir, "#Include hel<caret>\n"));
        // quoted (the closing quote is added), after *i, in a typed subdirectory
        assertEquals("#Include \"helper.ahk\"\n", insertedInFile(dir, "#Include \"hel<caret>\n"));
        assertEquals("#Include *i helper.ahk\n", insertedInFile(dir, "#Include *i hel<caret>\n"));
        assertEquals(List.of("part.ahk", "piece.ahk"), sorted(suggestionsInFile(dir, "#Include sub\\<caret>\n")));
        assertEquals(List.of("part.ahk", "piece.ahk"),
                sorted(suggestionsInFile(dir, "#Include %A_ScriptDir%/sub/p<caret>\n")));
        // after #Include Directory, relative paths refer to that directory
        assertEquals("#Include sub\n#Include part.ahk\n", insertedInFile(dir, "#Include sub\n#Include par<caret>\n"));
        // library: files without extension, closing >, subdirectories
        assertEquals("#Include <ZqLib>\n", insertedInFile(dir, "#Include <ZqL<caret>\n"));
        assertEquals("#Include <v2\\ZqTeil>\n", insertedInFile(dir, "#Include <v2\\Zq<caret>\n"));
        assertTrue(suggestionsInFile(dir, "#Include <v<caret>\n").contains("v2\\"));
        // other directives: no paths
        assertFalse(suggestionsInFile(dir, "#Warn <caret>\n").contains("helper.ahk"));
        // auto popup after "<" and a backslash only in an include line
        assertTrue(de.kumpewo.autohotkey.completion.AhkIncludeCompletion.isIncludeArgument("#Include <"));
        assertTrue(de.kumpewo.autohotkey.completion.AhkIncludeCompletion.isIncludeArgument("  #includeAgain sub\\"));
        assertFalse(de.kumpewo.autohotkey.completion.AhkIncludeCompletion.isIncludeArgument("x := \"a\\"));
    }

    /** Sorted copy of the list. */
    private static List<String> sorted(List<String> list) {
        List<String> copy = new ArrayList<>(list);
        java.util.Collections.sort(copy);
        return copy;
    }

    /** Suggestions in a real file main.ahk in the directory {@code dir} (for #Include). */
    private List<String> suggestionsInFile(java.nio.file.Path dir, String text) throws Exception {
        openInFile(dir, text);
        myFixture.completeBasic();
        List<String> list = myFixture.getLookupElementStrings();
        return list == null ? List.of() : list;
    }

    /** Text of main.ahk after completion (exactly one suggestion is inserted directly). */
    private String insertedInFile(java.nio.file.Path dir, String text) throws Exception {
        openInFile(dir, text);
        myFixture.completeBasic();
        return myFixture.getEditor().getDocument().getText();
    }

    private void openInFile(java.nio.file.Path dir, String text) throws Exception {
        // an unsaved document from an earlier call would not be reloaded from disk
        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments();
        int caret = text.indexOf("<caret>");
        java.nio.file.Path main = java.nio.file.Files.writeString(dir.resolve("main.ahk"),
                text.replace("<caret>", ""));
        com.intellij.openapi.vfs.VirtualFile file = java.util.Objects.requireNonNull(
                com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(main));
        file.refresh(false, false);
        myFixture.configureFromExistingVirtualFile(file);
        myFixture.getEditor().getCaretModel().moveToOffset(caret);
    }

    /** Targets of the reference to {@code name} in the last occurrence of {@code snippet}, as "file:line". */
    private List<String> targetsIn(String snippet, String name) {
        String text = myFixture.getFile().getText();
        int start = text.lastIndexOf(snippet);
        assertTrue(snippet, start >= 0 && snippet.contains(name));
        PsiReference ref = myFixture.getFile().findReferenceAt(start + snippet.indexOf(name));
        assertNotNull("Kein Verweis: " + snippet, ref);
        List<String> result = new ArrayList<>();
        for (com.intellij.psi.ResolveResult r : ((com.intellij.psi.PsiPolyVariantReference) ref).multiResolve(false)) {
            PsiElement e = r.getElement();
            if (e instanceof de.kumpewo.autohotkey.navigation.AhkHelpTarget) continue;   // the help does not count here
            com.intellij.psi.PsiFile f = e.getContainingFile();
            int line = f.getViewProvider().getDocument().getLineNumber(e.getTextOffset()) + 1;
            result.add(f.getName() + ":" + line);
        }
        return result;
    }

    /**
     * The Lib folder next to AutoHotkey (from the settings) is the library "AutoHotkey Lib": its files are not in
     * the project, but are fully colored (without the library no annotator runs).
     */
    public void testLibFolderIsFullyColored() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("ahk").toRealPath();
        java.nio.file.Path exe = java.nio.file.Files.writeString(dir.resolve("AutoHotkey64.exe"), "");
        java.nio.file.Files.createDirectories(dir.resolve("Lib"));
        java.nio.file.Path lib = java.nio.file.Files.writeString(dir.resolve("Lib").resolve("MyLib.ahk"),
                "x := A_Index\n#Unbekannt\n");
        de.kumpewo.autohotkey.settings.AhkSettings settings = de.kumpewo.autohotkey.settings.AhkSettings.getInstance();
        String vorher = settings.getAutoHotkeyPath();
        try {
            de.kumpewo.autohotkey.include.AhkLibraryRootsProvider.changeRoots(
                    () -> settings.setAutoHotkeyPath(exe.toString()));
            com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(getProject());
            com.intellij.openapi.vfs.VirtualFile file = java.util.Objects.requireNonNull(
                    com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(lib));
            com.intellij.openapi.roots.ProjectFileIndex index =
                    com.intellij.openapi.roots.ProjectFileIndex.getInstance(getProject());
            assertFalse(index.isInContent(file));
            assertTrue(index.isInLibrarySource(file));
            myFixture.configureFromExistingVirtualFile(file);
            List<String> gefunden = new ArrayList<>();
            for (HighlightInfo info : myFixture.doHighlighting()) {
                gefunden.add(info.getDescription() != null ? info.getDescription()
                        : String.valueOf(info.forcedTextAttributesKey.getExternalName()));
            }
            assertTrue(gefunden.toString(), gefunden.contains("AHK_BUILTIN_VARIABLE"));
            assertTrue(gefunden.toString(), gefunden.contains("Unknown directive."));
        } finally {
            de.kumpewo.autohotkey.include.AhkLibraryRootsProvider.changeRoots(
                    () -> settings.setAutoHotkeyPath(vorher));
        }
        com.intellij.openapi.vfs.VirtualFile file = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .findFileByNioFile(lib);
        assertFalse(com.intellij.openapi.roots.ProjectFileIndex.getInstance(getProject()).isInLibrarySource(file));
    }

    private static String includeTarget(de.kumpewo.autohotkey.psi.AhkDirective directive) {
        PsiReference ref = directive.getReference();
        assertNotNull(directive.getText(), ref);
        PsiElement target = ref.resolve();
        if (target == null) return null;
        if (target instanceof de.kumpewo.autohotkey.include.AhkIncludeDirectoryTarget d) {
            return "Verzeichnis " + d.getDirectory();
        }
        return java.nio.file.Path.of(((com.intellij.psi.PsiFile) target).getVirtualFile().getPath()).toString();
    }

    /** Targets of the reference at the caret as text of the chooser ("name in location"). */
    private List<String> targets(String text) {
        myFixture.configureByText("a.ahk", text);
        PsiReference ref = myFixture.getFile().findReferenceAt(myFixture.getCaretOffset());
        assertNotNull("no reference at the caret: " + text, ref);
        List<String> result = new ArrayList<>();
        for (com.intellij.psi.ResolveResult r : ((com.intellij.psi.PsiPolyVariantReference) ref).multiResolve(false)) {
            com.intellij.navigation.ItemPresentation p =
                    ((com.intellij.navigation.NavigationItem) r.getElement()).getPresentation();
            assertNotNull(p);
            result.add(p.getPresentableText() + (p.getLocationString() == null ? "" : " in " + p.getLocationString()));
        }
        return result;
    }

    // ---------------------------------------------------------------- ;@ignore-next-line

    public void testIgnoreNextLineHidesOnlyErrorsOfNextLine() {
        myFixture.configureByText("a.ahk", ""
                + "<warning descr=\"Parser errors suppressed.\">;@ignore-next-line Parser kennt extends %Y% nicht</warning>\n"
                + "class X extends %Y% {\n"
                + "    a() {\n"
                + "    }\n"
                + "<error descr=\"Unexpected '}'\">}</error>\n"
                + "c() {\n"
                + "}\n");
        myFixture.checkHighlighting(true, false, false);
    }

    // ---------------------------------------------------------------- generic syntax errors

    public void testTechnicalParserErrorsBecomeSyntaxError() {
        List<String> errors = errorTexts("x := (\ny := 1 +* 2\n");
        assertFalse(errors.toString(), errors.isEmpty());
        for (String error : errors) assertEquals(errors.toString(), "Syntax error", error);
        // The plugin's own parser messages stay.
        assertEquals(List.of("'case' or 'default' expected"), errorTexts("switch x {\n    a()\n    case 1: b()\n}\n"));
    }

    private List<String> errorTexts(String code) {
        myFixture.configureByText("a.ahk", code);
        List<String> errors = new ArrayList<>();
        for (var info : myFixture.doHighlighting(com.intellij.lang.annotation.HighlightSeverity.ERROR)) {
            errors.add(info.getDescription());
        }
        return errors;
    }

    public void testSyntaxErrorAtEndOfLineAndInIgnoredLine() {
        myFixture.configureByText("a.ahk", "x := <error descr=\"Syntax error\">)</error>\n");
        myFixture.checkHighlighting(true, false, false);
        myFixture.configureByText("a.ahk", "<warning descr=\"Parser errors suppressed.\">;@ignore-next-line</warning>\n"
                + "x := )\n");
        myFixture.checkHighlighting(true, false, false);
    }

    // ---------------------------------------------------------------- validation with AutoHotkey /validate

    public void testValidationOutputIsParsed() {
        var error = de.kumpewo.autohotkey.validate.AhkValidator.parse(
                "\nC:\\Some Dir\\a b.ahk (3) : ==> Missing \")\"\r\n     Specifically: y := (\r\n");
        assertNotNull(error);
        assertEquals("C:\\Some Dir\\a b.ahk", error.path());
        assertEquals(3, error.line());
        assertEquals("Missing \")\"", error.message());
        assertNull(de.kumpewo.autohotkey.validate.AhkValidator.parse("just some output\n"));
        // warnings (#Warn All, StdOut) are no errors
        String output = "C:\\a.ahk (4) : ==> Warning: This local variable appears to never be assigned a value.\n"
                + "     Specifically: y\n";
        assertNull(de.kumpewo.autohotkey.validate.AhkValidator.parse(output));
        assertEquals(1, de.kumpewo.autohotkey.validate.AhkValidator.warnings(output));
        error = de.kumpewo.autohotkey.validate.AhkValidator.parse(output + "C:\\a.ahk (7) : ==> Missing \")\"\n");
        assertNotNull(error);
        assertEquals(7, error.line());
    }

    public void testValidationCopyOfTheScript() {
        java.nio.file.Path original = java.nio.file.Path.of("C:\\Some Dir\\main.ahk");
        java.nio.file.Path copy = de.kumpewo.autohotkey.validate.AhkValidator.copyPath(original);
        assertEquals(java.nio.file.Path.of("C:\\Some Dir\\main.~validate.ahk"), copy);
        // appended as the last line, with the line separator of the file; a missing final line break is added
        var utf8 = java.nio.charset.StandardCharsets.UTF_8;
        var utf16 = java.nio.charset.StandardCharsets.UTF_16LE;
        assertEquals("a\r\nb\r\n#Warn All, StdOut\r\n", appended("a\r\nb\r\n".getBytes(utf8), utf8));
        assertEquals("a\nb\n#Warn All, StdOut\n", appended("a\nb".getBytes(utf8), utf8));
        assertEquals("\uFEFFx\r\n#Warn All, StdOut\r\n", appended("\uFEFFx\r\n".getBytes(utf8), utf8));
        assertEquals("\uFEFFx\n#Warn All, StdOut\n", appended("\uFEFFx\n".getBytes(utf16), utf16));
        // the output names the original file again, the appended directive disappears from "Specifically:"
        String output = copy + " (3) : ==> Missing \")\"\n     Specifically: MsgBox( #Warn All, StdOut\n"
                + "Error in main.~validate.ahk\n";
        assertEquals(original + " (3) : ==> Missing \")\"\n     Specifically: MsgBox(\nError in main.ahk\n",
                de.kumpewo.autohotkey.validate.AhkValidator.restorePaths(output, copy, original));
    }

    /** The copy of the validation as text in the given encoding. */
    private static String appended(byte[] content, java.nio.charset.Charset charset) {
        return new String(de.kumpewo.autohotkey.validate.AhkValidator.withWarnToStdOut(content), charset);
    }

    public void testValidationTargetIsTheScriptOfTheSelectedRunConfiguration() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("ahk");
        java.nio.file.Path main = java.nio.file.Files.writeString(dir.resolve("main.ahk"), "#Include part.ahk\n");
        java.nio.file.Path part = java.nio.file.Files.writeString(dir.resolve("part.ahk"), "#Include deep.ahk\nx := 1\n");
        java.nio.file.Path deep = java.nio.file.Files.writeString(dir.resolve("deep.ahk"), "y := 1\n");
        java.nio.file.Path other = java.nio.file.Files.writeString(dir.resolve("other.ahk"), "z := 1\n");
        var fileSystem = com.intellij.openapi.vfs.LocalFileSystem.getInstance();
        var mainFile = fileSystem.refreshAndFindFileByNioFile(main);
        var partFile = fileSystem.refreshAndFindFileByNioFile(part);
        var deepFile = fileSystem.refreshAndFindFileByNioFile(deep);
        var otherFile = fileSystem.refreshAndFindFileByNioFile(other);
        var runManager = com.intellij.execution.RunManager.getInstance(getProject());
        var selectedBefore = runManager.getSelectedConfiguration();
        // without a selected AutoHotkey run configuration: the file itself
        runManager.setSelectedConfiguration(null);
        var target = de.kumpewo.autohotkey.validate.AhkValidator.target(getProject(), partFile);
        assertNotNull(target);
        assertEquals(partFile, target.script());
        assertNull(target.configuration());
        var settings = runManager.createConfiguration("Main",
                de.kumpewo.autohotkey.run.AhkRunConfigurationType.getInstance().getFactory());
        var configuration = (de.kumpewo.autohotkey.run.AhkRunConfiguration) settings.getConfiguration();
        configuration.setScriptPath(main.toString());
        runManager.addConfiguration(settings);
        try {
            runManager.setSelectedConfiguration(settings);
            target = de.kumpewo.autohotkey.validate.AhkValidator.target(getProject(), partFile);
            assertNotNull(target);
            assertEquals(mainFile, target.script());
            assertEquals("Main", target.configuration());
            // the main script itself, an indirectly included file and no file: the main script
            assertEquals(mainFile, de.kumpewo.autohotkey.validate.AhkValidator.target(getProject(), mainFile).script());
            assertEquals(mainFile, de.kumpewo.autohotkey.validate.AhkValidator.target(getProject(), deepFile).script());
            assertEquals(mainFile, de.kumpewo.autohotkey.validate.AhkValidator.target(getProject(), null).script());
            // a script that does not belong to the run configuration (project with many small scripts): itself
            target = de.kumpewo.autohotkey.validate.AhkValidator.target(getProject(), otherFile);
            assertEquals(otherFile, target.script());
            assertNull(target.configuration());
            // a missing script falls back to the file itself
            configuration.setScriptPath(dir.resolve("missing.ahk").toString());
            assertEquals(partFile, de.kumpewo.autohotkey.validate.AhkValidator.target(getProject(), partFile).script());
        } finally {
            runManager.removeConfiguration(settings);
            runManager.setSelectedConfiguration(selectedBefore);
        }
    }

    /**
     * "Reformat Code" on an AutoHotkey file empties the console of the last validation (whatever the setting); other
     * actions and other files leave it.
     */
    public void testReformatClearsValidationConsole() {
        var console = (com.intellij.execution.impl.ConsoleViewImpl) com.intellij.execution.filters.TextConsoleBuilderFactory
                .getInstance().createBuilder(getProject()).getConsole();
        try {
            checkReformatClearsConsole(console);
        } finally {
            de.kumpewo.autohotkey.validate.AhkValidator.useConsoleForTest(getProject(), null);
            com.intellij.openapi.util.Disposer.dispose(console);   // releases the editor of the console
        }
    }

    /** Body of {@link #testReformatClearsValidationConsole} with the console of the test. */
    private void checkReformatClearsConsole(com.intellij.execution.impl.ConsoleViewImpl console) {
        console.getComponent();   // creates the editor of the console
        de.kumpewo.autohotkey.validate.AhkValidator.useConsoleForTest(getProject(), console);
        var triggers = new de.kumpewo.autohotkey.validate.AhkValidateTriggers();
        var manager = com.intellij.openapi.actionSystem.ActionManager.getInstance();

        java.util.function.BiConsumer<String, com.intellij.openapi.vfs.VirtualFile> perform = (actionId, file) -> {
            var context = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                    .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, getProject())
                    .add(com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE, file).build();
            var action = manager.getAction(actionId);
            var event = com.intellij.openapi.actionSystem.AnActionEvent.createEvent(context,
                    action.getTemplatePresentation().clone(), "test",
                    com.intellij.openapi.actionSystem.ActionUiKind.NONE, null);
            triggers.afterActionPerformed(action, event,
                    com.intellij.openapi.actionSystem.AnActionResult.PERFORMED);
        };
        com.intellij.openapi.vfs.VirtualFile ahk = myFixture.configureByText("a.ahk", "x := 1\n").getVirtualFile();
        com.intellij.openapi.vfs.VirtualFile txt = myFixture.configureByText("b.txt", "x\n").getVirtualFile();

        console.print("old output\n", com.intellij.execution.ui.ConsoleViewContentType.NORMAL_OUTPUT);
        console.flushDeferredText();
        perform.accept(com.intellij.openapi.actionSystem.IdeActions.ACTION_EDITOR_COPY, ahk);
        perform.accept(com.intellij.openapi.actionSystem.IdeActions.ACTION_EDITOR_REFORMAT, txt);
        console.flushDeferredText();
        assertTrue(console.getText(), console.getText().contains("old output"));
        perform.accept(com.intellij.openapi.actionSystem.IdeActions.ACTION_EDITOR_REFORMAT, ahk);
        console.flushDeferredText();
        com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();   // clear() is deferred
        console.flushDeferredText();
        assertEquals("", console.getText());
    }

    public void testValidationErrorIsMarkedUntilTheFileChanges() {
        // checkHighlighting rewrites the document (new modification stamp), therefore doHighlighting
        myFixture.configureByText("a.ahk", "x := 1\n  MsgBox x  \n");
        storeValidation(false, 2, "Something is wrong");
        List<String> marked = new ArrayList<>();
        for (var info : myFixture.doHighlighting(com.intellij.lang.annotation.HighlightSeverity.ERROR)) {
            marked.add(info.getDescription() + " @ " + info.getText());
        }
        assertEquals(List.of("AutoHotkey: Something is wrong @ MsgBox x"), marked);
        myFixture.type("y");
        for (var info : myFixture.doHighlighting(com.intellij.lang.annotation.HighlightSeverity.ERROR)) {
            fail("no error expected after a change: " + info.getDescription());
        }
    }

    public void testSyntaxErrorAcceptedByAutoHotkey() {
        // known gap of the parser: unary operator at the start of the next line inside parentheses
        List<String> before = errorTexts("x := (a\n!b)\n");
        assertFalse(before.isEmpty());
        storeValidation(true, 0, "");
        List<String> after = new ArrayList<>();
        for (var info : myFixture.doHighlighting(com.intellij.lang.annotation.HighlightSeverity.ERROR)) {
            after.add(info.getDescription());
        }
        assertEquals(before.size(), after.size());
        for (String error : after) assertEquals(after.toString(), "Syntax error (AutoHotkey accepts this code)", error);
        assertEquals(before.size(), de.kumpewo.autohotkey.validate.AhkValidator.parserErrors(getProject(),
                myFixture.getFile().getVirtualFile()));
    }

    /** Stores a validation result for the file in the editor, as AhkValidator does after a run. */
    private void storeValidation(boolean accepted, int line, String message) {
        var file = myFixture.getFile().getVirtualFile();
        long stamp = myFixture.getEditor().getDocument().getModificationStamp();
        de.kumpewo.autohotkey.validate.AhkValidation.getInstance(getProject()).store(file,
                java.util.Map.of(file, new de.kumpewo.autohotkey.validate.AhkValidation.Result(file, stamp, accepted,
                        line, message)));
        com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(getProject()).restart(myFixture.getFile(), "test");
    }

    /** Runs the real AutoHotkey (skipped if it is not installed) with the command line of the validation. */
    public void testValidationWithInstalledAutoHotkey() throws Exception {
        java.nio.file.Path exe = de.kumpewo.autohotkey.settings.AhkSettings.detectAutoHotkey();
        if (exe == null) return;
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("ahk");
        java.nio.file.Path script = java.nio.file.Files.writeString(dir.resolve("a b.ahk"),
                "#Requires AutoHotkey v2.0\nx := 1\ny := (\n");
        var commandLine = de.kumpewo.autohotkey.run.AhkRunState.createCommandLine(exe.toString(),
                List.of("/ErrorStdOut", "/validate"), script.toString(), "", dir.toString());
        var output = new com.intellij.execution.process.CapturingProcessHandler(commandLine)
                .runProcess(de.kumpewo.autohotkey.validate.AhkValidator.TIMEOUT_MS, true);
        assertFalse(output.isTimeout());
        assertTrue(output.getExitCode() != 0);
        var error = de.kumpewo.autohotkey.validate.AhkValidator.parse(output.getStdout() + "\n" + output.getStderr());
        assertNotNull(output.getStderr(), error);
        assertEquals(script.toString(), error.path());
        assertEquals(3, error.line());

        java.nio.file.Files.writeString(script, "#Requires AutoHotkey v2.0\nx := 1\n  + 2\n");
        output = new com.intellij.execution.process.CapturingProcessHandler(commandLine)
                .runProcess(de.kumpewo.autohotkey.validate.AhkValidator.TIMEOUT_MS, true);
        assertEquals(output.getStderr(), 0, output.getExitCode());

        // #Warn in message box mode: the appended "#Warn All, StdOut" wins, the warning goes to stdout (no dialog)
        java.nio.file.Path copy = de.kumpewo.autohotkey.validate.AhkValidator.copyPath(script);
        java.nio.file.Files.write(copy, de.kumpewo.autohotkey.validate.AhkValidator.withWarnToStdOut(
                "#Requires AutoHotkey v2.0\n#Warn All, MsgBox\nf() {\n  x := y\n}\n".getBytes()));
        try {
            commandLine = de.kumpewo.autohotkey.run.AhkRunState.createCommandLine(exe.toString(),
                    List.of("/ErrorStdOut", "/validate"), copy.toString(), "", dir.toString());
            output = new com.intellij.execution.process.CapturingProcessHandler(commandLine)
                    .runProcess(de.kumpewo.autohotkey.validate.AhkValidator.TIMEOUT_MS, true);
        } finally {
            java.nio.file.Files.delete(copy);
        }
        assertFalse(output.isTimeout());
        assertEquals(output.getStderr(), 0, output.getExitCode());
        String text = de.kumpewo.autohotkey.validate.AhkValidator.restorePaths(output.getStdout(), copy, script);
        assertNull(text, de.kumpewo.autohotkey.validate.AhkValidator.parse(text));
        assertEquals(text, 1, de.kumpewo.autohotkey.validate.AhkValidator.warnings(text));
        assertTrue(text, text.startsWith(script + " (4) : ==> Warning:"));
    }

    public void testCompileNeedsBaseDirective() {
        assertTrue(de.kumpewo.autohotkey.compile.AhkCompiler.hasBaseDirective(
                "#Requires AutoHotkey v2.0\r\n;@Ahk2Exe-Base ..\\v2\\AutoHotkey64.exe\r\nx := 1\r\n"));
        assertTrue(de.kumpewo.autohotkey.compile.AhkCompiler.hasBaseDirective("  ;@ahk2exe-bin Unicode 64-bit.bin\n"));
        assertFalse(de.kumpewo.autohotkey.compile.AhkCompiler.hasBaseDirective(";@Ahk2Exe-SetVersion 1.0\n"));
        assertFalse(de.kumpewo.autohotkey.compile.AhkCompiler.hasBaseDirective(";@Ahk2Exe-Base\n"));
        assertFalse(de.kumpewo.autohotkey.compile.AhkCompiler.hasBaseDirective("x := 1 ;@Ahk2Exe-Base a.exe\n"));
        assertEquals("C:\\a b\\x.exe", de.kumpewo.autohotkey.compile.AhkCompiler.compiledFile(
                "Ahk2Exe Status: Moving .exe to destination\r\nSuccessfully compiled as:\r\n\"C:\\a b\\x.exe\"\r\n"));
        assertNull(de.kumpewo.autohotkey.compile.AhkCompiler.compiledFile("Failed to compile: x.ahk\n"));
        var commandLine = de.kumpewo.autohotkey.compile.AhkCompiler.createCommandLine("C:/AHK/Compiler/Ahk2Exe.exe",
                "C:/scripts/a b.ahk", de.kumpewo.autohotkey.settings.AhkSettings.Compression.NONE);
        assertEquals("C:\\AHK\\Compiler\\Ahk2Exe.exe", commandLine.getExePath());
        assertEquals(List.of("/in", "C:\\scripts\\a b.ahk", "/compress", "0", "/silent", "verbose"),
                commandLine.getParametersList().getList());
        assertEquals("C:\\scripts", commandLine.getWorkDirectory().getPath());
    }

    public void testCompileWithInstalledAhk2Exe() throws Exception {
        java.nio.file.Path ahk2Exe = de.kumpewo.autohotkey.settings.AhkSettings.detectAhk2Exe();
        if (ahk2Exe == null || ahk2Exe.getParent().getParent() == null) return;
        java.nio.file.Path base = ahk2Exe.getParent().getParent().resolve("v2").resolve("AutoHotkey64.exe");
        if (!java.nio.file.Files.isRegularFile(base)) return;
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("ahk");
        String head = "#Requires AutoHotkey v2.0\r\n;@Ahk2Exe-Base " + base + "\r\n";
        java.nio.file.Path script = java.nio.file.Files.writeString(dir.resolve("a b.ahk"), head + "x := 1\r\n");
        var commandLine = de.kumpewo.autohotkey.compile.AhkCompiler.createCommandLine(ahk2Exe.toString(),
                script.toString(), de.kumpewo.autohotkey.settings.AhkSettings.Compression.NONE);
        var output = new com.intellij.execution.process.CapturingProcessHandler(commandLine)
                .runProcess(de.kumpewo.autohotkey.compile.AhkCompiler.TIMEOUT_MS, true);
        assertFalse(output.isTimeout());
        assertEquals(output.getStderr(), 0, output.getExitCode());
        java.nio.file.Path exe = dir.resolve("a b.exe");
        assertEquals(exe.toString(), de.kumpewo.autohotkey.compile.AhkCompiler.compiledFile(output.getStdout()));
        assertTrue(java.nio.file.Files.isRegularFile(exe));

        // syntax error: same format as /ErrorStdOut, so the console links it
        java.nio.file.Files.writeString(script, head + "y := (\r\n");
        output = new com.intellij.execution.process.CapturingProcessHandler(commandLine)
                .runProcess(de.kumpewo.autohotkey.compile.AhkCompiler.TIMEOUT_MS, true);
        assertTrue(output.getExitCode() != 0);
        var error = de.kumpewo.autohotkey.validate.AhkValidator.parse(output.getStdout() + "\n" + output.getStderr());
        assertNotNull(output.getStderr(), error);
        assertEquals(script.toString(), error.path());
        assertEquals(3, error.line());
    }

    public void testV2InterpreterInsteadOfLauncher() throws Exception {
        var v20 = de.kumpewo.autohotkey.parser.AhkVersion.scriptRequirement(
                "; #Requires AutoHotkey v1.1\r\n#Requires AutoHotkey v2.0 ; comment\r\nx := 1\r\n");
        assertEquals(new de.kumpewo.autohotkey.parser.AhkVersion.Requirement(true, 0), v20);
        for (String args : List.of("v2", "2.0.18", ">=2.0- <2.1", "v2.0-rc.2")) {
            assertTrue(args, de.kumpewo.autohotkey.parser.AhkVersion.requirement("AutoHotkey " + args).v20());
        }
        assertFalse(de.kumpewo.autohotkey.parser.AhkVersion.requirement("AutoHotkey v2.1-alpha.3").v20());
        assertFalse(de.kumpewo.autohotkey.parser.AhkVersion.requirement("AutoHotkey v1.1").v20());
        assertEquals(32, de.kumpewo.autohotkey.parser.AhkVersion.requirement("AutoHotkey v2.0 32-bit").bits());
        assertNull(de.kumpewo.autohotkey.parser.AhkVersion.scriptRequirement("#Requires AutoHotkey 64-bit\n"));

        // installation: UX\AutoHotkeyUX.exe + launcher.ahk, v2\AutoHotkey.exe/32/64
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("ahk");
        java.nio.file.Path ux = java.nio.file.Files.createDirectories(root.resolve("UX"));
        java.nio.file.Path v2 = java.nio.file.Files.createDirectories(root.resolve("v2"));
        java.nio.file.Path launcher = java.nio.file.Files.writeString(ux.resolve("AutoHotkeyUX.exe"), "");
        java.nio.file.Files.writeString(ux.resolve("launcher.ahk"), "");
        for (String name : List.of("AutoHotkey.exe", "AutoHotkey32.exe", "AutoHotkey64.exe")) {
            java.nio.file.Files.writeString(v2.resolve(name), "");
        }
        java.nio.file.Path script = root.resolve("a.ahk");
        de.kumpewo.autohotkey.settings.AhkSettings settings = de.kumpewo.autohotkey.settings.AhkSettings.getInstance();
        String before = settings.getAutoHotkeyPath();
        try {
            settings.setAutoHotkeyPath(launcher.toString());
            java.nio.file.Files.writeString(script, "#Requires AutoHotkey v2.0\r\n");
            assertEquals(v2.resolve("AutoHotkey.exe").toString(), settings.findAutoHotkey(script.toString()));
            java.nio.file.Files.writeString(script, "#Requires AutoHotkey v2.0 32-bit\r\n");
            assertEquals(v2.resolve("AutoHotkey32.exe").toString(), settings.findAutoHotkey(script.toString()));
            java.nio.file.Files.writeString(script, "#Requires AutoHotkey v2.1-alpha.3\r\n");
            assertEquals(launcher.toString(), settings.findAutoHotkey(script.toString()));
            java.nio.file.Files.writeString(script, "MsgBox 1\r\n");
            assertEquals(launcher.toString(), settings.findAutoHotkey(script.toString()));
            // a configured interpreter stays
            settings.setAutoHotkeyPath(v2.resolve("AutoHotkey64.exe").toString());
            java.nio.file.Files.writeString(script, "#Requires AutoHotkey v2.0 32-bit\r\n");
            assertEquals(v2.resolve("AutoHotkey64.exe").toString(), settings.findAutoHotkey(script.toString()));
        } finally {
            settings.setAutoHotkeyPath(before);
        }
    }

    public void testRunConfigurationCompileFirstCheck() throws Exception {
        de.kumpewo.autohotkey.run.AhkRunConfiguration configuration = (de.kumpewo.autohotkey.run.AhkRunConfiguration)
                de.kumpewo.autohotkey.run.AhkRunConfigurationType.getInstance().getFactory()
                        .createTemplateConfiguration(getProject());
        assertFalse(configuration.isCompileFirst());
        assertEquals(de.kumpewo.autohotkey.run.AhkRunConfiguration.ExeConsole.TERMINAL, configuration.getExeConsole());
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("ahk");
        java.nio.file.Path script = java.nio.file.Files.writeString(dir.resolve("a.ahk"), "MsgBox 1\n");
        java.nio.file.Path ahk2Exe = java.nio.file.Files.writeString(dir.resolve("Ahk2Exe.exe"), "");
        configuration.setScriptPath(script.toString());
        configuration.setCompileFirst(true);
        assertConfigurationError(configuration,
                "The script has no line ;@Ahk2Exe-Base, so it cannot be compiled with Ahk2Exe.");
        // UTF-16 with byte order mark is read, too
        java.nio.file.Files.write(script, ("\uFEFF;@Ahk2Exe-Base ..\\v2\\AutoHotkey64.exe\r\nMsgBox 1\r\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
        de.kumpewo.autohotkey.settings.AhkSettings settings = de.kumpewo.autohotkey.settings.AhkSettings.getInstance();
        String before = settings.getAhk2ExePath();
        try {
            settings.setAhk2ExePath(dir.resolve("fehlt.exe").toString());
            assertConfigurationError(configuration, AhkBundle.message("compile.ahk2exe.not.found"));
            settings.setAhk2ExePath(ahk2Exe.toString());
            configuration.checkConfiguration();
        } finally {
            settings.setAhk2ExePath(before);
        }
        configuration.setExeConsole(de.kumpewo.autohotkey.run.AhkRunConfiguration.ExeConsole.WINDOW);
        assertEquals(de.kumpewo.autohotkey.run.AhkRunConfiguration.ExeConsole.WINDOW, configuration.getExeConsole());
    }

    public void testExeCommandLine() {
        var line = de.kumpewo.autohotkey.run.AhkRunState.exeCommandLine("C:/x/a b.exe", "eins \"zwei drei\"",
                "C:/work", de.kumpewo.autohotkey.run.AhkRunConfiguration.ExeConsole.TERMINAL);
        assertEquals("C:\\x\\a b.exe", line.getExePath());
        assertEquals(List.of("eins", "zwei drei"), line.getParametersList().getList());
        assertEquals("C:\\work", line.getWorkDirectory().getPath());
        line = de.kumpewo.autohotkey.run.AhkRunState.exeCommandLine("C:/x/it's.exe", "eins", "C:/work",
                de.kumpewo.autohotkey.run.AhkRunConfiguration.ExeConsole.WINDOW);
        assertEquals("powershell.exe", line.getExePath());
        List<String> parameters = line.getParametersList().getList();
        assertEquals(List.of("-NoProfile", "-NonInteractive", "-EncodedCommand"), parameters.subList(0, 3));
        assertEquals("$p = Start-Process -FilePath 'C:\\x\\it''s.exe' -ArgumentList 'eins' -WorkingDirectory "
                        + "'C:\\work' -Wait -PassThru; exit $p.ExitCode",
                new String(java.util.Base64.getDecoder().decode(parameters.get(3)),
                        java.nio.charset.StandardCharsets.UTF_16LE));
        assertEquals("$p = Start-Process -FilePath 'a.exe' -Wait -PassThru; exit $p.ExitCode",
                de.kumpewo.autohotkey.run.AhkRunState.startProcessScript("a.exe", " ", ""));
    }

    public void testRunCompiledConsoleApp() throws Exception {
        java.nio.file.Path ahk2Exe = de.kumpewo.autohotkey.settings.AhkSettings.detectAhk2Exe();
        if (ahk2Exe == null || ahk2Exe.getParent().getParent() == null) return;
        java.nio.file.Path base = ahk2Exe.getParent().getParent().resolve("v2").resolve("AutoHotkey64.exe");
        if (!java.nio.file.Files.isRegularFile(base)) return;
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("ahk");
        java.nio.file.Path script = java.nio.file.Files.writeString(dir.resolve("a b.ahk"),
                "#Requires AutoHotkey v2.0\r\n;@Ahk2Exe-Base " + base + "\r\n;@Ahk2Exe-ConsoleApp\r\n"
                        + "FileAppend A_Args[2] \"|\" A_InitialWorkingDir,\"*\"\r\nExitApp 3\r\n");
        var output = de.kumpewo.autohotkey.compile.AhkCompiler.run(ahk2Exe.toString(), script.toString(),
                de.kumpewo.autohotkey.settings.AhkSettings.Compression.NONE,
                new com.intellij.openapi.progress.EmptyProgressIndicator());
        String exe = de.kumpewo.autohotkey.compile.AhkCompiler.compiledFile(output.getStdout());
        assertNotNull(output.getStderr(), exe);
        java.nio.file.Path work = java.nio.file.Files.createDirectory(dir.resolve("work dir"));
        // directly (as in the terminal, here without a pseudo console): parameters and working directory arrive
        var line = de.kumpewo.autohotkey.run.AhkRunState.exeCommandLine(exe, "eins \"zwei drei\"", work.toString(),
                de.kumpewo.autohotkey.run.AhkRunConfiguration.ExeConsole.TERMINAL);
        var result = new com.intellij.execution.process.CapturingProcessHandler(line).runProcess(30_000, true);
        assertEquals(3, result.getExitCode());
        assertEquals("zwei drei|" + work, result.getStdout());
        // in a pseudo console
        line = de.kumpewo.autohotkey.run.AhkRunState.exeCommandLine(exe, "eins \"zwei drei\"", work.toString(),
                de.kumpewo.autohotkey.run.AhkRunConfiguration.ExeConsole.TERMINAL);
        var pty = new com.intellij.execution.process.KillableProcessHandler(
                new com.intellij.execution.configurations.PtyCommandLine(line).withConsoleMode(false));
        pty.setHasPty(true);
        StringBuilder text = new StringBuilder();
        pty.addProcessListener(new com.intellij.execution.process.ProcessListener() {
            @Override
            public void onTextAvailable(@org.jetbrains.annotations.NotNull com.intellij.execution.process.ProcessEvent event,
                                        @org.jetbrains.annotations.NotNull com.intellij.openapi.util.Key outputType) {
                synchronized (text) {
                    text.append(event.getText());
                }
            }
        });
        pty.startNotify();
        assertTrue(pty.waitFor(30_000));
        assertEquals(Integer.valueOf(3), pty.getExitCode());
        synchronized (text) {
            assertTrue(text.toString(), text.toString().contains("zwei drei|" + work));
        }
        // separate console window: cmd /c start /wait passes the exit code through
        line = de.kumpewo.autohotkey.run.AhkRunState.exeCommandLine(exe, "eins \"zwei drei\"", work.toString(),
                de.kumpewo.autohotkey.run.AhkRunConfiguration.ExeConsole.WINDOW);
        result = new com.intellij.execution.process.CapturingProcessHandler(line).runProcess(30_000, true);
        assertFalse(result.isTimeout());
        assertEquals(line.getCommandLineString() + "\n" + result.getStderr(), 3, result.getExitCode());
    }

    public void testIgnoreNextLineWithoutErrorIsUnnecessary() {
        myFixture.configureByText("a.ahk", "x := 1 <warning descr=\"No parser errors in the next line, "
                + ";@ignore-next-line is unnecessary.\">;@ignore-next-line</warning>\ny := 2\n");
        myFixture.checkHighlighting(true, false, false);
    }

    // ---------------------------------------------------------------- #Requires AutoHotkey

    public void testOtherVersionWarnsOnlyAtRequires() {
        myFixture.configureByText("a.ahk", ""
                + "; Skript fuer v1\n"
                + "<warning descr=\"The parser only understands AutoHotkey version 2\">#Requires AutoHotkey v1.1</warning>"
                + " ; Kommentar\n"
                + "MsgBox, \"nicht geschlossen\n"
                + "x := 1 /* kein Fehler */\n"
                + "}\n");
        myFixture.checkHighlighting(true, false, true);
    }

    public void testMissingRequiresIsHint() {
        myFixture.configureByText("a.ahk", "\n<weak_warning descr=\"#Requires AutoHotkey v2 is missing\">x := 1</weak_warning>"
                + "\ny := 2\n");
        myFixture.checkHighlighting(true, false, true);
        myFixture.configureByText("a.ahk", "#Requires AutoHotkey v2.0\nx := 1\n");
        myFixture.checkHighlighting(true, false, true);
    }

    public void testQuickFixInsertsRequires() {
        // only offered in the range of the hint (first line)
        myFixture.configureByText("a.ahk", "; Kopf\nx<caret> := 1\n");
        assertEmpty(myFixture.filterAvailableIntentions("Insert '#Requires AutoHotkey v2.0'"));
        myFixture.configureByText("a.ahk", "; Ko<caret>pf\nx := 1\n");
        myFixture.launchAction(myFixture.findSingleIntention("Insert '#Requires AutoHotkey v2.0'"));
        assertEquals("#Requires AutoHotkey v2.0\n; Kopf\nx := 1\n", myFixture.getEditor().getDocument().getText());
        myFixture.configureByText("a.ahk", "x<caret> := 1\n");
        myFixture.launchAction(myFixture.findSingleIntention("Insert '#Requires AutoHotkey v2.0'"));
        assertEquals("#Requires AutoHotkey v2.0\nx := 1\n", myFixture.getEditor().getDocument().getText());
        // no hint afterwards
        assertTrue(myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).isEmpty());
    }

    /** Output of the helper script of the version detection, and the helper script itself. */
    public void testVersionDetectionOutput() {
        var v1 = de.kumpewo.autohotkey.annotation.AhkVersionDetector.parse("1\nv1-cmd v1-ass\n");
        assertEquals(1, v1.major());
        assertEquals("v1-cmd v1-ass", v1.marks());
        assertEquals(2, de.kumpewo.autohotkey.annotation.AhkVersionDetector.parse("2\r\nv2-xyz\r\n").major());
        assertEquals(0, de.kumpewo.autohotkey.annotation.AhkVersionDetector.parse("0\nno tell-tale matches\n").major());
        assertEquals(0, de.kumpewo.autohotkey.annotation.AhkVersionDetector.parse("\n\n").major());
        assertEquals(0, de.kumpewo.autohotkey.annotation.AhkVersionDetector.parse("").major());
        String helper = de.kumpewo.autohotkey.annotation.AhkVersionDetector.helperScript(
                java.nio.file.Path.of("C:\\AHK\\UX\\inc"));
        assertTrue(helper, helper.contains("#Include C:\\AHK\\UX\\inc\\\n#Include identify.ahk\n"));
        assertTrue(helper, helper.contains("IdentifyBySyntax(FileRead(A_Args[1], \"UTF-8\"))"));
    }

    /** The syntax detection of the installed AutoHotkey launcher (skipped without installation). */
    public void testVersionDetectionWithInstalledAutoHotkey() {
        if (de.kumpewo.autohotkey.annotation.AhkVersionDetector.identifyDirectory() == null) return;
        var v1 = de.kumpewo.autohotkey.annotation.AhkVersionDetector.detect("MsgBox, Hello\nx = abc\n");
        assertEquals(1, v1.major());
        assertTrue(v1.marks(), v1.marks().contains("v1-"));
        assertEquals(0, de.kumpewo.autohotkey.annotation.AhkVersionDetector.detect("x := 1\n").major());
        // non-ASCII text survives the round trip through the temporary file
        assertEquals(1, de.kumpewo.autohotkey.annotation.AhkVersionDetector.detect("MsgBox, Grüße\n").major());
    }

    /**
     * File without #Requires whose syntax looks like v1: after the detection in the background a warning with the
     * fix for "#Requires AutoHotkey v1.1" first (skipped without installation).
     */
    public void testV1CodeGetsV1RequiresFix() throws Exception {
        if (de.kumpewo.autohotkey.annotation.AhkVersionDetector.identifyDirectory() == null) return;
        de.kumpewo.autohotkey.annotation.AhkVersionDetector.enableForTest(getTestRootDisposable());
        myFixture.configureByText("a.ahk", "MsgBox<caret>, Hello\nx = abc\n");
        String v1Text = "#Requires is missing; this looks like AutoHotkey v1 code";
        long until = System.currentTimeMillis() + 20_000;
        boolean found = false;
        while (!found && System.currentTimeMillis() < until) {
            for (var info : myFixture.doHighlighting(HighlightSeverity.WARNING)) {
                if (info.getDescription() != null && info.getDescription().startsWith(v1Text)) found = true;
            }
            if (!found) {
                Thread.sleep(200);
                com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
            }
        }
        assertTrue("no v1 warning", found);
        var intentions = myFixture.getAvailableIntentions();
        List<String> texts = new ArrayList<>();
        for (var intention : intentions) texts.add(intention.getText());
        assertTrue(texts.toString(), texts.indexOf("Insert '#Requires AutoHotkey v1.1'")
                < texts.indexOf("Insert '#Requires AutoHotkey v2.0'"));
        assertTrue(texts.toString(), texts.indexOf("Insert '#Requires AutoHotkey v1.1'") >= 0);
        myFixture.launchAction(myFixture.findSingleIntention("Insert '#Requires AutoHotkey v1.1'"));
        assertTrue(myFixture.getEditor().getDocument().getText().startsWith("#Requires AutoHotkey v1.1\nMsgBox, Hello"));
        // now a file for another version: only the warning at #Requires
        List<String> warnings = new ArrayList<>();
        for (var info : myFixture.doHighlighting(HighlightSeverity.WARNING)) warnings.add(info.getDescription());
        assertEquals(List.of("The parser only understands AutoHotkey version 2"), warnings);
    }

    public void testOtherVersionIsNotFormatted() {
        String v1 = "#Requires AutoHotkey v1.1\nx:=1\nif (x){\nMsgBox,   x\n}\n";
        assertFormatted(v1, v1);
    }

    public void testIgnoreNextLineDoesNotApplyWithoutMarker() {
        myFixture.configureByText("a.ahk", ";@ignore-next-lines\nclass X extends %Y% {\n}\n");
        List<HighlightInfo> errors = myFixture.doHighlighting(HighlightSeverity.ERROR);
        assertFalse(errors.isEmpty());
    }

    public void testIgnoreNextLineColorsUniformly() {
        myFixture.configureByText("a.ahk", ";@ignore-next-line\nclass X extends %Y% {\n}\nclass Z {\n}\n");
        String text = myFixture.getFile().getText();
        int lineStart = text.indexOf("class X");
        int lineEnd = text.indexOf('\n', lineStart);
        boolean forced = false;
        boolean zColored = false;
        for (HighlightInfo info : myFixture.doHighlighting()) {
            boolean inLine = info.getStartOffset() >= lineStart && info.getEndOffset() <= lineEnd;
            if (inLine && info.forcedTextAttributes != null) forced = true;
            // the class name of the marked line gets no own color, the one of Z does
            if (inLine) assertNotSame(AhkColors.AHK_CLASS, info.forcedTextAttributesKey);
            else if (info.forcedTextAttributesKey == AhkColors.AHK_CLASS) zColored = true;
        }
        assertTrue("no uniform coloring in the marked line", forced);
        assertTrue("class Z not colored", zColored);
    }

    // ---------------------------------------------------------------- Formatter

    public void testFormatSwitch() {
        assertFormatted("""
                switch b {
                    case 1: msgbox ("eins")
                case 2:
                msgbox ("zwei")
                        y := 1
                  default  :   msgbox ("---")
                }
                """, """
                switch b {
                case 1: msgbox ("eins")
                case 2:
                    msgbox ("zwei")
                    y := 1
                default: msgbox ("---")
                }
                """);
    }

    public void testFormatElse() {
        assertFormatted("""
                if a {
                }   else {
                }
                for a in b
                c()
                else
                d()
                """, """
                if a {
                } else {
                }
                for a in b
                    c()
                else
                    d()
                """);
    }

    // ---------------------------------------------------------------- help with several entries (fourth column)

    private List<String> helpVariants(String textWithCaret) {
        myFixture.configureByText("a.ahk", textWithCaret);
        PsiElement leaf = myFixture.getFile().findElementAt(myFixture.getCaretOffset());
        return de.kumpewo.autohotkey.documentation.AhkDocumentationProvider.topics(leaf, null).stream()
                .map(de.kumpewo.autohotkey.documentation.AhkHelpRepository.Topic::label).toList();
    }

    /**
     * Lookup files with own test pages (src/test/resources/help/extra, invented names): several entries with a
     * class, narrowing by type, anchors, docs.txt before extra.txt, external help only for docs.txt.
     */
    public void testHelpVariants() {
        de.kumpewo.autohotkey.documentation.AhkHelpRepository.useListsForTest(String.join("\n",
                "; Kommentar",
                "TestVariante\t/extra/TestVariante.Gui.htm\t<null>\tGui",
                "TestVariante\t/extra/testvariante.menu.htm\t<null>\tMenu",              // no page: ignored
                "TestVariante\t/extra/testvariante.gui.listview.htm\t<null>\tGui.ListView",
                "TestVariante\t/extra/testvariante.gui.control.htm\tteil\tGui.Control",
                "TestVariante\t/extra/testvariante.gui.htm\t<null>\tgui",                // same target: once
                "EinzelVariante\t/extra/einzelvariante.gui.htm\t<null>\tGui",
                "OhneKlasse\t/extra/testanker.htm\tteil",
                "OhneKlasse\t/extra/einzelvariante.gui.htm\t<null>",
                "Kaputt\t/extra/testanker.htm",                                          // malformed: ignored
                "MsgBox\t/extra/einzelvariante.gui.htm\t<null>", ""), String.join("\n",
                "Pragma\t/extra/testanker.htm\tteil",
                "MsgBox\t/extra/testanker.htm\t<null>"), getTestRootDisposable());
        List<String> alle = List.of("Gui.TestVariante", "Gui.ListView.TestVariante", "Gui.Control.TestVariante");
        // type unknown: all entries with a page, in the order of the file
        assertEquals(alle, helpVariants("x.Test<caret>Variante()\n"));
        // narrowed: built-in class on the left, declared type with base classes
        assertEquals(List.of("Gui.TestVariante"), helpVariants("Gui.Test<caret>Variante()\n"));
        assertEquals(List.of("Gui.ListView.TestVariante"),
                helpVariants(";@declare-type lv as Gui.ListView\nlv.Test<caret>Variante()\n"));
        assertEquals(List.of("Gui.Control.TestVariante"),
                helpVariants(";@declare-type e as Gui.Edit\ne.Test<caret>Variante()\n"));
        // type without a matching entry (Menu has no page): all
        assertEquals(alle, helpVariants(";@declare-type m as Menu\nm.Test<caret>Variante()\n"));
        assertEquals(List.of("Gui.EinzelVariante"), helpVariants("x.Einzel<caret>Variante()\n"));
        // free-standing names: only entries without a class
        assertEquals(List.of(), helpVariants("Test<caret>Variante()\n"));
        assertEquals(List.of("OhneKlasse (testanker.htm#teil)", "OhneKlasse (einzelvariante.gui.htm)"),
                helpVariants("Ohne<caret>Klasse()\n"));
        assertEquals(List.of(), helpVariants("Kap<caret>utt()\n"));
        // docs.txt before extra.txt
        assertEquals(List.of("MsgBox (einzelvariante.gui.htm)"), helpVariants("Msg<caret>Box \"x\"\n"));

        // popup: overview page with links; anchor = page from the element with the id on
        de.kumpewo.autohotkey.documentation.AhkDocumentationProvider provider =
                new de.kumpewo.autohotkey.documentation.AhkDocumentationProvider();
        myFixture.configureByText("a.ahk", "x.Test<caret>Variante()\n");
        PsiElement leaf = myFixture.getFile().findElementAt(myFixture.getCaretOffset());
        String overview = provider.generateDoc(leaf, leaf);
        assertNotNull(overview);
        assertTrue(overview, overview.contains("href=\"psi_element://extra/testvariante.gui.htm\">Gui.TestVariante</a>"));
        assertTrue(overview, overview.contains(
                "href=\"psi_element://extra/testvariante.gui.control.htm#teil\">Gui.Control.TestVariante</a>"));
        assertFalse(overview, overview.contains("Menu"));
        // a link in the overview opens the page
        PsiElement linked = provider.getDocumentationElementForLink(getPsiManager(), "extra/testvariante.gui.htm", leaf);
        assertNotNull(linked);
        String fromLink = provider.generateDoc(linked, null);
        assertTrue(fromLink, fromLink.contains("Testseite gui"));
        // external viewer: path or path#anchor as in docs.txt
        assertEquals(List.of("/extra/TestVariante.Gui.htm", "/extra/testvariante.gui.listview.htm",
                "/extra/testvariante.gui.control.htm#teil"), provider.getUrlFor(leaf, leaf));
        assertTrue(provider.canHandleExternal(leaf, leaf));
        myFixture.configureByText("a.ahk", ";@declare-type lv as Gui.ListView\nlv.Test<caret>Variante()\n");
        leaf = myFixture.getFile().findElementAt(myFixture.getCaretOffset());
        String page = provider.generateDoc(leaf, leaf);
        assertTrue(page, page.contains("Testseite gui.listview"));
        assertEquals(List.of("/extra/testvariante.gui.listview.htm"), provider.getUrlFor(leaf, leaf));

        // extra.txt: popup with anchor; external help only if the plugin has its extra.chm (help/extra.chm)
        myFixture.configureByText("a.ahk", "Pra<caret>gma()\n");
        leaf = myFixture.getFile().findElementAt(myFixture.getCaretOffset());
        page = provider.generateDoc(leaf, leaf);
        assertNotNull(page);
        assertTrue(page, page.contains("<body><h2 id=\"teil\">Teil</h2>"));
        assertFalse(page, page.contains("Vorspann"));
        boolean chm = de.kumpewo.autohotkey.documentation.AhkExternalHelp.hasExtraChm();
        assertEquals(chm ? List.of("/extra/testanker.htm#teil") : null, provider.getUrlFor(leaf, leaf));
        assertEquals(chm, provider.canHandleExternal(leaf, leaf));
        // Ctrl+B: target is the help also for several entries
        assertEquals(List.of("TestVariante (help)"), targets("x.Test<caret>Variante()\n"));
    }

    // ---------------------------------------------------------------- live templates and file template

    /** Abbreviation + Tab with the default values; result with "|" at the caret position. */
    private String template(String textWithCaret) {
        com.intellij.codeInsight.template.impl.TemplateManagerImpl.setTemplateTesting(getTestRootDisposable());
        myFixture.configureByText("a.ahk", textWithCaret);
        myFixture.type('\t');
        com.intellij.codeInsight.template.impl.TemplateState state =
                com.intellij.codeInsight.template.impl.TemplateManagerImpl.getTemplateState(myFixture.getEditor());
        if (state != null) WriteCommandAction.runWriteCommandAction(getProject(), () -> state.gotoEnd(false));
        String text = myFixture.getEditor().getDocument().getText();
        int caret = myFixture.getEditor().getCaretModel().getOffset();
        return text.substring(0, caret) + "|" + text.substring(caret);
    }

    public void testLiveTemplates() {
        assertEquals("MyFunction() {\n    |\n}\n", template("fn<caret>\n"));
        assertEquals("#Requires AutoHotkey v2.0|\n", template("req<caret>\n"));
        assertEquals("Class MyClass {\n    __New() {\n        |\n    }\n}\n", template("cls<caret>\n"));
        // indented and with reformat: no empty lines at CRLF (bug of the PSI formatting path)
        assertEquals("f() {\n    If condition {\n        |\n    }\n    x := 1\n}\n",
                template("f() {\n    if<caret>\n    x := 1\n}\n"));
        assertEquals("Try {\n    |\n} Catch Error as e {\n    MsgBox e.Message\n}\n", template("try<caret>\n"));
        // case at the level of switch, body one level deeper
        assertEquals("Switch value {\nCase 1:\n    |\nDefault:\n}\n", template("sw<caret>\n"));
        assertEquals("For key, value in obj {\n    |\n}\n", template("for<caret>\n"));
        assertEquals("Loop Files \"*.*\", \"F\" {\n    path := A_LoopFileFullPath|\n}\n", template("lf<caret>\n"));
        assertEquals(";@declare-type myGui as Gui|\n", template("dt<caret>\n"));
        // not in comments and strings
        assertFalse(template("; fn<caret>\n").contains("MyFunction"));
        assertFalse(template("x := \"fn<caret>\"\n").contains("MyFunction"));
        assertFalse(template("::btw::fn<caret>\n").contains("MyFunction"));
    }

    /** Enter directly after "case x:" without a body: one level deeper; after a body at the level of switch. */
    public void testEnterAfterCase() {
        myFixture.configureByText("a.ahk", "switch x {\ncase 1:<caret>\n}\n");
        myFixture.type('\n');
        assertEquals("switch x {\ncase 1:\n    \n}\n", myFixture.getEditor().getDocument().getText());
        myFixture.configureByText("a.ahk", "switch x {\ncase 1: a()<caret>\n}\n");
        myFixture.type('\n');
        assertEquals("switch x {\ncase 1: a()\n\n}\n", myFixture.getEditor().getDocument().getText());
    }

    public void testLiveTemplatesSurround() {
        com.intellij.codeInsight.template.impl.TemplateManagerImpl.setTemplateTesting(getTestRootDisposable());
        myFixture.configureByText("a.ahk", "<selection>x := 1\n</selection>y := 2\n");
        com.intellij.codeInsight.template.impl.TemplateImpl template = com.intellij.codeInsight.template.impl
                .TemplateSettings.getInstance().getTemplate("stry", "AutoHotkey");
        assertNotNull(template);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> new com.intellij.codeInsight.template.impl
                .InvokeTemplateAction(template, myFixture.getEditor(), getProject(), new java.util.HashSet<>()).perform());
        com.intellij.codeInsight.template.impl.TemplateState state =
                com.intellij.codeInsight.template.impl.TemplateManagerImpl.getTemplateState(myFixture.getEditor());
        if (state != null) WriteCommandAction.runWriteCommandAction(getProject(), () -> state.gotoEnd(false));
        assertEquals("Try {\n    x := 1\n} Catch Error as e {\n    MsgBox e.Message\n}\ny := 2\n",
                myFixture.getEditor().getDocument().getText());
    }

    public void testFileTemplate() {
        com.intellij.ide.fileTemplates.FileTemplate t = com.intellij.ide.fileTemplates.FileTemplateManager
                .getInstance(getProject()).getInternalTemplate(de.kumpewo.autohotkey.template.AhkCreateFileAction.TEMPLATE);
        assertEquals("ahk", t.getExtension());
        assertEquals("#Requires AutoHotkey v2.0\n", t.getText());
    }

    /**
     * An empty .ahk file created by the IDE ("New > File") gets the file template; files from outside (refresh), files
     * with content and other file types stay as they are.
     */
    public void testNewEmptyAhkFileGetsTemplate() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("ahk-new");
        try {
            var vDir = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir);
            assertNotNull(vDir);
            Object requestor = this;
            com.intellij.openapi.vfs.VirtualFile ahk = com.intellij.openapi.application.WriteAction.compute(
                    () -> vDir.createChildData(requestor, "new.ahk"));
            com.intellij.openapi.vfs.VirtualFile txt = com.intellij.openapi.application.WriteAction.compute(
                    () -> vDir.createChildData(requestor, "new.txt"));
            com.intellij.openapi.vfs.VirtualFile full = com.intellij.openapi.application.WriteAction.compute(() -> {
                com.intellij.openapi.vfs.VirtualFile f = vDir.createChildData(requestor, "full.ahk");
                com.intellij.openapi.vfs.VfsUtil.saveText(f, "x := 1\n");
                return f;
            });
            // a file that appears on disk from outside
            java.nio.file.Files.writeString(dir.resolve("outside.ahk"), "");
            var outside = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir.resolve("outside.ahk"));
            com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
            var documents = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance();
            assertEquals("#Requires AutoHotkey v2.0\n", documents.getDocument(ahk).getText());
            assertEquals("", documents.getDocument(txt).getText());
            assertEquals("x := 1\n", documents.getDocument(full).getText());
            assertEquals("", documents.getDocument(outside).getText());
            // save, so that no modified document of the deleted folder is left behind
            com.intellij.openapi.application.WriteAction.run(() -> documents.saveAllDocuments());
        } finally {
            com.intellij.openapi.util.io.FileUtil.delete(dir);
        }
    }

    /** Operator at the start of a line inside ( ): continuation indent, spaces as configured, ". " stays a concatenation. */
    public void testFormatOperatorAtStartOfLine() {
        assertFormatted("x := (a\n+   b\n  -c)\n", "x := (a\n        + b\n        - c)\n");
        assertFormatted("f() {\nfoo(obj\n.First()\n   .Second())\n}\n",
                "f() {\n    foo(obj\n            .First()\n            .Second())\n}\n");
        assertFormatted("x := \"a\"\n. b\n", "x := \"a\"\n        . b\n");
        // default value of a parameter across lines: continuation indent, the body stays normal
        assertFormatted("f(a := \"x\"\n\"y\") {\nreturn a\n}\n", "f(a := \"x\"\n        \"y\") {\n    return a\n}\n");
    }

    /** Continuation indents do not add up for nested expressions: one level for all continuation lines. */
    public void testFormatUniformContinuationIndent() {
        // operators at the end of the line
        assertFormatted("f() {\nMsgBox(\"Sum: \" .\na +\nb)\n}\n",
                "f() {\n    MsgBox(\"Sum: \" .\n            a +\n            b)\n}\n");
        assertFormatted("f() {\nMsgBox(a +\nb *\nc)\n}\n", "f() {\n    MsgBox(a +\n            b *\n            c)\n}\n");
        assertFormatted("f() {\nx := a .\nb +\nc\n}\n", "f() {\n    x := a .\n            b +\n            c\n}\n");
        assertFormatted("x := a + b *\nc\n", "x := a + b *\n        c\n");
        // operators at the start of the line inside ( )
        assertFormatted("f() {\nMsgBox(a\n+ b\n* c)\n}\n", "f() {\n    MsgBox(a\n            + b\n            * c)\n}\n");
        assertFormatted("f() {\nx := (a\n. b\n+ c)\n}\n", "f() {\n    x := (a\n            . b\n            + c)\n}\n");
        assertFormatted("x := (a\n+ b * c\n* d\n- e)\n", "x := (a\n        + b * c\n        * d\n        - e)\n");
        // arguments after a comma: also one level
        assertFormatted("foo(a,\nb *\nc)\n", "foo(a,\n        b *\n        c)\n");
        // bracket at the end of the line: normal indent for the content, continuation for its continuation lines
        assertFormatted("foo(\na\n+ b\n)\n", "foo(\n    a\n            + b\n)\n");
    }

    public void testFormatCommaChain() {
        assertFormatted("MouseClick \"Left\" , ,  ,1\nf(a,  , b)\n", "MouseClick \"Left\",,, 1\nf(a,, b)\n");
    }

    // ---------------------------------------------------------------- run

    public void testRunConfigurationFromContext() {
        myFixture.configureByText("haupt.ahk", "MsgBox \"x\"<caret>\n");
        com.intellij.execution.actions.ConfigurationContext context =
                com.intellij.execution.actions.ConfigurationContext.getFromContext(
                        com.intellij.ide.DataManager.getInstance().getDataContext(myFixture.getEditor().getComponent()),
                        com.intellij.openapi.actionSystem.ActionPlaces.UNKNOWN);
        de.kumpewo.autohotkey.run.AhkRunConfigurationProducer producer =
                com.intellij.execution.actions.RunConfigurationProducer.getInstance(
                        de.kumpewo.autohotkey.run.AhkRunConfigurationProducer.class);
        com.intellij.execution.actions.ConfigurationFromContext fromContext =
                producer.createConfigurationFromContext(context);
        assertNotNull(fromContext);
        de.kumpewo.autohotkey.run.AhkRunConfiguration configuration =
                (de.kumpewo.autohotkey.run.AhkRunConfiguration) fromContext.getConfiguration();
        assertEquals(myFixture.getFile().getVirtualFile().getPath(), configuration.getScriptPath());
        assertEquals("haupt.ahk", configuration.getName());
        assertTrue(producer.isConfigurationFromContext(configuration, context));
        configuration.setScriptPath("/anderes.ahk");
        assertFalse(producer.isConfigurationFromContext(configuration, context));
    }

    public void testRunConfigurationCheck() throws Exception {
        de.kumpewo.autohotkey.run.AhkRunConfiguration configuration = (de.kumpewo.autohotkey.run.AhkRunConfiguration)
                de.kumpewo.autohotkey.run.AhkRunConfigurationType.getInstance().getFactory()
                        .createTemplateConfiguration(getProject());
        assertConfigurationError(configuration, "No script specified.");
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("ahk");
        java.nio.file.Path script = java.nio.file.Files.writeString(dir.resolve("a.ahk"), "MsgBox 1\n");
        java.nio.file.Path exe = java.nio.file.Files.writeString(dir.resolve("AutoHotkey.exe"), "");
        configuration.setScriptPath(dir.resolve("fehlt.ahk").toString());
        assertConfigurationError(configuration, "Script not found: " + dir.resolve("fehlt.ahk"));
        configuration.setScriptPath(script.toString());
        configuration.setWorkingDirectory(dir.resolve("fehlt").toString());
        assertConfigurationError(configuration, "Working directory not found: " + dir.resolve("fehlt"));
        configuration.setWorkingDirectory("");
        assertEquals(com.intellij.openapi.util.io.FileUtil.toSystemIndependentName(dir.toString()),
                configuration.getEffectiveWorkingDirectory());
        de.kumpewo.autohotkey.settings.AhkSettings settings = de.kumpewo.autohotkey.settings.AhkSettings.getInstance();
        String before = settings.getAutoHotkeyPath();
        try {
            settings.setAutoHotkeyPath(dir.resolve("fehlt.exe").toString());
            assertConfigurationError(configuration, "AutoHotkey executable not found: " + dir.resolve("fehlt.exe"));
            settings.setAutoHotkeyPath(exe.toString());
            configuration.checkConfiguration();
        } finally {
            settings.setAutoHotkeyPath(before);
        }
    }

    private static void assertConfigurationError(de.kumpewo.autohotkey.run.AhkRunConfiguration configuration,
                                                   String message) {
        try {
            configuration.checkConfiguration();
            fail("error expected: " + message);
        } catch (com.intellij.execution.configurations.RuntimeConfigurationException e) {
            assertEquals(message, e.getLocalizedMessage());
        }
    }

    public void testLauncherIsDetected() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("ahk");
        java.nio.file.Path ux = java.nio.file.Files.writeString(dir.resolve("AutoHotkeyUX.exe"), "");
        java.nio.file.Path exe = java.nio.file.Files.writeString(dir.resolve("AutoHotkey64.exe"), "");
        assertNull(de.kumpewo.autohotkey.settings.AhkSettings.launcherScript(ux));
        java.nio.file.Path launcher = java.nio.file.Files.writeString(dir.resolve("launcher.ahk"), "");
        assertEquals(launcher, de.kumpewo.autohotkey.settings.AhkSettings.launcherScript(ux));
        assertNull(de.kumpewo.autohotkey.settings.AhkSettings.launcherScript(exe));
    }

    public void testErrorInConsoleIsClickable() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("ahk");
        java.nio.file.Path script = java.nio.file.Files.writeString(dir.resolve("mein skript.ahk"), "a\nx := (\n");
        com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(script);
        com.intellij.execution.filters.Filter filter = new de.kumpewo.autohotkey.run.AhkErrorFilter(getProject());
        String line = script + " (2) : ==> Missing \")\"\n";
        int offset = 100;
        com.intellij.execution.filters.Filter.Result result = filter.applyFilter(line, offset + line.length());
        assertNotNull(result);
        com.intellij.execution.filters.Filter.ResultItem item = result.getResultItems().get(0);
        assertEquals(offset, item.getHighlightStartOffset());
        assertEquals(script + " (2)", line.substring(item.getHighlightStartOffset() - offset,
                item.getHighlightEndOffset() - offset));
        com.intellij.execution.filters.OpenFileHyperlinkInfo link =
                (com.intellij.execution.filters.OpenFileHyperlinkInfo) item.getHyperlinkInfo();
        // line 2 = offset 2 (after "a\n"); the platform converts the line into an offset
        assertEquals(2, link.getDescriptor().getOffset());
        assertNull(filter.applyFilter("     Specifically: x := (\n", 30));
        assertNull(filter.applyFilter(dir.resolve("fehlt.ahk") + " (2) : ==> x\n", 30));
    }

    public void testCommandLine() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("ahk");
        java.nio.file.Path ux = java.nio.file.Files.writeString(dir.resolve("AutoHotkeyUX.exe"), "");
        java.nio.file.Path launcher = java.nio.file.Files.writeString(dir.resolve("launcher.ahk"), "");
        String script = dir.resolve("a b.ahk").toString();
        com.intellij.execution.configurations.GeneralCommandLine line = de.kumpewo.autohotkey.run.AhkRunState
                .createCommandLine(ux.toString(), script, "eins \"zwei drei\"", dir.toString());
        assertEquals(ux.toString(), line.getExePath());
        assertEquals(java.util.List.of(launcher.toString(), "/ErrorStdOut", script, "eins", "zwei drei"),
                line.getParametersList().getList());
        assertEquals(dir.toFile(), line.getWorkDirectory());
        java.nio.file.Path exe = java.nio.file.Files.writeString(dir.resolve("AutoHotkey.exe"), "");
        line = de.kumpewo.autohotkey.run.AhkRunState.createCommandLine(exe.toString(), script, "", "");
        assertEquals(java.util.List.of("/ErrorStdOut", script), line.getParametersList().getList());
    }

    /** Options of the doc comment formatter: the defaults (like JavaDoc), changed by name. */
    private static de.kumpewo.autohotkey.formatter.AhkDocCommentFormatter.Options docOptions(String... on) {
        AhkCodeStyleSettings settings = new AhkCodeStyleSettings(com.intellij.psi.codeStyle.CodeStyleSettings.getDefaults());
        for (String option : on) {
            boolean value = !option.startsWith("!");
            String field = "DOC_" + option.replace("!", "");
            try {
                AhkCodeStyleSettings.class.getField(field).setBoolean(settings, value);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(field, e);
            }
        }
        return de.kumpewo.autohotkey.formatter.AhkDocCommentFormatter.Options.of(settings);
    }

    /** Formats a doc comment at column 0 with right margin 60 (test helper). */
    private static String formatDoc(String comment, List<String> parameters, String... options) {
        return de.kumpewo.autohotkey.formatter.AhkDocCommentFormatter.format(comment, "", 60, parameters, docOptions(options));
    }

    private static final String MESSY_DOC = String.join("\n",
            "/**",
            "Opens the box and counts the openings. A long description is wrapped at the right margin.",
            "     * @param {String} name name of the box",
            "  * @param {Boolean} force open even if the box is locked by someone else",
            " * @returns {Integer} the new number of openings",
            " * @example",
            " *   b := Box()",
            " *   b.Open(\"tools\")",
            " * @see Close",
            " */");

    public void testDocCommentFormatterDefaults() {
        assertEquals(String.join("\n",
                "/**",
                " * Opens the box and counts the openings. A long description",
                " * is wrapped at the right margin.",
                " *",
                " * @param {String}  name   name of the box",
                " * @param {Boolean} force  open even if the box is locked by",
                " *                         someone else",
                " * @returns {Integer} the new number of openings",
                " * @example",
                " *   b := Box()",
                " *   b.Open(\"tools\")",
                " * @see Close",
                " */"), formatDoc(MESSY_DOC, List.of("name", "force")));
        // indented: every line gets the indent, the width shrinks
        String indented = de.kumpewo.autohotkey.formatter.AhkDocCommentFormatter.format(
                "/** One two three four five six seven eight nine ten eleven twelve. */", "        ", 40, null, docOptions());
        // width 40 - 8 - 3 = 29
        assertEquals("/**\n         * One two three four five six\n         * seven eight nine ten eleven\n"
                + "         * twelve.\n         */", indented);
    }

    public void testDocCommentFormatterIsStable() {
        String[][] variants = {{}, {"!ALIGN_PARAM_DESCRIPTIONS"}, {"!ALIGN_TYPES_AND_NAMES"}, {"INDENT_CONTINUATION"},
                {"PARAM_DESCRIPTION_ON_NEW_LINE"}, {"BLANK_AFTER_PARAMS", "BLANK_AFTER_RETURNS", "BLANK_BEFORE_EXAMPLE"},
                {"!BLANK_AFTER_DESCRIPTION"}, {"!WRAP"}, {"KEEP_ONE_LINE"}, {"PRESERVE_LINE_FEEDS"}, {"!LEADING_ASTERISKS"},
                {"!KEEP_INVALID_TAGS"}, {"!KEEP_EMPTY_TAGS"}, {"SORT_PARAMS"}, {"ADD_MISSING_PARAMS"},
                {"ADD_MISSING_PARAMS", "!KEEP_EMPTY_TAGS"}};
        String markdown = String.join("\n", "/**", " * Text with a list:", " * - first item that is long enough to be"
                + " wrapped somewhere", " * - second", " *", " * ```", " * code  stays   as is", " * ```", " *",
                " * # Heading", " * | a | b |", " * hard break  ", " * after", " * @param x", " * @param", " * @throws",
                " */");
        for (String[] options : variants) {
            for (String comment : new String[]{MESSY_DOC, markdown, "/** Short. */", "/**\n * @param b\n * @param a\n */"}) {
                String once = formatDoc(comment, List.of("a", "b", "name", "force"), options);
                assertEquals(java.util.Arrays.toString(options) + "\n" + once, once,
                        formatDoc(once, List.of("a", "b", "name", "force"), options));
            }
        }
    }

    public void testDocCommentFormatterOptions() {
        String doc = "/**\n * Text.\n * @param {Gui} g the window\n * @param name\n * @param other\n * @returns x\n"
                + " * @example\n *   f()\n */";
        List<String> params = List.of("name", "g");
        // blank lines
        assertEquals("/**\n * Text.\n * @param {Gui} g      the window\n * @param       name\n * @param       other\n"
                + " *\n * @returns x\n *\n * @example\n *   f()\n */",
                formatDoc(doc, params, "!BLANK_AFTER_DESCRIPTION", "BLANK_AFTER_PARAMS", "BLANK_AFTER_RETURNS",
                        "BLANK_BEFORE_EXAMPLE"));
        // without alignment
        assertEquals("/**\n * Text.\n *\n * @param {Gui} g the window\n * @param name\n * @param other\n * @returns x\n"
                + " * @example\n *   f()\n */", formatDoc(doc, params, "!ALIGN_PARAM_DESCRIPTIONS", "!ALIGN_TYPES_AND_NAMES"));
        // description on its own line
        assertTrue(formatDoc(doc, params, "PARAM_DESCRIPTION_ON_NEW_LINE").contains(" * @param {Gui} g\n *     the window\n"));
        // invalid, empty, sorted, missing
        String tags = formatDoc(doc, params, "!KEEP_INVALID_TAGS", "!KEEP_EMPTY_TAGS", "SORT_PARAMS");
        assertTrue(tags, tags.contains(" * @param {Gui} g  the window\n * @returns x"));
        assertFalse(tags, tags.contains("other") || tags.contains("@param name"));
        String sorted = formatDoc(doc, params, "SORT_PARAMS", "!ALIGN_PARAM_DESCRIPTIONS", "!ALIGN_TYPES_AND_NAMES");
        assertTrue(sorted, sorted.contains(" * @param name\n * @param {Gui} g the window\n * @param other\n"));
        String added = formatDoc("/**\n * Text.\n */", List.of("a", "b"), "ADD_MISSING_PARAMS");
        assertEquals("/**\n * Text.\n *\n * @param a\n * @param b\n */", added);
        // one line comment, leading asterisks
        assertEquals("/** Short. */", formatDoc("/** Short. */", null, "KEEP_ONE_LINE"));
        assertEquals("/**\n * Short.\n */", formatDoc("/** Short. */", null));
        assertEquals("/**\n   No stars.\n   Second.\n */", formatDoc("/**\nNo stars.\nSecond.\n*/", null, "!LEADING_ASTERISKS",
                "!WRAP"));
        assertEquals("/**\n * No stars. Second.\n */", formatDoc("/**\nNo stars.\nSecond.\n*/", null));
        // wrapping off and preserved line feeds: lines stay
        assertEquals("/**\n * One\n * two.\n */", formatDoc("/**\n * One\n * two.\n */", null, "!WRAP"));
        assertEquals("/**\n * One\n * two.\n */", formatDoc("/**\n * One\n * two.\n */", null, "PRESERVE_LINE_FEEDS"));
        // continuation lines indented instead of below the text
        String continued = formatDoc("/**\n * @param x a description that is far too long for one single line here\n */",
                List.of("x"), "INDENT_CONTINUATION");
        assertTrue(continued, continued.contains("\n *     "));
    }

    public void testDocCommentFormatterKeepsMarkdown() {
        String doc = String.join("\n", "/**", " * A list:", " * - first item that is long enough to be wrapped at the",
                " *   right margin of sixty", " * - second", " *", " * ```", " * code  stays   as is", " * ```",
                " *     indented code   too", " *", " * # Heading stays", " * | a | b |", " * Line with hard break  ",
                " * next line", " * A word @param and - and 1. never start a line after wrapping here, ok", " */");
        String out = formatDoc(doc, null);
        assertTrue(out, out.contains(" * - first item that is long enough to be wrapped at the\n *   right margin of sixty\n"));
        assertTrue(out, out.contains(" * - second\n"));
        assertTrue(out, out.contains(" * ```\n * code  stays   as is\n * ```\n"));
        assertTrue(out, out.contains(" *     indented code   too\n"));
        assertTrue(out, out.contains(" * # Heading stays\n * | a | b |\n"));
        assertTrue(out, out.contains(" * Line with hard break  \n * next line"));
        for (String line : out.split("\n")) {
            String text = line.replaceFirst("^ \\* ?", "");
            assertFalse(out, text.startsWith("@param") || text.startsWith("- and") || text.startsWith("1. never"));
        }
    }

    /** "Reformat Code" with the main switch: the doc comments are formatted; without it only aligned. */
    public void testReformatFormatsDocComments() {
        String input = "class K {\n/**\n Opens it.\n     * @param {String} name the name\n */\nOpen(name, force) {\n}\n}\n";
        withCaseSettings(s -> s.DOC_FORMAT = true, () -> assertFormatted(input,
                "class K {\n    /**\n     * Opens it.\n     *\n     * @param {String} name  the name\n     */\n"
                        + "    Open(name, force) {\n    }\n}\n"));
        withCaseSettings(s -> {
            s.DOC_FORMAT = true;
            s.DOC_ADD_MISSING_PARAMS = true;
        }, () -> assertFormatted(input,
                "class K {\n    /**\n     * Opens it.\n     *\n     * @param {String} name   the name\n"
                        + "     * @param          force\n     */\n    Open(name, force) {\n    }\n}\n"));
        // main switch off: only the * lines follow the first line
        assertFormatted(input, "class K {\n    /**\n Opens it.\n     * @param {String} name the name\n     */\n"
                + "    Open(name, force) {\n    }\n}\n");
    }

    /** Every switch of the tab "Doc Comments" is a field of the settings. */
    public void testDocCommentStyleFields() throws Exception {
        List<String> fields = de.kumpewo.autohotkey.formatter.AhkDocCommentCodeStylePanelAccess.fields();
        assertEquals(16, fields.size());
        for (String field : fields) assertEquals(boolean.class, AhkCodeStyleSettings.class.getField(field).getType());
    }

    /**
     * Z.25: formatting through the PSI (previews of the code style page, refactorings) keeps the CRLF tokens: no
     * extra blank lines, bodies are indented (assertFormatted checks the PSI path and the tree).
     */
    public void testFormatThroughPsiKeepsLineBreaks() {
        // method in a class with an already indented body (the preview showed a blank line and no new indent)
        assertFormatted("class B {\nOpen(a) {\n    return 1\n}\n}\n", "class B {\n    Open(a) {\n        return 1\n    }\n}\n");
        // a blank line with spaces (CRLF, whitespace, CRLF) becomes one CRLF token with two line breaks
        assertFormatted("class B {\nM() {\nx := 1\n    \ny := 2\n}\n}\n",
                "class B {\n    M() {\n        x := 1\n\n        y := 2\n    }\n}\n");
        // comments and a doc comment between the lines
        assertFormatted("class B {\n; note\n/** Doc. */\nM() {\n; inside\nreturn\n}\n}\n",
                "class B {\n    ; note\n    /** Doc. */\n    M() {\n        ; inside\n        return\n    }\n}\n");
        // another indent size, as in the preview of the tab "Tabs and Indents"
        CodeStyle.doWithTemporarySettings(getProject(), CodeStyle.getSettings(getProject()),
                (com.intellij.psi.codeStyle.CodeStyleSettings settings) -> {
                    settings.getCommonSettings(de.kumpewo.autohotkey.AhkLanguage.INSTANCE).getIndentOptions().INDENT_SIZE = 2;
                    assertFormatted("class B {\n    M() {\n        return 1\n    }\n}\n",
                            "class B {\n  M() {\n    return 1\n  }\n}\n");
                });
    }

    /** Block and doc comments keep their place: formatting twice changes nothing. */
    public void testFormatBlockCommentIsStable() {
        String[] stable = {
                "/**\n * Top.\n */\nf() {\n}\n",
                "class K {\n    /**\n     * Method.\n     */\n    M() {\n    }\n}\n",
                "class K {\n    /** One line. */\n    M() {\n    }\n}\n",
                "f() {\n    /* plain */\n    x := 1\n}\n",
                "f() {\n    /*\n    plain\n    */\n    x := 1\n}\n",
        };
        for (String text : stable) {
            assertFormatted(text, text);
            assertFormatted(myFixture.getEditor().getDocument().getText(), text);
        }
        // a wrongly indented comment gets the indent of the block
        assertFormatted("class K {\n/** Doc. */\nM() {\n}\n}\n", "class K {\n    /** Doc. */\n    M() {\n    }\n}\n");
        assertFormatted("class K {\n            /** Doc. */\n    M() {\n    }\n}\n",
                "class K {\n    /** Doc. */\n    M() {\n    }\n}\n");
        // the lines of a doc comment that start with * follow the first line
        assertFormatted("class K {\n/**\n * Doc.\n * @param x\n */\nM(x) {\n}\n}\n",
                "class K {\n    /**\n     * Doc.\n     * @param x\n     */\n    M(x) {\n    }\n}\n");
        assertFormatted("/**\n      * Doc.\n   */\nf() {\n}\n", "/**\n * Doc.\n */\nf() {\n}\n");
        // lines without a star and plain block comments stay as they are
        assertFormatted("class K {\n/**\nNo star.\n * Star.\n */\nM() {\n}\n}\n",
                "class K {\n    /**\nNo star.\n     * Star.\n     */\n    M() {\n    }\n}\n");
        assertFormatted("class K {\n/*\n * plain\n */\nM() {\n}\n}\n", "class K {\n    /*\n * plain\n */\n    M() {\n    }\n}\n");
    }

    /**
     * Formats the input like "Reformat Code" (text path) and compares it with the expected text. Before that (Z.25):
     * the same input formatted through the PSI ({@code CodeStyleManager.reformat(PsiElement)}, used by the previews of
     * the code style page and by refactorings) must give the same text, and its tree must be the one a fresh parse of
     * that text gives (CRLF tokens stay CRLF tokens).
     */
    private void assertFormatted(String input, String expected) {
        myFixture.configureByText("psi.ahk", input);
        WriteCommandAction.runWriteCommandAction(getProject(), (Runnable) () ->
                CodeStyleManager.getInstance(getProject()).reformat(myFixture.getFile()));
        String psiText = myFixture.getFile().getText();
        assertEquals("PSI path (CodeStyleManager.reformat(PsiElement))", expected, psiText);
        com.intellij.psi.PsiFile fresh = com.intellij.psi.PsiFileFactory.getInstance(getProject())
                .createFileFromText("fresh.ahk", de.kumpewo.autohotkey.AhkLanguage.INSTANCE, psiText);
        assertEquals("tree after the PSI path", com.intellij.psi.impl.DebugUtil.psiToString(fresh, false),
                com.intellij.psi.impl.DebugUtil.psiToString(myFixture.getFile(), false));
        myFixture.configureByText("a.ahk", input);
        WriteCommandAction.runWriteCommandAction(getProject(), (Runnable) () ->
                CodeStyleManager.getInstance(getProject())
                        .reformatText(myFixture.getFile(), 0, myFixture.getFile().getTextLength()));
        assertEquals(expected, myFixture.getEditor().getDocument().getText());
    }

    /** Instead of the stylesheet links the help contains the merged CSS as a style block. */
    public void testHelpWithEmbeddedStylesheet() {
        for (String name : new String[]{"MsgBox", "StrLen"}) {
            String html = de.kumpewo.autohotkey.documentation.AhkHelpRepository.getHelp(name);
            assertNotNull(name, html);
            assertFalse(name, html.toLowerCase().matches("(?s).*<link[^>]*stylesheet.*"));
            assertFalse(name, html.contains("@import"));
            int style = html.indexOf("<style>");
            assertTrue(name, style >= 0 && style < html.toLowerCase().indexOf("</head>"));
            assertTrue(name, html.contains(".highlight>.str"));   // light.css
            assertTrue(name, html.contains(".methodShort"));      // theme.css
            assertTrue(name, html.contains("line-numbers-rows")); // highlighter.css
        }
        // starts as a complete document, nothing in the head that could be shown as text (title with "/")
        String menu = de.kumpewo.autohotkey.documentation.AhkHelpRepository.getPage("docs/lib/menu.htm");
        assertNotNull(menu);
        assertTrue(menu, menu.startsWith("<html>"));
        assertFalse(menu, menu.matches("(?is).*<(title|meta|script|!doctype)\\b.*"));
        assertTrue(menu, menu.contains("<style>"));
        String withoutHead = de.kumpewo.autohotkey.documentation.AhkHelpRepository.withStyle(
                "<link href=\"x.css\" rel=\"stylesheet\">\n<p>x</p>");
        assertTrue(withoutHead.startsWith("<style>"));
        assertFalse(withoutHead.contains("<link"));
    }

    /**
     * Help for pragmas from help/lookup/extra.txt: anywhere in the pragma comment, not in other comments, no external
     * help (only docs.txt has an external key).
     */
    public void testHelpForPragmas() {
        de.kumpewo.autohotkey.documentation.AhkDocumentationProvider provider =
                new de.kumpewo.autohotkey.documentation.AhkDocumentationProvider();
        String[][] cases = {
                {";@declare-type g as G<caret>ui\n", "<h1>;@declare-type</h1>", "/extra/declare-type.htm"},
                {"x := 1 ;@decl<caret>are-auto-type Gui*\n", "<h1>;@declare-auto-type</h1>",
                        "/extra/declare-auto-type.htm"},
                {";@ignore-next-line<caret>\nSetTimer () => 1, 100\n", "<h1>;@ignore-next-line</h1>",
                        "/extra/ignore-next-line.htm"},
                {";@declare-private _<caret>*, §*\n", "<h1>;@declare-private</h1>", "/extra/declare-private.htm"},
                {";@IGNORE-NEXT-LINE re<caret>ason\nx := 1\n", "<h1>;@ignore-next-line</h1>",
                        "/extra/ignore-next-line.htm"},
        };
        // external help (topic in extra.chm) only if the plugin has its extra.chm (help/extra.chm)
        boolean chm = de.kumpewo.autohotkey.documentation.AhkExternalHelp.hasExtraChm();
        for (String[] c : cases) {
            myFixture.configureByText("a.ahk", c[0]);
            PsiElement leaf = pragmaHelpElement();
            String doc = provider.generateDoc(leaf, leaf);
            assertNotNull(c[0], doc);
            assertTrue(c[0], doc.contains(c[1]));
            assertEquals(c[0], chm ? List.of(c[2]) : null, provider.getUrlFor(leaf, leaf));
            assertEquals(c[0], chm, provider.canHandleExternal(leaf, leaf));
        }
        // links between the own pages stay inside extra/
        myFixture.configureByText("a.ahk", ";@declare-type g as G<caret>ui\n");
        PsiElement leaf = pragmaHelpElement();
        assertTrue(provider.generateDoc(leaf, leaf).contains("href=\"psi_element://extra/pragmas.htm\""));
        // other comments and markers without whitespace after them: no help
        for (String text : new String[]{"; Msg<caret>Box\n", ";@declare-types g a<caret>s Gui\n",
                "; @declare-type g a<caret>s Gui\n", ";@unknown-pra<caret>gma\n"}) {
            myFixture.configureByText("a.ahk", text);
            leaf = pragmaHelpElement();
            assertNull(text, provider.generateDoc(leaf, leaf));
        }
        // all compiler directives ;@Ahk2Exe-... lead to the one page of the AutoHotkey help (entry @Ahk2Exe in docs.txt)
        for (String text : new String[]{";@Ahk2Exe-SetMain<caret>Icon app.ico\n",
                ";@ahk2exe-base ..\\v2\\Auto<caret>Hotkey64.exe\n", "x := 1  ;@Ahk2Exe-Con<caret>soleApp\n"}) {
            myFixture.configureByText("a.ahk", text);
            leaf = pragmaHelpElement();
            String doc = provider.generateDoc(leaf, leaf);
            assertNotNull(text, doc);
            assertTrue(text, doc.contains("<h1>Script Compiler Directives</h1>"));
            assertEquals(text, List.of("/docs/misc/Ahk2ExeDirectives.htm"), provider.getUrlFor(leaf, leaf));
        }
        // a disabled directive (;;@) and the marker without "-" are ordinary comments
        for (String text : new String[]{";;@Ahk2Exe-SetMain<caret>Icon app.ico\n", ";@Ahk2Exe<caret>Foo\n"}) {
            myFixture.configureByText("a.ahk", text);
            leaf = pragmaHelpElement();
            assertNull(text, provider.generateDoc(leaf, leaf));
        }
    }

    /** Documentation element at the caret, as the IDE determines it (comment also with the caret at its end). */
    private PsiElement pragmaHelpElement() {
        int offset = myFixture.getCaretOffset();
        return de.kumpewo.autohotkey.documentation.AhkDocumentationProvider.documentationElement(
                myFixture.getFile(), myFixture.getFile().findElementAt(offset), offset);
    }

    /** Help pages and external keys from help/lookup/docs.txt. */
    public void testHelpFromTheLists() {
        de.kumpewo.autohotkey.documentation.AhkHelpRepository.Topic msgBox =
                de.kumpewo.autohotkey.documentation.AhkHelpRepository.topic("msgbox");
        assertNotNull(msgBox);
        assertEquals("/docs/lib/MsgBox.htm", msgBox.externalKey());
        assertEquals("docs/lib/msgbox.htm", msgBox.page());
        // with anchor: external key path#anchor, popup from the element with the id on
        de.kumpewo.autohotkey.documentation.AhkHelpRepository.Topic readInt =
                de.kumpewo.autohotkey.documentation.AhkHelpRepository.topic("ReadInt");
        assertNotNull(readInt);
        assertEquals("/docs/lib/File.htm#ReadNum", readInt.externalKey());
        String close = de.kumpewo.autohotkey.documentation.AhkHelpRepository.getHelp("CLOSE");
        assertNotNull(close);
        assertTrue(close, close.matches("(?s).*<body[^>]*><div class=\"methodShort\" id=\"Close\">.*"));
        assertFalse(close, close.contains("site.init()"));
        assertNull(de.kumpewo.autohotkey.documentation.AhkHelpRepository.topic("GibtEsNicht"));
        assertNull(de.kumpewo.autohotkey.documentation.AhkHelpRepository.getHelp("GibtEsNicht"));
        // directive: looked up with "#", the page has "_" in its name
        assertEquals("#include", de.kumpewo.autohotkey.documentation.AhkHelpRepository.listKey("#Include"));
        de.kumpewo.autohotkey.documentation.AhkHelpRepository.Topic include =
                de.kumpewo.autohotkey.documentation.AhkHelpRepository.topic("#include");
        assertNotNull(include);
        assertEquals("docs/lib/_include.htm", include.page());
        assertEquals("/docs/lib/_Include.htm", include.externalKey());
        assertNotNull(de.kumpewo.autohotkey.documentation.AhkHelpRepository.getHelp("#Include"));
        // anchor on a direct child of body
        String isAlnum = de.kumpewo.autohotkey.documentation.AhkHelpRepository.getHelp("IsAlnum");
        assertNotNull(isAlnum);
        assertTrue(isAlnum, isAlnum.matches("(?s).*<body[^>]*><[a-z0-9]+ [^>]*id=\"cat-string\".*"));
        // several entries with a class (fourth column), narrowed by the type on the left
        assertEquals(5, de.kumpewo.autohotkey.documentation.AhkHelpRepository.topics("Add").size());
        myFixture.configureByText("a.ahk", ";@declare-type lv as Gui.ListView\nlv.A<caret>dd()\n");
        PsiElement add = myFixture.getFile().findElementAt(myFixture.getCaretOffset());
        assertEquals(List.of("/docs/lib/ListView.htm#Add"),
                new de.kumpewo.autohotkey.documentation.AhkDocumentationProvider().getUrlFor(add, add));

        // links resolved relative to the page, page also loadable with another spelling
        String html = de.kumpewo.autohotkey.documentation.AhkHelpRepository.getHelp("MsgBox");
        assertNotNull(html);
        assertTrue(html.contains("href=\"psi_element://docs/misc/WinTitle.htm#ahk_id\""));
        assertTrue(html.contains("href=\"psi_element://docs/lib/Gui.htm\""));
        assertTrue(html.contains("href=\"#Result\""));
        assertNotNull(de.kumpewo.autohotkey.documentation.AhkHelpRepository.getPage("docs/misc/WinTitle.htm"));
        assertNotNull(de.kumpewo.autohotkey.documentation.AhkHelpRepository.getPage("docs/lib/Gui.htm"));
        assertNull(de.kumpewo.autohotkey.documentation.AhkHelpRepository.getPage("../geheim.htm"));
    }

    /** Help key by role: nested built-in class as Gui.Button. */
    public void testHelpKeyByRole() {
        String text = "#Include x.ahk\ng := Gui.Button\nctrl.Text := 1\nclass X extends Gui.Edit {\n}\nMsgBox \"x\"\n";
        myFixture.configureByText("a.ahk", text);
        java.util.function.Function<String, String> key = word -> de.kumpewo.autohotkey.documentation
                .AhkDocumentationProvider.helpKey(myFixture.getFile().findElementAt(text.indexOf(word)));
        assertEquals("#Include", key.apply("#Include"));
        assertEquals("Gui.Button", key.apply("Button"));
        assertEquals("Text", key.apply("Text"));
        assertEquals("Gui.Edit", key.apply("Edit"));
        assertEquals("MsgBox", key.apply("MsgBox"));
    }

    /** External help: enabling Shift+F1 and the command line. */
    public void testExternalHelp() {
        myFixture.configureByText("a.ahk", "MsgBox \"x\"\nGibtEsNicht()\n");
        var provider = new de.kumpewo.autohotkey.documentation.AhkDocumentationProvider();
        PsiElement msgBox = myFixture.getFile().findElementAt(1);
        assertEquals(List.of("/docs/lib/MsgBox.htm"), provider.getUrlFor(msgBox, msgBox));
        assertTrue(provider.canHandleExternal(msgBox, msgBox));
        PsiElement unknown = myFixture.getFile().findElementAt(myFixture.getFile().getText().indexOf("GibtEs"));
        assertNull(provider.getUrlFor(unknown, unknown));
        assertFalse(provider.canHandleExternal(unknown, unknown));

        assertEquals(List.of("hh.exe", "mk:@MSITStore:C:\\Program Files\\AutoHotkey\\v2\\AutoHotkey.chm::/docs/lib/MsgBox.htm"),
                de.kumpewo.autohotkey.documentation.AhkExternalHelp.commandLine(
                        "hh.exe \"mk:@MSITStore:C:\\Program Files\\AutoHotkey\\v2\\AutoHotkey.chm::%s\"",
                        "/docs/lib/MsgBox.htm"));
        assertEquals(List.of("hh.exe", "mk:@MSITStore:" + java.nio.file.Path.of("C:\\x\\AutoHotkey.chm") + "::/k"),
                de.kumpewo.autohotkey.documentation.AhkExternalHelp.defaultCommandLine(
                        java.nio.file.Path.of("C:\\x\\AutoHotkey.chm"), "/k"));
    }

    /**
     * The plugin's extra.chm is copied to the temporary directory (hh.exe cannot read from the JAR), with the checksum
     * in the name; a second call reuses the copy. Skipped without help/extra.chm.
     */
    public void testExtraChmIsCopiedToTemp() throws Exception {
        if (!de.kumpewo.autohotkey.documentation.AhkExternalHelp.hasExtraChm()) return;
        java.nio.file.Path chm = de.kumpewo.autohotkey.documentation.AhkExternalHelp.extraChm();
        assertNotNull(chm);
        assertTrue(chm.toString(), chm.getFileName().toString().matches("extra-[0-9a-f]{8}\\.chm"));
        assertEquals(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "intellij-autohotkey").toAbsolutePath(),
                chm.getParent().toAbsolutePath());
        byte[] original;
        try (java.io.InputStream in = getClass().getResourceAsStream(
                de.kumpewo.autohotkey.documentation.AhkExternalHelp.EXTRA_CHM_RESOURCE)) {
            original = in.readAllBytes();
        }
        assertTrue(java.util.Arrays.equals(original, java.nio.file.Files.readAllBytes(chm)));
        java.nio.file.attribute.FileTime written = java.nio.file.Files.getLastModifiedTime(chm);
        assertEquals(chm, de.kumpewo.autohotkey.documentation.AhkExternalHelp.extraChm());
        assertEquals(written, java.nio.file.Files.getLastModifiedTime(chm));
        assertEquals(List.of("hh.exe", "mk:@MSITStore:" + chm + "::/extra/declare-type.htm"),
                de.kumpewo.autohotkey.documentation.AhkExternalHelp.defaultCommandLine(chm, "/extra/declare-type.htm"));
    }

    /**
     * The manual (help/extra.hhp, [FILES]): every listed file exists, every page in help/extra/ is listed, and every
     * relative link of a page points to a listed file and, with an anchor, to an existing id. Skipped without
     * help/extra.hhp.
     */
    public void testManualFilesAndLinks() throws Exception {
        List<String> files = de.kumpewo.autohotkey.documentation.AhkManual.files();
        if (files.isEmpty()) return;
        assertTrue(files.contains("extra/index.htm"));
        assertTrue(files.contains("styles/theme.css"));
        java.util.Map<String, String> pages = new java.util.HashMap<>();
        for (String file : files) {
            try (java.io.InputStream in = getClass().getResourceAsStream("/help/" + file)) {
                assertNotNull("listed in extra.hhp, but missing: " + file, in);
                if (file.endsWith(".htm")) pages.put(file, new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        // the pages in the source folder (the resources of the test run may be packed into a JAR)
        java.nio.file.Path dir = java.nio.file.Path.of("src", "main", "resources", "help", "extra");
        if (java.nio.file.Files.isDirectory(dir)) try (java.util.stream.Stream<java.nio.file.Path> list = java.nio.file.Files.list(dir)) {
            list.map(p -> "extra/" + p.getFileName()).filter(f -> f.endsWith(".htm"))
                    .forEach(f -> assertTrue("not listed in extra.hhp: " + f, files.contains(f)));
        }
        java.util.regex.Pattern link = java.util.regex.Pattern.compile("(?:href|src)=\"([^\"]+)\"");
        for (java.util.Map.Entry<String, String> page : pages.entrySet()) {
            java.util.regex.Matcher m = link.matcher(page.getValue());
            while (m.find()) {
                String target = m.group(1);
                if (target.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) continue;   // https:, mailto: ...
                int hash = target.indexOf('#');
                String file = hash < 0 ? target : target.substring(0, hash);
                String anchor = hash < 0 ? "" : target.substring(hash + 1);
                String resolved = file.isEmpty() ? page.getKey()
                        : java.nio.file.Path.of(page.getKey()).resolveSibling(file).normalize().toString().replace('\\', '/');
                String where = page.getKey() + " -> " + target;
                assertTrue(where, files.contains(resolved));
                if (!anchor.isEmpty()) {
                    assertTrue(where, pages.get(resolved).contains("id=\"" + anchor + "\""));
                }
            }
        }
    }

    /** Without a CHM file the manual is copied into a folder: pages and styles at their paths, start page returned. */
    public void testManualIsExtracted() throws Exception {
        if (de.kumpewo.autohotkey.documentation.AhkManual.files().isEmpty()) return;
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("ahk-manual-test");
        try {
            java.nio.file.Path start = de.kumpewo.autohotkey.documentation.AhkManual.extract(dir);
            assertEquals(dir.resolve("extra").resolve("index.htm"), start);
            assertTrue(java.nio.file.Files.isRegularFile(dir.resolve("styles").resolve("theme.css")));
            assertTrue(java.nio.file.Files.isRegularFile(dir.resolve("styles").resolve("highlighter").resolve("light.css")));
            assertTrue(java.nio.file.Files.readString(start).contains("../styles/theme.css"));
            // a second extraction replaces the files
            assertEquals(start, de.kumpewo.autohotkey.documentation.AhkManual.extract(dir));
        } finally {
            com.intellij.openapi.util.io.FileUtil.delete(dir);
        }
    }

    /** Where the Ahk2Exe head goes and which base file it names. */
    public void testAhk2ExeHeaderPosition() {
        assertEquals(0, de.kumpewo.autohotkey.compile.AhkAhk2ExeHeader.insertionOffset("MsgBox 1\n"));
        assertEquals(26, de.kumpewo.autohotkey.compile.AhkAhk2ExeHeader.insertionOffset("#Requires AutoHotkey v2.0\nx := 1\n"));
        assertEquals(27, de.kumpewo.autohotkey.compile.AhkAhk2ExeHeader.insertionOffset("#Requires AutoHotkey v2.0\r\nx := 1\n"));
        assertEquals(33, de.kumpewo.autohotkey.compile.AhkAhk2ExeHeader.insertionOffset("; head\n  #requires AutoHotkey v2\nx\n"));
        String last = "#Requires AutoHotkey v2.0";
        int offset = de.kumpewo.autohotkey.compile.AhkAhk2ExeHeader.insertionOffset(last);
        assertEquals(last.length(), offset);
        assertTrue(de.kumpewo.autohotkey.compile.AhkAhk2ExeHeader.needsLineBreakBefore(last, offset));
        assertFalse(de.kumpewo.autohotkey.compile.AhkAhk2ExeHeader.needsLineBreakBefore(last + "\n", offset + 1));
        assertEquals("32", de.kumpewo.autohotkey.compile.AhkAhk2ExeHeader.bits("#Requires AutoHotkey v2.0 32-bit\n"));
        assertEquals("64", de.kumpewo.autohotkey.compile.AhkAhk2ExeHeader.bits("#Requires AutoHotkey v2.0\n"));
        assertEquals("64", de.kumpewo.autohotkey.compile.AhkAhk2ExeHeader.bits("MsgBox \"32-bit\"\n"));
    }

    /**
     * Alt+Enter "Add Ahk2Exe compiler directives": only without ;@Ahk2Exe-Base; inserts the head as live template below
     * #Requires (here finished at once with the defaults); afterwards the script counts as prepared for compiling.
     */
    public void testAhk2ExeHeaderIntention() {
        String name = de.kumpewo.autohotkey.AhkBundle.message("intention.add.ahk2exe");
        com.intellij.codeInsight.template.impl.TemplateManagerImpl.setTemplateTesting(getTestRootDisposable());
        myFixture.configureByText("tool.ahk", "#Requires AutoHotkey v2.0 32-bit\nMsgBox 1<caret>\n");
        myFixture.launchAction(myFixture.findSingleIntention(name));
        var state = com.intellij.codeInsight.template.impl.TemplateManagerImpl.getTemplateState(myFixture.getEditor());
        if (state != null) state.gotoEnd(false);
        String text = myFixture.getEditor().getDocument().getText();
        String[] lines = text.split("\n");
        assertEquals("#Requires AutoHotkey v2.0 32-bit", lines[0]);
        assertEquals(";@Ahk2Exe-Base ..\\v2\\AutoHotkey32.exe", lines[1]);
        assertTrue(text, text.contains("Ahk2Exe_ProductName := \t\t\t\"tool\"\n"
                + ";@Ahk2Exe-SetProductName\t\t%A_PriorLine~U)(.+\")(.*)\".*$~$2%\n"));
        assertTrue(text, text.contains("Ahk2Exe_ProductVersion := \t\t\"1.0\"\n"));
        assertTrue(text, text.contains("Ahk2Exe_Description := \t\t\t\"tool\"\n"));
        assertTrue(text, text.contains(";@Ahk2Exe-SetCopyright\t\t\t© " + java.time.Year.now().getValue() + " Your Name\n"));
        assertTrue(text, text.contains(";;@Ahk2Exe-SetMainIcon\t\t\ttool.ico\n;;@Ahk2Exe-ConsoleApp\n"));
        assertTrue(text, text.contains(";GetProductInfo() => Ahk2Exe_ProductName \" v\" Ahk2Exe_ProductVersion\n"));
        assertTrue(text, text.endsWith("---\nMsgBox 1\n"));
        assertTrue(de.kumpewo.autohotkey.compile.AhkCompiler.hasBaseDirective(text));
        // the inserted head is valid code for the plugin's parser
        assertTrue(myFixture.doHighlighting(HighlightSeverity.ERROR).isEmpty());
        assertTrue(myFixture.filterAvailableIntentions(name).isEmpty());
        // without #Requires: at the start of the file; for another AutoHotkey version: not offered
        myFixture.configureByText("b.ahk", "MsgBox 1<caret>\n");
        myFixture.launchAction(myFixture.findSingleIntention(name));
        state = com.intellij.codeInsight.template.impl.TemplateManagerImpl.getTemplateState(myFixture.getEditor());
        if (state != null) state.gotoEnd(false);
        assertTrue(myFixture.getEditor().getDocument().getText().startsWith(";@Ahk2Exe-Base ..\\v2\\AutoHotkey64.exe\n"));
        myFixture.configureByText("c.ahk", "#Requires AutoHotkey v1.1\nMsgBox 1<caret>\n");
        assertTrue(myFixture.filterAvailableIntentions(name).isEmpty());
    }
}
