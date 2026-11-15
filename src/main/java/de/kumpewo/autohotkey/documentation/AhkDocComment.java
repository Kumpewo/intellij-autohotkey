package de.kumpewo.autohotkey.documentation;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.PsiTreeUtil;
import de.kumpewo.autohotkey.psi.AhkClass;
import de.kumpewo.autohotkey.psi.AhkFunc;
import de.kumpewo.autohotkey.psi.AhkParameter;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Doc comment of a function, method or class (Z.22): a block comment {@code /** ... *}{@code /} directly before the
 * definition (only whitespace and at most one line break in between). The leading {@code *} of each line is removed;
 * the text before the first tag is the description (Markdown). Tags at the start of a line:
 * {@code @param [{Type|Type}] name text}, {@code @returns [{Type}] text} (also {@code @return}), {@code @throws text},
 * {@code @example} (code, the following lines as written), {@code @see name|url [text]}, {@code @deprecated [text]}.
 * A tag continues on the following lines up to the next tag; other words with {@code @} are normal text.
 */
public final class AhkDocComment {

    /** A type in {@code {...}} with its absolute range in the file (for the warning "Unknown class."). */
    public record Type(@NotNull String name, @NotNull TextRange range) {
    }

    /** {@code @param}: name (without {@code &}, {@code *}, {@code ?}), types, description and range of the name. */
    public record Param(@NotNull String name, @NotNull List<Type> types, @NotNull String text,
                        @NotNull TextRange nameRange) {
    }

    /** {@code @returns}: types and description. */
    public record Returns(@NotNull List<Type> types, @NotNull String text) {
    }

    private static final Pattern TAG = Pattern.compile("^@([A-Za-z]+)(?=\\s|$)");
    private static final Pattern TYPES = Pattern.compile("^\\s*\\{([^}]*)}");
    private static final Pattern PARAM_NAME = Pattern.compile("^\\s*[&]?([A-Za-z0-9_\\x{80}-\\x{FFFF}]+)[*?]?");

    private final String description;
    private final List<Param> params;
    private final @Nullable Returns returns;
    private final List<String> throwsList;
    private final List<String> examples;
    private final List<String> sees;
    private final @Nullable String deprecated;

    private AhkDocComment(String description, List<Param> params, @Nullable Returns returns, List<String> throwsList,
                          List<String> examples, List<String> sees, @Nullable String deprecated) {
        this.description = description;
        this.params = params;
        this.returns = returns;
        this.throwsList = throwsList;
        this.examples = examples;
        this.sees = sees;
        this.deprecated = deprecated;
    }

    /** Description before the first tag (Markdown), may be empty. */
    public @NotNull String description() {
        return description;
    }

    /** The {@code @param} tags in their order. */
    public @NotNull List<Param> params() {
        return params;
    }

    /** {@code @param} for this name (case-insensitive), or {@code null}. */
    public @Nullable Param param(@NotNull String name) {
        for (Param param : params) {
            if (param.name().equalsIgnoreCase(name)) return param;
        }
        return null;
    }

    /** {@code @returns}, or {@code null}. */
    public @Nullable Returns returns() {
        return returns;
    }

    /** Texts of the {@code @throws} tags. */
    public @NotNull List<String> throwsList() {
        return throwsList;
    }

    /** Code of the {@code @example} tags. */
    public @NotNull List<String> examples() {
        return examples;
    }

    /** Texts of the {@code @see} tags. */
    public @NotNull List<String> sees() {
        return sees;
    }

    /** Text of {@code @deprecated} ("" without text), {@code null} if not deprecated. */
    public @Nullable String deprecated() {
        return deprecated;
    }

    /** Doc comment of a function, method or class (cached until the next PSI change), {@code null} = none. */
    public static @Nullable AhkDocComment of(@Nullable PsiElement definition) {
        if (!(definition instanceof AhkFunc) && !(definition instanceof AhkClass)) return null;
        return CachedValuesManager.getCachedValue(definition, () -> CachedValueProvider.Result.create(
                Optional.ofNullable(parse(definition)), PsiModificationTracker.getInstance(definition.getProject())))
                .orElse(null);
    }

    /** Parameter names of the function (as written, without {@code &}), for the check of {@code @param}. */
    public static @NotNull List<String> parameterNames(@NotNull AhkFunc func) {
        List<String> result = new ArrayList<>();
        for (AhkParameter parameter : PsiTreeUtil.findChildrenOfType(func.getParameterList(), AhkParameter.class)) {
            var id = parameter.getNode().findChildByType(AhkTypes.IDENTIFIER);
            if (id != null) result.add(id.getText());
        }
        return result;
    }

    /**
     * The function, method or class documented by the comment whose last token ({@code *}{@code /}) is
     * {@code commentEnd}, or {@code null} (no definition directly after it).
     */
    public static @Nullable PsiElement documentedDefinition(@NotNull PsiElement commentEnd) {
        PsiElement next = PsiTreeUtil.nextLeaf(commentEnd);
        while (next != null && isSpace(next)) next = PsiTreeUtil.nextLeaf(next);
        PsiElement definition = PsiTreeUtil.getParentOfType(next, AhkFunc.class, AhkClass.class);
        if (definition == null || PsiTreeUtil.prevLeaf(definition) == null) return null;
        Source source = source(definition);
        return source != null && source.offset() + source.text().length() == commentEnd.getTextRange().getEndOffset()
                ? definition : null;
    }

    /** The comment tokens directly before the definition as one text with its start offset, or {@code null}. */
    private record Source(String text, int offset) {
    }

    /** Finds and parses the comment. */
    private static @Nullable AhkDocComment parse(PsiElement definition) {
        Source source = source(definition);
        return source == null ? null : parse(source.text(), source.offset());
    }

    /**
     * The block comment before the definition: skips whitespace and line breaks (at most one) backwards, then
     * collects the consecutive BLOCK_COMMENT tokens (the lexer splits a block comment into several). Must start
     * with {@code /**} and end with {@code *}{@code /}.
     */
    private static @Nullable Source source(PsiElement definition) {
        PsiElement leaf = PsiTreeUtil.prevLeaf(definition);
        int lineBreaks = 0;
        while (leaf != null && isSpace(leaf)) {
            lineBreaks += count(leaf.getText(), '\n');
            if (lineBreaks > 1) return null;
            leaf = PsiTreeUtil.prevLeaf(leaf);
        }
        if (leaf == null || leaf.getNode().getElementType() != AhkTypes.BLOCK_COMMENT
                || !leaf.getText().endsWith("*/")) return null;
        PsiElement first = leaf;
        StringBuilder text = new StringBuilder(leaf.getText());
        while (!first.getText().stripLeading().startsWith("/*")) {
            PsiElement prev = PsiTreeUtil.prevLeaf(first);
            if (prev == null || prev.getNode().getElementType() != AhkTypes.BLOCK_COMMENT) return null;
            text.insert(0, prev.getText());
            first = prev;
        }
        int start = text.indexOf("/**");
        if (start < 0 || !text.substring(0, start).isBlank() || text.length() < start + 5) return null;
        return new Source(text.toString(), first.getTextRange().getStartOffset());
    }

    /** Whitespace or line break between the comment and the definition. */
    private static boolean isSpace(PsiElement leaf) {
        var type = leaf.getNode().getElementType();
        return type == com.intellij.psi.TokenType.WHITE_SPACE || type == AhkTypes.CRLF;
    }

    /** Number of occurrences of the character. */
    private static int count(String text, char c) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == c) n++;
        return n;
    }

    /** One line of the comment without the leading {@code *}: text and absolute offset of its start. */
    private record Line(String text, int offset) {
    }

    /** Parses the whole comment text ({@code /** ... *}{@code /}); {@code offset} = start in the file. */
    static @NotNull AhkDocComment parse(@NotNull String text, int offset) {
        int bodyStart = text.indexOf("/**") + 3;
        int bodyEnd = text.lastIndexOf("*/");
        if (bodyEnd < bodyStart) bodyEnd = bodyStart;
        List<Line> lines = new ArrayList<>();
        int lineStart = bodyStart;
        while (lineStart <= bodyEnd) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0 || lineEnd > bodyEnd) lineEnd = bodyEnd;
            lines.add(strip(text.substring(lineStart, lineEnd), offset + lineStart));
            lineStart = lineEnd + 1;
        }
        StringBuilder description = new StringBuilder();
        List<Param> params = new ArrayList<>();
        Returns returns = null;
        List<String> throwsList = new ArrayList<>();
        List<String> examples = new ArrayList<>();
        List<String> sees = new ArrayList<>();
        String deprecated = null;
        int i = 0;
        while (i < lines.size() && tag(lines.get(i)) == null) {
            description.append(lines.get(i).text()).append('\n');
            i++;
        }
        while (i < lines.size()) {
            Line head = lines.get(i);
            String tag = tag(head);
            // "@tag" plus one separating whitespace character
            int restStart = Math.min(head.text().length(), 1 + tag.length() + 1);
            Line first = new Line(head.text().substring(restStart), head.offset() + restStart);
            List<Line> block = new ArrayList<>();
            i++;
            while (i < lines.size() && (tag(lines.get(i)) == null)) block.add(lines.get(i++));
            String more = join(block);
            switch (tag.toLowerCase(Locale.ROOT)) {
                case "param" -> {
                    Line rest = first;
                    List<Type> types = new ArrayList<>();
                    Matcher t = TYPES.matcher(rest.text());
                    if (t.find()) {
                        types = types(t.group(1), rest.offset() + t.start(1));
                        rest = new Line(rest.text().substring(t.end()), rest.offset() + t.end());
                    }
                    Matcher n = PARAM_NAME.matcher(rest.text());
                    if (!n.find()) break;
                    TextRange range = TextRange.create(rest.offset() + n.start(1), rest.offset() + n.end(1));
                    params.add(new Param(n.group(1), types, joinText(rest.text().substring(n.end()), more), range));
                }
                case "returns", "return" -> {
                    List<Type> types = new ArrayList<>();
                    String rest = first.text();
                    Matcher t = TYPES.matcher(rest);
                    if (t.find()) {
                        types = types(t.group(1), first.offset() + t.start(1));
                        rest = rest.substring(t.end());
                    }
                    returns = new Returns(types, joinText(rest, more));
                }
                case "throws", "throw" -> throwsList.add(joinText(first.text(), more));
                case "example" -> examples.add(trimBlankLines(first.text().isBlank() ? more : first.text() + "\n" + more));
                case "see" -> sees.add(joinText(first.text(), more));
                case "deprecated" -> deprecated = joinText(first.text(), more);
                default -> {
                    // unknown tag: normal text of the description
                    description.append(head.text()).append('\n');
                    for (Line line : block) description.append(line.text()).append('\n');
                }
            }
        }
        return new AhkDocComment(description.toString().strip(), List.copyOf(params), returns,
                List.copyOf(throwsList), List.copyOf(examples), List.copyOf(sees), deprecated);
    }

    /** Removes leading whitespace, one {@code *} and one space after it (indentation after that is kept). */
    private static Line strip(String line, int offset) {
        if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
        if (i < line.length() && line.charAt(i) == '*' && !line.startsWith("*/", i)) {
            i++;
            if (i < line.length() && line.charAt(i) == ' ') i++;
        } else if (line.isBlank()) {
            i = line.length();
        } else {
            i = 0;
            while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
        }
        return new Line(line.substring(i), offset + i);
    }

    /** The tag at the start of the line ({@code param}), or {@code null}. */
    private static @Nullable String tag(Line line) {
        Matcher m = TAG.matcher(line.text());
        return m.find() ? m.group(1) : null;
    }

    /** Types of {@code {A|B}}: names with absolute ranges, empty names left out. */
    private static List<Type> types(String text, int offset) {
        List<Type> result = new ArrayList<>();
        int pos = 0;
        for (String part : text.split("\\|", -1)) {
            String name = part.strip();
            if (!name.isEmpty()) {
                int start = offset + pos + part.indexOf(name);
                result.add(new Type(name, TextRange.create(start, start + name.length())));
            }
            pos += part.length() + 1;
        }
        return result;
    }

    /** The lines of a block as text. */
    private static String join(List<Line> lines) {
        StringBuilder result = new StringBuilder();
        for (Line line : lines) result.append(line.text()).append('\n');
        return result.toString();
    }

    /**
     * Text of a tag: the first line and the following lines, each without indentation (an indented continuation line
     * would be a code block in Markdown).
     */
    private static String joinText(String first, String more) {
        StringBuilder result = new StringBuilder(first.strip());
        for (String line : more.split("\n")) result.append('\n').append(line.strip());
        return result.toString().strip();
    }

    /** Code without empty lines at the start and end (indentation is kept). */
    private static String trimBlankLines(String code) {
        String[] lines = code.split("\n", -1);
        int from = 0;
        int to = lines.length;
        while (from < to && lines[from].isBlank()) from++;
        while (to > from && lines[to - 1].isBlank()) to--;
        return String.join("\n", java.util.Arrays.asList(lines).subList(from, to));
    }
}
