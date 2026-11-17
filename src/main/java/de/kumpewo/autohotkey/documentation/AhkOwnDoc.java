package de.kumpewo.autohotkey.documentation;

import com.intellij.lang.ASTNode;
import com.intellij.lang.documentation.DocumentationMarkup;
import com.intellij.markdown.utils.doc.DocMarkdownToHtmlConverter;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiReference;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.util.PsiTreeUtil;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.completion.AhkCommandInfo;
import de.kumpewo.autohotkey.include.AhkInclude;
import de.kumpewo.autohotkey.navigation.AhkDefinitionReference;
import de.kumpewo.autohotkey.psi.AhkClass;
import de.kumpewo.autohotkey.psi.AhkDefinitions;
import de.kumpewo.autohotkey.psi.AhkFunc;
import de.kumpewo.autohotkey.psi.AhkTypes;
import de.kumpewo.autohotkey.types.AhkTypeDeclarations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Quick documentation of own functions, methods and classes: signature, file and the doc comment
 * ({@link AhkDocComment}). The definitions are found by name like Ctrl+B ({@link AhkDefinitionReference}); an own
 * definition takes precedence over the built-in help. Several matching definitions give an overview page with one
 * link each ({@code psi_element://ahkdef:offset:url}).
 */
public final class AhkOwnDoc {

    /** Prefix of a link to an own definition: {@code ahkdef:<offset of the name>:<file url>}. */
    static final String LINK_PREFIX = "ahkdef:";

    private static final Pattern URL = Pattern.compile("^(https?://\\S+)(.*)$", Pattern.DOTALL);
    private static final Pattern SEE_NAME = Pattern.compile("^([A-Za-z0-9_\\x{80}-\\x{FFFF}]+(?:\\.[A-Za-z0-9_\\x{80}-\\x{FFFF}]+)*)(.*)$",
            Pattern.DOTALL);

    private AhkOwnDoc() {
    }

    /** Is the element a definition with own documentation (function, method or class)? */
    public static boolean isDocumented(@Nullable PsiElement element) {
        return element instanceof AhkFunc || element instanceof AhkClass;
    }

    /**
     * The own functions, methods and classes the token under the caret stands for: the definition itself if it is
     * its name, otherwise the targets of the definition reference at the token (properties and fields left out).
     */
    public static @NotNull List<PsiElement> definitions(@Nullable PsiElement leaf) {
        if (leaf == null || leaf instanceof AhkHelpElement) return List.of();
        PsiElement parent = leaf.getParent();
        if (isDocumented(parent) && ((com.intellij.psi.PsiNameIdentifierOwner) parent).getNameIdentifier() == leaf) {
            return List.of(parent);
        }
        if (parent == null) return List.of();
        int offset = leaf.getTextRange().getStartOffset();
        List<PsiElement> result = new ArrayList<>();
        for (PsiReference reference : parent.getReferences()) {
            if (!(reference instanceof AhkDefinitionReference definitionReference)) continue;
            if (!reference.getAbsoluteRange().containsOffset(offset)) continue;
            for (ResolveResult resolved : definitionReference.multiResolve(false)) {
                PsiElement target = resolved.getElement();
                if (isDocumented(target) && !result.contains(target)) result.add(target);
            }
        }
        return result;
    }

    /** Signature as in the parameter info: {@code Box.Open(name, [force := false])}, {@code class Box extends Base}. */
    public static @NotNull String signature(@NotNull PsiElement definition) {
        if (definition instanceof AhkClass cls) {
            String text = "class " + AhkTypeDeclarations.qualifiedName(cls);
            ASTNode extendsKeyword = cls.getNode().findChildByType(AhkTypes.KEYWORD_EXTENDS);
            if (extendsKeyword == null) return text;
            // base class path after "extends": IDENTIFIER (DOT IDENTIFIER)*
            StringBuilder base = new StringBuilder();
            for (ASTNode node = extendsKeyword.getTreeNext(); node != null; node = node.getTreeNext()) {
                var type = node.getElementType();
                if (type == AhkTypes.IDENTIFIER || type == AhkTypes.DOT) {
                    base.append(node.getText());
                } else if (base.length() > 0) {
                    break;
                }
            }
            return text + " extends " + base;
        }
        AhkFunc func = (AhkFunc) definition;
        AhkCommandInfo.Signature signature = AhkCommandInfo.ownSignature(func);
        AhkClass owner = AhkDefinitions.ownerClass(func);
        StringBuilder text = new StringBuilder();
        if (owner != null) text.append(AhkTypeDeclarations.qualifiedName(owner)).append('.');
        text.append(signature.name()).append('(');
        for (int i = 0; i < signature.parameters().size(); i++) {
            if (i > 0) text.append(", ");
            text.append(signature.parameters().get(i).display());
        }
        return text.append(')').toString();
    }

    /** The documentation page of one definition. */
    public static @NotNull String render(@NotNull PsiElement definition) {
        AhkDocComment doc = AhkDocComment.of(definition);
        PsiFile file = definition.getContainingFile();
        StringBuilder html = new StringBuilder(DocumentationMarkup.DEFINITION_START)
                .append(escape(signature(definition)));
        if (doc != null && doc.returns() != null && !doc.returns().types().isEmpty()) {
            html.append(" → ").append(escape(typeText(doc.returns().types())));
        }
        html.append(DocumentationMarkup.DEFINITION_END);
        if (doc != null && !doc.description().isEmpty()) {
            html.append(DocumentationMarkup.CONTENT_START).append(markdown(definition, doc.description()))
                    .append(DocumentationMarkup.CONTENT_END);
        }
        html.append(DocumentationMarkup.SECTIONS_START);
        if (doc != null) {
            if (doc.deprecated() != null) {
                section(html, AhkBundle.message("doc.section.deprecated"),
                        doc.deprecated().isEmpty() ? "" : markdown(definition, doc.deprecated()));
            }
            if (!doc.params().isEmpty()) {
                StringBuilder params = new StringBuilder();
                for (AhkDocComment.Param param : doc.params()) {
                    params.append("<p><code>").append(escape(param.name())).append("</code>");
                    if (!param.types().isEmpty()) params.append(" (").append(escape(typeText(param.types()))).append(')');
                    if (!param.text().isEmpty()) params.append(" – ").append(inline(definition, param.text()));
                }
                section(html, AhkBundle.message("doc.section.params"), params.toString());
            }
            if (doc.returns() != null && !doc.returns().text().isEmpty()) {
                section(html, AhkBundle.message("doc.section.returns"), inline(definition, doc.returns().text()));
            }
            if (!doc.throwsList().isEmpty()) {
                StringBuilder text = new StringBuilder();
                for (String t : doc.throwsList()) text.append("<p>").append(inline(definition, t));
                section(html, AhkBundle.message("doc.section.throws"), text.toString());
            }
            if (!doc.examples().isEmpty()) {
                StringBuilder text = new StringBuilder();
                for (String code : doc.examples()) text.append("<pre><code>").append(escape(code)).append("</code></pre>");
                section(html, AhkBundle.message("doc.section.example"), text.toString());
            }
            if (!doc.sees().isEmpty()) {
                StringBuilder text = new StringBuilder();
                for (String see : doc.sees()) text.append("<p>").append(see(definition, see));
                section(html, AhkBundle.message("doc.section.see"), text.toString());
            }
        }
        if (file != null) section(html, AhkBundle.message("doc.section.file"), escape(file.getName()));
        return html.append(DocumentationMarkup.SECTIONS_END).toString();
    }

    /** Overview for several definitions with the same name: one link each. */
    public static @NotNull String overview(@NotNull String name, @NotNull List<PsiElement> definitions) {
        StringBuilder html = new StringBuilder(DocumentationMarkup.DEFINITION_START).append(escape(name))
                .append(DocumentationMarkup.DEFINITION_END).append(DocumentationMarkup.CONTENT_START)
                .append("<p>").append(escape(AhkBundle.message("doc.variants.intro"))).append("</p><ul>");
        for (PsiElement definition : definitions) {
            PsiFile file = definition.getContainingFile();
            html.append("<li><a href=\"psi_element://").append(escape(link(definition))).append("\">")
                    .append(escape(signature(definition))).append("</a>");
            if (file != null) html.append(" – ").append(escape(file.getName()));
            html.append("</li>");
        }
        return html.append("</ul>").append(DocumentationMarkup.CONTENT_END).toString();
    }

    /** Link target of a definition: {@code ahkdef:<offset>:<url>}. */
    static @NotNull String link(@NotNull PsiElement definition) {
        PsiFile file = definition.getContainingFile();
        VirtualFile virtualFile = file == null ? null : file.getOriginalFile().getVirtualFile();
        String url = virtualFile == null ? "" : virtualFile.getUrl();
        return LINK_PREFIX + definition.getTextOffset() + ":" + url;
    }

    /** The definition of a link {@code ahkdef:<offset>:<url>}; the context file if the url is empty or unknown. */
    static @Nullable PsiElement resolveLink(@NotNull String link, @NotNull PsiElement context) {
        if (!link.startsWith(LINK_PREFIX)) return null;
        String rest = link.substring(LINK_PREFIX.length());
        int colon = rest.indexOf(':');
        if (colon < 0) return null;
        int offset;
        try {
            offset = Integer.parseInt(rest.substring(0, colon));
        } catch (NumberFormatException e) {
            return null;
        }
        String url = rest.substring(colon + 1);
        PsiFile file = null;
        VirtualFile virtualFile = url.isEmpty() ? null : VirtualFileManager.getInstance().findFileByUrl(url);
        if (virtualFile != null) file = PsiManager.getInstance(context.getProject()).findFile(virtualFile);
        if (file == null) file = context.getContainingFile();
        if (file == null) return null;
        PsiElement leaf = file.findElementAt(offset);
        PsiElement definition = PsiTreeUtil.getParentOfType(leaf, AhkFunc.class, AhkClass.class);
        return definition != null && definition.getTextOffset() == offset ? definition : null;
    }

    /** One row of the sections table. */
    private static void section(StringBuilder html, String header, String content) {
        html.append(DocumentationMarkup.SECTION_HEADER_START).append(escape(header)).append(':')
                .append(DocumentationMarkup.SECTION_SEPARATOR).append(content).append(DocumentationMarkup.SECTION_END);
    }

    /** Types as written: {@code Gui|Menu}. */
    private static String typeText(List<AhkDocComment.Type> types) {
        return String.join("|", types.stream().map(AhkDocComment.Type::name).toList());
    }

    /** Markdown to HTML (the platform's converter, as for other languages). */
    private static String markdown(PsiElement context, String text) {
        return DocMarkdownToHtmlConverter.convert(context.getProject(), text);
    }

    /**
     * Markdown of a short text without the surrounding paragraph (the converter returns {@code <p>text}, without
     * {@code </p>}), so that it continues the line of the tag. Several paragraphs stay as they are.
     */
    static String inline(PsiElement context, String text) {
        String html = markdown(context, text).strip();
        if (html.startsWith("<p>") && html.indexOf("<p>", 3) < 0) {
            html = html.substring(3);
            if (html.endsWith("</p>")) html = html.substring(0, html.length() - 4);
        }
        return html.strip();
    }

    /**
     * {@code @see}: an url = external link; a name ({@code Box.Open}, {@code MsgBox}) = link to the own definition or
     * else to the help page; otherwise text. The rest after the url or name is shown as text.
     */
    private static String see(PsiElement context, String text) {
        Matcher url = URL.matcher(text);
        if (url.matches()) {
            return "<a href=\"" + escape(url.group(1)) + "\">" + escape(url.group(1)) + "</a>" + escape(url.group(2));
        }
        Matcher name = SEE_NAME.matcher(text);
        if (name.matches()) {
            String target = seeTarget(context, name.group(1));
            if (target != null) {
                return "<a href=\"psi_element://" + escape(target) + "\"><code>" + escape(name.group(1)) + "</code></a>"
                        + escape(name.group(2));
            }
        }
        return escape(text);
    }

    /**
     * Link target for a name in {@code @see}: own function or class ({@code Helper}, {@code Box}, {@code Outer.Inner}),
     * method or nested class of an own class ({@code Box.Open}) in the file of the comment and its direct #Include
     * files; otherwise a help page ({@code MsgBox}, {@code Gui.Add}); {@code null} = none.
     */
    static @Nullable String seeTarget(@NotNull PsiElement context, @NotNull String name) {
        PsiFile file = context.getContainingFile();
        if (file == null) return null;
        PsiElement own = ownDefinition(file, name);
        if (own != null) return link(own);
        de.kumpewo.autohotkey.parser.AhkLanguageLevel level = de.kumpewo.autohotkey.parser.AhkLanguageLevel.of(file);
        // the whole name without a class (MsgBox, nested classes such as Gui.Button)
        for (AhkHelpRepository.Topic topic : AhkHelpRepository.topics(level, name)) {
            if (topic.className() == null) return topic.link();
        }
        // Class.Member: the entry of the word with this class (Gui.Add)
        int dot = name.lastIndexOf('.');
        if (dot < 0) return null;
        String owner = name.substring(0, dot);
        for (AhkHelpRepository.Topic topic : AhkHelpRepository.topics(level, name.substring(dot + 1))) {
            if (owner.equalsIgnoreCase(topic.className())) return topic.link();
        }
        return null;
    }

    /** Own definition for a name in {@code @see}, see {@link #seeTarget}. */
    private static @Nullable PsiElement ownDefinition(PsiFile file, String name) {
        AhkClass cls = AhkTypeDeclarations.ownClass(file, name);
        if (cls != null) return cls;
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            List<PsiFile> files = new ArrayList<>();
            files.add(file);
            files.addAll(AhkInclude.includedFiles(file));
            for (PsiFile source : files) {
                for (AhkFunc func : PsiTreeUtil.findChildrenOfType(source, AhkFunc.class)) {
                    if (AhkDefinitions.ownerClass(func) == null && name.equalsIgnoreCase(func.getName())) return func;
                }
            }
            return null;
        }
        AhkClass owner = AhkTypeDeclarations.ownClass(file, name.substring(0, dot));
        if (owner == null) return null;
        String member = name.substring(dot + 1);
        for (AhkFunc func : PsiTreeUtil.findChildrenOfType(owner, AhkFunc.class)) {
            if (AhkDefinitions.ownerClass(func) == owner && member.equalsIgnoreCase(func.getName())) return func;
        }
        return null;
    }

    /** Escapes text for HTML. */
    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
