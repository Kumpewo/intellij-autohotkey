package de.kumpewo.autohotkey.structure;

import com.intellij.icons.AllIcons;
import com.intellij.lang.Language;
import com.intellij.psi.PsiElement;
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider;
import de.kumpewo.autohotkey.AhkLanguage;
import de.kumpewo.autohotkey.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * Breadcrumbs (at the bottom of the editor) and sticky lines (header lines at the top of the editor; the IDE
 * builds them from this provider): class, function/method, property, get/set, hotkey, hotstring and below them
 * the control structures. The header line is the line of {@code getTextOffset()} (for else the keyword, see
 * AhkElseBranchMixin); the IDE does not show single-line elements as header lines.
 */
public class AhkBreadcrumbsProvider implements BreadcrumbsProvider {

    private static final int MAX_LENGTH = 40;

    /** Only AutoHotkey. */
    @Override
    public Language @NotNull [] getLanguages() {
        return new Language[]{AhkLanguage.INSTANCE};
    }

    /** Definitions, hotkeys/hotstrings and control structures. */
    @Override
    public boolean acceptElement(@NotNull PsiElement e) {
        return e instanceof AhkClass || e instanceof AhkFunc || e instanceof AhkClassProperty
                || e instanceof AhkAccessor || e instanceof AhkPersistent
                || e instanceof AhkIfStmt || e instanceof AhkElseBranch
                || e instanceof AhkWhileStmt || e instanceof AhkLoopStmt || e instanceof AhkForStmt
                || e instanceof AhkTryStmt || e instanceof AhkCatchClause || e instanceof AhkFinallyClause
                || e instanceof AhkSwitchStmt || e instanceof AhkCaseClause;
    }

    /** Text of the breadcrumb: the header line; for a hotkey/hotstring only its token. */
    @Override
    public @NotNull String getElementInfo(@NotNull PsiElement e) {
        if (e instanceof AhkPersistent) {
            PsiElement first = e.getFirstChild(); // HOTKEY or HOTSTRING
            return first != null ? shorten(first.getText().trim()) : "";
        }
        return head(e);
    }

    /** Icon for definitions and hotkeys; none for control structures. */
    @Override
    public @Nullable Icon getElementIcon(@NotNull PsiElement e) {
        if (e instanceof AhkClass) return AllIcons.Nodes.Class;
        if (e instanceof AhkFunc) return AllIcons.Nodes.Function;
        if (e instanceof AhkClassProperty || e instanceof AhkAccessor) return AllIcons.Nodes.Property;
        if (e instanceof AhkPersistent) return AllIcons.Nodes.Artifact;
        return null;
    }

    /**
     * Header line = the line of the text offset (from the line start, so that "class"/"static" before the name
     * stay): up to the line end, without comment and without "{" at the end.
     */
    static @NotNull String head(@NotNull PsiElement e) {
        String text = e.getText();
        int start = Math.max(0, Math.min(text.length(), e.getTextOffset() - e.getTextRange().getStartOffset()));
        start = indexOfLastLineBreak(text, start) + 1;
        String line = text.substring(start);
        int end = indexOfAny(line, '\r', '\n');
        if (end >= 0) line = line.substring(0, end);
        int comment = commentStart(line);
        if (comment >= 0) line = line.substring(0, comment);
        line = line.trim();
        if (line.endsWith("{")) line = line.substring(0, line.length() - 1).trim();
        return shorten(line);
    }

    /** Last line break before {@code end}, -1 if none. */
    private static int indexOfLastLineBreak(String text, int end) {
        for (int i = end - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') return i;
        }
        return -1;
    }

    /** Comment: ";" with whitespace before it (as in the lexer); ";" at the start does not occur here. */
    private static int commentStart(String line) {
        for (int i = 1; i < line.length(); i++) {
            if (line.charAt(i) == ';' && Character.isWhitespace(line.charAt(i - 1))) return i;
        }
        return -1;
    }

    /** First position of {@code a} or {@code b}, -1 if neither occurs. */
    private static int indexOfAny(String s, char a, char b) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == a || c == b) return i;
        }
        return -1;
    }

    /** Cuts the text to MAX_LENGTH characters with an ellipsis. */
    private static String shorten(String s) {
        return s.length() <= MAX_LENGTH ? s : s.substring(0, MAX_LENGTH - 1) + "…";
    }
}
