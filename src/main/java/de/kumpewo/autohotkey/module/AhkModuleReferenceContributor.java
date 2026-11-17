package de.kumpewo.autohotkey.module;

import com.intellij.openapi.util.TextRange;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementResolveResult;
import com.intellij.psi.PsiPolyVariantReferenceBase;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceContributor;
import com.intellij.psi.PsiReferenceProvider;
import com.intellij.psi.PsiReferenceRegistrar;
import com.intellij.psi.ResolveResult;
import com.intellij.util.ProcessingContext;
import de.kumpewo.autohotkey.documentation.AhkHelpRepository;
import de.kumpewo.autohotkey.navigation.AhkDefinitionReference;
import de.kumpewo.autohotkey.navigation.AhkHelpTarget;
import de.kumpewo.autohotkey.parser.AhkLanguageLevel;
import de.kumpewo.autohotkey.psi.AhkDirective;
import de.kumpewo.autohotkey.psi.AhkImportItem;
import de.kumpewo.autohotkey.psi.AhkImportModule;
import de.kumpewo.autohotkey.psi.AhkRenames;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * References in {@code #Import} (AutoHotkey v2.1 modules): the module name ({@link ModuleReference}) and the
 * names in the list ({@link ImportReference}). Only in v2.1 files.
 */
public final class AhkModuleReferenceContributor extends PsiReferenceContributor {

    /** Registers the providers for {@code import_module} and {@code import_item}. */
    @Override
    public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(AhkImportModule.class), new PsiReferenceProvider() {
            /** The module reference (v2.1 files only). */
            @Override
            public PsiReference @NotNull [] getReferencesByElement(@NotNull PsiElement element,
                                                                   @NotNull ProcessingContext context) {
                if (!AhkModules.isActive(element.getContainingFile())) return PsiReference.EMPTY_ARRAY;
                TextRange range = ModuleReference.nameRange(element);
                return range == null ? PsiReference.EMPTY_ARRAY : new PsiReference[]{new ModuleReference(element, range)};
            }
        });
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(AhkImportItem.class), new PsiReferenceProvider() {
            /** The reference of the exported name (v2.1 files only; not for {@code *}). */
            @Override
            public PsiReference @NotNull [] getReferencesByElement(@NotNull PsiElement element,
                                                                   @NotNull ProcessingContext context) {
                if (!AhkModules.isActive(element.getContainingFile())) return PsiReference.EMPTY_ARRAY;
                PsiElement first = element.getFirstChild();
                if (first == null || first.getNode().getElementType() != de.kumpewo.autohotkey.psi.AhkTypes.IDENTIFIER) {
                    return PsiReference.EMPTY_ARRAY;
                }
                return new PsiReference[]{new ImportReference(element, new TextRange(0, first.getTextLength()))};
            }
        });
    }

    /** The {@code #Import} directive around an element of its spec. */
    static @Nullable AhkDirective directive(@NotNull PsiElement element) {
        return com.intellij.psi.util.PsiTreeUtil.getParentOfType(element, AhkDirective.class);
    }

    /**
     * Module name in {@code #Import}: the {@code #Module} of the group (renamed with it), the module file, or
     * nothing for {@code AHK}. In {@code "Path:Name"} only {@code Name} belongs to the reference.
     */
    public static final class ModuleReference extends PsiPolyVariantReferenceBase<PsiElement> {

        /** Reference to the module named at {@code range} of the {@code import_module} element. */
        ModuleReference(@NotNull PsiElement element, @NotNull TextRange range) {
            super(element, range, true);
        }

        /**
         * Range of the name: the identifier, or the text inside the quotes (only the part after the last
         * {@code :} for {@code "Path:Name"}); {@code null} if empty.
         */
        static @Nullable TextRange nameRange(@NotNull PsiElement importModule) {
            String text = importModule.getText();
            if (text.isEmpty()) return null;
            char first = text.charAt(0);
            if (first != '"' && first != '\'') return new TextRange(0, text.length());
            int end = text.length() > 1 && text.charAt(text.length() - 1) == first ? text.length() - 1 : text.length();
            int start = 1;
            String inner = text.substring(start, end);
            int colon = inner.lastIndexOf(':');
            if (colon > 1 && AhkModules.isIdentifier(inner.substring(colon + 1))) start += colon + 1;
            return start >= end ? null : new TextRange(start, end);
        }

        /** The definition of the module (see class comment). */
        @Override
        public ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
            AhkDirective directive = directive(myElement);
            AhkModules.Import imp = directive == null ? null : AhkModules.parse(directive);
            if (imp == null) return ResolveResult.EMPTY_ARRAY;
            AhkModules.Module module = AhkModules.resolveModule(imp, AhkModules.moduleOf(directive));
            PsiElement target = module == null ? null : AhkModules.moduleTarget(module);
            return target == null ? ResolveResult.EMPTY_ARRAY : PsiElementResolveResult.createResults(target);
        }

        /** Rename of a {@code #Module}: replaces the name; a file module keeps its text. */
        @Override
        public PsiElement handleElementRename(@NotNull String newElementName) {
            if (newElementName.toLowerCase(java.util.Locale.ROOT).endsWith(".ahk")) return myElement;
            return AhkRenames.replaceRange(myElement, getRangeInElement(), newElementName);
        }

        /** No variants: completion is done by AhkCompletionContributor. */
        @Override
        public Object @NotNull [] getVariants() {
            return EMPTY_ARRAY;
        }
    }

    /**
     * A name in the list of an {@code #Import} ({@code Name} in {@code {Name as Alias}}): what the module exports
     * under this name ({@link AhkModules#exports}); for the built-in module {@code AHK} the help of the name.
     */
    public static final class ImportReference extends PsiPolyVariantReferenceBase<PsiElement> {

        /** Reference to the exported name at {@code range} of the {@code import_item} element. */
        ImportReference(@NotNull PsiElement element, @NotNull TextRange range) {
            super(element, range, true);
        }

        /** The exported definition, the help of a built-in name, or nothing. */
        @Override
        public ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
            AhkDirective directive = directive(myElement);
            AhkModules.Import imp = directive == null ? null : AhkModules.parse(directive);
            if (imp == null) return ResolveResult.EMPTY_ARRAY;
            AhkModules.Module module = AhkModules.resolveModule(imp, AhkModules.moduleOf(directive));
            if (module == null) return ResolveResult.EMPTY_ARRAY;
            String name = getValue();
            if (module.isBuiltin()) {
                if (AhkHelpRepository.topic(AhkLanguageLevel.of(myElement), name) == null) return ResolveResult.EMPTY_ARRAY;
                return PsiElementResolveResult.createResults(new AhkHelpTarget(name, myElement,
                        myElement.getTextRange().getStartOffset()));
            }
            List<PsiElement> targets = AhkModules.exports(module, name,
                    AhkDefinitionReference.finder(AhkDefinitionReference.Kind.FREE, name, null), true).targets();
            return PsiElementResolveResult.createResults(targets);
        }

        /** Rename of the exported definition: replaces the name (an alias stays). */
        @Override
        public PsiElement handleElementRename(@NotNull String newElementName) {
            return AhkRenames.replaceRange(myElement, getRangeInElement(), newElementName);
        }

        /** No variants: completion is done by AhkCompletionContributor. */
        @Override
        public Object @NotNull [] getVariants() {
            return EMPTY_ARRAY;
        }
    }
}
