package de.kumpewo.autohotkey.module;

import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiReference;
import com.intellij.psi.ResolveResult;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import de.kumpewo.autohotkey.include.AhkInclude;
import de.kumpewo.autohotkey.navigation.AhkHelpTarget;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Modules of AutoHotkey v2.1: namespaces of {@code #Module}, {@code #Import} with alias, list and wildcard,
 * module files in the search path, and what navigation, Find Usages, Rename, completion, highlighting and the checks
 * make of them. The files lie in a temporary folder that is a content root (module files are found on disk).
 */
public class AhkModuleTest extends BasePlatformTestCase {

    private static final String V21 = "#Requires AutoHotkey v2.1-\n";

    private Path dir;

    /** A temporary folder as content root; AhkLibPath of the plugin points to an empty folder (no user library). */
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        dir = Files.createTempDirectory("ahk-modules").toRealPath();
        Path noLib = Files.createDirectories(dir.resolve("nolib"));
        var previous = AhkInclude.environment;
        AhkInclude.environment = name -> name.equals("AhkLibPath") ? noLib.toString() : previous.apply(name);
        com.intellij.openapi.util.Disposer.register(getTestRootDisposable(), () -> AhkInclude.environment = previous);
        VirtualFile root = Objects.requireNonNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir));
        var entry = PsiTestUtil.addContentRoot(myFixture.getModule(), root);
        com.intellij.openapi.util.Disposer.register(getTestRootDisposable(),
                () -> PsiTestUtil.removeContentEntry(myFixture.getModule(), entry.getFile()));
    }

    /** Writes a file below the temporary folder and brings it into the VFS. */
    private PsiFile write(String name, String text) throws Exception {
        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments();
        Path path = dir.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
        VirtualFile file = Objects.requireNonNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path));
        file.refresh(false, false);
        return Objects.requireNonNull(PsiManager.getInstance(getProject()).findFile(file));
    }

    /** Writes main.ahk (caret at {@code <caret>}, if any) and opens it in the editor. */
    private void open(String text) throws Exception {
        int caret = text.indexOf("<caret>");
        PsiFile file = write("main.ahk", text.replace("<caret>", ""));
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        if (caret >= 0) myFixture.getEditor().getCaretModel().moveToOffset(caret);
    }

    /**
     * Targets of the reference at {@code name} in the last occurrence of {@code snippet} in the open file, as
     * "file:line" ("help" for the help of a built-in name).
     */
    private List<String> targets(String snippet, String name) {
        String text = myFixture.getFile().getText();
        int start = text.lastIndexOf(snippet);
        assertTrue(snippet, start >= 0 && snippet.contains(name));
        PsiReference ref = myFixture.getFile().findReferenceAt(start + snippet.indexOf(name));
        assertNotNull("no reference: " + snippet, ref);
        List<String> result = new ArrayList<>();
        for (ResolveResult r : ((PsiPolyVariantReference) ref).multiResolve(false)) {
            result.add(where(r.getElement()));
        }
        return result;
    }

    /** "file:line" of an element ("help" for the help target). */
    private static String where(PsiElement e) {
        if (e instanceof AhkHelpTarget) return "help";
        PsiFile f = e instanceof PsiFile file ? file : e.getContainingFile();
        int line = f.getViewProvider().getDocument().getLineNumber(e instanceof PsiFile ? 0 : e.getTextOffset()) + 1;
        return f.getName() + ":" + line;
    }

    /** Usages of the element as sorted "file:line". */
    private List<String> usages(PsiElement target) {
        List<String> out = new ArrayList<>();
        for (com.intellij.usageView.UsageInfo usage : myFixture.findUsages(target)) {
            PsiFile file = Objects.requireNonNull(usage.getFile());
            var document = Objects.requireNonNull(com.intellij.psi.PsiDocumentManager.getInstance(getProject()).getDocument(file));
            int offset = Objects.requireNonNull(usage.getSegment()).getStartOffset();
            out.add(file.getName() + ":" + (document.getLineNumber(offset) + 1));
        }
        Collections.sort(out);
        return out;
    }

    /** The current text of a file of the temporary folder. */
    private String text(String name) {
        com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        VirtualFile file = Objects.requireNonNull(LocalFileSystem.getInstance().findFileByNioFile(dir.resolve(name)));
        return Objects.requireNonNull(PsiManager.getInstance(getProject()).findFile(file)).getText();
    }

    /** The named definition (function, class) in the file. */
    private static PsiElement definition(PsiFile file, String name) {
        for (var element : com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(file, de.kumpewo.autohotkey.psi.AhkNamedElement.class)) {
            if (name.equals(element.getName())) return element;
        }
        throw new AssertionError("No definition " + name);
    }

    /** The target the platform finds at the start of the last occurrence of {@code snippet} (+ {@code shift}). */
    private PsiElement targetAt(String snippet, int shift) {
        String text = myFixture.getFile().getText();
        myFixture.getEditor().getCaretModel().moveToOffset(text.lastIndexOf(snippet) + shift);
        PsiElement element = com.intellij.codeInsight.TargetElementUtil.findTargetElement(myFixture.getEditor(),
                com.intellij.codeInsight.TargetElementUtil.getInstance().getAllAccepted());
        assertNotNull(snippet, element);
        return element;
    }

    // ---------------------------------------------------------------- navigation

    /** Each module has its own namespace; imports with alias, list and wildcard. */
    public void testNamespacesAndImports() throws Exception {
        open(V21 + String.join("\n",
                "#Import X",                 // 2
                "#Import Y as Z",            // 3
                "#Import X {H as Hx, H}",    // 4
                "#Import W {*}",             // 5
                "F()",                       // 6
                "X.F()",                     // 7
                "Z.F()",                     // 8
                "Hx()",                      // 9
                "Wfunc()",                   // 10
                "Gonly()",                   // 11
                "_hidden()",                 // 12
                "F() => 1",                  // 13
                "#Module X",                 // 14
                "F() => 2",                  // 15
                "H() => 3",                  // 16
                "Gonly() => 7",              // 17
                "F() + 0",                   // 18
                "#Module Y",                 // 19
                "F() => 4",                  // 20
                "#Module W",                 // 21
                "Wfunc() => 5",              // 22
                "_hidden() => 6",            // 23
                ""));
        assertEquals(List.of("main.ahk:13"), targets("F()\nX.F()", "F"));
        assertEquals(List.of("main.ahk:15"), targets("X.F()", "F"));
        assertEquals(List.of("main.ahk:14"), targets("X.F()", "X"));
        assertEquals(List.of("main.ahk:20"), targets("Z.F()", "F"));
        assertEquals(List.of("main.ahk:3"), targets("Z.F()", "Z"));
        assertEquals(List.of("main.ahk:4"), targets("Hx()", "Hx"));
        assertEquals(List.of("main.ahk:22"), targets("Wfunc()\n", "Wfunc"));
        // not visible in __Main: defined in X only, and _hidden is not brought by {*}
        assertEquals(List.of(), targets("Gonly()\n_", "Gonly"));
        assertEquals(List.of(), targets("_hidden()\n", "_hidden"));
        // inside X: its own F
        assertEquals(List.of("main.ahk:15"), targets("F() + 0", "F"));
        // names in #Import
        assertEquals(List.of("main.ahk:14"), targets("#Import X\n", "X"));
        assertEquals(List.of("main.ahk:16"), targets("{H as Hx", "H"));
        assertEquals(List.of("main.ahk:16"), targets(", H}", "H"));
    }

    /** Module files: next to the script, Name\__Init.ahk, Name.ahk, "Path:Name", AhkLibPath; global variables. */
    public void testModuleFiles() throws Exception {
        write("M.ahk", V21 + "Mf() => 1\nglobal Counter := 0\nclass Mc {\n}\n#Module Sub\nSf() => 2\n");
        write("Pkg/__Init.ahk", V21 + "Pf() => 3\n");
        Path lib = Files.createDirectories(dir.resolve("mylib"));
        write("mylib/L.ahk", V21 + "Lf() => 4\n");
        var previous = AhkInclude.environment;
        AhkInclude.environment = name -> name.equals("AhkLibPath") ? lib.toString() : previous.apply(name);
        open(V21 + String.join("\n",
                "#Import M",                     // 2
                "#Import Pkg",                   // 3
                "#Import \"M.ahk:Sub\" as S",    // 4
                "#Import L",                     // 5
                "#Import M {Counter}",           // 6
                "M.Mf()",                        // 7
                "Pkg.Pf()",                      // 8
                "S.Sf()",                        // 9
                "L.Lf()",                        // 10
                "x := M.Counter + Counter",      // 11
                "class D extends M.Mc {",        // 12
                "}",
                ""));
        assertEquals(List.of("M.ahk:2"), targets("M.Mf()", "Mf"));
        assertEquals(List.of("M.ahk:1"), targets("#Import M\n", "M"));
        assertEquals(List.of("__Init.ahk:2"), targets("Pkg.Pf()", "Pf"));
        assertEquals(List.of("M.ahk:7"), targets("S.Sf()", "Sf"));
        assertEquals(List.of("M.ahk:6"), targets("M.ahk:Sub", "Sub"));
        assertEquals(List.of("L.ahk:2"), targets("L.Lf()", "Lf"));
        assertEquals(List.of("M.ahk:3"), targets("M.Counter", "Counter"));
        assertEquals(List.of("M.ahk:3"), targets("+ Counter", "Counter"));
        assertEquals(List.of("M.ahk:4"), targets("M.Mc {", "Mc"));
    }

    /** The built-in module: AHK.MsgBox goes to the help; an own MsgBox hides the built-in one. */
    public void testBuiltinModule() throws Exception {
        open(V21 + String.join("\n",
                "#Import AHK",
                "AHK.MsgBox(\"x\")",
                "MsgBox(\"y\")",
                "MsgBox(t) => AHK.MsgBox(t)",
                ""));
        assertEquals(List.of("help"), targets("AHK.MsgBox(\"x\")", "MsgBox"));
        assertEquals(List.of("main.ahk:5"), targets("MsgBox(\"y\")", "MsgBox"));
    }

    /** #Include adds the file to the module of its line; v2.0 files ignore modules. */
    public void testIncludeBelongsToModule() throws Exception {
        write("inc.ahk", "Inc() => 1\n");
        open(V21 + String.join("\n",
                "Inc()",                 // 2
                "#Module X",             // 3
                "#Include inc.ahk",      // 4
                "Inc() + 1",             // 5
                ""));
        assertEquals(List.of(), targets("Inc()\n#", "Inc"));
        assertEquals(List.of("inc.ahk:1"), targets("Inc() + 1", "Inc"));
        open("#Requires AutoHotkey v2.0\nF()\nF() {\n}\n#Module X\n");
        assertEquals(List.of("main.ahk:3"), targets("F()\n", "F"));
    }

    // ---------------------------------------------------------------- find usages and rename

    /** Find Usages of a function in a module file finds X.F and {F} in the importing file. */
    public void testFindUsagesAcrossImports() throws Exception {
        PsiFile m = write("M.ahk", V21 + "Mf() => 1\nMf()\n");
        write("other.ahk", V21 + "Mf()\n");
        open(V21 + "#Import M\n#Import M {Mf}\nM.Mf()\nMf()\n");
        assertEquals(List.of("M.ahk:3", "main.ahk:3", "main.ahk:4", "main.ahk:5"), usages(definition(m, "Mf")));
    }

    /** Rename of a #Module renames the imports and the qualified names. */
    public void testRenameModule() throws Exception {
        open(V21 + "#Import X\nX.F()\n#Module X\nF() => 2\n");
        PsiElement module = targetAt("#Module X", "#Module ".length());
        assertTrue(module.toString(), module instanceof AhkModuleSymbol);
        assertEquals(List.of("main.ahk:2", "main.ahk:3"), usages(module));
        myFixture.renameElement(module, "Y", false, false);
        assertEquals(V21 + "#Import Y\nY.F()\n#Module Y\nF() => 2\n", text("main.ahk"));
    }

    /** Rename of an exported function renames X.F and {F} (an alias stays), also in the importing file. */
    public void testRenameExportedFunction() throws Exception {
        PsiFile m = write("M.ahk", V21 + "Mf() => 1\n");
        open(V21 + "#Import M\n#Import M {Mf, Mf as Other}\nM.Mf()\nMf()\nOther()\n");
        myFixture.renameElement(definition(m, "Mf"), "Nf", false, false);
        assertEquals(V21 + "Nf() => 1\n", text("M.ahk"));
        assertEquals(V21 + "#Import M\n#Import M {Nf, Nf as Other}\nM.Nf()\nNf()\nOther()\n", text("main.ahk"));
    }

    // ---------------------------------------------------------------- completion

    /** Suggestions at the caret of main.ahk. */
    private List<String> suggestions(String text) throws Exception {
        open(text);
        myFixture.completeBasic();
        List<String> list = myFixture.getLookupElementStrings();
        return list == null ? List.of() : list;
    }

    /** Free-standing names by module, X. gives the exports, #Import the modules and the importable names. */
    public void testCompletion() throws Exception {
        write("M.ahk", V21 + "Mf() => 1\nglobal Mv := 0\n_Mh() => 2\n");
        String modules = "#Import M\n#Import M {Mf}\n#Import X {*}\n";
        String tail = "\n#Module X\nXf() => 1\n_Xh() => 2\nYf() => 3\n";
        List<String> member = suggestions(V21 + modules + "x := M.<caret>" + tail);
        assertTrue(member.toString(), member.containsAll(List.of("Mf", "Mv", "_Mh")));
        assertFalse(member.toString(), member.contains("Xf") || member.contains("Length"));
        List<String> free = suggestions(V21 + modules + "x := <caret>" + tail);
        assertTrue(free.toString(), free.containsAll(List.of("M", "Mf", "Xf", "Yf", "MsgBox")));
        assertFalse(free.toString(), free.contains("_Xh") || free.contains("Mv"));
        List<String> inX = suggestions(V21 + modules + tail + "global Xv := 1\nXa := 2\ny := <caret>\n");
        assertTrue(inX.toString(), inX.containsAll(List.of("Xf", "_Xh", "Xv")));
        assertFalse(inX.toString(), inX.contains("Mf") || inX.contains("Xa"));        List<String> names = suggestions(V21 + "#Import <caret>\n#Module X\n");
        assertTrue(names.toString(), names.containsAll(List.of("AHK", "M", "X")));
        assertFalse(names.toString(), names.contains("main"));
        List<String> items = suggestions(V21 + "#Import M {<caret>}\n");
        assertTrue(items.toString(), items.containsAll(List.of("Mf", "Mv", "_Mh")));
    }

    /** Parameter info of an imported function and of X.F(). */
    public void testParameterInfo() throws Exception {
        open(V21 + "#Import X\n#Import X {G}\nF(0)\nX.F(1, 2)\nG(3)\nF(a) => 1\n#Module X\nF(first, second) => 2\nG(g) => 3\n");
        assertEquals(List.of("a"), parameters("F(0)"));
        assertEquals(List.of("first, second"), parameters("X.F(1"));
        assertEquals(List.of("g"), parameters("G(3)"));
    }

    /** The parameter info texts at the end of the snippet. */
    private List<String> parameters(String snippet) {
        String text = myFixture.getFile().getText();
        int offset = text.indexOf(snippet) + snippet.indexOf('(') + 1;
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var owner = de.kumpewo.autohotkey.completion.AhkArguments.find(myFixture.getFile(), offset);
        assertNotNull(snippet, owner);
        List<de.kumpewo.autohotkey.completion.AhkCommandInfo.Signature> signatures;
        if (owner.method()) {
            signatures = de.kumpewo.autohotkey.completion.AhkCommandInfo.moduleSignatures(Objects.requireNonNull(owner.qualifier()), owner.name());
        } else {
            signatures = de.kumpewo.autohotkey.completion.AhkCommandInfo.signatures(owner.element(), owner.name());
        }
        List<String> result = new ArrayList<>();
        for (var signature : Objects.requireNonNull(signatures)) {
            List<String> parts = new ArrayList<>();
            for (var parameter : signature.parameters()) parts.add(parameter.text());
            result.add(String.join(", ", parts));
        }
        return result;
    }

    // ---------------------------------------------------------------- highlighting and checks

    /** Color of the identifier at the last occurrence of the snippet. */
    private com.intellij.openapi.editor.colors.TextAttributesKey color(String snippet) {
        String text = myFixture.getFile().getText();
        PsiElement leaf = myFixture.getFile().findElementAt(text.lastIndexOf(snippet));
        return de.kumpewo.autohotkey.annotation.AhkAnnotator.identifierColor(Objects.requireNonNull(leaf));
    }

    /** A module may hide a built-in function; AHK.Name keeps the built-in color. */
    public void testHiddenBuiltins() throws Exception {
        open(V21 + "#Import AHK\nMsgBox(\"x\")\nAHK.MsgBox(\"y\")\nMsgBox(t) => 1\n#Module X\nMsgBox(\"z\")\n");
        assertNull(color("MsgBox(\"x\")"));
        assertEquals(de.kumpewo.autohotkey.highlighting.AhkColors.AHK_BUILTIN_FUNCTION, color("MsgBox(\"y\")"));
        assertEquals(de.kumpewo.autohotkey.highlighting.AhkColors.AHK_BUILTIN_FUNCTION, color("MsgBox(\"z\")"));
    }

    /** Messages of the highlighting with the given severity, as "description@text". */
    private List<String> messages(com.intellij.lang.annotation.HighlightSeverity severity) {
        List<String> result = new ArrayList<>();
        for (var info : myFixture.doHighlighting()) {
            if (info.getSeverity() == severity && info.getDescription() != null) {
                result.add(info.getDescription() + "@" + info.getText());
            }
        }
        return result;
    }

    /** Module not found, a name the module does not have, a conflict with an own definition. */
    public void testChecks() throws Exception {
        write("Lib1.ahk", V21 + "L() => 1\n");
        open(V21 + String.join("\n",
                "#Import Nope",
                "#Import Lib1",
                "#Import \"*RES\"",
                "#Import X {Missing, F, _Hidden}",
                "#Import Y {G}",
                "#Import Y as V",
                "#Import AHK {MsgBox, NoSuchThing}",
                "G() => 1",
                "V := 2",
                "#Module X",
                "F() => 1",
                "_Hidden() => 1",
                "#Module Y",
                "G() => 2",
                ""));
        assertEquals(List.of("Module not found: Nope@Nope",
                "'G' is imported here, but this module also defines a function of this name.@G",
                "'V' is imported here, but this module also defines a global variable of this name.@V"),
                messages(com.intellij.lang.annotation.HighlightSeverity.ERROR));
        assertEquals(List.of("Module X has no name 'Missing'; it stays unset.@Missing",
                "Module AHK has no name 'NoSuchThing'; it stays unset.@NoSuchThing"),
                messages(com.intellij.lang.annotation.HighlightSeverity.WARNING));
    }

    /** The example of the manual page (runs with v2.1-alpha.39) has no errors and resolves. */
    public void testManualExample() throws Exception {
        open(String.join("\n",
                "#Requires AutoHotkey v2.1-alpha.39",
                "#Import Tools                  ; the module Tools (below or Tools.ahk)",
                "#Import Tools {Clamp as Fit}   ; one function under another name",
                "#Import Shapes {*}             ; everything Shapes offers",
                "MsgBox Tools.Clamp(5, 1, 3) \" \" Fit(0, 1, 3) \" \" Area(2)",
                "",
                "#Module Tools",
                "Clamp(v, lo, hi) => Min(Max(v, lo), hi)",
                "",
                "#Module Shapes",
                "Area(r) => 3.14159 * r * r",
                ""));
        assertEquals(List.of(), messages(com.intellij.lang.annotation.HighlightSeverity.WARNING));
        assertEquals(List.of(), messages(com.intellij.lang.annotation.HighlightSeverity.ERROR));
        assertEquals(List.of("main.ahk:8"), targets("Tools.Clamp(5", "Clamp"));
        assertEquals(List.of("main.ahk:3"), targets("Fit(0", "Fit"));
        assertEquals(List.of("main.ahk:11"), targets("Area(2)", "Area"));
    }

    // ---------------------------------------------------------------- structure and folding

    /** __Main first, then one node per #Module with its definitions; each module region folds. */
    public void testStructureAndFolding() throws Exception {
        open(V21 + "Main() => 1\n#Module X\nXf() => 2\nclass Xc {\n}\n#Module Y\nYf() => 3\n\n");
        var root = new de.kumpewo.autohotkey.structure.AhkStructureViewElement(myFixture.getFile());
        List<String> top = new ArrayList<>();
        for (var child : root.getChildren()) {
            List<String> inner = new ArrayList<>();
            for (var grandChild : child.getChildren()) inner.add(grandChild.getPresentation().getPresentableText());
            top.add(child.getPresentation().getPresentableText() + (inner.isEmpty() ? "" : inner.toString()));
        }
        assertEquals(List.of("Main()", "X[Xf(), Class Xc]", "Y[Yf()]"), top);
        var document = myFixture.getEditor().getDocument();
        List<String> folds = new ArrayList<>();
        for (var descriptor : new de.kumpewo.autohotkey.structure.AhkFoldingBuilder()
                .buildFoldRegions(myFixture.getFile(), document, false)) {
            if (descriptor.getElement().getPsi() instanceof de.kumpewo.autohotkey.psi.AhkDirective) {
                folds.add(document.getText(descriptor.getRange()).trim());
            }
        }
        assertEquals(List.of("Xf() => 2\nclass Xc {\n}", "Yf() => 3"), folds);
    }

    /** Rename of an alias renames its uses. */
    public void testRenameAlias() throws Exception {
        open(V21 + "#Import X as Z\nZ.F()\n#Module X\nF() => 2\n");
        PsiElement alias = targetAt("as Z", 3);
        myFixture.renameElement(alias, "Q", false, false);
        assertEquals(V21 + "#Import X as Q\nQ.F()\n#Module X\nF() => 2\n", text("main.ahk"));
    }
}
