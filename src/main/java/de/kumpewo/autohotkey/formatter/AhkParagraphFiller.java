package de.kumpewo.autohotkey.formatter;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Joins and wraps one paragraph of a comment ("Edit > Fill Paragraph"). A pure text function, used by
 * {@link AhkParagraphFillHandler}.
 * <p>
 * A paragraph ends at blank lines, at a changed indentation, at list items and tags (each starts a paragraph of its
 * own) and at lines that are never changed: separators, headings, tables, code blocks, {@code @example}, and in line
 * comments directives ({@code ;@...}), {@code ;;} and region markers. Words are never split, and a word that would
 * change the meaning at the start of a line stays at the end of the line before.
 */
final class AhkParagraphFiller {

    /** The kinds of comments. */
    enum Kind {LINE, BLOCK, DOC}

    /** The lines {@code first} to {@code last} (inclusive) are replaced by {@code text}. */
    record Result(int first, int last, @NotNull String text) {
    }

    /**
     * One line: {@code prefix} (everything before the text, including the marker of an item), {@code head} (length of
     * the marker at the end of the prefix, 0 = no item), {@code text}, {@code tail} (the end of a block comment).
     */
    private record Line(String prefix, int head, String text, String tail, boolean fixed, boolean opener) {
    }

    private static final Pattern LINE_COMMENT = Pattern.compile("^([ \\t]*;[ \\t]*)(.*)$");
    private static final Pattern OPENER = Pattern.compile("^([ \\t]*/\\*+(?!/)[ \\t]*)(.*)$");
    private static final Pattern STAR = Pattern.compile("^([ \\t]*\\*(?!/)[ \\t]*)(.*)$");
    private static final Pattern END = Pattern.compile("[ \\t]*\\*/[ \\t]*$");
    private static final Pattern LIST_HEAD = Pattern.compile("^([-*+]|\\d+[.)])\\s+(?=\\S)");
    private static final Pattern TAG_HEAD = Pattern.compile(
            "^(@param\\s+(\\{[^}]*}\\s+)?\\S+\\s+|@(?!param\\b)\\w+\\s+(\\{[^}]*}\\s+)?)(?=\\S)");
    /** Lines that are no running text (besides blank lines and lines without letters and digits). */
    private static final Pattern FIXED = Pattern.compile("^(#{1,6}\\s|\\||```|~~~).*");
    private static final Pattern FIXED_LINE_COMMENT = Pattern.compile("^([@;]|#?(end)?region\\b).*",
            Pattern.CASE_INSENSITIVE);
    /** Words that must not become the first one of a line. */
    private static final Pattern LINE_START_WORD = Pattern.compile(
            "^(@.*|;.*|\\*.*|[-+>]|\\d+[.)]|#.*|\\|.*|```.*|~~~.*|[^\\p{L}\\p{N}]+|(end)?region)$",
            Pattern.CASE_INSENSITIVE);

    private AhkParagraphFiller() {
    }

    /**
     * Fills the paragraph of one comment.
     *
     * @param lines   the lines of the comment, complete and without line breaks
     * @param caret   index of the line in the paragraph
     * @param margin  right margin
     * @param tabSize width of a tab
     * @return {@code null} if there is nothing to fill at this line
     */
    static @Nullable Result fill(@NotNull List<String> lines, int caret, @NotNull Kind kind, int margin, int tabSize) {
        List<Line> parsed = kind == Kind.LINE ? parseLineComments(lines) : parseBlock(lines, kind);
        int first = 0;
        while (first <= caret) {
            int last = first;
            while (last + 1 < parsed.size() && continues(parsed, first, last, tabSize)) last++;
            if (caret <= last) {
                return parsed.get(first).fixed ? null
                        : new Result(first, last, wrap(parsed.subList(first, last + 1), kind, margin, tabSize));
            }
            first = last + 1;
        }
        return null;
    }

    /** Does the line after {@code last} belong to the paragraph that starts at {@code first}? */
    private static boolean continues(List<Line> lines, int first, int last, int tabSize) {
        Line current = lines.get(last);
        Line next = lines.get(last + 1);
        if (current.fixed || next.fixed || next.head > 0) return false;
        if (current.text.endsWith("  ") || current.text.endsWith("\\")) return false;   // hard line break
        int column = width(next.prefix, tabSize);
        if (last == first && current.opener) return true;
        if (last == first && current.head > 0) {
            return column >= width(current.prefix.substring(0, current.prefix.length() - current.head), tabSize);
        }
        return column == width(current.prefix, tabSize);
    }

