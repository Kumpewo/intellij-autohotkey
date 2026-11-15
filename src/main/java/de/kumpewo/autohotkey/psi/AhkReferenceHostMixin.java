package de.kumpewo.autohotkey.psi;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiReference;
import com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Base of elements that can carry references (ref_expr, literal_expr, member_expr, command_stmt, directive, and the
 * definitions of local variables: parameter, decl_item, for_vars, catch_clause, lambda_expr). Otherwise
 * ASTWrapperPsiElement does not ask the registered psi.referenceContributor extensions (e.g.
 * AhkLabelReferenceContributor for goto targets).
 */
public abstract class AhkReferenceHostMixin extends ASTWrapperPsiElement {

    public AhkReferenceHostMixin(@NotNull ASTNode node) {
        super(node);
    }

    /** All references the registered contributors provide for this element. */
    @Override
    public PsiReference @NotNull [] getReferences() {
        return ReferenceProvidersRegistry.getReferencesFromProviders(this);
    }

    /** The first reference, or {@code null}. */
    @Override
    public @Nullable PsiReference getReference() {
        PsiReference[] refs = getReferences();
        return refs.length == 0 ? null : refs[0];
    }
}
