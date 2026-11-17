package de.kumpewo.autohotkey.structure;

import com.intellij.ide.structureView.StructureViewModel;
import com.intellij.ide.structureView.StructureViewModelBase;
import com.intellij.ide.structureView.StructureViewTreeElement;
import com.intellij.ide.util.treeView.smartTree.Sorter;
import com.intellij.psi.PsiFile;
import de.kumpewo.autohotkey.navigation.AhkFieldTarget;
import de.kumpewo.autohotkey.psi.AhkClass;
import de.kumpewo.autohotkey.psi.AhkClassProperty;
import de.kumpewo.autohotkey.psi.AhkFunc;
import de.kumpewo.autohotkey.psi.AhkLabel;
import de.kumpewo.autohotkey.psi.AhkPersistent;
import org.jetbrains.annotations.NotNull;

/** Model of the structure view: root is the file, sorting by {@link AhkStructureSorter}. */
public class AhkStructureViewModel extends StructureViewModelBase implements StructureViewModel.ElementInfoProvider {

    public AhkStructureViewModel(@NotNull PsiFile psiFile) {
        super(psiFile, new AhkStructureViewElement(psiFile));
        withSuitableClasses(AhkClass.class, AhkClassProperty.class, AhkFunc.class, AhkLabel.class);
    }

    /** The only sorter is the grouping by category. */
    @NotNull
    @Override
    public Sorter[] getSorters() {
        return new Sorter[]{
//                Sorter.ALPHA_SORTER,
                AhkStructureSorter.INSTANCE
        };
    }

    /** Nodes show an expand handle only if they have children. */
    @Override
    public boolean isAlwaysShowsPlus(@NotNull StructureViewTreeElement element) {
        return false;
    }

    /** Hotkeys/hotstrings, labels and fields never have children. */
    @Override
    public boolean isAlwaysLeaf(@NotNull StructureViewTreeElement element) {
        Object value = element.getValue();
        return value instanceof AhkPersistent || value instanceof AhkLabel || value instanceof AhkFieldTarget;
    }
}
