package de.kumpewo.autohotkey.navigation;

import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.PsiTreeUtil;
import de.kumpewo.autohotkey.AhkFileType;
import de.kumpewo.autohotkey.documentation.AhkDocComment;
import de.kumpewo.autohotkey.documentation.AhkHelpRepository;
import de.kumpewo.autohotkey.highlighting.AhkNames;
import de.kumpewo.autohotkey.include.AhkInclude;
import de.kumpewo.autohotkey.parser.AhkLanguageLevel;
import de.kumpewo.autohotkey.psi.AhkClass;
import de.kumpewo.autohotkey.psi.AhkDefinitions;
import de.kumpewo.autohotkey.psi.AhkRenames;
import de.kumpewo.autohotkey.psi.AhkTypes;
import de.kumpewo.autohotkey.types.AhkTypeDeclarations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reference from a class name in a comment to the class, so that Ctrl+B, Find Usages and Rename (without the text
 * search in comments) reach it:
 * <ul>
 *   <li>the types in {@code {...}} after {@code @param} and {@code @returns} of a doc comment;</li>
 *   <li>the class after {@code as} and the class of {@code Class.member} in {@code ;@declare-type}, the class of
 *       {@code ;@declare-member-type} ({@link AhkTypeDeclarations#nameOccurrences}).</li>
 * </ul>
 * A nested name ({@code Outer.Inner}, {@code Gui.Edit}) gives one reference per part, as after {@code extends}. Class
 * names are resolved as in the type pragmas: a built-in class first (target: its help, {@link AhkHelpTarget}), then an
 * own class of the file and the files it includes; in a {@code pragmas.ahk} also through the files of the project
 * that include it directly ({@link #pragmaIncluders}). The member of {@code Class.member} refers to the instance field
 * or property of that class or its own base classes ({@link Kind#MEMBER}). Unknown names refer to nothing (the
 * warning "Unknown class." comes from the annotators).
 */
public final class AhkTypeNameReference extends PsiReferenceBase<PsiElement> {

    /** A class name (or one part of a nested name), or the member of {@code Class.member}. */
    public enum Kind {CLASS, MEMBER}

    /** A class name in the form the pragmas accept: names separated by dots. */
    private static final Pattern CLASS_NAME = Pattern.compile("[\\p{L}\\p{N}_]+(\\.[\\p{L}\\p{N}_]+)*");

    private final Kind kind;
    /** {@link Kind#CLASS}: the name up to and including this part ({@code Outer.Inner}); MEMBER: the class. */
    private final String path;

    /** Reference in the comment leaf for the range of the name inside it. */
    private AhkTypeNameReference(@NotNull PsiElement comment, @NotNull TextRange range, @NotNull Kind kind,
                                 @NotNull String path) {
        super(comment, range, true);
        this.kind = kind;
        this.path = path;
    }

    /** Class name or member. */
    public @NotNull Kind kind() {
        return kind;
    }

    /** The references of one comment leaf (pragma line comment or a leaf of a doc comment). */
    public static @NotNull List<AhkTypeNameReference> in(@NotNull PsiElement comment) {
        List<AhkTypeNameReference> result = new ArrayList<>();
        if (!(comment instanceof PsiComment psiComment)) return result;
        TextRange leafRange = comment.getTextRange();
        if (comment.getNode().getElementType() == AhkTypes.LINE_COMMENT) {
            for (AhkTypeDeclarations.NameOccurrence name : AhkTypeDeclarations.nameOccurrences(psiComment)) {
                if (name.owner() == null) {
                    addClassName(comment, name.range(), result);
                } else {
                    result.add(new AhkTypeNameReference(comment, name.range().shiftLeft(leafRange.getStartOffset()),
                            Kind.MEMBER, name.owner()));
                }
            }
        } else if (comment.getNode().getElementType() == AhkTypes.BLOCK_COMMENT) {
            for (AhkDocComment.Type type : docTypes(comment)) {
                if (leafRange.contains(type.range()) && CLASS_NAME.matcher(type.name()).matches()) {
                    addClassName(comment, type.range(), result);
                }
            }
        }
        return result;
    }

    /** One reference per part of the (absolute) range of a class name. */
    private static void addClassName(PsiElement comment, TextRange range, List<AhkTypeNameReference> result) {
        String text = range.substring(comment.getContainingFile().getText());
        int leafStart = comment.getTextRange().getStartOffset();
        int start = 0;
        while (start <= text.length()) {
            int dot = text.indexOf('.', start);
            int end = dot < 0 ? text.length() : dot;
            TextRange part = new TextRange(range.getStartOffset() + start, range.getStartOffset() + end);
            result.add(new AhkTypeNameReference(comment, part.shiftLeft(leafStart), Kind.CLASS,
                    text.substring(0, end)));
            if (dot < 0) break;
            start = dot + 1;
        }
    }

    /**
     * The types of {@code @param} and {@code @returns} of the doc comment the leaf belongs to (empty if the comment
     * documents nothing).
     */
    private static List<AhkDocComment.Type> docTypes(PsiElement comment) {
        List<AhkDocComment.Type> result = new ArrayList<>();
        PsiElement end = comment;
        while (!end.getText().endsWith("*/")) {
            PsiElement next = PsiTreeUtil.nextLeaf(end);
            if (next == null || next.getNode().getElementType() != AhkTypes.BLOCK_COMMENT) return result;
            end = next;
        }
        AhkDocComment doc = AhkDocComment.of(AhkDocComment.documentedDefinition(end));
        if (doc == null) return result;
        for (AhkDocComment.Param param : doc.params()) result.addAll(param.types());
        if (doc.returns() != null) result.addAll(doc.returns().types());
        return result;
    }

    /**
     * The class (an {@link AhkClass} or the help of a built-in class), or for a member the instance field
     * ({@link AhkFieldTarget}) or property; {@code null} if unknown.
     */
    @Override
    public @Nullable PsiElement resolve() {
        PsiFile file = myElement.getContainingFile();
        if (file == null) return null;
        if (kind == Kind.CLASS) {
            for (PsiFile context : contextFiles(file)) {
                PsiElement target = resolveClass(context);
                if (target != null) return target;
            }
            return null;
        }
        for (PsiFile context : contextFiles(file)) {
            if (AhkNames.of(context).builtinClass(path) != null) return null;   // member of a built-in class
            if (AhkTypeDeclarations.ownClass(context, path) == null) continue;
            String member = getValue();
            for (AhkTypeDeclarations.Level level : AhkTypeDeclarations.hierarchy(context, path)) {
                if (level.own() == null) continue;
                Object found = AhkTypeDeclarations.instanceMember(level.own(), member);
                if (found instanceof AhkDefinitions.Field field) return new AhkFieldTarget(field);
                if (found instanceof PsiElement property) return property;
            }
            return null;
        }
        return null;
    }

    /** The class for {@link #path} seen from the file: built-in first (its help, if there is any), then own. */
    private @Nullable PsiElement resolveClass(PsiFile context) {
        AhkNames.BuiltinClass builtin = AhkNames.of(context).builtinClass(path);
        if (builtin != null) {
            if (AhkHelpRepository.topic(AhkLanguageLevel.of(myElement), builtin.name()) == null) return null;
            return new AhkHelpTarget(builtin.name(), myElement, getAbsoluteRange().getStartOffset());
        }
        return AhkTypeDeclarations.ownClass(context, path);
    }

    /** The file itself; for a {@code pragmas.ahk} then the files of the project that include it directly. */
    private static List<PsiFile> contextFiles(PsiFile file) {
        List<PsiFile> result = new ArrayList<>();
        result.add(file);
        if (AhkTypeDeclarations.isPragmaFile(file)) result.addAll(pragmaIncluders(file));
        return result;
    }

    /**
     * The AutoHotkey files in the project content that include the {@code pragmas.ahk} directly (cached until the
     * next PSI change). Files in the AutoHotkey Lib folders do not count: their pragmas belong to the library.
     */
    public static @NotNull List<PsiFile> pragmaIncluders(@NotNull PsiFile pragmaFile) {
        PsiFile original = pragmaFile.getOriginalFile();
        return CachedValuesManager.getCachedValue(original, () -> {
            List<PsiFile> result = new ArrayList<>();
            PsiManager manager = original.getManager();
            ProjectFileIndex.getInstance(original.getProject()).iterateContent(virtualFile -> {
                if (virtualFile.isDirectory() || virtualFile.getFileType() != AhkFileType.INSTANCE) return true;
                PsiFile file = manager.findFile(virtualFile);
                if (file != null && !file.equals(original) && AhkInclude.includedFiles(file).contains(original)) {
                    result.add(file);
                }
                return true;
            });
            return CachedValueProvider.Result.create(List.copyOf(result),
                    PsiModificationTracker.getInstance(original.getProject()));
        });
    }

    /**
     * Is the file part of the project content (not a file of an AutoHotkey Lib folder outside of it)? Files without
     * a virtual file (in memory) are.
     */
    public static boolean inProject(@NotNull PsiFile file) {
        VirtualFile virtualFile = file.getOriginalFile().getVirtualFile();
        return virtualFile == null || ProjectFileIndex.getInstance(file.getProject()).isInContent(virtualFile);
    }

    /** Same class, field or property (field targets are new objects, compared with equals). */
    @Override
    public boolean isReferenceTo(@NotNull PsiElement element) {
        PsiElement target = resolve();
        return target != null
                && (target.equals(element) || myElement.getManager().areElementsEquivalent(target, element));
    }

    /** Rename: replaces the name in the comment leaf. */
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
