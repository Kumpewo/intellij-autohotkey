package de.kumpewo.autohotkey.navigation;

import com.intellij.openapi.application.QueryExecutorBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiReference;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.Processor;
import de.kumpewo.autohotkey.AhkFile;
import de.kumpewo.autohotkey.AhkFileType;
import de.kumpewo.autohotkey.include.AhkInclude;
import de.kumpewo.autohotkey.psi.AhkNamedElement;
import de.kumpewo.autohotkey.module.AhkModuleSymbol;
import de.kumpewo.autohotkey.module.AhkModules;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * References to a function, method, class, property, field or label (Find Usages, Alt+F7; Rename, Shift+F6).
 * <ul>
 *   <li>Find Usages ({@code transitive = false}): the file of the definition and every AutoHotkey file in the
 *       search scope that includes it, also indirectly ({@link AhkInclude#visibleFiles}) - exactly the files from which
 *       navigation reaches the definition. A reference counts if one of its targets ({@code multiResolve}) is the
 *       definition.</li>
 *   <li>Rename ({@code transitive = true}, {@link AhkRenameProcessor}): also files of language level v2.1 that
 *       include the file of the definition indirectly. A reference in such a file counts if the definition is among the
 *       matching definitions in that file and all files it includes, directly or indirectly.</li>
 * </ul>
 * <ul>
 *   <li>A local variable or parameter ({@link AhkLocalVariable}): only the function that owns it, including
 *       nested functions that share it; a reference counts if it resolves to the same variable. For a parameter
 *       also its name after {@code @param} in the doc comment of the function ({@link AhkDocParamReference}).</li>
 * </ul>
 * Names are compared ignoring case, as in AutoHotkey, so the case-sensitive word index of the platform is not used.
 */
public class AhkReferencesSearcher extends QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters> {

    /** Runs in a read action. */
    public AhkReferencesSearcher() {
        super(true);
    }

    /** Reports the references to the element if it is an AutoHotkey definition (Find Usages). */
    @Override
    public void processQuery(@NotNull ReferencesSearch.SearchParameters parameters,
                             @NotNull Processor<? super PsiReference> consumer) {
        search(parameters.getElementToSearch(), parameters.getEffectiveSearchScope(), false, consumer);
    }

    /** Is the element a definition this searcher handles? */
    public static boolean isDefinition(@Nullable PsiElement element) {
        return element instanceof AhkNamedElement || element instanceof AhkFieldTarget
                || element instanceof AhkLocalVariable || element instanceof AhkModuleSymbol symbol
                && symbol.kind() != AhkModuleSymbol.Kind.VARIABLE;
    }

    /**
     * Reports the references to {@code target} in the scope; with {@code transitive} also in v2.1 files
     * that include the definition's file indirectly. Returns false if the consumer stopped.
     */
    public static boolean search(@NotNull PsiElement target, @NotNull SearchScope scope, boolean transitive,
                                 @NotNull Processor<? super PsiReference> consumer) {
        if (!isDefinition(target)) return true;
        if (target instanceof AhkLocalVariable local) return searchLocal(local, consumer);
        String name = ((PsiNamedElement) target).getName();
        PsiFile definitionFile = target.getContainingFile();
        if (name == null || !(definitionFile instanceof AhkFile)) return true;
        for (PsiFile file : candidateFiles(definitionFile, scope, transitive)) {
            List<PsiFile> visible = transitive ? includeClosure(file) : null;
            for (PsiElement element : PsiTreeUtil.collectElements(file, e -> e.getReferences().length > 0)) {
                for (PsiReference reference : element.getReferences()) {
                    if (!reference.getRangeInElement().substring(element.getText()).equalsIgnoreCase(name)) continue;
                    if (pointsTo(reference, target, visible) && !consumer.process(reference)) return false;
                }
            }
        }
        return true;
    }

    /** Reports the references to the local variable inside the function that owns it (and in its doc comment). */
    private static boolean searchLocal(@NotNull AhkLocalVariable target,
                                       @NotNull Processor<? super PsiReference> consumer) {
        if (!target.isValid()) return true;
        PsiElement scope = target.local().scope();
        for (PsiElement element : PsiTreeUtil.collectElements(scope, e -> e.getReferences().length > 0)) {
            for (PsiReference reference : element.getReferences()) {
                if (reference instanceof AhkLocalReference && reference.isReferenceTo(target)
                        && !consumer.process(reference)) {
                    return false;
                }
            }
        }
        for (AhkDocParamReference reference : AhkDocParamReference.of(target.local())) {
            if (reference.isReferenceTo(target) && !consumer.process(reference)) return false;
        }
        return true;
    }

    /**
     * The file of the definition and the AutoHotkey files in the scope that include it, also indirectly
     * (v2.1 files only directly, unless {@code transitive}), and (v2.1) those that import it directly with
     * {@code #Import} (a module file, or a module file that includes it).
     */
    public static @NotNull List<PsiFile> candidateFiles(@NotNull PsiFile definitionFile, @NotNull SearchScope scope,
                                                        boolean transitive) {
        List<PsiFile> result = new ArrayList<>();
        PsiFile original = definitionFile.getOriginalFile();
        if (inScope(original, scope)) result.add(original);
        if (scope instanceof GlobalSearchScope global) {
            PsiManager manager = original.getManager();
            for (VirtualFile virtualFile : ahkFiles(original.getProject(), global)) {
                PsiFile file = manager.findFile(virtualFile);
                if (file == null || file.equals(original)) continue;
                List<PsiFile> included = transitive ? includeClosure(file) : AhkInclude.visibleFiles(file);
                if (included.contains(original) || imports(file, original)) result.add(file);
            }
        }
        return result;
    }

    /**
     * AutoHotkey files of the project content and of the additional libraries (AutoHotkey Lib) in the scope. Walks the
     * file system tree instead of the file type index: in tests the index queried from the background thread of
     * Find Usages/Rename did not show files of a content root added in the same test run.
     */
    private static List<VirtualFile> ahkFiles(com.intellij.openapi.project.Project project, GlobalSearchScope scope) {
        List<VirtualFile> result = new ArrayList<>();
        com.intellij.openapi.roots.ProjectFileIndex.getInstance(project).iterateContent(file -> {
            if (isAhkFile(file) && scope.contains(file)) result.add(file);
            return true;
        });
        for (com.intellij.openapi.roots.AdditionalLibraryRootsProvider provider
                : com.intellij.openapi.roots.AdditionalLibraryRootsProvider.EP_NAME.getExtensionList()) {
            for (com.intellij.openapi.roots.SyntheticLibrary library : provider.getAdditionalProjectLibraries(project)) {
                for (VirtualFile root : library.getAllRoots()) {
                    com.intellij.openapi.vfs.VfsUtilCore.iterateChildrenRecursively(root, null, file -> {
                        if (isAhkFile(file) && scope.contains(file) && !result.contains(file)) result.add(file);
                        return true;
                    });
                }
            }
        }
        return result;
    }

    /** A file (not a directory) of the AutoHotkey file type. */
    private static boolean isAhkFile(VirtualFile file) {
        return !file.isDirectory() && file.getFileType() == AhkFileType.INSTANCE;
    }

    /** Does an {@code #Import} of the file load a module whose parts lie in {@code target}? */
    private static boolean imports(@NotNull PsiFile file, @NotNull PsiFile target) {
        for (AhkModules.Import imp : AhkModules.imports(file)) {
            AhkModules.Module module = AhkModules.resolveModule(imp, AhkModules.moduleOf(imp.directive()));
            if (module == null || module.home() == null) continue;
            for (AhkModules.Part part : AhkModules.parts(module)) {
                if (part.file().equals(target)) return true;
            }
        }
        return false;
    }

    /** The file and all files it includes, directly or indirectly (each once). */
    public static @NotNull List<PsiFile> includeClosure(@NotNull PsiFile file) {
        List<PsiFile> result = new ArrayList<>();
        result.add(file.getOriginalFile());
        result.addAll(AhkInclude.allIncludedFiles(file));
        return result;
    }

    /** Is the file in the scope? Files without a virtual file (in memory) always are. */
    private static boolean inScope(PsiFile file, SearchScope scope) {
        VirtualFile virtualFile = file.getVirtualFile();
        return virtualFile == null || scope.contains(virtualFile);
    }

    /**
     * Is one of the targets of the reference the definition? With {@code visible} (rename), definition references
     * are resolved over these files instead of the visible ones (not in files with modules: their
     * references are resolved by module). Fields are compared with equals
     * (new objects).
     */
    private static boolean pointsTo(PsiReference reference, PsiElement target, @Nullable List<PsiFile> visible) {
        if (visible != null && reference instanceof AhkDefinitionReference definitionReference
                && !AhkModules.usesModules(reference.getElement().getContainingFile())) {
            for (PsiElement definition : definitionReference.definitionsIn(visible)) {
                if (same(definition, target)) return true;
            }
            return false;
        }
        if (reference instanceof PsiPolyVariantReference poly) {
            for (ResolveResult result : poly.multiResolve(false)) {
                if (same(result.getElement(), target)) return true;
            }
            return false;
        }
        return reference.isReferenceTo(target);
    }

    /** Same definition (fields are new objects each time, compared with equals). */
    private static boolean same(@Nullable PsiElement element, @NotNull PsiElement target) {
        return element != null && (element.equals(target) || target.getManager().areElementsEquivalent(element, target));
    }
}
