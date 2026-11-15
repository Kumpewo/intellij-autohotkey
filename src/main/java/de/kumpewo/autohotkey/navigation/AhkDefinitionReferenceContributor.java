package de.kumpewo.autohotkey.navigation;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.TextRange;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceContributor;
import com.intellij.psi.PsiReferenceProvider;
import com.intellij.psi.PsiReferenceRegistrar;
import com.intellij.psi.tree.IElementType;
import com.intellij.util.ProcessingContext;
import de.kumpewo.autohotkey.psi.AhkClass;
import de.kumpewo.autohotkey.psi.AhkCommandStmt;
import de.kumpewo.autohotkey.psi.AhkGotoStmt;
import de.kumpewo.autohotkey.psi.AhkJumpStmt;
import de.kumpewo.autohotkey.psi.AhkMemberExpr;
import de.kumpewo.autohotkey.psi.AhkRefExpr;
import de.kumpewo.autohotkey.psi.AhkTokenSets;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * References to functions, classes and members ({@link AhkDefinitionReference}): free-standing names
 * ({@code ref_expr} except goto/break/continue targets, command names), names after the dot (only a whole name,
 * not {@code obj.a%b%}) and the base class after {@code extends}.
 */
public final class AhkDefinitionReferenceContributor extends PsiReferenceContributor {

    /** Registers one provider each for ref_expr, command_stmt, member_expr and class. */
    @Override
    public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(AhkRefExpr.class), provider(element -> {
            PsiElement parent = element.getParent();
            if (parent instanceof AhkGotoStmt || parent instanceof AhkJumpStmt) return none();   // labels
            return one(new AhkDefinitionReference(element, new TextRange(0, element.getTextLength()),
                    AhkDefinitionReference.Kind.FREE, null));
        }));
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(AhkCommandStmt.class), provider(element -> {
            ASTNode first = element.getNode().getFirstChildNode();
            if (first == null || first.getElementType() != AhkTypes.IDENTIFIER) return none();
            return one(new AhkDefinitionReference(element, new TextRange(0, first.getTextLength()),
                    AhkDefinitionReference.Kind.FREE, null));
        }));
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(AhkMemberExpr.class), provider(element -> {
            ASTNode name = memberName(element.getNode());
            if (name == null) return none();
            int start = name.getStartOffset() - element.getTextRange().getStartOffset();
            return one(new AhkDefinitionReference(element, new TextRange(start, start + name.getTextLength()),
                    AhkDefinitionReference.Kind.MEMBER, null));
        }));
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(AhkClass.class), provider(element -> {
            List<PsiReference> result = new ArrayList<>();
            ASTNode node = element.getNode().findChildByType(AhkTypes.KEYWORD_EXTENDS);
            String previous = null;
            for (node = node == null ? null : node.getTreeNext(); node != null; node = node.getTreeNext()) {
                IElementType type = node.getElementType();
                if (type == AhkTypes.IDENTIFIER) {
                    int start = node.getStartOffset() - element.getTextRange().getStartOffset();
                    result.add(new AhkDefinitionReference(element, new TextRange(start, start + node.getTextLength()),
                            previous == null ? AhkDefinitionReference.Kind.BASE_CLASS
                                    : AhkDefinitionReference.Kind.NESTED_BASE_CLASS, previous));
                    previous = node.getText();
                } else if (type != AhkTypes.DOT && type != com.intellij.psi.TokenType.WHITE_SPACE) {
                    break;
                }
            }
            return result.toArray(PsiReference.EMPTY_ARRAY);
        }));
    }

    /**
     * The single name token after the dot of the member_expr; {@code null} for dynamic names
     * ({@code obj.%x%}, {@code obj.a%b%}).
     */
    private static ASTNode memberName(ASTNode memberExpr) {
        ASTNode dot = memberExpr.findChildByType(AhkTypes.DOT);
        if (dot == null) return null;
        ASTNode name = dot.getTreeNext();
        while (name != null && (name.getElementType() == com.intellij.psi.TokenType.WHITE_SPACE
                || AhkTokenSets.COMMENTS.contains(name.getElementType()))) {
            name = name.getTreeNext();
        }
        if (name == null || name.getPsi() instanceof com.intellij.psi.PsiErrorElement) return null;
        if (name.getFirstChildNode() != null) return null;          // element (deref_expr), not a token
        if (name.getTreeNext() != null) return null;                // further parts: dynamic name
        return name;
    }

    /** Creates the references for one element. */
    private interface Factory {
        PsiReference[] create(PsiElement element);
    }

    /** Wraps a factory as a reference provider. */
    private static PsiReferenceProvider provider(Factory factory) {
        return new PsiReferenceProvider() {
            @Override
            public PsiReference @NotNull [] getReferencesByElement(@NotNull PsiElement element,
                                                                   @NotNull ProcessingContext context) {
                return factory.create(element);
            }
        };
    }

    /** No reference. */
    private static PsiReference[] none() {
        return PsiReference.EMPTY_ARRAY;
    }

    /** Exactly one reference. */
    private static PsiReference[] one(PsiReference reference) {
        return new PsiReference[]{reference};
    }
}
