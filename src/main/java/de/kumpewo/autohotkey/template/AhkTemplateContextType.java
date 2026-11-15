package de.kumpewo.autohotkey.template;

import com.intellij.codeInsight.template.TemplateActionContext;
import com.intellij.codeInsight.template.TemplateContextType;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.AhkLanguage;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Contexts of the live templates (Settings > Editor > Live Templates). IDs and base context are in plugin.xml:
 * {@code AUTOHOTKEY} = whole AutoHotkey file, below it {@code AUTOHOTKEY_CODE} (not in comments, strings,
 * hotstring replacement text) and {@code AUTOHOTKEY_COMMENT} (in comments). The bundled templates
 * (liveTemplates/AutoHotkey.xml) apply in code.
 */
public abstract class AhkTemplateContextType extends TemplateContextType {

    protected AhkTemplateContextType(@NotNull String presentableName) {
        super(presentableName);
    }

    /** Only in AutoHotkey files, and there as decided by {@link #matches}. */
    @Override
    public boolean isInContext(@NotNull TemplateActionContext context) {
        PsiFile file = context.getFile();
        if (!file.getLanguage().isKindOf(AhkLanguage.INSTANCE)) return false;
        return matches(file, context.getStartOffset());
    }

    /** Does the position {@code offset} belong to this context? */
    protected abstract boolean matches(@NotNull PsiFile file, int offset);

    /** Leaf containing the abbreviation (at the end of the file the last leaf before it). */
    static @Nullable PsiElement leafAt(@NotNull PsiFile file, int offset) {
        PsiElement leaf = file.findElementAt(offset);
        return leaf != null || offset == 0 ? leaf : file.findElementAt(offset - 1);
    }

    /** Whole AutoHotkey file. */
    public static final class Generic extends AhkTemplateContextType {
        public Generic() {
            super(AhkBundle.message("template.context.generic"));
        }

        /** Everywhere. */
        @Override
        protected boolean matches(@NotNull PsiFile file, int offset) {
            return true;
        }
    }

    /** Code: not in comments, strings and hotstring replacement text. */
    public static final class Code extends AhkTemplateContextType {
        public Code() {
            super(AhkBundle.message("template.context.code"));
        }

        /** Not in a comment, string or hotstring. */
        @Override
        protected boolean matches(@NotNull PsiFile file, int offset) {
            PsiElement leaf = leafAt(file, offset);
            if (leaf == null) return true;
            if (leaf instanceof PsiComment) return false;
            IElementType type = leaf.getNode().getElementType();
            return type != AhkTypes.STRING && type != AhkTypes.UNCLOSED_STRING && type != AhkTypes.HOTSTRING
                    && type != AhkTypes.HOTSTRING_TEXT;
        }
    }

    /** Inside a comment. */
    public static final class Comment extends AhkTemplateContextType {
        public Comment() {
            super(AhkBundle.message("template.context.comment"));
        }

        /** In a comment. */
        @Override
        protected boolean matches(@NotNull PsiFile file, int offset) {
            return leafAt(file, offset) instanceof PsiComment;
        }
    }
}
