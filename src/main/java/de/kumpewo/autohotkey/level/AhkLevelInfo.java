package de.kumpewo.autohotkey.level;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.AhkFile;
import de.kumpewo.autohotkey.parser.AhkLanguageLevel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Language level of one AutoHotkey file and where it comes from, for the status bar widget
 * ({@link AhkLevelWidget}) and the Project view ({@link AhkLevelDecorator}). The level itself always comes from
 * {@link AhkLanguageLevel#of}.
 *
 * @param level          the language level of the file
 * @param requiresOffset offset of the deciding {@code #Requires} ({@link AhkLanguageLevel#findRequires}), -1 if the
 *                       level is the default from the settings
 * @param requiresLine   0-based line of that {@code #Requires}, -1 without one
 */
public record AhkLevelInfo(@NotNull AhkLanguageLevel level, int requiresOffset, int requiresLine) {

    /** Info for an AutoHotkey file; {@code null} for other files. Needs read access. */
    public static @Nullable AhkLevelInfo of(@NotNull PsiFile file) {
        if (!(file instanceof AhkFile)) return null;
        PsiElement requires = AhkLanguageLevel.findRequires(file);
        int offset = requires == null ? -1 : requires.getTextRange().getStartOffset();
        int line = offset < 0 ? -1 : StringUtil.offsetToLineNumber(file.getViewProvider().getContents(), offset);
        return new AhkLevelInfo(AhkLanguageLevel.of(file), offset, line);
    }

    /** Info for a file of the project; {@code null} if it is not a valid AutoHotkey file. Needs read access. */
    public static @Nullable AhkLevelInfo of(@NotNull Project project, @Nullable VirtualFile file) {
        if (file == null || !file.isValid() || file.isDirectory() || project.isDisposed()) return null;
        PsiFile psi = PsiManager.getInstance(project).findFile(file);
        return psi == null ? null : of(psi);
    }

    /** Does the level come from a {@code #Requires} in the file (and not from the default of the settings)? */
    public boolean fromRequires() {
        return requiresOffset >= 0;
    }

    /** Short label: {@code v2.0}, {@code v2.1}, {@code v1} (another version, file not parsed). */
    public @NotNull String shortLabel() {
        return AhkBundle.message("level.short." + level.name().toLowerCase(Locale.ROOT));
    }

    /** Text of the status bar widget: {@code AHK v2.1}. */
    public @NotNull String statusText() {
        return AhkBundle.message("level.status.text", shortLabel());
    }

    /** Tooltip of the status bar widget: level, where it comes from and what a click does. */
    public @NotNull String tooltip() {
        if (level == AhkLanguageLevel.OTHER) {
            return AhkBundle.message("level.status.tooltip.other", requiresLine + 1);
        }
        return fromRequires()
                ? AhkBundle.message("level.status.tooltip.requires", shortLabel(), requiresLine + 1)
                : AhkBundle.message("level.status.tooltip.default", shortLabel());
    }
}