    /** The lines of a paragraph, joined and wrapped. */
    private static String wrap(List<Line> lines, Kind kind, int margin, int tabSize) {
        Line first = lines.get(0);
        Line lastLine = lines.get(lines.size() - 1);
        String following = lines.size() > 1 ? lines.get(1).prefix : following(first, kind);
        List<String> words = new ArrayList<>();
        for (Line line : lines) words.addAll(Arrays.asList(line.text.strip().split("\\s+")));
        int end = words.size() - 1;
        words.set(end, words.get(end) + lastLine.tail);   // the end of the comment stays behind the last word
        StringBuilder result = new StringBuilder(first.prefix).append(words.get(0));
        int column = width(result, tabSize);
        for (int i = 1; i < words.size(); i++) {
            String word = words.get(i);
            // "*/" at the start or end of a line would close the comment
            boolean mayBreak = !LINE_START_WORD.matcher(word).matches() && !words.get(i - 1).endsWith("*/");
            if (mayBreak && column + 1 + word.length() > margin) {
                result.append('\n').append(following).append(word);
                column = width(following, tabSize) + word.length();
            } else {
                result.append(' ').append(word);
                column += 1 + word.length();
            }
        }
        // two spaces at the end are a hard line break
        if (kind == Kind.DOC && lastLine.tail.isEmpty() && lastLine.text.endsWith("  ")) result.append("  ");
        return result.toString();
    }

    /** The prefix of a new second line of a paragraph of one line. */
    private static String following(Line line, Kind kind) {
        String prefix = line.prefix.substring(0, line.prefix.length() - line.head);
        if (line.opener) {
            String indent = prefix.substring(0, prefix.indexOf('/'));
            prefix = indent + (kind == Kind.DOC ? " * " : "   ");
        }
        return prefix + " ".repeat(line.head);
    }

    /** Lines of line comments. */
    private static List<Line> parseLineComments(List<String> lines) {
        List<Line> result = new ArrayList<>();
        for (String line : lines) {
            Matcher m = LINE_COMMENT.matcher(line);
            if (m.matches()) {
                String text = m.group(2).stripTrailing();
                result.add(line(m.group(1), text, "", FIXED_LINE_COMMENT.matcher(text).matches(), false, Kind.LINE));
            } else {
                result.add(new Line("", 0, line, "", true, false));
            }
        }
        return result;
    }

    /** Lines of a block or doc comment. */
    private static List<Line> parseBlock(List<String> lines, Kind kind) {
        boolean stars = lines.size() > 1;
        for (int i = 1; i < lines.size(); i++) {
            String line = END.matcher(lines.get(i)).replaceFirst("");
            if (!line.isBlank() && !STAR.matcher(line).matches()) stars = false;
        }
        List<Line> result = new ArrayList<>();
        boolean fence = false;
        boolean example = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String tail = "";
            if (i == lines.size() - 1) {
                Matcher end = END.matcher(line);
                if (end.find()) {
                    tail = end.group();
                    line = line.substring(0, end.start());
                }
            }
            Pattern pattern = i == 0 ? OPENER : stars ? STAR : null;
            Matcher m = pattern == null ? null : pattern.matcher(line);
            int start = m != null && m.matches() ? m.start(2) : line.length() - line.stripLeading().length();
            String prefix = line.substring(0, start);
            String text = line.substring(start);
            boolean marker = text.startsWith("```") || text.startsWith("~~~");
            if (kind == Kind.DOC && text.startsWith("@")) example = text.matches("@example\\b.*");
            // Markdown: four more spaces than the usual one are code
            boolean code = i > 0 && stars && prefix.endsWith("     ") && result.get(i - 1).fixed;
            boolean fixed = fence || marker || example || code;
            if (marker) fence = !fence;
            result.add(line(prefix, text, tail, fixed, i == 0, kind));
        }
        return result;
    }

    /** A line; recognizes items and lines that are never changed. */
    private static Line line(String prefix, String text, String tail, boolean fixed, boolean opener, Kind kind) {
        boolean plain = text.codePoints().noneMatch(Character::isLetterOrDigit);
        if (fixed || plain || FIXED.matcher(text).matches()) return new Line(prefix, 0, text, tail, true, opener);
        Matcher list = LIST_HEAD.matcher(text);
        Matcher tag = TAG_HEAD.matcher(text);
        int head = list.find() ? list.end() : kind == Kind.DOC && tag.find() ? tag.end() : 0;
        // a tag without text: the lines below are a paragraph of their own
        if (head == 0 && kind == Kind.DOC && text.startsWith("@")) return new Line(prefix, 0, text, tail, true, opener);
        return new Line(prefix + text.substring(0, head), head, text.substring(head), tail, false, opener);
    }

    /** Width with tabs expanded. */
    private static int width(CharSequence text, int tabSize) {
        int column = 0;
        for (int i = 0; i < text.length(); i++) {
            column += text.charAt(i) == '\t' ? tabSize - column % tabSize : 1;
        }
        return column;
    }
}
