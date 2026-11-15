package de.kumpewo.autohotkey.structure;

import com.intellij.icons.AllIcons;
import com.intellij.ide.structureView.StructureViewTreeElement;
import com.intellij.navigation.ItemPresentation;
import com.intellij.psi.PsiElement;
import de.kumpewo.autohotkey.psi.AhkClass;
import de.kumpewo.autohotkey.psi.AhkFunc;
import de.kumpewo.autohotkey.psi.AhkLabel;
import de.kumpewo.autohotkey.psi.AhkPersistent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Node of the structure view: the file, a class, function/method, hotkey/hotstring or label.
 * The children are the next structure elements below it, skipping blocks and other containers.
 */
public class AhkStructureViewElement implements StructureViewTreeElement {

    private final PsiElement element;

    public AhkStructureViewElement(PsiElement element) {
        this.element = element;
    }

    /** The PSI element of the node. */
    @Override
    @NotNull
    public Object getValue() {
        return element;
    }

    /** Text and icon: from the element itself (mixins) or a fallback for the file. */
    @Override
    @NotNull
    public ItemPresentation getPresentation() {
        if (element instanceof ItemPresentation) {
            return (ItemPresentation) element;
        }

        // Fallback for AhkFile (PsiFile), which does not implement ItemPresentation
        return new ItemPresentation() {
            @Override
            public @NotNull String getPresentableText() {
                if (element instanceof com.intellij.psi.PsiFile) {
                    return ((com.intellij.psi.PsiFile) element).getName();
                }
                return element.getText();
            }

            @Override
            @Nullable
            public Icon getIcon(boolean open) {
                if (element instanceof AhkClass) {
                    return AllIcons.Nodes.Class;
                } else if (element instanceof AhkFunc) {
                    return AllIcons.Nodes.Function;
                } else if (element instanceof AhkPersistent) {
                    return AllIcons.Nodes.Artifact;
                } else if (element instanceof AhkLabel) {
                    return AllIcons.Nodes.Symlink;
                }
                return null;
            }
        };
    }

    /** The structure elements directly below this node. */
    @Override
    @NotNull
    public StructureViewTreeElement @NotNull [] getChildren() {
        List<StructureViewTreeElement> children = new ArrayList<>();

        // Search recursively to get through blocks
        collectStructureChildren(element, children);

        return children.toArray(new StructureViewTreeElement[0]);
    }

    /**
     * Walks the PSI tree recursively, skips containers (such as blocks) and collects all directly contained
     * class, function, hotkey/hotstring and label nodes.
     */
    private void collectStructureChildren(@NotNull PsiElement current, @NotNull List<StructureViewTreeElement> children) {
        for (PsiElement sub : current.getChildren()) {
            if (sub instanceof AhkClass || sub instanceof AhkFunc
                    || sub instanceof AhkPersistent
                    || sub instanceof AhkLabel) {
                // Add the structure element found as a tree child
                children.add(new AhkStructureViewElement(sub));
                // IMPORTANT: do NOT recurse further here,
                // the children of this element are only shown
                // when the node is expanded in the UI!
            } else {
                // An intermediate node (e.g. a block or the file body),
                // so dig deeper to find structures inside it
                collectStructureChildren(sub, children);
            }
        }
    }

    /** Navigates to the element in the editor. */
    @Override
    public void navigate(boolean requestFocus) {
        if (element instanceof com.intellij.pom.Navigatable) {
            ((com.intellij.pom.Navigatable) element).navigate(requestFocus);
        }
    }

    /** True if the element can be navigated to. */
    @Override
    public boolean canNavigate() {
        return element instanceof com.intellij.pom.Navigatable && ((com.intellij.pom.Navigatable) element).canNavigate();
    }

    /** Same as {@link #canNavigate()}. */
    @Override
    public boolean canNavigateToSource() {
        return canNavigate();
    }
}
