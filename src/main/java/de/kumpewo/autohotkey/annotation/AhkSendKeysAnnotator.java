package de.kumpewo.autohotkey.annotation;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiTreeUtil;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.highlighting.AhkColors;
import de.kumpewo.autohotkey.highlighting.AhkNames;
import de.kumpewo.autohotkey.psi.AhkArgList;
import de.kumpewo.autohotkey.psi.AhkArrayExpr;
import de.kumpewo.autohotkey.psi.AhkCallExpr;
import de.kumpewo.autohotkey.psi.AhkCommandStmt;
import de.kumpewo.autohotkey.psi.AhkDirective;
import de.kumpewo.autohotkey.psi.AhkIndexList;
import de.kumpewo.autohotkey.psi.AhkObjectExpr;
import de.kumpewo.autohotkey.psi.AhkOtherVersionCode;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Keys in send text: valid key syntax gets the color "Key in send text", unknown key names and options in braces a
 * warning (AutoHotkey ignores them silently). Syntax: {@link AhkSendKeys}. Send text is
 * <ul>
 *   <li>the replacement text of a hotstring ({@code ::sig::Regards{Enter}Name}), except in raw or text mode, where
 *       the text is sent literally: option {@code R} or {@code T} in the head of the hotstring or in a
 *       {@code #Hotstring} directive above it in the same file (the head wins), and a continuation section as the
 *       replacement text (text mode by default; only {@code R0}/{@code T0} in the head switches it off);</li>
 *   <li>a string literal in the first argument of {@code Send}, {@code SendInput}, {@code SendEvent},
 *       {@code SendPlay} or {@code ControlSend} (call or command), also as part of a concatenation or condition
 *       ({@code Send "{Ctrl down}" key "{Ctrl up}"}); each literal is checked on its own. Not
 *       {@code SendText}/{@code ControlSendText}, which send literally.</li>
 * </ul>
 */
public class AhkSendKeysAnnotator implements Annotator {

    /** Functions whose first argument is send text (lower case). */
    private static final Set<String> SEND_FUNCTIONS = Set.of("send", "sendinput", "sendevent", "sendplay",
            "controlsend");

    /** Colors and checks the replacement text of a hotstring or a string passed to a Send function. */
    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        IElementType type = element.getNode().getElementType();
        if (type != AhkTypes.HOTSTRING_TEXT && type != AhkTypes.STRING) return;
        if (AhkIgnoredLines.isIgnored(element)
                || PsiTreeUtil.getParentOfType(element, AhkOtherVersionCode.class) != null) {
            return;
        }
        if (type == AhkTypes.STRING) {
            String text = element.getText();
            if (text.length() >= 2 && isSendArgument(element)) annotate(element, 1, text.length() - 1, holder);
            return;
        }
        String text = element.getText();
        boolean section = text.startsWith("(");
        Boolean headOption = headOption(element);
        boolean literal = headOption != null ? headOption
                : section || directiveOption(element.getContainingFile(), element.getTextRange().getStartOffset());
        if (literal) return;

        int start = 0;
        int end = text.length();
        if (section) {
            // only the content lines, not the line with "(" and its options and not the line with ")"
            start = text.indexOf('\n') + 1;
            int lastLine = text.lastIndexOf('\n') + 1;
            end = Math.max(start, lastLine);
        }
        annotate(element, start, end, holder);
    }

    /** Colors and checks the send text in {@code element} from {@code start} to {@code end} (relative). */
    private static void annotate(PsiElement element, int start, int end, AnnotationHolder holder) {
        AhkNames names = AhkNames.of(element);
        int offset = element.getTextRange().getStartOffset();
        String text = element.getText();
        for (AhkSendKeys.Part part : AhkSendKeys.parse(text, start, end, n -> names.spelling(n, "keys") != null)) {
            TextRange range = new TextRange(offset + part.start(), offset + part.end());
            switch (part.kind()) {
                case KEY -> holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                        .range(range).textAttributes(AhkColors.AHK_SEND_KEY).create();
                case UNKNOWN_KEY -> warn(holder, range, AhkBundle.message("annotator.send.unknown.key", part.name()));
                case UNKNOWN_OPTION ->
                        warn(holder, range, AhkBundle.message("annotator.send.unknown.option", part.name()));
                case BLIND_NOT_FIRST -> warn(holder, range, AhkBundle.message("annotator.send.blind.first"));
            }
        }
    }

    /**
     * Whether the string is (part of) the first argument of a Send function: walking up from the string, the first
     * call or command reached is one of {@link #SEND_FUNCTIONS} and the string lies before the first comma. Index
     * expressions, object and array literals in between end the search ({@code Send keys["{x}"]}).
     */
    private static boolean isSendArgument(PsiElement string) {
        PsiElement child = string;
        for (PsiElement e = string.getParent(); e != null && !(e instanceof PsiFile); child = e, e = e.getParent()) {
            if (e instanceof AhkIndexList || e instanceof AhkObjectExpr || e instanceof AhkArrayExpr) return false;
            PsiElement name;
            if (e instanceof AhkArgList && e.getParent() instanceof AhkCallExpr call) {
                name = call.getFirstChild();
            } else if (e instanceof AhkCommandStmt) {
                name = e.getFirstChild();
                if (name == child) return false;
            } else {
                continue;
            }
            if (!SEND_FUNCTIONS.contains(name.getText().toLowerCase(Locale.ROOT))) return false;
            for (PsiElement before = child.getPrevSibling(); before != null; before = before.getPrevSibling()) {
                if (before.getNode().getElementType() == AhkTypes.COMMA) return false;
            }
            return true;
        }
        return false;
    }

    /** Warning on a range. */
    private static void warn(AnnotationHolder holder, TextRange range, String message) {
        holder.newAnnotation(HighlightSeverity.WARNING, message).range(range).create();
    }

    /** Raw/text option in the head of the hotstring ({@code :R:abc::}), {@code null} if it has none. */
    private static @Nullable Boolean headOption(PsiElement text) {
        PsiElement head = text.getPrevSibling();
        while (head instanceof PsiWhiteSpace || head instanceof PsiComment) head = head.getPrevSibling();
        if (head == null || head.getNode().getElementType() != AhkTypes.HOTSTRING) return null;
        String s = head.getText();
        int second = s.indexOf(':', 1);
        return second < 0 ? null : AhkSendKeys.literalOption(s.substring(1, second));
    }

    /** Raw/text mode set by the last {@code #Hotstring} directive before {@code offset} in the file. */
    private static boolean directiveOption(PsiFile file, int offset) {
        boolean literal = false;
        for (DirectiveOption option : directiveOptions(file)) {
            if (option.offset() >= offset) break;
            literal = option.literal();
        }
        return literal;
    }

    /** A {@code #Hotstring} directive that switches the raw/text mode on or off. */
    private record DirectiveOption(int offset, boolean literal) {
    }

    /** The {@code #Hotstring} directives of the file that mention R or T, in file order (cached per file). */
    private static List<DirectiveOption> directiveOptions(PsiFile file) {
        return CachedValuesManager.getCachedValue(file, () -> {
            List<DirectiveOption> list = new ArrayList<>();
            for (AhkDirective directive : PsiTreeUtil.findChildrenOfType(file, AhkDirective.class)) {
                PsiElement name = directive.getDirectiveName();
                PsiElement args = directive.getDirectiveArgs();
                if (args == null || !name.getText().equalsIgnoreCase("#Hotstring")) continue;
                String options = args.getText().trim();
                String first = options.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
                if (first.equals("nomouse") || first.equals("endchars")) continue;
                Boolean literal = AhkSendKeys.literalOption(options);
                if (literal != null) list.add(new DirectiveOption(directive.getTextRange().getStartOffset(), literal));
            }
            return CachedValueProvider.Result.create(list, file);
        });
    }
}
