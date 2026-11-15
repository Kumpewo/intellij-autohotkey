package de.kumpewo.autohotkey.structure;

import com.intellij.ide.util.treeView.smartTree.ActionPresentation;
import com.intellij.ide.util.treeView.smartTree.ActionPresentationData;
import com.intellij.ide.util.treeView.smartTree.Sorter;
import com.intellij.ide.util.treeView.smartTree.TreeElement;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiNamedElement;
import de.kumpewo.autohotkey.psi.AhkClass;
import de.kumpewo.autohotkey.psi.AhkFunc;
import de.kumpewo.autohotkey.psi.AhkLabel;
import de.kumpewo.autohotkey.psi.AhkPersistent;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;

/**
 * Sorting of the structure view: hotkeys/hotstrings first (in file order), then classes and functions
 * (alphabetically), then labels (in file order).
 */
public class AhkStructureSorter implements Sorter {
    public static final Sorter INSTANCE = new AhkStructureSorter();

    /** Internal ID of the sorter. */
    @Override
    public @NotNull String getName() {
        return "AHK_CUSTOM_SORTING";
    }

    /** Text and tooltip of the toolbar button. */
    @Override
    public @NotNull ActionPresentation getPresentation() {
        return new ActionPresentationData(
                "Group by Category",
                "Group hotkeys first, then classes, functions and labels",
                null // optional: an icon could be passed here
        );
    }

    /** Compares by group first, then within the group (see class comment). */
    @Override
    public @NotNull Comparator<TreeElement> getComparator() {
        return (o1, o2) -> {
            int w1 = getWeight(o1);
            int w2 = getWeight(o2);

            // 1. Sort by group priority
            if (w1 != w2) {
                return Integer.compare(w1, w2);
            }

            // 2. Sort within the same group
            if (o1 instanceof AhkStructureViewElement && o2 instanceof AhkStructureViewElement) {
                PsiElement el1 = (PsiElement) (((AhkStructureViewElement) o1).getValue());
                PsiElement el2 = (PsiElement) (((AhkStructureViewElement) o2).getValue());

                // Group 1 (persistent) and 4 (labels): keep declaration order
                if (el1 instanceof AhkPersistent && el2 instanceof AhkPersistent
                        || el1 instanceof AhkLabel && el2 instanceof AhkLabel) {
                    return Integer.compare(el1.getTextOffset(), el2.getTextOffset());
                }

                // Group 2 & 3 (classes & functions): alphabetically by name
                if (el1 instanceof PsiNamedElement && el2 instanceof PsiNamedElement) {
                    String name1 = ((PsiNamedElement) el1).getName();
                    String name2 = ((PsiNamedElement) el2).getName();
                    if (name1 != null && name2 != null) {
                        return name1.compareToIgnoreCase(name2);
                    }
                }
            }
            return 0;
        };
    }

    /**
     * Order of the main groups:
     * 1 = AhkPersistent (hotkeys / hotstrings)
     * 2 = AhkClass
     * 3 = AhkFunc
     * 4 = AhkLabel
     */
    private int getWeight(TreeElement element) {
        if (element instanceof AhkStructureViewElement) {
            PsiElement psi = (PsiElement) (((AhkStructureViewElement) element).getValue());
            if (psi instanceof AhkPersistent) return 1;
            if (psi instanceof AhkClass) return 2;
            if (psi instanceof AhkFunc) return 3;
            if (psi instanceof AhkLabel) return 4;
        }
        return 5;
    }

    /** The sorter is shown in the toolbar. */
    @Override
    public boolean isVisible() {
        return true;
    }
}
