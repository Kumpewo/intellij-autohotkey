package de.kumpewo.autohotkey.completion;

import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.documentation.AhkDocComment;
import de.kumpewo.autohotkey.psi.AhkFunc;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Completion in doc comments ({@link AhkDocComment}): after {@code @} at the start of a line the tags with a meaning
 * ({@link AhkDocComment#TAGS}; the tolerated JSDoc tags are not offered), after {@code @param } (also with a type in
 * {@code {}}) the parameters of the documented function that have no {@code @param} yet. "Start of a line" as for the
 * parser of the comment: after the leading {@code *} and at most one space, or right after {@code /**}.
 */
final class AhkDocTagCompletion {

    /** Start of a comment line up to the tag: indentation, then {@code /**} or {@code *} with one space, or nothing. */
    private static final String LINE_START = "^[ \\t]*(?:/\\*\\*[ \\t]*|\\*(?!/) ?)?";
    private static final Pattern TAG_PREFIX = Pattern.compile(LINE_START + "@([A-Za-z]*)$");
    private static final Pattern PARAM_PREFIX = Pattern.compile(
            LINE_START + "@param[ \\t]+(?:\\{[^}]*}[ \\t]*)?&?([A-Za-z0-9_\\x{80}-\\x{FFFF}]*)$",
            Pattern.CASE_INSENSITIVE);
    /** Text before the caret that may be followed by a tag: see {@link #isTagStart}. */
    private static final Pattern BEFORE_TAG = Pattern.compile(LINE_START + "$");

    private AhkDocTagCompletion() {
    }

    /** Adds tags or parameter names if the caret is in a doc comment at one of the positions of the class comment. */
    static void add(@NotNull CompletionParameters parameters, @NotNull CompletionResultSet result) {
        PsiElement leaf = parameters.getPosition();
        if (!isInDocComment(leaf)) return;
        CharSequence text = leaf.getContainingFile().getViewProvider().getContents();
        int offset = parameters.getOffset();
        int lineStart = offset;
        while (lineStart > 0 && text.charAt(lineStart - 1) != '\n') lineStart--;
        String line = text.subSequence(lineStart, offset).toString();
        Matcher tag = TAG_PREFIX.matcher(line);
        if (tag.find()) {
            CompletionResultSet tags = result.withPrefixMatcher(tag.group(1)).caseInsensitive();
            for (String name : AhkDocComment.TAGS) {
                tags.addElement(LookupElementBuilder.create(name).withPresentableText("@" + name)
                        .withTypeText(AhkBundle.message("completion.doc.tag")).withIcon(AllIcons.Nodes.Tag));
            }
            return;
        }
        Matcher param = PARAM_PREFIX.matcher(line);
        if (!param.find()) return;
        AhkFunc func = documentedFunction(leaf);
        if (func == null) return;
        AhkDocComment doc = AhkDocComment.of(func);
        List<AhkDocComment.Param> documented = doc == null ? List.of() : doc.params();
        CompletionResultSet names = result.withPrefixMatcher(param.group(1)).caseInsensitive();
        for (String name : AhkDocComment.parameterNames(func)) {
            if (documented.stream().anyMatch(p -> p.name().equalsIgnoreCase(name))) continue;
            names.addElement(LookupElementBuilder.create(name)
                    .withTypeText(AhkBundle.message("completion.doc.param")).withIcon(AllIcons.Nodes.Parameter));
        }
    }

    /**
     * Is the text of the line before the caret ({@code lineBeforeCaret}) a place where a typed {@code @} starts a tag
     * (for the automatic popup)?
     */
    static boolean isTagStart(@NotNull CharSequence lineBeforeCaret) {
        return BEFORE_TAG.matcher(lineBeforeCaret).find();
    }

    /** Is the leaf part of a block comment that starts with {@code /**}? (The lexer splits a comment at each "*".) */
    private static boolean isInDocComment(PsiElement leaf) {
        PsiElement first = leaf;
        while (isComment(first) && !first.getText().stripLeading().startsWith("/*")) {
            PsiElement prev = PsiTreeUtil.prevLeaf(first);
            if (!isComment(prev)) return false;
            first = prev;
        }
        if (!isComment(first)) return false;
        String start = first.getText().stripLeading();
        if (start.startsWith("/**")) return true;
        // "/*" and "*" may be separate tokens
        PsiElement next = PsiTreeUtil.nextLeaf(first);
        return start.equals("/*") && isComment(next) && next.getText().startsWith("*") && !next.getText().startsWith("*/");
    }

    /** The function documented by the comment that contains the leaf, or {@code null}. */
    private static @Nullable AhkFunc documentedFunction(PsiElement leaf) {
        PsiElement end = leaf;
        while (isComment(end) && !end.getText().endsWith("*/")) {
            PsiElement next = PsiTreeUtil.nextLeaf(end);
            if (!isComment(next)) return null;
            end = next;
        }
        if (!isComment(end)) return null;
        return AhkDocComment.documentedDefinition(end) instanceof AhkFunc func ? func : null;
    }

    /** Is the element a token of a block comment? */
    private static boolean isComment(@Nullable PsiElement element) {
        return element != null && element.getNode() != null
                && element.getNode().getElementType() == AhkTypes.BLOCK_COMMENT;
    }
}
