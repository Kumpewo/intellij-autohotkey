package de.kumpewo.autohotkey.debug;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.highlighting.AhkColors;
import de.kumpewo.autohotkey.highlighting.AhkNames;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Evaluation in a frame (watches, Evaluate, hover): only name paths ({@link AhkNamePath}), because the engine of
 * AutoHotkey evaluates no expressions. A path is looked up locally, then globally
 * ({@link AhkDebugController#resolve}). {@link #getExpressionRangeAtOffset} gives the path under the mouse.
 */
final class AhkDebuggerEvaluator extends XDebuggerEvaluator {

    private final AhkDebugController controller;
    private final int epoch;
    private final int level;

    AhkDebuggerEvaluator(@NotNull AhkDebugController controller, int epoch, int level) {
        this.controller = controller;
        this.epoch = epoch;
        this.level = level;
    }

    /** Evaluates a name path; other expressions give an error message. */
    @Override
    public void evaluate(@NotNull String expression, @NotNull XEvaluationCallback callback,
                         @Nullable XSourcePosition expressionPosition) {
        String path = AhkNamePath.normalize(expression);
        if (path == null) {
            callback.errorOccurred(AhkBundle.message("debug.evaluate.not.path"));
            return;
        }
        controller.resolve(epoch, path, level)
                .thenAccept(resolved -> callback.evaluated(new AhkValue(expression.trim(), controller, epoch,
                        resolved.context(), level, resolved.property())))
                .exceptionally(error -> {
                    callback.errorOccurred(AhkStackFrame.message(error));
                    return null;
                });
    }

    /** Only one-line paths, no code fragments. */
    @Override
    public boolean isCodeFragmentEvaluationSupported() {
        return false;
    }

    /** The name path under the mouse (see {@link #pathRange}). */
    @Override
    public @Nullable TextRange getExpressionRangeAtOffset(@NotNull Project project, @NotNull Document document,
                                                          int offset, boolean sideEffectsAllowed) {
        return ReadAction.compute(() -> {
            PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(document);
            return file == null ? null : pathRange(file, offset);
        });
    }

    /**
     * The name path up to the name at the offset: {@code obj} on {@code obj}, {@code obj.nested} on {@code nested}
     * in {@code obj.nested.deep}. None for calls ({@code f(}, {@code obj.M(}, command names), built-in functions
     * and classes, keywords (except {@code this}), dynamic names, strings, comments, definitions and code of another
     * AutoHotkey version. Parameters count as variables.
     */
    static @Nullable TextRange pathRange(@NotNull PsiFile file, int offset) {
        PsiElement leaf = file.findElementAt(offset);
        if (leaf == null) return null;
        IElementType type = leaf.getNode().getElementType();
        boolean isThis = type == AhkTypes.KEYWORD_LITERAL && leaf.getText().equalsIgnoreCase("this");
        if (type != AhkTypes.IDENTIFIER && !isThis) return null;
        if (PsiTreeUtil.findFirstParent(leaf, e -> e.getNode() != null
                && e.getNode().getElementType() == AhkTypes.OTHER_VERSION_CODE) != null) {
            return null;
        }
        PsiElement parent = leaf.getParent();
        if (parent == null) return null;
        IElementType parentType = parent.getNode().getElementType();
        if (parentType == AhkTypes.PARAMETER) return leaf.getTextRange();
        PsiElement path;
        if (isThis && parentType == AhkTypes.LITERAL_EXPR) {
            path = parent;
        } else if (parentType == AhkTypes.REF_EXPR) {
            if (!isThis) {
                var color = AhkNames.nonMember(leaf.getText());
                if (color == AhkColors.AHK_BUILTIN_FUNCTION || color == AhkColors.AHK_BUILTIN_CLASS) return null;
            }
            path = parent;
        } else if (parentType == AhkTypes.MEMBER_EXPR && parent.getLastChild() == leaf) {
            path = parent;
        } else {
            return null;
        }
        PsiElement outer = path.getParent();
        if (outer != null && outer.getNode().getElementType() == AhkTypes.CALL_EXPR && outer.getFirstChild() == path) {
            return null;
        }
        return AhkNamePath.isPath(path.getText()) ? path.getTextRange() : null;
    }
}
