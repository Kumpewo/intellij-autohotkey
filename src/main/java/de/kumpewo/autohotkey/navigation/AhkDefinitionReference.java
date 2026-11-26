package de.kumpewo.autohotkey.navigation;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementResolveResult;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiNameIdentifierOwner;
import com.intellij.psi.PsiPolyVariantReferenceBase;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.util.PsiTreeUtil;
import de.kumpewo.autohotkey.documentation.AhkDocumentationProvider;
import de.kumpewo.autohotkey.documentation.AhkHelpRepository;
import de.kumpewo.autohotkey.include.AhkInclude;
import de.kumpewo.autohotkey.module.AhkModules;
import de.kumpewo.autohotkey.parser.AhkLanguageLevel;
import de.kumpewo.autohotkey.psi.AhkClass;
import de.kumpewo.autohotkey.psi.AhkClassProperty;
import de.kumpewo.autohotkey.psi.AhkDefinitions;
import de.kumpewo.autohotkey.psi.AhkFunc;
import de.kumpewo.autohotkey.psi.AhkMemberExpr;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reference from a name to definitions in the same file and in the files it includes, also indirectly
 * ({@link AhkInclude#visibleFiles}; no search in the whole project).
 * Without type information only the name is matched (case-insensitive); several matches produce a chooser:
 * first the own file, then the directly included files in the order of the #Include lines, then deeper ones.
 * <ul>
 *   <li>{@link Kind#FREE}: free-standing name ({@code f()}, {@code MsgBox "x"}, {@code MyClass.x}) = functions
 *       outside of classes and top-level classes;</li>
 *   <li>{@link Kind#MEMBER}: name after the dot = methods, properties, fields ({@link AhkFieldTarget}) and nested
 *       classes of all classes of the file;</li>
 *   <li>{@link Kind#BASE_CLASS}: first name after {@code extends} = top-level class;</li>
 *   <li>{@link Kind#NESTED_BASE_CLASS}: further names ({@code extends A.B}) = nested class B in A.</li>
 * </ul>
 * In v2.1 files the modules decide ({@link AhkModules}): a free-standing name or base class finds the
 * definitions of its own module, otherwise what the module imports (also global variables of imported modules);
 * a name after a module ({@code X.Name}, {@code extends X.Base}) finds what the module exports. Names after the dot
 * of other objects still find the members of all classes of the file and its includes.
 * If there is no own definition but a help page for the name, the target is the help ({@link AhkHelpTarget}); this
 * includes {@code AHK.Name} after {@code #Import AHK}.
 */
public final class AhkDefinitionReference extends PsiPolyVariantReferenceBase<PsiElement> {

    /** Which definitions a name can refer to (see class comment). */
    public enum Kind {FREE, MEMBER, BASE_CLASS, NESTED_BASE_CLASS}

    private final Kind kind;
    private final String name;
    private final @Nullable String outerClass;

    /** {@code outerClass}: only for {@link Kind#NESTED_BASE_CLASS}, the name left of the dot. */
    public AhkDefinitionReference(@NotNull PsiElement element, @NotNull TextRange range, @NotNull Kind kind,
                                  @Nullable String outerClass) {
        super(element, range, true);
        this.kind = kind;
        this.name = range.substring(element.getText());
        this.outerClass = outerClass;
    }

    /** Which definitions the name can refer to. */
    public @NotNull Kind kind() {
        return kind;
    }

    /** All matching definitions; without any, the help target if the name has help. */
    @Override
    public ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
        PsiFile file = myElement.getContainingFile();
        List<PsiElement> targets = definitions(file);
        if (targets.isEmpty()) {
            int offset = myElement.getTextRange().getStartOffset() + getRangeInElement().getStartOffset();
            AhkLanguageLevel level = AhkLanguageLevel.of(myElement);
            boolean hasHelp;
            if (builtinModuleMember()) {
                hasHelp = AhkHelpRepository.topic(level, name) != null;
            } else {
                // Topics by role as in the quick documentation (Gui.Button instead of Button, variants);
                // with several variants the quick documentation shows the overview page
                PsiElement leaf = file == null ? null : file.findElementAt(offset);
                hasHelp = leaf == null ? AhkHelpRepository.topic(level, name) != null
                        : !AhkDocumentationProvider.topics(leaf, null).isEmpty();
            }
            if (hasHelp) {
                targets = List.of(new AhkHelpTarget(name, myElement, offset));
            }
        }
        return PsiElementResolveResult.createResults(targets);
    }

    /** Rename: replaces the name in the element (only the range of this reference). */
    @Override
    public PsiElement handleElementRename(@NotNull String newElementName) {
        return de.kumpewo.autohotkey.psi.AhkRenames.replaceRange(myElement, getRangeInElement(), newElementName);
    }

    /**
     * Matching definitions in the given files, in this order (rename: the file and all files it includes, also
     * indirectly; see AhkReferencesSearcher).
     */
    public @NotNull List<PsiElement> definitionsIn(@NotNull java.util.Collection<PsiFile> files) {
        List<PsiElement> result = new ArrayList<>();
        for (PsiFile file : files) result.addAll(definitionsIn(file, kind, name, outerClass));
        return result;
    }

    /** No variants: completion is done by AhkCompletionContributor. */
    @Override
    public Object @NotNull [] getVariants() {
        return EMPTY_ARRAY;   // completion is done by AhkCompletionContributor
    }

    /** Definitions in the file itself, then in the files it includes; in v2.1 by module. */
    private List<PsiElement> definitions(@Nullable PsiFile file) {
        List<PsiElement> result = new ArrayList<>();
        if (file == null) return result;
        if (AhkModules.isActive(file)) {
            List<PsiElement> modular = moduleDefinitions();
            if (modular != null) return modular;
        }
        result.addAll(definitionsIn(file, kind, name, outerClass));
        for (PsiFile included : AhkInclude.visibleFiles(file)) result.addAll(definitionsIn(included, kind, name, outerClass));
        return result;
    }

    /**
     * Definitions through the modules (v2.1); {@code null} if the modules do not decide (a name after the dot of
     * an object that is no module, {@code extends A.B} with a class A).
     */
    private @Nullable List<PsiElement> moduleDefinitions() {
        switch (kind) {
            case FREE, BASE_CLASS -> {
                return AhkModules.resolve(myElement, name, finder(kind, name, null), kind == Kind.FREE).targets();
            }
            case MEMBER -> {
                AhkModules.Module module = qualifierModule();
                if (module == null) return null;
                return AhkModules.exports(module, name, finder(Kind.FREE, name, null), true).targets();
            }
            case NESTED_BASE_CLASS -> {
                if (outerClass == null) return null;
                AhkModules.Module module = AhkModules.resolve(myElement, outerClass, finder(Kind.FREE, outerClass, null), false).module();
                if (module == null) return null;
                return AhkModules.exports(module, name, finder(Kind.BASE_CLASS, name, null), false).targets();
            }
        }
        return null;
    }

    /** The module left of the dot of a name after the dot ({@code X.Name}), or {@code null}. */
    private @Nullable AhkModules.Module qualifierModule() {
        if (kind != Kind.MEMBER || !(myElement instanceof AhkMemberExpr)) return null;
        PsiElement qualifier = myElement.getFirstChild();
        return qualifier == null ? null : AhkModules.qualifierModule(qualifier, n -> finder(Kind.FREE, n, null));
    }

    /** {@code AHK.Name} after {@code #Import AHK}: the target is the help of the built-in name. */
    private boolean builtinModuleMember() {
        PsiFile file = myElement.getContainingFile();
        if (!AhkModules.isActive(file)) return false;
        AhkModules.Module module = qualifierModule();
        return module != null && module.isBuiltin();
    }

    /** Finder for the module logic: the definitions of one kind and name in a whole file. */
    public static @NotNull AhkModules.Finder finder(@NotNull Kind kind, @NotNull String name, @Nullable String outerClass) {
        return file -> definitionsIn(file, kind, name, outerClass);
    }

    /** Definitions of one file, sorted by position. */
    private static List<PsiElement> definitionsIn(PsiFile file, Kind kind, String name, @Nullable String outerClass) {
        List<PsiElement> result = new ArrayList<>();
        if (kind == Kind.FREE || kind == Kind.MEMBER) {
            for (AhkFunc func : PsiTreeUtil.findChildrenOfType(file, AhkFunc.class)) {
                if (matches(func, name) && isMember(func) == (kind == Kind.MEMBER)) result.add(func);
            }
        }
        if (kind == Kind.MEMBER) {
            for (AhkClassProperty property : PsiTreeUtil.findChildrenOfType(file, AhkClassProperty.class)) {
                if (matches(property, name) && isMember(property)) result.add(property);
            }
            for (AhkClass cls : PsiTreeUtil.findChildrenOfType(file, AhkClass.class)) {
                for (AhkDefinitions.Field field : AhkDefinitions.fields(cls)) {
                    if (name.equalsIgnoreCase(field.name())) result.add(new AhkFieldTarget(field));
                }
            }
        }
        for (AhkClass cls : PsiTreeUtil.findChildrenOfType(file, AhkClass.class)) {
            if (!matches(cls, name)) continue;
            AhkClass owner = AhkDefinitions.ownerClass(cls);
            boolean ok = switch (kind) {
                case FREE, BASE_CLASS -> owner == null && PsiTreeUtil.getParentOfType(cls, AhkFunc.class) == null;
                case MEMBER -> owner != null;
                case NESTED_BASE_CLASS -> owner != null && outerClass != null
                        && outerClass.equalsIgnoreCase(owner.getName());
            };
            if (ok) result.add(cls);
        }
        result.sort(Comparator.comparingInt(PsiElement::getTextOffset));
        return result;
    }

    /** Same name, case-insensitive. */
    private static boolean matches(PsiNameIdentifierOwner definition, String name) {
        return name.equalsIgnoreCase(definition.getName());
    }

    /** Defined directly in a class. */
    private static boolean isMember(PsiElement definition) {
        return AhkDefinitions.ownerClass(definition) != null;
    }
}
