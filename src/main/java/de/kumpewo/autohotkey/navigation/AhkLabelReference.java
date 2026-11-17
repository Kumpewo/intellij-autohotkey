package de.kumpewo.autohotkey.navigation;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.psi.util.PsiTreeUtil;
import de.kumpewo.autohotkey.psi.AhkFunc;
import de.kumpewo.autohotkey.psi.AhkLabel;
import de.kumpewo.autohotkey.psi.AhkPersistent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Reference from the target of a goto/break/continue ("goto Done", "goto 'Done'", "break Outer") to the label
 * "Done:". In AHK labels are local to the function or hotkey; the search therefore looks in the nearest enclosing
 * AhkFunc/AhkPersistent, otherwise in the file. Case does not matter.
 */
public class AhkLabelReference extends PsiReferenceBase<PsiElement> {

    private final String name;

    public AhkLabelReference(@NotNull PsiElement element, @NotNull TextRange range) {
        super(element, range);
        this.name = range.substring(element.getText());
    }

    /** The first label with this name in the same scope, or {@code null}. */
    @Override
    public @Nullable PsiElement resolve() {
        for (AhkLabel label : labelsInScope(myElement)) {
            if (name.equalsIgnoreCase(label.getName())) return label;
        }
        return null;
    }

    /** Rename: replaces the label name (also inside quotes: goto "Target"). */
    @Override
    public PsiElement handleElementRename(@NotNull String newElementName) {
        return de.kumpewo.autohotkey.psi.AhkRenames.replaceRange(myElement, getRangeInElement(), newElementName);
    }

    /** All label names in the same scope (completion after goto/break/continue). */
    @Override
    public Object @NotNull [] getVariants() {
        List<String> names = new ArrayList<>();
        for (AhkLabel label : labelsInScope(myElement)) {
            if (label.getName() != null) names.add(label.getName());
        }
        return names.toArray();
    }

    /** Labels in the same scope, without those in nested functions. */
    private static List<AhkLabel> labelsInScope(PsiElement element) {
        PsiElement scope = PsiTreeUtil.getParentOfType(element, AhkFunc.class, AhkPersistent.class);
        if (scope == null) scope = element.getContainingFile();
        List<AhkLabel> result = new ArrayList<>();
        if (scope == null) return result;
        for (AhkLabel label : PsiTreeUtil.findChildrenOfType(scope, AhkLabel.class)) {
            PsiElement owner = PsiTreeUtil.getParentOfType(label, AhkFunc.class, AhkPersistent.class);
            PsiElement ownerScope = owner == null ? label.getContainingFile() : owner;
            if (ownerScope == scope) result.add(label);
        }
        return result;
    }
}
