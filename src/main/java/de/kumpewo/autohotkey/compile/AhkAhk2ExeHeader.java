package de.kumpewo.autohotkey.compile;

import com.intellij.codeInsight.template.Template;
import com.intellij.codeInsight.template.TemplateManager;
import com.intellij.codeInsight.template.impl.ConstantNode;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import de.kumpewo.autohotkey.AhkLanguage;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Year;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Head of {@code ;@Ahk2Exe-...} compiler directives that makes a script ready for "Compile with Ahk2Exe": the base file
 * ({@code ;@Ahk2Exe-Base}), product name, version ({@code ;@Ahk2Exe-SetVersion}, the line that "Set Version" changes),
 * description, language, copyright, and disabled lines for icon and console application. The values are written without
 * quotes, because Ahk2Exe would keep them in the executable; their column is aligned. Inserted below the first {@code
 * #Requires} line (otherwise at the start of the file) as a live template whose placeholders (name, version,
 * description, copyright) are visited with Tab. A line {@code ;@SetVersion x} (or {@code ;@Ahk2Exe-SetVersion x}) that
 * the script has already is taken into the head: it is removed and {@code x} becomes the default of the version (other
 * existing directives stay as they are). Offered by the intention {@link AhkAddAhk2ExeHeaderIntention} and by the
 * notification of {@link AhkCompiler} when the base directive is missing.
 */
public final class AhkAhk2ExeHeader {

    /** The first {@code #Requires} line (also indented, any case). */
    private static final Pattern REQUIRES = Pattern.compile("(?im)^[ \\t]*#Requires\\b.*$");
    /** Width of the directive part of a line: the values start in this column. */
    private static final int VALUE_COLUMN = 28;
    private static final String SEPARATOR =
            "; ---------------------------------------------------------------------------\n";

    private AhkAhk2ExeHeader() {
    }

    /**
     * True if the head can be inserted: an AutoHotkey file, not for another AutoHotkey version, without
     * {@code ;@Ahk2Exe-Base} (or {@code ;@Ahk2Exe-Bin}).
     */
    public static boolean isApplicable(@Nullable PsiFile file) {
        if (file == null || file.getLanguage() != AhkLanguage.INSTANCE) return false;
        if (file.getNode().findChildByType(AhkTypes.OTHER_VERSION_CODE) != null) return false;
        return !AhkCompiler.hasBaseDirective(file.getViewProvider().getContents());
    }

    /**
     * Where the head goes: the start of the line after the first {@code #Requires} line, the end of the document if
     * that line is the last one ({@link #needsLineBreakBefore}), otherwise the start of the document.
     */
    public static int insertionOffset(@NotNull CharSequence text) {
        Matcher m = REQUIRES.matcher(text);
        if (!m.find()) return 0;
        int end = m.end();
        if (end < text.length() && text.charAt(end) == '\r') end++;
        return end < text.length() ? end + 1 : text.length();
    }

    /** True if the head is inserted behind a last line without line break, so it has to start with one. */
    public static boolean needsLineBreakBefore(@NotNull CharSequence text, int offset) {
        return offset > 0 && offset == text.length() && text.charAt(offset - 1) != '\n';
    }

    /** {@code 32} if the {@code #Requires} line asks for {@code 32-bit}, otherwise {@code 64}. */
    public static @NotNull String bits(@NotNull CharSequence text) {
        Matcher m = REQUIRES.matcher(text);
        return m.find() && m.group().toLowerCase(Locale.ROOT).contains("32-bit") ? "32" : "64";
    }

