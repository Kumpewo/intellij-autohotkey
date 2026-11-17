package de.kumpewo.autohotkey.navigation;

import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.refactoring.rename.RenamePsiElementProcessor;
import com.intellij.refactoring.util.NonCodeUsageInfo;
import com.intellij.usageView.UsageInfo;
import com.intellij.util.containers.MultiMap;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.psi.AhkClass;
import de.kumpewo.autohotkey.psi.AhkClassProperty;
import de.kumpewo.autohotkey.psi.AhkDefinitions;
import de.kumpewo.autohotkey.psi.AhkFunc;
import de.kumpewo.autohotkey.psi.AhkLabel;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rename (Shift+F6) of functions, methods, classes, properties, fields and labels (local variables and
 * parameters are renamed in place, see AhkRefactoringSupportProvider):
 * <ul>
 *   <li>always with preview: all occurrences appear in the Find tool window, single occurrences or files can be
 *       excluded, only "Do Refactor" changes the code ({@link #forcesShowPreview});</li>
 *   <li>code occurrences: {@link AhkReferencesSearcher} with {@code transitive = true}, i.e. also files that include
 *       the file of the definition indirectly;</li>
 *   <li>"Search in comments and strings": whole-word occurrences, ignoring case, in comments and strings of the same
 *       files, as non-code usages (the platform's own search needs the word index, which AutoHotkey files don't
 *       have, see AhkFindUsagesProvider);</li>
 *   <li>conflicts: a definition of the same kind with the new name (function/class at the top level, member of the
 *       same class, label of the same file).</li>
 * </ul>
 * For these definitions inline rename in the editor is switched off (AhkRefactoringSupportProvider), so the dialog
 * always opens.
 */
public class AhkRenameProcessor extends RenamePsiElementProcessor {

    /** State of the dialog option and the flag of the running search. */
    private static final class State {
        /** "Search in comments and strings" (for the session; default on, the preview is shown anyway). */
        static volatile boolean searchInComments = true;
        /** The flag of the current search, passed from findReferences to findCollisions (same thread). */
        static final ThreadLocal<Boolean> CURRENT = new ThreadLocal<>();
    }

    /**
     * Definitions of AutoHotkey; not local variables (renamed in place by the platform's default processor, which
     * finds their references through AhkReferencesSearcher).
     */
    @Override
    public boolean canProcessElement(@NotNull PsiElement element) {
        return AhkReferencesSearcher.isDefinition(element) && !(element instanceof AhkLocalVariable);
    }

    /** The preview always opens. */
    @Override
    public boolean forcesShowPreview() {
        return true;
    }

    /** Code occurrences, also in files that include the definition's file indirectly. */
    @Override
    public @NotNull Collection<PsiReference> findReferences(@NotNull PsiElement element, @NotNull SearchScope searchScope,
                                                            boolean searchInCommentsAndStrings) {
        State.CURRENT.set(searchInCommentsAndStrings);
        List<PsiReference> result = new ArrayList<>();
        AhkReferencesSearcher.search(element, searchScope, true, result::add);
        return result;
    }

    /** No search of the platform in comments and strings (it needs the word index); done in findCollisions. */
    @Override
    public @Nullable PsiElement getElementToSearchInStringsAndComments(@NotNull PsiElement element) {
        return null;
    }

    /** Adds the occurrences in comments and strings as non-code usages, if the option is on. */
    @Override
    public void findCollisions(@NotNull PsiElement element, @NotNull String newName,
                               @NotNull Map<? extends PsiElement, String> allRenames, @NotNull List<UsageInfo> result) {
        Boolean flag = State.CURRENT.get();
        State.CURRENT.remove();
        if (flag == null || !flag) return;
        String name = ((PsiNamedElement) element).getName();
        PsiFile definitionFile = element.getContainingFile();
        if (name == null || definitionFile == null) return;
        Pattern word = Pattern.compile("(?<![\\w\\u0080-\\uFFFF])" + Pattern.quote(name) + "(?![\\w\\u0080-\\uFFFF])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        SearchScope scope = GlobalSearchScope.projectScope(element.getProject());
        for (PsiFile file : AhkReferencesSearcher.candidateFiles(definitionFile, scope, true)) {
            for (PsiElement leaf = PsiTreeUtil.firstChild(file); leaf != null; leaf = PsiTreeUtil.nextLeaf(leaf)) {
                if (!isCommentOrString(leaf)) continue;
                Matcher matcher = word.matcher(leaf.getText());
                int start = leaf.getTextRange().getStartOffset();
                while (matcher.find()) {
                    NonCodeUsageInfo usage = NonCodeUsageInfo.create(file, start + matcher.start(), start + matcher.end(),
                            element, newName);
                    if (usage != null) result.add(usage);
                }
            }
        }
    }

    /** Comment, string, continuation section or hotstring text. */
    private static boolean isCommentOrString(PsiElement leaf) {
        var type = leaf.getNode().getElementType();
        return leaf instanceof PsiComment || type == AhkTypes.STRING || type == AhkTypes.UNCLOSED_STRING
                || type == AhkTypes.HOTSTRING_TEXT;
    }

    /** Option "Search in comments and strings" of the dialog. */
    @Override
    public boolean isToSearchInComments(@NotNull PsiElement element) {
        return State.searchInComments;
    }

    /** Remembers the option of the dialog for the session. */
    @Override
    public void setToSearchInComments(@NotNull PsiElement element, boolean enabled) {
        State.searchInComments = enabled;
    }

    /** A definition of the same kind that already has the new name. */
    @Override
    public void findExistingNameConflicts(@NotNull PsiElement element, @NotNull String newName,
                                          @NotNull MultiMap<PsiElement, String> conflicts) {
        for (PsiElement other : sameKind(element)) {
            String otherName = other instanceof PsiNamedElement named ? named.getName() : null;
            if (other.equals(element) || otherName == null || !otherName.equalsIgnoreCase(newName)) continue;
            PsiFile file = other.getContainingFile();
            conflicts.putValue(other, AhkBundle.message("rename.conflict.exists", newName,
                    file == null ? "" : file.getName()));
        }
    }

    /**
     * Definitions that would clash with the element: top-level functions and classes in the file and all files it
     * includes or that include it; members of the same class; labels of the same file.
     */
    private static List<PsiElement> sameKind(PsiElement element) {
        List<PsiElement> result = new ArrayList<>();
        PsiFile file = element.getContainingFile();
        if (file == null) return result;
        AhkClass owner = element instanceof AhkFieldTarget field ? PsiTreeUtil.getParentOfType(field.getParent(), AhkClass.class)
                : AhkDefinitions.ownerClass(element);
        if (element instanceof AhkLabel) {
            result.addAll(PsiTreeUtil.findChildrenOfType(file, AhkLabel.class));
        } else if (owner != null) {
            for (AhkFunc func : PsiTreeUtil.findChildrenOfType(owner, AhkFunc.class)) {
                if (AhkDefinitions.ownerClass(func) == owner) result.add(func);
            }
            for (AhkClassProperty property : PsiTreeUtil.findChildrenOfType(owner, AhkClassProperty.class)) {
                if (AhkDefinitions.ownerClass(property) == owner) result.add(property);
            }
            for (AhkDefinitions.Field field : AhkDefinitions.fields(owner)) result.add(new AhkFieldTarget(field));
        } else {
            List<PsiFile> files = new ArrayList<>(AhkReferencesSearcher.includeClosure(file));
            for (PsiFile including : AhkReferencesSearcher.candidateFiles(file,
                    GlobalSearchScope.projectScope(element.getProject()), true)) {
                if (!files.contains(including)) files.add(including);
            }
            for (PsiFile f : files) {
                for (AhkFunc func : PsiTreeUtil.findChildrenOfType(f, AhkFunc.class)) {
                    if (AhkDefinitions.ownerClass(func) == null) result.add(func);
                }
                for (AhkClass cls : PsiTreeUtil.findChildrenOfType(f, AhkClass.class)) {
                    if (AhkDefinitions.ownerClass(cls) == null) result.add(cls);
                }
            }
        }
        return result;
    }
}
