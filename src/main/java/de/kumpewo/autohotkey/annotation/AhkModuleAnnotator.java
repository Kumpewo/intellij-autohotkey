package de.kumpewo.autohotkey.annotation;

import com.intellij.lang.ASTNode;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.parser.AhkLanguageLevel;
import de.kumpewo.autohotkey.psi.AhkAccessor;
import de.kumpewo.autohotkey.psi.AhkBlock;
import de.kumpewo.autohotkey.psi.AhkBody;
import de.kumpewo.autohotkey.psi.AhkDirective;
import de.kumpewo.autohotkey.psi.AhkFunc;
import de.kumpewo.autohotkey.psi.AhkOtherVersionCode;
import de.kumpewo.autohotkey.psi.AhkPersistent;
import de.kumpewo.autohotkey.psi.AhkTypes;
import de.kumpewo.autohotkey.psi.AhkImportModule;
import de.kumpewo.autohotkey.highlighting.AhkNames;
import de.kumpewo.autohotkey.module.AhkModules;
import de.kumpewo.autohotkey.navigation.AhkDefinitionReference;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNull;

/**
 * Modules of AutoHotkey v2.1 ({@code #Module Name}, {@code #Import ...}):
 * <ul>
 *   <li>outside v2.1 files both directives are an error "requires AutoHotkey v2.1" (on the directive name, instead
 *       of the warning "unknown directive"; the syntax of #Import is parsed on every level)</li>
 *   <li>{@code #Module} only at the top level of the file (AutoHotkey: "Unexpected directive")</li>
 *   <li>{@code #Import} only at the top level or directly in the body of a function, method, property accessor or
 *       hotkey/hotstring, not in other blocks (if, loop, plain block) or a class body</li>
 *   <li>the name after {@code #Module} must be a valid identifier</li>
 *   <li>{@code #Import} of a module that is neither a {@code #Module} of the group nor a file in the search
 *       path: error "Module not found" (as AutoHotkey at load time; not for {@code "*Resource"})</li>
 *   <li>a name in the list that the module does not have: warning (AutoHotkey accepts it, the name stays
 *       unset)</li>
 *   <li>a name that a top-level {@code #Import} adds to the module (the module name or alias, the names of
 *       the list) and that the module also defines as function, class or global variable: error (AutoHotkey: "This
 *       import declaration conflicts with an existing Func", "cannot be used as an output variable")</li>
 * </ul>
 */
public class AhkModuleAnnotator implements Annotator {

    /** Checks a #Module or #Import directive. */
    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (!(element instanceof AhkDirective directive)) return;
        ASTNode nameNode = directive.getNode().findChildByType(AhkTypes.DIRECTIVE_NAME);
        if (nameNode == null) return;
        String name = nameNode.getText();
        boolean isModule = "#Module".equalsIgnoreCase(name);
        if (!isModule && !"#Import".equalsIgnoreCase(name)) return;
        if (PsiTreeUtil.getParentOfType(directive, AhkOtherVersionCode.class) != null) return;

