package de.kumpewo.autohotkey.parser;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiTreeUtil;
import de.kumpewo.autohotkey.psi.AhkTypes;
import de.kumpewo.autohotkey.settings.AhkSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Language level of an AutoHotkey file: which version of the language the parser, the name lists and the help
 * follow. It comes from the first {@code #Requires AutoHotkey ...} with a version ({@link AhkVersion#level});
 * without one from the setting {@link AhkSettings#getDefaultLanguageLevel()}.
 */
public enum AhkLanguageLevel {
    /** A version the plugin does not parse (v1): the file is swallowed unparsed ({@code OTHER_VERSION_CODE}). */
    OTHER("other", null),
    /** AutoHotkey v2.0. */
    V2_0("2.0", null),
    /** AutoHotkey v2.1; data sets that differ from v2.0 lie in the folder {@code v2.1/} of the resources. */
    V2_1("2.1", "v2.1");

    /** Version as text ({@code 2.0}, {@code 2.1}). */
    public final String version;
    /**
     * Folder of the data sets of this level next to those of v2.0 ({@code names/v2.1/}, {@code help/v2.1/},
     * {@code help/lookup/v2.1/}); {@code null} = the v2.0 data sets themselves.
     */
    public final @Nullable String folder;

    AhkLanguageLevel(String version, @Nullable String folder) {
        this.version = version;
        this.folder = folder;
    }

    /** The levels that can be chosen as default for files without {@code #Requires}. */
    public static AhkLanguageLevel[] selectable() {
        return new AhkLanguageLevel[]{V2_0, V2_1};
    }

    /** Level for the data sets: {@link #OTHER} uses those of v2.0. */
    public @NotNull AhkLanguageLevel dataLevel() {
        return this == OTHER ? V2_0 : this;
    }

    /**
     * Level of the file of the element (see the class comment); without a file the default from the settings.
     * Cached per file; recomputed when the file or the setting changes.
     */
    public static @NotNull AhkLanguageLevel of(@Nullable PsiElement element) {
        PsiFile file = element == null ? null : element.getContainingFile();
        if (file == null) return AhkSettings.defaultLevel();
        PsiFile original = file.getOriginalFile();
        PsiFile target = original != file ? original : file;
        return CachedValuesManager.getCachedValue(target, () -> CachedValueProvider.Result.create(
                compute(target), target, AhkSettings.LANGUAGE_LEVEL_TRACKER));
    }

    /** First {@code #Requires} directive with a version decides, otherwise the default. */
    private static AhkLanguageLevel compute(PsiFile file) {
        PsiElement requires = findRequires(file);
        AhkLanguageLevel level = requires == null ? null : AhkVersion.level(arguments(requires).getText());
        return level != null ? level : AhkSettings.defaultLevel();
    }

    /**
     * The deciding directive: the name {@code #Requires} (leaf {@code DIRECTIVE_NAME}) of the first
     * {@code #Requires AutoHotkey ...} with a version in the file; {@code null} if there is none (then the level is the
     * default from the settings). Also used to show where the level comes from (status bar).
     */
    public static @Nullable PsiElement findRequires(@NotNull PsiFile file) {
        for (PsiElement leaf = PsiTreeUtil.firstChild(file); leaf != null; leaf = PsiTreeUtil.nextLeaf(leaf)) {
            if (leaf.getNode().getElementType() != AhkTypes.DIRECTIVE_NAME
                    || !"#Requires".equalsIgnoreCase(leaf.getText())) {
                continue;
            }
            PsiElement args = arguments(leaf);
            if (args != null && AhkVersion.level(args.getText()) != null) return leaf;
        }
        return null;
    }

    /** The arguments (leaf {@code DIRECTIVE_ARGS}) after a directive name, or {@code null}. */
    private static @Nullable PsiElement arguments(@NotNull PsiElement name) {
        PsiElement args = name.getNextSibling();
        while (args != null && args.getNode().getElementType() == TokenType.WHITE_SPACE) args = args.getNextSibling();
        return args == null || args.getNode().getElementType() != AhkTypes.DIRECTIVE_ARGS ? null : args;
    }
}
