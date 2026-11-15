package de.kumpewo.autohotkey.include;

import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiReferenceBase;
import de.kumpewo.autohotkey.psi.AhkDirective;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * Reference from the argument of an {@code #Include} to the file (Ctrl+B opens it). If the argument is a
 * directory, the target is {@link AhkIncludeDirectoryTarget} (Ctrl+B shows a warning).
 */
public final class AhkIncludeReference extends PsiReferenceBase<AhkDirective> {

    public AhkIncludeReference(@NotNull AhkDirective directive, @NotNull TextRange range) {
        super(directive, range, true);
    }

    /** The included file, a directory target, or {@code null} if nothing is found. */
    @Override
    public @Nullable PsiElement resolve() {
        Path target = AhkInclude.resolve(myElement);
        if (target == null) return null;
        VirtualFile file = LocalFileSystem.getInstance().findFileByNioFile(target);
        if (file == null) return null;
        if (file.isDirectory()) return new AhkIncludeDirectoryTarget(myElement, target);
        return PsiManager.getInstance(myElement.getProject()).findFile(file);
    }

    /** No completion variants. */
    @Override
    public Object @NotNull [] getVariants() {
        return EMPTY_ARRAY;
    }
}
