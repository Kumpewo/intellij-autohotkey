package de.kumpewo.autohotkey.debug.dbgp;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * A command from the IDE to the engine: {@code name -i id [-x value …] [-- base64data]} followed by NUL. The
 * transaction id is added when the command is sent ({@link DbgpConnection#send}). A value that is empty or contains
 * whitespace is enclosed in double quotes, with backslash and double quote escaped by a backslash; other values are
 * sent as they are (the engine accepts e.g. {@code m["b"]} unquoted). Data is sent Base64-encoded (UTF-8).
 */
public final class DbgpCommand {

    private final String name;
    private final List<String> arguments = new ArrayList<>();
    private @Nullable String data;

    private DbgpCommand(@NotNull String name) {
        if (name.isEmpty() || !name.chars().allMatch(c -> c >= 'a' && c <= 'z' || c == '_')) {
            throw new IllegalArgumentException("Invalid DBGp command: " + name);
        }
        this.name = name;
    }

    /** New command with the name, e.g. {@code breakpoint_set}. */
    public static @NotNull DbgpCommand of(@NotNull String name) {
        return new DbgpCommand(name);
    }

    /** Adds the argument {@code -flag value}; the flag is one letter other than {@code i}. */
    public @NotNull DbgpCommand arg(char flag, @NotNull Object value) {
        if (!(flag >= 'a' && flag <= 'z' || flag >= 'A' && flag <= 'Z') || flag == 'i') {
            throw new IllegalArgumentException("Invalid DBGp argument: -" + flag);
        }
        String text = value.toString();
        if (text.indexOf('\0') >= 0) throw new IllegalArgumentException("NUL in a DBGp argument");
        arguments.add("-" + flag + " " + quote(text));
        return this;
    }

    /** Sets the data after {@code --} (text, sent as Base64 of its UTF-8 bytes). */
    public @NotNull DbgpCommand data(@NotNull String text) {
        data = text;
        return this;
    }

    /** Name of the command. */
    public @NotNull String name() {
        return name;
    }

    /** The command line without the final NUL. */
    public @NotNull String toLine(int transactionId) {
        StringBuilder line = new StringBuilder(name).append(" -i ").append(transactionId);
        for (String argument : arguments) line.append(' ').append(argument);
        if (data != null) {
            line.append(" -- ").append(Base64.getEncoder().encodeToString(data.getBytes(StandardCharsets.UTF_8)));
        }
        return line.toString();
    }

    /** The bytes to send: the command line in UTF-8 and NUL. */
    public byte @NotNull [] toBytes(int transactionId) {
        byte[] line = toLine(transactionId).getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[line.length + 1];
        System.arraycopy(line, 0, bytes, 0, line.length);
        return bytes;
    }

    /** The value, in double quotes if it is empty or contains whitespace (backslash and quote escaped). */
    static @NotNull String quote(@NotNull String value) {
        boolean plain = !value.isEmpty() && value.chars().noneMatch(Character::isWhitespace);
        if (plain) return value;
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** The command line with the transaction id 0, for logs and tests. */
    @Override
    public String toString() {
        return toLine(0);
    }
}
