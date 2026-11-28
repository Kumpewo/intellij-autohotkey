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
import de.kumpewo.autohotkey.navigation.AhkFieldTarget;
import de.kumpewo.autohotkey.psi.AhkClass;
import de.kumpewo.autohotkey.psi.AhkClassProperty;
import de.kumpewo.autohotkey.psi.AhkDefinitions;
import de.kumpewo.autohotkey.psi.AhkFunc;
import de.kumpewo.autohotkey.psi.AhkMemberExpr;
import de.kumpewo.autohotkey.psi.AhkTypes;
import de.kumpewo.autohotkey.types.AhkTypeDeclarations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Quick documentation of own functions, methods, classes, properties and fields: signature, file and the doc comment
 * ({@link AhkDocComment}); for instance properties and fields also the declared type ({@code ;@declare-member-type},
 * {@code ;@declare-type Class.member}). The definitions are found by name like Ctrl+B ({@link AhkDefinitionReference});
 * after the dot of an expression with a declared type (or {@code this}) only those of its class and the own base
 * classes count. An own definition takes precedence over the built-in help. Several matching definitions give an
 * overview page with one link each ({@code psi_element://ahkdef:offset:url}).
 */
public final class AhkOwnDoc {

    /** Prefix of a link to an own definition: {@code ahkdef:<offset of the name>:<file url>}. */
    static final String LINK_PREFIX = "ahkdef:";

    private static final Pattern URL = Pattern.compile("^(https?://\\S+)(.*)$", Pattern.DOTALL);
    private static final Pattern SEE_NAME = Pattern.compile("^([A-Za-z0-9_\\x{80}-\\x{FFFF}]+(?:\\.[A-Za-z0-9_\\x{80}-\\x{FFFF}]+)*)(.*)$",
            Pattern.DOTALL);

    private AhkOwnDoc() {
    }

    /** Is the element a definition with own documentation (function, method, class, property or field)? */
    public static boolean isDocumented(@Nullable PsiElement element) {
        return element instanceof AhkFunc || element instanceof AhkClass || element instanceof AhkClassProperty
                || element instanceof AhkFieldTarget;
    }

    /**
     * The own definitions the token under the caret stands for: the definition itself if it is its name (also the
     * name of a field in its defining statement), otherwise the targets of the definition reference at the token. After
     * the dot of an expression with a declared type ({@link AhkTypeDeclarations#typeOf}) only the members of that
     * class and its own base classes; none of them = no own definition (the help of a built-in type may follow).
     */
    public static @NotNull List<PsiElement> definitions(@Nullable PsiElement leaf) {
        if (leaf == null || leaf instanceof AhkHelpElement) return List.of();
        PsiElement parent = leaf.getParent();
        if (isDocumented(parent) && parent instanceof com.intellij.psi.PsiNameIdentifierOwner owner
                && owner.getNameIdentifier() == leaf) {
            return List.of(parent);
        }
        AhkDefinitions.Field field = leaf.getNode().getElementType() == AhkTypes.IDENTIFIER
                ? AhkDefinitions.field(leaf) : null;
        if (field != null) return List.of(new AhkFieldTarget(field));
        if (parent == null) return List.of();
        int offset = leaf.getTextRange().getStartOffset();
        List<PsiElement> result = new ArrayList<>();
        for (PsiReference reference : parent.getReferences()) {
            if (!(reference instanceof AhkDefinitionReference definitionReference)) continue;
            if (!reference.getAbsoluteRange().containsOffset(offset)) continue;
            Set<AhkClass> owners = receiverClasses(definitionReference);
            for (ResolveResult resolved : definitionReference.multiResolve(false)) {
                PsiElement target = resolved.getElement();
                if (!isDocumented(target) || result.contains(target)) continue;
                if (owners != null && !owners.contains(ownerOf(target))) continue;
                result.add(target);
            }
        }
        return result;
    }

    /**
     * For a name after the dot whose left side has a declared type (or is {@code this}): the own classes of that type
     * and their own base classes; {@code null} if the type is unknown (then all definitions of the name count).
     */
    private static @Nullable Set<AhkClass> receiverClasses(AhkDefinitionReference reference) {
        PsiElement element = reference.getElement();
        if (reference.kind() != AhkDefinitionReference.Kind.MEMBER || !(element instanceof AhkMemberExpr)) return null;
        AhkTypeDeclarations.Declared declared = AhkTypeDeclarations.typeOf(element.getFirstChild());
        PsiFile file = element.getContainingFile();
        if (declared == null || file == null) return null;
        Set<AhkClass> result = new HashSet<>();
        for (String className : declared.classes()) {
            for (AhkTypeDeclarations.Level level : AhkTypeDeclarations.hierarchy(file, className)) {
                if (level.own() != null) result.add(level.own());
            }
        }
        return result;
    }

    /** The class that defines the member (method, property, field, nested class), or {@code null}. */
    private static @Nullable AhkClass ownerOf(PsiElement definition) {
        if (definition instanceof AhkFieldTarget target) {
            return PsiTreeUtil.getParentOfType(target.identifier(), AhkClass.class);
        }
        return AhkDefinitions.ownerClass(definition);
    }

    /**
     * Signature as in the parameter info: {@code Box.Open(name, [force := false])}, {@code class Box extends Base};
     * for members the kind and the name with the class: {@code field Box.count}, {@code static field Box.total},
     * {@code property Box.Item[index] (get, set)}.
     */
    public static @NotNull String signature(@NotNull PsiElement definition) {
        if (definition instanceof AhkFieldTarget target) {
            AhkClass owner = ownerOf(target);
            return (target.field().isStatic() ? "static field " : "field ")
                    + (owner == null ? "" : AhkTypeDeclarations.qualifiedName(owner) + ".") + target.getName();
        }
        if (definition instanceof AhkClassProperty property) return propertySignature(property);
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

    /** {@code property Box.Item[index] (get, set)}: {@code static}, class, parameters, accessors ({@code =>} = get). */
    private static String propertySignature(AhkClassProperty property) {
        StringBuilder text = new StringBuilder(AhkTypeDeclarations.isStatic(property) ? "static property "
                : "property ");
        AhkClass owner = AhkDefinitions.ownerClass(property);
        if (owner != null) text.append(AhkTypeDeclarations.qualifiedName(owner)).append('.');
        text.append(property.getName());
        ASTNode parameters = property.getNode().findChildByType(AhkTypes.INDEX_PARAMETER_LIST);
        if (parameters != null) text.append(parameters.getText().replaceAll("\\s+", " "));
        List<String> accessors = new ArrayList<>();
        ASTNode block = property.getNode().findChildByType(AhkTypes.PROPERTY_BLOCK);
        if (block == null) {
            accessors.add("get");
        } else {
            for (ASTNode accessor : block.getChildren(com.intellij.psi.tree.TokenSet.create(AhkTypes.ACCESSOR))) {
                ASTNode name = accessor.findChildByType(AhkTypes.IDENTIFIER);
                if (name != null) accessors.add(name.getText().toLowerCase(java.util.Locale.ROOT));
            }
        }
        if (!accessors.isEmpty()) text.append(" (").append(String.join(", ", accessors)).append(')');
        return text.toString();
    }

    /**
     * Declared type of an instance property or field ({@code ;@declare-member-type},
     * {@code ;@declare-type Class.member}): at a use after the dot of an expression with a declared type that of the
     * expression's class, otherwise that of the defining class; {@code null} for other definitions, static members and
     * undeclared ones. {@code context} = token under the caret (or {@code null}).
     */
    public static @Nullable List<String> memberType(@NotNull PsiElement definition, @Nullable PsiElement context) {
        String name;
        if (definition instanceof AhkFieldTarget target && !target.field().isStatic()) {
            name = target.getName();
        } else if (definition instanceof AhkClassProperty property && !AhkTypeDeclarations.isStatic(property)) {
            name = property.getName();
        } else {
            return null;
        }
        AhkClass owner = ownerOf(definition);
        PsiFile file = context != null && context.getContainingFile() != null ? context.getContainingFile()
                : definition.getContainingFile();
        if (owner == null || file == null || name == null) return null;
        AhkTypeDeclarations.Declared receiver = null;
        PsiElement parent = context == null ? null : context.getParent();
        if (parent instanceof AhkMemberExpr && parent.getFirstChild() != context) {
            receiver = AhkTypeDeclarations.typeOf(parent.getFirstChild());
        }
        if (receiver == null) {
            receiver = new AhkTypeDeclarations.Declared(List.of(AhkTypeDeclarations.qualifiedName(owner)), true);
        }
        AhkTypeDeclarations.Declared type = AhkTypeDeclarations.memberType(file, receiver, name);
        return type == null ? null : type.classes();
    }

    /** Short info (Ctrl+hover): the signature, for a member with a declared type {@code signature : Type}. */
    public static @NotNull String shortInfo(@NotNull PsiElement definition, @Nullable PsiElement context) {
        List<String> type = memberType(definition, context);
        return signature(definition) + (type == null ? "" : " : " + String.join("|", type));
    }

    /** The documentation page of one definition (without a place of use). */
    public static @NotNull String render(@NotNull PsiElement definition) {
        return render(definition, null);
    }

    /** The documentation page of one definition; {@code context} = token under the caret for the member type. */
    public static @NotNull String render(@NotNull PsiElement definition, @Nullable PsiElement context) {
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
        List<String> type = memberType(definition, context);
        if (type != null) section(html, AhkBundle.message("doc.section.type"), escape(String.join("|", type)));
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
            // tolerated tags (@author ...): one section per tag, in the order of the list
            for (String tag : AhkDocComment.TOLERATED_TAGS) {
                StringBuilder text = new StringBuilder();
                for (AhkDocComment.Other other : doc.others()) {
                    if (other.tag().equals(tag)) text.append("<p>").append(inline(definition, other.text()));
                }
                if (!text.isEmpty()) section(html, AhkBundle.message("doc.section." + tag), text.toString());
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
        if (leaf == null) return null;
        AhkDefinitions.Field field = AhkDefinitions.field(leaf);
        if (field != null) return new AhkFieldTarget(field);
        PsiElement definition = PsiTreeUtil.getParentOfType(leaf, AhkFunc.class, AhkClass.class, AhkClassProperty.class);
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
     * method or nested class of an own class ({@code Box.Open}) in the file of the comment and the files
     * it includes; otherwise a help page ({@code MsgBox}, {@code Gui.Add}); {@code null} = none.
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
            files.addAll(AhkInclude.visibleFiles(file));
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