        if (AhkLanguageLevel.of(directive) != AhkLanguageLevel.V2_1) {
            error(holder, nameNode.getPsi(), AhkBundle.message("parser.requires.v21"));
            return;
        }
        if (isModule) {
            if (!(directive.getParent() instanceof PsiFile)) {
                error(holder, nameNode.getPsi(), AhkBundle.message("annotator.module.misplaced"));
            }
            ASTNode args = directive.getNode().findChildByType(AhkTypes.DIRECTIVE_ARGS);
            String moduleName = args == null ? "" : args.getText().trim();
            if (!isIdentifier(moduleName)) {
                TextRange range = args == null ? nameNode.getTextRange() : TextRange.from(
                        args.getStartOffset() + args.getText().indexOf(moduleName), moduleName.length());
                holder.newAnnotation(HighlightSeverity.ERROR, AhkBundle.message("annotator.module.name"))
                        .range(range)
                        .create();
            }
        } else if (!importAllowedIn(directive.getParent())) {
            error(holder, nameNode.getPsi(), AhkBundle.message("annotator.import.misplaced"));
        } else {
            checkImport(directive, holder);
        }
    }

    /** Module not found, names the module does not have, conflicts with own definitions (see class comment). */
    private static void checkImport(@NotNull AhkDirective directive, @NotNull AnnotationHolder holder) {
        AhkModules.Import imp = AhkModules.parse(directive);
        AhkImportModule moduleElement = imp == null ? null : imp.moduleElement();
        if (imp == null || moduleElement == null || imp.module().isEmpty()) return;
        AhkModules.Module from = AhkModules.moduleOf(directive);
        AhkModules.Module module = AhkModules.resolveModule(imp, from);
        if (module == null && !AhkModules.exists(imp, from)) {
            error(holder, moduleElement, AhkBundle.message("annotator.import.not.found", imp.module()));
        }
        if (module != null) {
            for (AhkModules.Item item : imp.items()) {
                String name = item.name().getText();
                boolean known = module.isBuiltin()
                        ? AhkNames.of(directive).nonMember(name) != null
                        : !AhkModules.exports(module, name,
                        AhkDefinitionReference.finder(AhkDefinitionReference.Kind.FREE, name, null), true).isEmpty();
                if (!known) {
                    holder.newAnnotation(HighlightSeverity.WARNING,
                            AhkBundle.message("annotator.import.name.missing", module.isBuiltin() ? AhkModules.BUILTIN
                                    : imp.module(), name)).range(item.name()).create();
                }
            }
        }
        if (imp.scope() != null) return;
        PsiElement local = imp.alias() != null ? imp.alias() : imp.localName() != null ? moduleElement : null;
        if (local != null) checkConflict(from, imp.localName(), local, holder);
        for (AhkModules.Item item : imp.items()) {
            checkConflict(from, item.localName(), item.alias() != null ? item.alias() : item.name(), holder);
        }
    }

    /** Error on {@code element} if the module defines {@code name} as function, class or global variable. */
    private static void checkConflict(@NotNull AhkModules.Module module, @Nullable String name, @NotNull PsiElement element,
                                      @NotNull AnnotationHolder holder) {
        if (name == null) return;
        for (PsiElement definition : AhkModules.topLevelDefinitions(module)) {
            if (name.equalsIgnoreCase(((com.intellij.psi.PsiNamedElement) definition).getName())) {
                error(holder, element, AhkBundle.message("annotator.import.conflict", name, AhkBundle.message(
                        definition instanceof AhkFunc ? "findUsages.type.function" : "findUsages.type.class")));
                return;
            }
        }
        if (AhkModules.variable(module, name) != null) {
            error(holder, element, AhkBundle.message("annotator.import.conflict", name,
                    AhkBundle.message("module.symbol.variable")));
        }
    }

    /**
     * #Import is allowed at the top level and directly in the block of a function, accessor or hotkey/hotstring
     * (for a hotkey the block can also be its body element).
     */
    private static boolean importAllowedIn(PsiElement parent) {
        if (parent instanceof PsiFile) return true;
        if (!(parent instanceof AhkBlock)) return false;
        PsiElement owner = parent.getParent();
        if (owner instanceof AhkBody) owner = owner.getParent();
        return owner instanceof AhkFunc || owner instanceof AhkAccessor || owner instanceof AhkPersistent;
    }

    /**
     * Valid AutoHotkey name: letters, digits, underscore and non-ASCII characters, not starting with a digit.
     */
    static boolean isIdentifier(@NotNull String text) {
        if (text.isEmpty() || Character.isDigit(text.charAt(0))) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!(c == '_' || c >= 0x80 || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9')) {
                return false;
            }
        }
        return true;
    }

    /** Error on one element. */
    private static void error(AnnotationHolder holder, PsiElement element, String message) {
        holder.newAnnotation(HighlightSeverity.ERROR, message).range(element).create();
    }
}
