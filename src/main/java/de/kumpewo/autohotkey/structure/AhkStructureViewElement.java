package de.kumpewo.autohotkey.structure;

import com.intellij.icons.AllIcons;
import com.intellij.ide.structureView.StructureViewTreeElement;
import com.intellij.lang.ASTNode;
import com.intellij.navigation.ItemPresentation;
import com.intellij.psi.PsiElement;
import de.kumpewo.autohotkey.navigation.AhkFieldTarget;
import de.kumpewo.autohotkey.psi.AhkClass;
import de.kumpewo.autohotkey.psi.AhkClassProperty;
import de.kumpewo.autohotkey.psi.AhkDefinitions;
import de.kumpewo.autohotkey.psi.AhkFunc;
import de.kumpewo.autohotkey.psi.AhkLabel;
import de.kumpewo.autohotkey.psi.AhkPersistent;
import de.kumpewo.autohotkey.psi.AhkTypes;
import com.intellij.openapi.util.TextRange;
import de.kumpewo.autohotkey.module.AhkModuleSymbol;
import de.kumpewo.autohotkey.module.AhkModules;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Node of the structure view: the file, a class, function/method, property, field, hotkey/hotstring or label.
 * The children are the next structure elements below it, skipping blocks and other containers. The fields of a class
 * ({@link AhkDefinitions#fields}: {@code x := 0}, {@code static x := 0}, typed properties also inside {@code union})
 * are nodes of their {@link AhkFieldTarget}; a typed property shows its type.
 * <p>
 * v2.1: a file with {@code #Module} shows the definitions of {@code __Main} first, then one node per
 * {@code #Module} ({@link AhkModuleSymbol}) with the definitions of its region.
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

    /** Text and icon: own presentation for fields and properties, from the element itself (mixins) or a fallback. */
    @Override
    @NotNull
    public ItemPresentation getPresentation() {
        if (element instanceof AhkFieldTarget field) {
            return presentation(field.getName(), typeOf(field), AllIcons.Nodes.Field);
        }
        if (element instanceof AhkModuleSymbol module) {
            return presentation(module.getName(), null, AllIcons.Nodes.Module);
        }
        if (element instanceof AhkClassProperty property) {
            String name = property.getName();
            return presentation(name != null ? name : "?", null, AllIcons.Nodes.Property);
        }
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

    /** Fixed text, location string (grey, after the text) and icon. */
    private static ItemPresentation presentation(@NotNull String text, @Nullable String location, @NotNull Icon icon) {
        return new ItemPresentation() {
            @Override
            public @NotNull String getPresentableText() {
                return text;
            }

            @Override
            public @Nullable String getLocationString() {
                return location;
            }

            @Override
            public @NotNull Icon getIcon(boolean unused) {
                return icon;
            }
        };
    }

    /** The type of a typed property ({@code Int32} in {@code x : Int32}), otherwise {@code null}. */
    private static @Nullable String typeOf(@NotNull AhkFieldTarget field) {
        PsiElement parent = field.field().identifier().getParent();
        if (parent == null || parent.getNode().getElementType() != AhkTypes.TYPED_PROPERTY) return null;
        ASTNode type = parent.getNode().findChildByType(AhkTypes.TYPE_SPEC);
        return type == null ? null : type.getText().trim();
    }

    /** The structure elements directly below this node. */
    @Override
    @NotNull
    public StructureViewTreeElement @NotNull [] getChildren() {
        List<StructureViewTreeElement> children = new ArrayList<>();
        if (element instanceof AhkClass cls) {
            for (AhkDefinitions.Field field : AhkDefinitions.fields(cls)) {
                children.add(new AhkStructureViewElement(new AhkFieldTarget(field)));
            }
        }
        if (element instanceof AhkModuleSymbol module) {
            AhkModules.Region region = region(module);
            if (region != null) {
                List<StructureViewTreeElement> all = new ArrayList<>();
                collectStructureChildren(module.getContainingFile(), all);
                for (StructureViewTreeElement child : all) {
                    if (region.range().contains(((PsiElement) child.getValue()).getTextOffset())) children.add(child);
                }
            }
        } else if (!(element instanceof AhkFieldTarget)) {
            // Search recursively to get through blocks
            collectStructureChildren(element, children);
            if (element instanceof com.intellij.psi.PsiFile file) groupByModule(file, children);
        }
        return children.toArray(new StructureViewTreeElement[0]);
    }

    /**
     * v2.1 file with {@code #Module}: keeps the nodes of the first region ({@code __Main}) and adds a module node per
     * {@code #Module} instead of the nodes of its region.
     */
    private static void groupByModule(com.intellij.psi.PsiFile file, List<StructureViewTreeElement> children) {
        List<AhkModules.Region> regions = AhkModules.regions(file);
        if (regions.size() < 2) return;
        TextRange main = regions.get(0).range();
        children.removeIf(child -> !main.contains(((PsiElement) child.getValue()).getTextOffset()));
        for (AhkModules.Region region : regions) {
            AhkModuleSymbol symbol = region.directive() == null ? null : AhkModules.moduleSymbol(region.directive());
            if (symbol != null) children.add(new AhkStructureViewElement(symbol));
        }
    }

    /** The region that the {@code #Module} of the symbol starts. */
    private static @Nullable AhkModules.Region region(AhkModuleSymbol module) {
        for (AhkModules.Region region : AhkModules.regions(module.getContainingFile())) {
            if (region.directive() != null && region.directive() == module.getParent()) return region;
        }
        return null;
    }

    /**
     * Walks the PSI tree recursively, skips containers (such as blocks) and collects all directly contained
     * class, function, property, hotkey/hotstring and label nodes.
     */
    private void collectStructureChildren(@NotNull PsiElement current, @NotNull List<StructureViewTreeElement> children) {
        for (PsiElement sub : current.getChildren()) {
            // A function expression without a name is no node; named functions inside it still are.
            if (sub instanceof AhkClass || sub instanceof AhkFunc func && func.getName() != null
                    || sub instanceof AhkClassProperty
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

    /** Equal if it stands for the same element (field targets are new objects each time). */
    @Override
    public boolean equals(Object o) {
        return o instanceof AhkStructureViewElement other && other.element.equals(element);
    }

    /** Hash of the element. */
    @Override
    public int hashCode() {
        return element.hashCode();
    }
}
