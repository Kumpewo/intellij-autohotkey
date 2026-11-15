package de.kumpewo.autohotkey.navigation;

import com.intellij.icons.AllIcons;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import de.kumpewo.autohotkey.psi.AhkClass;
import de.kumpewo.autohotkey.psi.AhkClassProperty;
import de.kumpewo.autohotkey.psi.AhkDefinitions;
import de.kumpewo.autohotkey.psi.AhkFunc;
import de.kumpewo.autohotkey.psi.AhkLabel;
import de.kumpewo.autohotkey.psi.AhkPersistent;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;

/**
 * The definitions of a file that Go to Symbol and Go to Class find ({@link AhkSymbolIndex},
 * {@link AhkGotoSymbolContributor}): functions, methods, classes (also nested), properties, fields, labels, hotkeys
 * and hotstrings. The index and the contributors use the same list, so the names in the index always match.
 */
public final class AhkSymbols {

    private AhkSymbols() {
    }

    /** Kind of a symbol, with its icon. */
    public enum Kind {
        FUNCTION(AllIcons.Nodes.Function),
        METHOD(AllIcons.Nodes.Method),
        CLASS(AllIcons.Nodes.Class),
        PROPERTY(AllIcons.Nodes.Property),
        FIELD(AllIcons.Nodes.Field),
        LABEL(AllIcons.Nodes.Symlink),
        HOTKEY(AllIcons.Nodes.Artifact),
        HOTSTRING(AllIcons.Nodes.Artifact);

        private final Icon icon;

        Kind(Icon icon) {
            this.icon = icon;
        }

        /** Icon in the result list. */
        public @NotNull Icon icon() {
            return icon;
        }
    }

    /**
     * A symbol: {@code name} is what the user types and the key in the index (for a hotkey the keys without
     * {@code ::}, for a hotstring the abbreviation), {@code qualifiedName} includes the enclosing classes
     * ({@code Outer.Inner.Method}), {@code text} is shown in the list ({@code Outer.Inner.Method()}, {@code F1::}),
     * {@code target} is the element to navigate to.
     */
    public record Symbol(@NotNull Kind kind, @NotNull String name, @NotNull String qualifiedName,
                         @NotNull String text, @NotNull PsiElement target) {
    }

    /** All symbols of the file in the order of the code. */
    public static @NotNull List<Symbol> collect(@NotNull PsiFile file) {
        List<Symbol> result = new ArrayList<>();
        for (PsiElement element : PsiTreeUtil.findChildrenOfAnyType(file, AhkFunc.class, AhkClass.class,
                AhkClassProperty.class, AhkLabel.class, AhkPersistent.class)) {
            if (element instanceof AhkClass cls) {
                String qualified = qualified(cls);
                if (cls.getName() != null) result.add(new Symbol(Kind.CLASS, cls.getName(), qualified, qualified, cls));
                for (AhkDefinitions.Field field : AhkDefinitions.fields(cls)) {
                    result.add(new Symbol(Kind.FIELD, field.name(), qualified + "." + field.name(),
                            qualified + "." + field.name(), new AhkFieldTarget(field)));
                }
            } else if (element instanceof AhkFunc func) {
                String name = func.getName();
                if (name == null) continue;
                AhkClass owner = AhkDefinitions.ownerClass(func);
                String qualified = owner == null ? name : qualified(owner) + "." + name;
                result.add(new Symbol(owner == null ? Kind.FUNCTION : Kind.METHOD, name, qualified, qualified + "()", func));
            } else if (element instanceof AhkClassProperty property) {
                String name = property.getName();
                AhkClass owner = AhkDefinitions.ownerClass(property);
                if (name == null) continue;
                String qualified = owner == null ? name : qualified(owner) + "." + name;
                result.add(new Symbol(Kind.PROPERTY, name, qualified, qualified, property));
            } else if (element instanceof AhkLabel label) {
                String name = label.getName();
                if (name != null) result.add(new Symbol(Kind.LABEL, name, name, name + ":", label));
            } else if (element instanceof AhkPersistent persistent) {
                Symbol symbol = hotkey(persistent);
                if (symbol != null) result.add(symbol);
            }
        }
        return result;
    }

    /** Name of the class with all enclosing classes ({@code Outer.Inner}). */
    static @NotNull String qualified(@NotNull AhkClass cls) {
        StringBuilder result = new StringBuilder(String.valueOf(cls.getName()));
        for (AhkClass owner = AhkDefinitions.ownerClass(cls); owner != null; owner = AhkDefinitions.ownerClass(owner)) {
            result.insert(0, owner.getName() + ".");
        }
        return result.toString();
    }

    /**
     * Hotkey ({@code F1::}, name {@code F1}) or hotstring ({@code :X:btw::}, name {@code btw}); {@code null} if the
     * name would be empty.
     */
    private static @Nullable Symbol hotkey(@NotNull AhkPersistent persistent) {
        PsiElement token = persistent.getFirstChild();
        if (token == null) return null;
        String text = token.getText().trim();
        if (!text.endsWith("::")) return null;
        String head = text.substring(0, text.length() - 2);
        if (token.getNode().getElementType() == AhkTypes.HOTSTRING) {
            int colon = head.indexOf(':', 1); // ":options:abbreviation"
            String name = colon < 0 ? "" : head.substring(colon + 1);
            return name.isEmpty() ? null : new Symbol(Kind.HOTSTRING, name, name, text, persistent);
        }
        String name = head.trim();
        return name.isEmpty() ? null : new Symbol(Kind.HOTKEY, name, name, text, persistent);
    }
}
