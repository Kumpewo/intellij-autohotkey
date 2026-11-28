package de.kumpewo.autohotkey.psi;

import de.kumpewo.autohotkey.AhkBundle;
import com.intellij.navigation.ItemPresentation;
import com.intellij.ide.projectView.PresentationData;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Helpers shared by all definitions (function/method, class, property, field) for navigation, completion and choosers.
 */
public final class AhkDefinitions {

    private AhkDefinitions() {
    }

    /** A field of a class: its name ({@code IDENTIFIER} leaf) and whether it is declared {@code static}. */
    public record Field(@NotNull PsiElement identifier, boolean isStatic) {
        /** Name as written. */
        public @NotNull String name() {
            return identifier.getText();
        }

        /** The class that defines the field. */
        public @Nullable AhkClass owner() {
            return PsiTreeUtil.getParentOfType(identifier, AhkClass.class);
        }
    }

    /**
     * Fields of the class: statements directly in its body that assign to a name ({@code count := 0},
     * {@code a := 1, b := 2}), declare one with {@code static} ({@code static count := 0}) or declare a typed property
     * (v2.1, {@code x : Int32}, also inside a {@code union} block); not in methods or nested classes. In the order of
     * the code.
     */
    public static @NotNull List<Field> fields(@NotNull AhkClass cls) {
        List<Field> result = new ArrayList<>();
        ASTNode block = cls.getNode().findChildByType(AhkTypes.BLOCK);
        if (block != null) addFields(block, result);
        return result;
    }

    /** Adds the fields declared by the statements directly in the block (a class body or a union block). */
    private static void addFields(@NotNull ASTNode block, @NotNull List<Field> result) {
        for (ASTNode statement = block.getFirstChildNode(); statement != null; statement = statement.getTreeNext()) {
            IElementType type = statement.getElementType();
            if (type == AhkTypes.EXPR_STMT || type == AhkTypes.TYPED_DECL_STMT) {
                for (ASTNode item = statement.getFirstChildNode(); item != null; item = item.getTreeNext()) {
                    PsiElement name = item.getElementType() == AhkTypes.TYPED_PROPERTY
                            ? identifier(item) : assignedName(item);
                    if (name != null) result.add(new Field(name, false));
                }
            } else if (type == AhkTypes.UNION_BLOCK) {
                ASTNode union = statement.findChildByType(AhkTypes.BLOCK);
                if (union != null) addFields(union, result);
            } else if (type == AhkTypes.DECL_STMT) {
                ASTNode keyword = statement.getFirstChildNode();
                if (keyword == null || !"static".equalsIgnoreCase(keyword.getText())) continue;
                for (ASTNode item : statement.getChildren(TokenSet.create(AhkTypes.DECL_ITEM))) {
                    ASTNode id = item.findChildByType(AhkTypes.IDENTIFIER);
                    if (id != null) result.add(new Field(id.getPsi(), true));
                }
            }
        }
    }

    /** The name of a typed property ({@code x} in {@code x : Int32}). */
    private static @Nullable PsiElement identifier(ASTNode typedProperty) {
        ASTNode id = typedProperty.findChildByType(AhkTypes.IDENTIFIER);
        return id == null ? null : id.getPsi();
    }

    /** Is the identifier the name of a field ({@link #fields})? */
    public static boolean isFieldName(@NotNull PsiElement identifier) {
        return field(identifier) != null;
    }

    /** The field whose name in its defining statement is this identifier ({@link #fields}), or {@code null}. */
    public static @Nullable Field field(@NotNull PsiElement identifier) {
        AhkClass cls = PsiTreeUtil.getParentOfType(identifier, AhkClass.class, AhkFunc.class) instanceof AhkClass c ? c : null;
        if (cls == null) return null;
        for (Field field : fields(cls)) {
            if (field.identifier() == identifier) return field;
        }
        return null;
    }

    /** {@code name := …}: the identifier of the assigned name, otherwise {@code null}. */
    private static @Nullable PsiElement assignedName(ASTNode node) {
        if (node.getElementType() != AhkTypes.ASSIGN_EXPR) return null;
        ASTNode target = node.getFirstChildNode();
        if (target == null || target.getElementType() != AhkTypes.REF_EXPR) return null;
        ASTNode id = target.findChildByType(AhkTypes.IDENTIFIER);
        return id == null ? null : id.getPsi();
    }

    /**
     * The names declared with {@code global} in the file, at the top level and in functions ({@code global x},
     * {@code global x := 1}, {@code global a, b}): their {@code IDENTIFIER} leaves in the order of the code. A
     * {@code global} without a name (assume-global) declares nothing.
     */
    public static @NotNull List<PsiElement> globalDeclarations(@NotNull PsiFile file) {
        return CachedValuesManager.getCachedValue(file, () -> {
            List<PsiElement> result = new ArrayList<>();
            for (AhkDeclStmt decl : PsiTreeUtil.findChildrenOfType(file, AhkDeclStmt.class)) {
                ASTNode keyword = decl.getNode().getFirstChildNode();
                if (keyword == null || !"global".equalsIgnoreCase(keyword.getText())) continue;
                for (ASTNode item : decl.getNode().getChildren(TokenSet.create(AhkTypes.DECL_ITEM))) {
                    ASTNode id = item.findChildByType(AhkTypes.IDENTIFIER);
                    if (id != null) result.add(id.getPsi());
                }
            }
            return CachedValueProvider.Result.create(List.copyOf(result), file);
        });
    }

    /**
     * Directly enclosing class (of a method, property or nested class), or {@code null}. A function definition
     * expression is never a method, also not in the initializer of a field ({@code p := () { ... }}).
     */
    public static @Nullable AhkClass ownerClass(@NotNull PsiElement definition) {
        if (isFunctionExpression(definition)) return null;
        PsiElement owner = PsiTreeUtil.getParentOfType(definition, AhkClass.class, AhkFunc.class);
        return owner instanceof AhkClass cls ? cls : null;
    }

    /** The function of a function definition expression (v2.1, {@code x := f() { ... }}), possibly without a name. */
    public static boolean isFunctionExpression(@Nullable PsiElement definition) {
        return definition instanceof AhkFunc && definition.getParent() instanceof AhkFuncExpr;
    }

    /**
     * Presentation in choosers (e.g. Ctrl+B with several targets): the name and, if present, the enclosing class
     * as location. The structure view still uses the element itself as {@link ItemPresentation}.
     */
    public static @NotNull ItemPresentation presentation(@NotNull PsiElement definition, @NotNull String text,
                                                        @Nullable Icon icon) {
        AhkClass owner = ownerClass(definition);
        String location = owner == null || owner.getName() == null ? null : AhkBundle.message("navigation.location.class", owner.getName());
        return new PresentationData(text, location, icon, null);
    }
}