    /**
     * The head as live template: {@code name} (file name without extension) is the default for the product name,
     * {@code <name> description} for the description, {@code version} for the version, {@code © year Your Name} for
     * the copyright; commas in the defaults are escaped ({@code `,}), because Ahk2Exe separates arguments with them.
     * {@code lineBreakBefore} puts a line break in front (behind a last line without one).
     */
    public static @NotNull Template template(@NotNull Project project, @NotNull String name, @NotNull String bits,
                                             boolean lineBreakBefore, @NotNull String version) {
        Template t = TemplateManager.getInstance(project).createTemplate("", "");
        t.setToReformat(false);
        if (lineBreakBefore) t.addTextSegment("\n");
        String value = name.replace(",", "`,");
        t.addTextSegment(";@Ahk2Exe-Base ..\\v2\\AutoHotkey" + bits + ".exe\n" + SEPARATOR);
        t.addTextSegment(directive(";@Ahk2Exe-SetProductName"));
        t.addVariable("PRODUCT", new ConstantNode(value), new ConstantNode(value), true);
        t.addTextSegment("\n" + directive(";@Ahk2Exe-SetVersion"));
        t.addVariable("VERSION", new ConstantNode(version), new ConstantNode(version), true);
        t.addTextSegment("\n" + directive(";@Ahk2Exe-SetDescription"));
        String description = value + " description";
        t.addVariable("DESCRIPTION", new ConstantNode(description), new ConstantNode(description), true);
        t.addTextSegment("\n" + directive(";@Ahk2Exe-SetLanguage") + "0x0409\n");
        String copyright = "© " + Year.now().getValue() + " Your Name";
        t.addTextSegment(directive(";@Ahk2Exe-SetCopyright"));
        t.addVariable("COPYRIGHT", new ConstantNode(copyright), new ConstantNode(copyright), true);
        t.addTextSegment("\n" + directive(";;@Ahk2Exe-SetMainIcon") + name + ".ico\n");
        t.addTextSegment(";;@Ahk2Exe-ConsoleApp\n" + SEPARATOR);
        t.addEndVariable();
        return t;
    }

    /** The directive padded with spaces to {@link #VALUE_COLUMN} (at least one space). */
    static @NotNull String directive(@NotNull String directive) {
        return directive + " ".repeat(Math.max(1, VALUE_COLUMN - directive.length()));
    }

    /** Version default of the head without an existing line. */
    static final String DEFAULT_VERSION = "1.0.0";

    /**
     * The existing version line ({@code ;@SetVersion} or the directive) to take into the head: exactly one,
     * with a written value; {@code null} otherwise.
     */
    static @Nullable de.kumpewo.autohotkey.install.AhkVersionDirective.Line existingVersion(@NotNull CharSequence text) {
        var lines = de.kumpewo.autohotkey.install.AhkVersionDirective.find(text);
        return lines.size() == 1 && !lines.get(0).computed() && !lines.get(0).value().isEmpty() ? lines.get(0) : null;
    }

    /** Start and end (after its line break) of the line that contains the offset. */
    static int @NotNull [] lineRange(@NotNull CharSequence text, int offset) {
        int start = offset;
        while (start > 0 && text.charAt(start - 1) != '\n') start--;
        int end = offset;
        while (end < text.length() && text.charAt(end) != '\n') end++;
        if (end < text.length()) end++;
        return new int[]{start, end};
    }

    /**
     * Inserts the head into the file of the editor as live template (call on the EDT, in a write command: the removal
     * of an existing version line and the head are one undo step).
     */
    public static void insert(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        Document document = editor.getDocument();
        PsiDocumentManager.getInstance(project).commitDocument(document);
        String version = DEFAULT_VERSION;
        var existing = existingVersion(document.getCharsSequence());
        if (existing != null) {
            version = existing.value();
            int[] range = lineRange(document.getCharsSequence(), existing.valueStart());
            document.deleteString(range[0], range[1]);
            PsiDocumentManager.getInstance(project).commitDocument(document);
        }
        CharSequence text = document.getCharsSequence();
        int offset = insertionOffset(text);
        String name = FileUtilRt.getNameWithoutExtension(file.getName());
        Template template = template(project, name, bits(text), needsLineBreakBefore(text, offset),
                version.replace(",", "`,"));
        editor.getCaretModel().moveToOffset(offset);
        TemplateManager.getInstance(project).startTemplate(editor, template);
    }

    /** Opens the script in an editor and inserts the head (from the notification of a failed compilation). */
    public static void insert(@NotNull Project project, @NotNull VirtualFile script) {
        Editor editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, script), true);
        if (editor == null) return;
        PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
        if (isApplicable(file)) {
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project,
                    de.kumpewo.autohotkey.AhkBundle.message("compile.no.base.action"), null,
                    () -> insert(project, editor, file));
        }
    }
}
