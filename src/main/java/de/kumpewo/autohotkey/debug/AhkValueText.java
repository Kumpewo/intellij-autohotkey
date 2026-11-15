package de.kumpewo.autohotkey.debug;

import de.kumpewo.autohotkey.debug.dbgp.DbgpProperty;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Text of values for "Set Value" and for conditions: the editor text of a value, the typed text as DBGp type and
 * data, and the truth of a value. Rules for typed text (the engine evaluates nothing itself):
 * <ul>
 *     <li>a number ({@code 42}, {@code -3}, {@code 0x1F} as decimal, {@code 1.5}) becomes {@code integer} or
 *     {@code float};</li>
 *     <li>{@code true}/{@code false} become 1/0;</li>
 *     <li>{@code "text"} or {@code 'text'} becomes a string without the quotes, with AutoHotkey's backtick escapes
 *     ({@code `n}, {@code `t}, {@code `"} …) converted;</li>
 *     <li>anything else is an error: unquoted text would be a variable name in AutoHotkey.</li>
 * </ul>
 */
public final class AhkValueText {

    /** Typed value: DBGp type ({@code integer}, {@code float}, {@code string}) and data. */
    public record Typed(@NotNull String type, @NotNull String data) {
    }

    private AhkValueText() {
    }

    /** The value as typed text, or {@code null} if the text follows none of the rules. */
    public static @Nullable Typed parse(@NotNull String text) {
        String t = text.trim();
        if (t.equalsIgnoreCase("true")) return new Typed("integer", "1");
        if (t.equalsIgnoreCase("false")) return new Typed("integer", "0");
        if (t.length() >= 2 && (t.charAt(0) == '"' || t.charAt(0) == '\'') && t.charAt(t.length() - 1) == t.charAt(0)) {
            String content = unescape(t.substring(1, t.length() - 1), t.charAt(0));
            return content == null ? null : new Typed("string", content);
        }
        String number = AhkNamePath.number(t);
        if (number == null) return null;
        return new Typed(number.matches("-?\\d+") ? "integer" : "float", number);
    }

    /**
     * Converts backtick escapes; {@code null} if a quote of the enclosing kind is not escaped or the text ends with a
     * single backtick.
     */
    static @Nullable String unescape(@NotNull String content, char quote) {
        StringBuilder out = new StringBuilder(content.length());
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == quote) return null;
            if (c != '`') {
                out.append(c);
                continue;
            }
            if (++i >= content.length()) return null;
            char e = content.charAt(i);
            switch (Character.toLowerCase(e)) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'v' -> out.append('\u000B');
                case 'a' -> out.append('\u0007');
                case 's' -> out.append(' ');
                default -> out.append(e);   // ` ; : { " ' and unknown escapes: the character itself
            }
        }
        return out.toString();
    }

    /** Initial text of the "Set Value" editor: strings quoted and escaped, numbers as they are, others empty. */
    public static @NotNull String editorText(@NotNull DbgpProperty property) {
        return switch (property.type()) {
            case "string" -> quote(property.value());
            case "integer", "float" -> property.value();
            default -> "";
        };
    }

    /** The string as an AutoHotkey string literal in double quotes. */
    static @NotNull String quote(@NotNull String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '`' -> out.append("``");
                case '"' -> out.append("`\"");
                case '\n' -> out.append("`n");
                case '\r' -> out.append("`r");
                case '\t' -> out.append("`t");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }

    /** Truth of a value as in AutoHotkey: unset, empty string, 0 and "0" are false, everything else is true. */
    public static boolean isTrue(@NotNull DbgpProperty property) {
        return switch (property.type()) {
            case "undefined" -> false;
            case "object" -> true;
            case "integer", "float" -> {
                try {
                    yield Double.parseDouble(property.value()) != 0;
                } catch (NumberFormatException e) {
                    yield !property.value().isEmpty();
                }
            }
            default -> !property.value().isEmpty() && !property.value().equals("0");
        };
    }

    /** Short text of a value for log expressions: strings as they are, objects as their class. */
    public static @NotNull String display(@NotNull DbgpProperty property) {
        return switch (property.type()) {
            case "undefined" -> "unset";
            case "object" -> property.className().isEmpty() ? "Object" : property.className();
            default -> property.value();
        };
    }
}
