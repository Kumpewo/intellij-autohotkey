package de.kumpewo.autohotkey.documentation;

import com.intellij.lang.documentation.AbstractDocumentationProvider;
import com.intellij.lang.documentation.ExternalDocumentationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import de.kumpewo.autohotkey.highlighting.AhkNames;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Quick documentation (Ctrl+Q, hover) from the help pages and external help (Shift+F1, {@link AhkExternalHelp}).
 * {@link #helpKey} decides which page belongs to a word; {@link AhkHelpRepository} whether there is one. Own
 * functions, methods and classes come first: their signature and doc comment ({@link AhkOwnDoc}).
 */
public class AhkDocumentationProvider extends AbstractDocumentationProvider implements ExternalDocumentationHandler {

    /**
     * Key for the lookup files by role, as in highlighting: after the dot with exactly the outer built-in class on the
     * left ({@code Gui.Button}, also {@code extends Gui.Edit}) the nested class {@code Gui.Button}, otherwise the
     * word itself ({@code #Include} with {@code #}). In a line comment the pragma marker without the {@code ;}
     * ({@code ;@declare-type g as Gui} = {@code @declare-type}), wherever the caret is in the comment; other
     * comments have no key.
     */
    public static @Nullable String helpKey(@Nullable PsiElement element) {
        if (element == null) return null;
        String text = element.getText();
        if (element.getNode().getElementType() == AhkTypes.LINE_COMMENT) return pragmaKey(text);
        if (element.getNode().getElementType() != AhkTypes.IDENTIFIER) return text;
        PsiElement parent = element.getParent();
        if (parent == null) return text;
        if (parent.getNode().getElementType() == AhkTypes.MEMBER_EXPR) {
            PsiElement qualifier = parent.getFirstChild();
            if (qualifier != null && qualifier != element
                    && qualifier.getNode().getElementType() == AhkTypes.REF_EXPR
                    && AhkNames.of(element).nestedClass(qualifier.getText(), text) != null) {
                return qualifier.getText() + "." + text;
            }
        } else if (parent.getNode().getElementType() == AhkTypes.CLASS) {
            PsiElement dot = PsiTreeUtil.skipWhitespacesAndCommentsBackward(element);
            if (dot != null && dot.getNode().getElementType() == AhkTypes.DOT) {
                PsiElement outer = PsiTreeUtil.skipWhitespacesAndCommentsBackward(dot);
                if (outer != null && AhkNames.of(element).nestedClass(outer.getText(), text) != null) {
                    return outer.getText() + "." + text;
                }
            }
        }
        return text;
    }

    /**
     * Pragma marker at the start of a line comment (the token begins with the whitespace before {@code ;} if the
     * comment follows code), followed by whitespace or the end.
     */
    private static final java.util.regex.Pattern PRAGMA =
            java.util.regex.Pattern.compile("^\\s*;(@[\\w-]+)(?:\\s|$)");

    /**
     * Key of a pragma comment: the marker without the {@code ;} ({@code @ignore-next-line}), because a line starting
     * with {@code ;} is a comment in the lookup files; {@code null} for other comments. All directives of the compiler
     * ({@code ;@Ahk2Exe-SetMainIcon}, {@code ;@Ahk2Exe-Base} ...) share the key {@link #AHK2EXE_KEY}, which leads to
     * the one help page about them. A disabled directive ({@code ;;@Ahk2Exe-...}) is an ordinary comment.
     */
    static @Nullable String pragmaKey(@NotNull String commentText) {
        java.util.regex.Matcher m = PRAGMA.matcher(commentText);
        if (!m.find()) return null;
        String key = m.group(1);
        if (key.regionMatches(true, 0, AHK2EXE_KEY + "-", 0, AHK2EXE_KEY.length() + 1)) return AHK2EXE_KEY;
        return key;
    }

    /** Help key of the compiler directives {@code ;@Ahk2Exe-...} (entry in {@code help/lookup/docs.txt}). */
    static final String AHK2EXE_KEY = "@Ahk2Exe";

    /**
     * Entries of the lookup files for the element (the element under the caret first), empty = no help. A name
     * after the dot gets all entries, narrowed by {@link #narrow} if there are several. A free-standing name
     * (function, variable ...) only gets the entries without a class (fourth column).
     */
    public static @NotNull List<AhkHelpRepository.Topic> topics(@Nullable PsiElement element,
                                                               @Nullable PsiElement originalElement) {
        for (PsiElement e : new PsiElement[]{originalElement, element}) {
            if (e == null || e instanceof AhkHelpElement) continue;
            String key = helpKey(e);
            if (key == null) continue;
            List<AhkHelpRepository.Topic> topics =
                    AhkHelpRepository.topics(de.kumpewo.autohotkey.parser.AhkLanguageLevel.of(e), key);
            if (isMemberName(e)) {
                if (topics.size() > 1) return narrow(e, topics);
            } else {
                topics = topics.stream().filter(t -> t.className() == null).toList();
            }
            if (!topics.isEmpty()) return topics;
        }
        return List.of();
    }

    /**
     * Entries that can be shown in the external viewer: those from docs.txt, those from extra.txt only if the plugin
     * ships {@code /help/extra.chm} ({@link AhkExternalHelp#hasExtraChm}).
     */
    private static List<AhkHelpRepository.Topic> externalTopics(@Nullable PsiElement element,
                                                                @Nullable PsiElement originalElement) {
        return topics(element, originalElement).stream()
                .filter(t -> t.fromDocs() || AhkExternalHelp.hasExtraChm()).toList();
    }

    /** Name after the dot ({@code obj.Name}, whole name). */
    private static boolean isMemberName(PsiElement e) {
        PsiElement parent = e.getParent();
        return e.getNode().getElementType() == AhkTypes.IDENTIFIER && parent != null
                && parent.getNode().getElementType() == AhkTypes.MEMBER_EXPR && parent.getFirstChild() != e;
    }

    /**
     * Narrows several entries by their class (fourth column) and the class left of the dot, as in the parameter
     * info of methods: built-in class ({@code Gui.}) or a name declared with ;@declare-type, including base classes;
     * the first class of the chain with an entry wins ({@code lv as Gui.ListView}: {@code lv.Add} = ListView,
     * {@code lv.Focus} = Gui.Control). Unknown type or no matching entry: all entries.
     */
    private static List<AhkHelpRepository.Topic> narrow(PsiElement name, List<AhkHelpRepository.Topic> topics) {
        PsiFile file = name.getContainingFile();
        List<String> classes = file == null ? null
                : de.kumpewo.autohotkey.completion.AhkCommandInfo.qualifierClasses(file, name.getParent().getFirstChild());
        if (classes == null) return topics;
        java.util.Set<AhkHelpRepository.Topic> result = new java.util.LinkedHashSet<>();
        for (String className : classes) {
            for (de.kumpewo.autohotkey.types.AhkTypeDeclarations.Level level
                    : de.kumpewo.autohotkey.types.AhkTypeDeclarations.hierarchy(file, className)) {
                List<AhkHelpRepository.Topic> found = topics.stream()
                        .filter(t -> level.name().equalsIgnoreCase(t.className())).toList();
                if (!found.isEmpty()) {
                    result.addAll(found);
                    break;
                }
            }
        }
        return result.isEmpty() ? topics : List.copyOf(result);
    }

    /** One entry or {@code null} (for navigation and the short info). */
    private static @Nullable AhkHelpRepository.Topic topic(@Nullable PsiElement element,
                                                         @Nullable PsiElement originalElement) {
        List<AhkHelpRepository.Topic> topics = topics(element, originalElement);
        return topics.isEmpty() ? null : topics.get(0);
    }

    /**
     * Own functions, methods and classes the token stands for ({@link AhkOwnDoc#definitions}); the token under
     * the caret first.
     */
    private static List<PsiElement> ownDefinitions(@Nullable PsiElement element, @Nullable PsiElement originalElement) {
        for (PsiElement e : new PsiElement[]{originalElement, element}) {
            List<PsiElement> own = AhkOwnDoc.definitions(e);
            if (!own.isEmpty()) return own;
        }
        return List.of();
    }

    /**
     * Own definition: its signature and doc comment, with several definitions an overview page with links.
     * Otherwise the help page; with several entries an overview page with links.
     */
    @Override
    public @Nullable String generateDoc(PsiElement element, @Nullable PsiElement originalElement) {
        // First check: was a link followed?
        if (element instanceof AhkHelpElement help) {
            String html = AhkHelpRepository.getPage(help.getPage());
            return html == null ? null : AhkHelpRepository.fromAnchor(html, help.getAnchor());
        }
        // a definition itself (link of the overview, completion item)
        if (AhkOwnDoc.isDocumented(element)) return AhkOwnDoc.render(element);
        List<PsiElement> own = ownDefinitions(element, originalElement);
        if (own.size() == 1) return AhkOwnDoc.render(own.get(0));
        if (own.size() > 1) {
            String name = ((com.intellij.psi.PsiNamedElement) own.get(0)).getName();
            return AhkOwnDoc.overview(name == null ? "" : name, own);
        }
        List<AhkHelpRepository.Topic> topics = topics(element, originalElement);
        if (topics.isEmpty()) return null;
        if (topics.size() > 1) return AhkHelpRepository.overview(topics.get(0).name(), topics);
        return AhkHelpRepository.getTopicPage(topics.get(0));
    }

    /** Same as {@link #generateDoc}, so that the page is shown on mouse hover as well. */
    @Nullable
    @Override
    public String generateHoverDoc(@NotNull PsiElement element, @Nullable PsiElement originalElement) {
        // Makes sure the text is also shown when just hovering with the mouse
        return generateDoc(element, originalElement);
    }

    /** Short info (Ctrl+hover): the signature of an own definition, otherwise the name of the help entry. */
    @Override
    public @Nullable String getQuickNavigateInfo(PsiElement element, PsiElement originalElement) {
        if (AhkOwnDoc.isDocumented(element)) return AhkOwnDoc.escape(AhkOwnDoc.signature(element));
        List<PsiElement> own = ownDefinitions(element, originalElement);
        if (!own.isEmpty()) return AhkOwnDoc.escape(AhkOwnDoc.signature(own.get(0)));
        AhkHelpRepository.Topic topic = topic(element, originalElement);
        return topic == null ? null : topic.name();
    }

    /**
     * The token under the caret itself is the documentation element (names have no PsiReference to a help page). With
     * the caret at the end of a line comment (on the line break) the comment, so that a pragma has help there too.
     */
    @Override
    public @Nullable PsiElement getCustomDocumentationElement(
            @NotNull Editor editor,
            @NotNull PsiFile file,
            @Nullable PsiElement contextElement,
            int targetOffset) {
        return documentationElement(file, contextElement, targetOffset);
    }

    /** See {@link #getCustomDocumentationElement}; without editor for tests. */
    public static @Nullable PsiElement documentationElement(@NotNull PsiFile file, @Nullable PsiElement contextElement,
                                                            int targetOffset) {
        if (targetOffset > 0 && (contextElement == null
                || contextElement.getNode().getElementType() == AhkTypes.CRLF)) {
            PsiElement before = file.findElementAt(targetOffset - 1);
            if (before != null && before.getNode().getElementType() == AhkTypes.LINE_COMMENT) return before;
        }
        return contextElement;
    }

    /** Completion item of an own function, method or class: the definition, for Ctrl+Q in the list. */
    @Override
    public @Nullable PsiElement getDocumentationElementForLookupItem(PsiManager psiManager, Object object,
                                                                     PsiElement element) {
        return object instanceof PsiElement psi && AhkOwnDoc.isDocumented(psi) ? psi : null;
    }

    /**
     * Target of a link in the popup: {@code psi_element://ahkdef:offset:url} = an own definition,
     * {@code psi_element://page#anchor} = a help page.
     */
    @Override
    public @Nullable PsiElement getDocumentationElementForLink(
            PsiManager psiManager, String link, PsiElement context) {
        if (context == null) return null;
        if (link.startsWith(AhkOwnDoc.LINK_PREFIX)) return AhkOwnDoc.resolveLink(link, context);
        int hash = link.indexOf('#');
        String page = hash >= 0 ? link.substring(0, hash) : link;
        String anchor = hash >= 0 ? link.substring(hash + 1) : null;
        if (AhkHelpRepository.getPage(page) == null) return null;
        return new AhkHelpElement(page, anchor, context);
    }

    // --- external help (Shift+F1) ---

    /** Enables the action "External Documentation"; the call itself is done by {@link #handleExternal}
     * (Shift+F1) and {@link #handleExternalLink} (link in the popup). */
    @Override
    public @Nullable List<String> getUrlFor(PsiElement element, PsiElement originalElement) {
        List<AhkHelpRepository.Topic> topics = externalTopics(element, originalElement);
        return topics.isEmpty() ? null : topics.stream().map(AhkHelpRepository.Topic::externalKey).toList();
    }

    /** The plugin opens the external help itself if the word has an entry for the external viewer. */
    @Override
    public boolean canHandleExternal(@Nullable PsiElement element, @Nullable PsiElement originalElement) {
        return !externalTopics(element, originalElement).isEmpty();
    }

    /** One entry: start directly; several: a chooser first ({@link AhkExternalHelp#choose}). */
    @Override
    public boolean handleExternal(PsiElement element, PsiElement originalElement) {
        List<AhkHelpRepository.Topic> topics = externalTopics(element, originalElement);
        if (topics.isEmpty()) return false;
        PsiElement context = originalElement != null ? originalElement : element;
        AhkExternalHelp.choose(context == null ? null : context.getProject(), topics);
        return true;
    }

    /**
     * Link the platform puts at the bottom of the popup for the keys from {@link #getUrlFor}: they are no web
     * addresses, so it shows the generic link "External documentation" ({@value #GENERIC_EXTERNAL_LINK}); a key
     * itself may also arrive. The platform cannot open either, so the plugin opens the topic(s) itself, like Shift+F1.
     */
    static final String GENERIC_EXTERNAL_LINK = "external_doc";

    /** Opens the external help for the link at the bottom of the popup ({@link #externalTopicsForLink}). */
    @Override
    public boolean handleExternalLink(PsiManager psiManager, String link, PsiElement context) {
        List<AhkHelpRepository.Topic> topics = com.intellij.openapi.application.ReadAction.compute(
                () -> externalTopicsForLink(link, context));
        if (topics.isEmpty()) return false;
        AhkExternalHelp.choose(context.getProject(), topics);
        return true;
    }

    /**
     * The external topics of the element for a link of the popup: all of them for the generic link
     * ({@link #GENERIC_EXTERNAL_LINK}), the one whose key ({@link AhkHelpRepository.Topic#externalKey}) is the link
     * otherwise; empty for other links.
     */
    public static @NotNull List<AhkHelpRepository.Topic> externalTopicsForLink(@Nullable String link,
                                                                           @Nullable PsiElement context) {
        if (link == null || context == null || !context.isValid()) return List.of();
        List<AhkHelpRepository.Topic> topics = externalTopics(context, null);
        if (link.equals(GENERIC_EXTERNAL_LINK)) return topics;
        return topics.stream().filter(t -> t.externalKey().equals(link)).toList();
    }
}
