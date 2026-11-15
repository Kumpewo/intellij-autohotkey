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
import de.kumpewo.autohotkey.psi.AhkClass;
import de.kumpewo.autohotkey.psi.AhkClassProperty;
import de.kumpewo.autohotkey.psi.AhkDefinitions;
import de.kumpewo.autohotkey.psi.AhkFunc;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reference from a name to definitions in the same file and in the files it includes with a direct #Include
 * (not recursive, no search in the whole project).
 * Without type information only the name is matched (case-insensitive); several matches produce a chooser:
 * first the own file, then the included files in the order of the #Include lines.
 * <ul>
 *   <li>{@link Kind#FREE}: free-standing name ({@code f()}, {@code MsgBox "x"}, {@code MyClass.x}) = functions
 *       outside of classes and top-level classes;</li>
 *   <li>{@link Kind#MEMBER}: name after the dot = methods, properties, fields ({@link AhkFieldTarget}) and nested
 *       classes of all classes of the file;</li>
 *   <li>{@link Kind#BASE_CLASS}: first name after {@code extends} = top-level class;</li>
 *   <li>{@link Kind#NESTED_BASE_CLASS}: further names ({@code extends A.B}) = nested class B in A.</li>
 * </ul>
 * If there is no own definition but a help page for the name, the target is the help ({@link AhkHelpTarget}).
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

    /** All matching definitions; without any, the help target if the name has help. */
    @Override
    public ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
        List<PsiElement> targets = definitions(myElement.getContainingFile());
        if (targets.isEmpty()) {
            int offset = myElement.getTextRange().getStartOffset() + getRangeInElement().getStartOffset();
            PsiFile file = myElement.getContainingFile();
            // Topics by role as in the quick documentation (Gui.Button instead of Button, variants);
            // with several variants the quick documentation shows the overview page
            PsiElement leaf = file == null ? null : file.findElementAt(offset);
            boolean hasHelp = leaf == null ? AhkHelpRepository.topic(name) != null
                    : !AhkDocumentationProvider.topics(leaf, null).isEmpty();
            if (hasHelp) {
                targets = List.of(new AhkHelpTarget(name, myElement, offset));
            }
        }
        return PsiElementResolveResult.createResults(targets);
    }

    /** Rename (Z.20): replaces the name in the element (only the range of this reference). */
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
        for (PsiFile file : files) result.addAll(definitionsIn(file));
        return result;
    }

    /** No variants: completion is done by AhkCompletionContributor. */
    @Override
    public Object @NotNull [] getVariants() {
        return EMPTY_ARRAY;   // completion is done by AhkCompletionContributor
    }

    /** Definitions in the file itself, then in the files included directly with #Include. */
    private List<PsiElement> definitions(@Nullable PsiFile file) {
        List<PsiElement> result = new ArrayList<>();
        if (file == null) return result;
        result.addAll(definitionsIn(file));
        for (PsiFile included : AhkInclude.includedFiles(file)) result.addAll(definitionsIn(included));
        return result;
    }

    /** Definitions of one file, sorted by position. */
    private List<PsiElement> definitionsIn(PsiFile file) {
        List<PsiElement> result = new ArrayList<>();
        if (kind == Kind.FREE || kind == Kind.MEMBER) {
            for (AhkFunc func : PsiTreeUtil.findChildrenOfType(file, AhkFunc.class)) {
                if (matches(func) && isMember(func) == (kind == Kind.MEMBER)) result.add(func);
            }
        }
        if (kind == Kind.MEMBER) {
            for (AhkClassProperty property : PsiTreeUtil.findChildrenOfType(file, AhkClassProperty.class)) {
                if (matches(property) && isMember(property)) result.add(property);
            }
            for (AhkClass cls : PsiTreeUtil.findChildrenOfType(file, AhkClass.class)) {
                for (AhkDefinitions.Field field : AhkDefinitions.fields(cls)) {
                    if (name.equalsIgnoreCase(field.name())) result.add(new AhkFieldTarget(field));
                }
            }
        }
        for (AhkClass cls : PsiTreeUtil.findChildrenOfType(file, AhkClass.class)) {
            if (!matches(cls)) continue;
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
    private boolean matches(PsiNameIdentifierOwner definition) {
        return name.equalsIgnoreCase(definition.getName());
    }

    /** Defined directly in a class. */
    private static boolean isMember(PsiElement definition) {
        return AhkDefinitions.ownerClass(definition) != null;
    }
}
