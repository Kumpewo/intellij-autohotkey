package de.kumpewo.autohotkey.formatter;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formats the text of one doc comment (Z.23, tab "Doc Comments", switches in {@link AhkCodeStyleSettings}): alignment
 * of the {@code @param} lines, blank lines between the sections, wrapping at the right margin, leading stars and the
 * handling of invalid, empty, unsorted and missing {@code @param} tags. A pure text function (input: the whole
 * comment from {@code /**} to its end), used by {@link AhkDocCommentPostFormatProcessor}.
 * <p>
 * The description is Markdown: blank lines separate paragraphs (several become one), the lines of a paragraph may be
 * joined and wrapped. Never changed: {@code @example}, code blocks (fenced or indented), headings, tables, the end of a
 * line with a hard break (two spaces or a backslash). A list item is wrapped with its continuation lines below its
 * text. Words are never split (an url may go past the margin), and a word that would change the meaning at the start
 * of a line ({@code @tag}, {@code -}, {@code #}, {@code 1.} ...) stays at the end of the line before.
 */
public final class AhkDocCommentFormatter {

    /** The switches of the tab "Doc Comments" (see the fields DOC_* of {@link AhkCodeStyleSettings}). */
    public record Options(boolean alignDescriptions, boolean alignTypesAndNames, boolean indentContinuation,
                          boolean descriptionOnNewLine, boolean blankAfterDescription, boolean blankAfterParams,
                          boolean blankAfterReturns, boolean blankBeforeExample, boolean wrap, boolean keepOneLine,
                          boolean preserveLineFeeds, boolean leadingAsterisks, boolean keepInvalid, boolean keepEmpty,
                          boolean sortParams, boolean addMissing) {

        /** The switches from the code style settings. */
        public static @NotNull Options of(@NotNull AhkCodeStyleSettings s) {
            return new Options(s.DOC_ALIGN_PARAM_DESCRIPTIONS, s.DOC_ALIGN_TYPES_AND_NAMES, s.DOC_INDENT_CONTINUATION,
                    s.DOC_PARAM_DESCRIPTION_ON_NEW_LINE, s.DOC_BLANK_AFTER_DESCRIPTION, s.DOC_BLANK_AFTER_PARAMS,
                    s.DOC_BLANK_AFTER_RETURNS, s.DOC_BLANK_BEFORE_EXAMPLE, s.DOC_WRAP, s.DOC_KEEP_ONE_LINE,
                    s.DOC_PRESERVE_LINE_FEEDS, s.DOC_LEADING_ASTERISKS, s.DOC_KEEP_INVALID_TAGS, s.DOC_KEEP_EMPTY_TAGS,
                    s.DOC_SORT_PARAMS, s.DOC_ADD_MISSING_PARAMS);
        }
    }

    /** Kind of a section of the comment. */
    private enum Kind {DESCRIPTION, PARAM, RETURNS, THROWS, EXAMPLE, SEE, DEPRECATED}

    /**
     * A section: the description or one tag. {@code tag} = tag as written ({@code return}), {@code type} = text in
     * {@code {}} or null, {@code name} = parameter name as written ({@code &a}, {@code args*}), {@code lines} = the
     * text (for a tag: the rest of its first line, then the following lines).
     */
    private static final class Block {
        final Kind kind;
        final String tag;
        @Nullable String type;
        @Nullable String name;
        final List<String> lines = new ArrayList<>();

        Block(Kind kind, String tag) {
            this.kind = kind;
            this.tag = tag;
        }

        /** No text (only blank lines). */
        boolean isEmpty() {
            return lines.stream().allMatch(String::isBlank);
        }

        /** Parameter name without {@code &}, {@code *}, {@code ?}. */
        String plainName() {
            return name == null ? "" : name.replaceAll("^&|[*?]$", "");
        }
    }

    private static final Pattern TAG = Pattern.compile("^@([A-Za-z]+)(?=\\s|$)");
    private static final Pattern LIST_ITEM = Pattern.compile("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$");
    /** Words that would change the meaning of the Markdown at the start of a line. */
    private static final Pattern LINE_START_WORD = Pattern.compile("^(@.*|[-*+>]|\\d+[.)]|#+.*|\\|.*|```.*|~~~.*|=+|-+)$");

    private AhkDocCommentFormatter() {
    }

    /**
     * The formatted comment. {@code text} = the comment from {@code /**} to {@code *}{@code /}, {@code indent} = the
     * whitespace before {@code /**} in its line, {@code parameters} = the parameter names of the documented function
     * (null = no function: no check, sorting or adding of {@code @param}).
     */
    public static @NotNull String format(@NotNull String text, @NotNull String indent, int rightMargin,
                                         @Nullable List<String> parameters, @NotNull Options options) {
        int start = text.indexOf("/**");
        int end = text.lastIndexOf("*/");
        if (start < 0 || end < start + 3) return text;
        String body = text.substring(start + 3, end).replace("\r", "");
        boolean singleLine = !body.contains("\n");
        List<String> raw = new ArrayList<>();
        boolean usesStars = true;
        boolean first = true;
        boolean decided = false;
        for (String line : body.split("\n", -1)) {
            String trimmed = line.stripLeading();
            if (first) {
                raw.add(line.startsWith(" ") ? line.substring(1) : line);
            } else if (trimmed.startsWith("*")) {
                String rest = trimmed.substring(1);
                raw.add(rest.startsWith(" ") ? rest.substring(1) : rest);
                if (!decided && !rest.isBlank()) decided = true;
            } else {
                // without a star: remove at most the prefix this formatter writes (indent + 3 spaces), so that the
                // relative indentation of code stays
                int strip = 0;
                while (strip < line.length() && strip < indent.length() + 3
                        && (line.charAt(strip) == ' ' || line.charAt(strip) == '\t')) strip++;
                raw.add(line.substring(strip));
                if (!decided && !trimmed.isBlank()) {
                    usesStars = false;
                    decided = true;
                }
            }
            first = false;
        }
        List<Block> blocks = parse(raw);
        adjustParams(blocks, parameters, options);
        int width = Math.max(20, rightMargin - indent.length() - 3);
        List<String> lines = render(blocks, width, options);
        boolean stars = options.leadingAsterisks() || usesStars;
        if (options.keepOneLine() && singleLine && lines.size() == 1 && !lines.get(0).isEmpty()
                && indent.length() + 4 + lines.get(0).length() + 3 <= rightMargin) {
            return "/** " + lines.get(0) + " */";
        }
        if (lines.isEmpty()) lines.add("");
        StringBuilder result = new StringBuilder("/**");
        for (String line : lines) {
            result.append('\n').append(indent);
            if (stars) {
                result.append(line.isEmpty() ? " *" : " * " + line);
            } else if (!line.isEmpty()) {
                result.append("   ").append(line);
            }
        }
        return result.append('\n').append(indent).append(" */").toString();
    }

    /** Splits the lines (without stars) into the description and the tags; unknown tags are text. */
    private static List<Block> parse(List<String> lines) {
        List<Block> blocks = new ArrayList<>();
        Block current = new Block(Kind.DESCRIPTION, "");
        blocks.add(current);
        for (String line : lines) {
            Matcher m = TAG.matcher(line);
            Kind kind = m.find() ? kind(m.group(1)) : null;
            if (kind == null) {
                current.lines.add(line);
                continue;
            }
            current = new Block(kind, m.group(1));
            blocks.add(current);
            String rest = line.substring(m.end());
            if (kind == Kind.EXAMPLE) {
                if (!rest.isBlank()) current.lines.add(rest.strip());
                continue;
            }
            rest = rest.strip();
            if (kind == Kind.PARAM || kind == Kind.RETURNS) {
                if (rest.startsWith("{") && rest.indexOf('}') > 0) {
                    current.type = rest.substring(1, rest.indexOf('}')).strip();
                    rest = rest.substring(rest.indexOf('}') + 1).strip();
                }
            }
            if (kind == Kind.PARAM) {
                int space = indexOfSpace(rest);
                current.name = space < 0 ? rest : rest.substring(0, space);
                rest = space < 0 ? "" : rest.substring(space).strip();
                if (current.name.isEmpty()) current.name = null;
            }
            current.lines.add(rest);
        }
        // leading and trailing blank lines of every section are not text
        for (Block block : blocks) {
            if (block.kind == Kind.EXAMPLE) {
                while (!block.lines.isEmpty() && block.lines.get(block.lines.size() - 1).isBlank()) {
                    block.lines.remove(block.lines.size() - 1);
                }
                continue;
            }
            while (!block.lines.isEmpty() && block.lines.get(0).isBlank()) block.lines.remove(0);
            while (!block.lines.isEmpty() && block.lines.get(block.lines.size() - 1).isBlank()) {
                block.lines.remove(block.lines.size() - 1);
            }
        }
        if (blocks.get(0).isEmpty()) blocks.remove(0);
        return blocks;
    }

    /** Kind of a known tag, null = unknown (normal text). */
    private static @Nullable Kind kind(String tag) {
        return switch (tag.toLowerCase(Locale.ROOT)) {
            case "param" -> Kind.PARAM;
            case "returns", "return" -> Kind.RETURNS;
            case "throws", "throw" -> Kind.THROWS;
            case "example" -> Kind.EXAMPLE;
            case "see" -> Kind.SEE;
            case "deprecated" -> Kind.DEPRECATED;
            default -> null;
        };
    }

    /** Index of the first space or tab, -1 = none. */
    private static int indexOfSpace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == ' ' || text.charAt(i) == '\t') return i;
        }
        return -1;
    }

    /**
     * Removes invalid and empty {@code @param} (and empty {@code @returns}/{@code @throws}), adds missing
     * {@code @param} and sorts them, according to the options. Empty {@code @param} stay if missing ones are added
     * (otherwise every second reformat would add and remove them). A tag with a type counts as not empty.
     */
    private static void adjustParams(List<Block> blocks, @Nullable List<String> parameters, Options options) {
        if (parameters != null && !options.keepInvalid()) {
            blocks.removeIf(b -> b.kind == Kind.PARAM && parameters.stream().noneMatch(b.plainName()::equalsIgnoreCase));
        }
        if (!options.keepEmpty()) {
            blocks.removeIf(b -> b.type == null && b.isEmpty() && (b.kind == Kind.RETURNS || b.kind == Kind.THROWS
                    || b.kind == Kind.PARAM && !(options.addMissing() && parameters != null)));
        }
        if (parameters == null) return;
        if (options.addMissing()) {
            int insert = -1;
            for (int i = 0; i < blocks.size(); i++) if (blocks.get(i).kind == Kind.PARAM) insert = i + 1;
            if (insert < 0) insert = !blocks.isEmpty() && blocks.get(0).kind == Kind.DESCRIPTION ? 1 : 0;
            for (String parameter : parameters) {
                boolean documented = blocks.stream()
                        .anyMatch(b -> b.kind == Kind.PARAM && b.plainName().equalsIgnoreCase(parameter));
                if (documented) continue;
                Block block = new Block(Kind.PARAM, "param");
                block.name = parameter;
                blocks.add(insert++, block);
            }
        }
        if (options.sortParams()) {
            List<Block> params = new ArrayList<>(blocks.stream().filter(b -> b.kind == Kind.PARAM).toList());
            if (params.isEmpty()) return;
            int position = blocks.indexOf(params.get(0));
            blocks.removeAll(params);
            params.sort(Comparator.comparingInt(b -> {
                for (int i = 0; i < parameters.size(); i++) {
                    if (parameters.get(i).equalsIgnoreCase(b.plainName())) return i;
                }
                return parameters.size();
            }));
            blocks.addAll(position, params);
        }
    }

    /** The lines of the comment body (without stars), "" = blank line. */
    private static List<String> render(List<Block> blocks, int width, Options options) {
        List<Block> params = blocks.stream().filter(b -> b.kind == Kind.PARAM).toList();
        int typeWidth = 0;
        int nameWidth = 0;
        for (Block p : params) {
            typeWidth = Math.max(typeWidth, p.type == null ? 0 : p.type.length() + 2);
            nameWidth = Math.max(nameWidth, p.name == null ? 0 : p.name.length());
        }
        List<String> prefixes = new ArrayList<>();
        int prefixWidth = 0;
        for (Block p : params) {
            String type = p.type == null ? "" : "{" + p.type + "}";
            String name = p.name == null ? "" : p.name;
            String prefix = options.alignTypesAndNames()
                    ? "@" + p.tag + " " + (typeWidth > 0 ? pad(type, typeWidth) + " " : "") + pad(name, nameWidth)
                    : "@" + p.tag + " " + (type.isEmpty() ? "" : type + " ") + name;
            prefixes.add(prefix);
            prefixWidth = Math.max(prefixWidth, prefix.length());
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            if (i > 0 && blankBefore(blocks.get(i - 1), block, options)) out.add("");
            switch (block.kind) {
                case DESCRIPTION -> out.addAll(markdown(block.lines, width, options));
                case EXAMPLE -> {
                    // "@example" alone in its line, the code below it as written
                    out.add("@" + block.tag);
                    out.addAll(block.lines);
                }
                case PARAM -> {
                    String prefix = prefixes.get(params.indexOf(block));
                    String head = options.alignDescriptions() ? pad(prefix, prefixWidth + 1) + " " : prefix.stripTrailing() + " ";
                    out.addAll(tagText(head, block.lines, width, options, options.descriptionOnNewLine()));
                }
                default -> {
                    String head = "@" + block.tag + (block.type == null ? "" : " {" + block.type + "}") + " ";
                    out.addAll(tagText(head, block.lines, width, options, false));
                }
            }
        }
        return out;
    }

    /** Is there a blank line between the two sections? */
    private static boolean blankBefore(Block previous, Block next, Options options) {
        if (previous.kind == Kind.DESCRIPTION) return options.blankAfterDescription();
        if (next.kind == Kind.EXAMPLE && options.blankBeforeExample()) return true;
        if (previous.kind == Kind.PARAM && next.kind != Kind.PARAM) return options.blankAfterParams();
        return previous.kind == Kind.RETURNS && options.blankAfterReturns();
    }

    /**
     * Lines of a tag: {@code head} ({@code @param name  }) with the text after it, continuation lines below the text
     * (or indented by 4 with {@code indentContinuation}); {@code ownLine}: the text starts in the next line,
     * indented by 4. Paragraphs of the text are separated by a blank line.
     */
    private static List<String> tagText(String head, List<String> lines, int width, Options options, boolean ownLine) {
        List<String> out = new ArrayList<>();
        if (lines.stream().allMatch(String::isBlank)) {
            out.add(head.stripTrailing());
            return out;
        }
        int column = ownLine || options.indentContinuation() ? 4 : head.length();
        String continuation = " ".repeat(column);
        String firstPrefix = head;
        if (ownLine) {
            out.add(head.stripTrailing());
            firstPrefix = continuation;
        }
        boolean firstLine = true;
        for (List<String> paragraph : paragraphs(lines)) {
            if (!firstLine) out.add("");
            List<List<String>> units = options.preserveLineFeeds() || !options.wrap()
                    ? paragraph.stream().map(List::of).toList() : List.of(paragraph);
            for (List<String> unit : units) {
                String prefix = firstLine ? firstPrefix : continuation;
                List<String> wrapped = options.wrap()
                        ? wrap(words(unit), width - prefix.length(), width - continuation.length())
                        : List.of(String.join(" ", unit.stream().map(String::strip).toList()));
                for (int k = 0; k < wrapped.size(); k++) {
                    out.add((k == 0 ? prefix : continuation) + wrapped.get(k));
                }
                firstLine = false;
            }
        }
        return out;
    }

    /** The lines split at blank lines (each line stripped). */
    private static List<List<String>> paragraphs(List<String> lines) {
        List<List<String>> result = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) {
                if (!current.isEmpty()) result.add(current);
                current = new ArrayList<>();
            } else {
                current.add(line.strip());
            }
        }
        if (!current.isEmpty()) result.add(current);
        return result;
    }

    /** Lines of the Markdown description; code, headings and tables stay as written. */
    private static List<String> markdown(List<String> lines, int width, Options options) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            String trimmed = line.strip();
            if (line.isBlank()) {
                if (!out.isEmpty() && !out.get(out.size() - 1).isEmpty()) out.add("");
                i++;
            } else if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                String fence = trimmed.substring(0, 3);
                out.add(line.stripTrailing());
                i++;
                while (i < lines.size()) {
                    out.add(lines.get(i).stripTrailing());
                    if (lines.get(i).strip().startsWith(fence)) {
                        i++;
                        break;
                    }
                    i++;
                }
            } else if (isVerbatim(line) || !options.wrap()) {
                out.add(hardBreak(line) ? line : line.stripTrailing());
                i++;
            } else {
                Matcher item = LIST_ITEM.matcher(line);
                String prefix = "";
                List<String> unit = new ArrayList<>();
                if (item.matches()) {
                    prefix = item.group(1) + item.group(2) + " ";
                    unit.add(item.group(3));
                } else {
                    unit.add(line);
                }
                i++;
                boolean broken = hardBreak(line);
                while (!broken && !options.preserveLineFeeds() && i < lines.size()) {
                    String next = lines.get(i);
                    if (next.isBlank() || isVerbatim(next) || LIST_ITEM.matcher(next).matches()
                            || next.strip().startsWith("```") || next.strip().startsWith("~~~")) break;
                    unit.add(next);
                    broken = hardBreak(next);
                    i++;
                }
                String marker = broken ? hardBreakMarker(unit.get(unit.size() - 1)) : "";
                String continuation = " ".repeat(prefix.length());
                List<String> wrapped = wrap(words(unit), width - prefix.length(), width - continuation.length());
                for (int k = 0; k < wrapped.size(); k++) {
                    String text = (k == 0 ? prefix : continuation) + wrapped.get(k);
                    out.add(k == wrapped.size() - 1 ? text + marker : text);
                }
            }
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
        return out;
    }

    /** Indented code (4 spaces or a tab), heading or table row: never joined or wrapped. */
    private static boolean isVerbatim(String line) {
        String trimmed = line.strip();
        return line.startsWith("    ") || line.startsWith("\t") || trimmed.startsWith("#") || trimmed.startsWith("|");
    }

    /** Ends the line with a hard break (two spaces or a backslash)? */
    private static boolean hardBreak(String line) {
        return line.endsWith("  ") && !line.isBlank() || line.endsWith("\\");
    }

    /** The hard break at the end of the line ("  " or "\"). */
    private static String hardBreakMarker(String line) {
        return line.endsWith("\\") ? "" : "  ";
    }

    /** The words of the lines (a trailing backslash of a hard break stays at its word). */
    private static List<String> words(List<String> lines) {
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            for (String word : line.strip().split("\\s+")) if (!word.isEmpty()) result.add(word);
        }
        return result;
    }

    /**
     * Fills lines greedily: the first line has {@code firstWidth} characters, the others {@code width}. A word longer
     * than the line stays alone; a word that would change the meaning at the start of a line is kept at the end of the
     * line before.
     */
    static List<String> wrap(List<String> words, int firstWidth, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int limit = Math.max(10, firstWidth);
        for (String word : words) {
            if (line.isEmpty()) {
                line.append(word);
            } else if (line.length() + 1 + word.length() <= limit || LINE_START_WORD.matcher(word).matches()) {
                line.append(' ').append(word);
            } else {
                lines.add(line.toString());
                line = new StringBuilder(word);
                limit = Math.max(10, width);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    /** The text padded with spaces to the width. */
    private static String pad(String text, int width) {
        return text.length() >= width ? text : text + " ".repeat(width - text.length());
    }
}
