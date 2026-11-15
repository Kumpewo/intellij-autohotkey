package de.kumpewo.autohotkey.completion;

import de.kumpewo.autohotkey.AhkBundle;
import com.intellij.lang.parameterInfo.CreateParameterInfoContext;
import com.intellij.lang.parameterInfo.ParameterInfoHandler;
import com.intellij.lang.parameterInfo.ParameterInfoUIContext;
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Parameter info (Ctrl+P) for own functions (file and direct #Include files; they take precedence) as well as
 * built-in functions and directives from names/signatures.txt, for a call {@code f(|)}, a command {@code f |} and
 * a directive {@code #Warn |}. The current argument is highlighted; commands with several forms (BlockInput) show
 * all of them.
 * <p>
 * Methods ({@code obj.Add(|}): forms of the own classes and from names/method-signatures.txt (selection see
 * {@link AhkCommandInfo#methodSignatures}), shown as {@code Class.Method(parameters)}.
 */
public class AhkParameterInfoHandler implements ParameterInfoHandler<PsiElement, AhkCommandInfo.Signature> {

    /** Finds the owner of the arguments at the caret and the forms to show. */
    @Override
    public @Nullable PsiElement findElementForParameterInfo(@NotNull CreateParameterInfoContext context) {
        AhkArguments.Owner owner = AhkArguments.find(context.getFile(), context.getOffset());
        if (owner == null) return null;
        List<AhkCommandInfo.Signature> signatures = owner.method()
                ? AhkCommandInfo.methodSignatures(context.getFile(), owner.qualifier(), owner.name())
                : AhkCommandInfo.signatures(context.getFile(), owner.name());
        if (signatures.isEmpty()) return null;
        context.setItemsToShow(signatures.toArray());
        return owner.element();
    }

    /** Shows the hint at the start of the owner. */
    @Override
    public void showParameterInfo(@NotNull PsiElement element, @NotNull CreateParameterInfoContext context) {
        context.showHint(element, element.getTextRange().getStartOffset(), this);
    }

    /** Owner at the caret while the hint is open. */
    @Override
    public @Nullable PsiElement findElementForUpdatingParameterInfo(@NotNull UpdateParameterInfoContext context) {
        AhkArguments.Owner owner = AhkArguments.find(context.getFile(), context.getOffset());
        return owner == null ? null : owner.element();
    }

    /** The current argument from the number of commas before the caret. */
    @Override
    public void updateParameterInfo(@NotNull PsiElement owner, @NotNull UpdateParameterInfoContext context) {
        context.setCurrentParameter(AhkArguments.index(owner, context.getOffset()));
    }

    /** Text of one form with the current parameter highlighted. */
    @Override
    public void updateUI(AhkCommandInfo.Signature signature, @NotNull ParameterInfoUIContext context) {
        List<AhkCommandInfo.Parameter> params = signature.parameters();
        // Method: "Class.Method(...)", so that forms of different classes can be told apart
        String prefix = signature.owner() == null ? "" : signature.owner() + "." + signature.name() + "(";
        String suffix = signature.owner() == null ? "" : ")";
        if (params.isEmpty()) {
            if (!prefix.isEmpty()) {
                context.setupUIComponentPresentation(prefix + suffix, -1, -1, false, false, false,
                        context.getDefaultParameterColor());
                return;
            }
            context.setupUIComponentPresentation(AhkBundle.message("parameter.info.none"), -1, -1, false, false, false,
                    context.getDefaultParameterColor());
            return;
        }
        int current = context.getCurrentParameterIndex();
        StringBuilder text = new StringBuilder(prefix);
        int start = -1;
        int end = -1;
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) text.append(", ");
            String p = params.get(i).display();
            // variadic parameters (Values..., Params*, [...]) also apply to all further arguments
            boolean last = i == params.size() - 1;
            if (i == current || last && current > i && isVariadic(p)) {
                start = text.length();
                end = start + p.length();
            }
            text.append(p);
        }
        text.append(suffix);
        String description = currentDescription(params, current);
        if (description == null) {
            context.setupUIComponentPresentation(text.toString(), start, end, false, false, false,
                    context.getDefaultParameterColor());
            return;
        }
        // own function with a doc comment (Z.22): the description of the current parameter in a second line
        String html = start < 0 ? escape(text.toString())
                : escape(text.substring(0, start)) + "<b>" + escape(text.substring(start, end)) + "</b>"
                + escape(text.substring(end));
        context.setupRawUIComponentPresentation("<html>" + html + "<br><small>" + escape(description)
                + "</small></html>");
    }

    /**
     * {@code @param} text of the current parameter (a variadic last parameter also for further arguments), or
     * {@code null}.
     */
    public static @Nullable String currentDescription(List<AhkCommandInfo.Parameter> params, int current) {
        if (current < 0 || params.isEmpty()) return null;
        int index = current;
        if (index >= params.size()) {
            AhkCommandInfo.Parameter last = params.get(params.size() - 1);
            if (!isVariadic(last.display())) return null;
            index = params.size() - 1;
        }
        return params.get(index).description();
    }

    /** Escapes text for the HTML of the hint. */
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Parameter that takes any number of arguments. */
    private static boolean isVariadic(String p) {
        return p.contains("...") || p.endsWith("*") || p.endsWith("*]");
    }
}
