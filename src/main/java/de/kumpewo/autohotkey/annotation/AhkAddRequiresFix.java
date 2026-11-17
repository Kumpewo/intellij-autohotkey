package de.kumpewo.autohotkey.annotation;

import de.kumpewo.autohotkey.AhkBundle;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import de.kumpewo.autohotkey.parser.AhkLanguageLevel;
import de.kumpewo.autohotkey.parser.AhkVersion;
import de.kumpewo.autohotkey.settings.AhkSettings;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Quick fix for the hint about a missing #Requires (AhkVersionAnnotator): inserts the line that matches the language
 * level of the file ({@link #lineFor}: "#Requires AutoHotkey v2.0", or for the default level v2.1 the version of the
 * v2.1 interpreter) or, for code that looks like AutoHotkey v1 ({@link AhkVersionDetector}),
 * "#Requires AutoHotkey v1.1" as the first line of the file.
 */
public class AhkAddRequiresFix implements IntentionAction, PriorityAction {

    static final String LINE = "#Requires AutoHotkey v2.0";
    static final String LINE_V1 = "#Requires AutoHotkey v1.1";
    /**
     * Version for v2.1 if the version of the v2.1 interpreter is unknown: the hyphen admits pre-releases
     * ({@code v2.1} alone would reject {@code v2.1-alpha.39}).
     */
    static final String V21_FALLBACK = "v2.1-";
    /** Folder name of a version folder of the installation ({@code v2.1-alpha.39}). */
    private static final Pattern VERSION_DIR = Pattern.compile("(?i)v(\\d+\\.\\d+\\S*)");

    private final String line;
    private final Priority priority;

    /** Fix for "#Requires AutoHotkey v2.0", at the top of the menu. */
    public AhkAddRequiresFix() {
        this(LINE, Priority.HIGH);
    }

    /**
     * The #Requires line for a file of the language level (a file without #Requires has the default level of the
     * settings): v2.1 = {@code #Requires AutoHotkey v2.1-alpha.39} with the version from the folder of the v2.1
     * interpreter ({@link AhkSettings#findAutoHotkeyV21(int)}), otherwise {@code v2.1-}
     * ({@link #V21_FALLBACK}); every other level {@link #LINE}.
     */
    public static @NotNull String lineFor(@NotNull AhkLanguageLevel level) {
        return level == AhkLanguageLevel.V2_1 ? "#Requires AutoHotkey " + v21Version() : LINE;
    }

    /** Version of the v2.1 interpreter from the name of its folder ({@code v2.1-alpha.39}), else {@link #V21_FALLBACK}. */
    private static @NotNull String v21Version() {
        try {
            String program = AhkSettings.getInstance().findAutoHotkeyV21(0);
            Path dir = program == null ? null : Path.of(program).toAbsolutePath().getParent();
            Path name = dir == null ? null : dir.getFileName();
            Matcher m = name == null ? null : VERSION_DIR.matcher(name.toString());
            if (m != null && m.matches() && AhkVersion.atLeastV21(m.group(1))) return "v" + m.group(1);
        } catch (RuntimeException ignored) {
            // invalid path: fallback
        }
        return V21_FALLBACK;
    }

    /** Fix for the given #Requires line at the given position in the menu. */
    AhkAddRequiresFix(@NotNull String line, @NotNull Priority priority) {
        this.line = line;
        this.priority = priority;
    }

    /** Text in the Alt+Enter menu. */
    @Override
    public @NotNull String getText() {
        return AhkBundle.message("intention.add.requires.line", line);
    }

    /** Family name (same as the text). */
    @Override
    public @NotNull String getFamilyName() {
        return AhkBundle.message("intention.add.requires.line", line);
    }

    /** Always available in an AutoHotkey file. */
    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        return file != null;   // the platform checks write protection (startInWriteAction)
    }

    /** Inserts the line at the start of the document. */
    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file) {
        Document document = PsiDocumentManager.getInstance(project).getDocument(file);
        if (document == null) return;
        document.insertString(0, line + "\n");
        PsiDocumentManager.getInstance(project).commitDocument(document);
    }

    /** The fix changes the document and therefore runs in a write action. */
    @Override
    public boolean startInWriteAction() {
        return true;
    }

    /** Position in the menu: the fix for the detected version first. */
    @Override
    public @NotNull Priority getPriority() {
        return priority;
    }
}
