package de.kumpewo.autohotkey.debug.dbgp;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * File URIs of DBGp and Windows paths. AutoHotkey sends {@code file:///C%3A/dir%20name/%C3%A4.ahk} (colon, space and
 * every non-ASCII byte percent-encoded, UTF-8) and accepts the same form; {@link #toUri} produces it, so the URI never
 * needs quoting in a command. Network paths: {@code \\server\share\a.ahk} = {@code file://server/share/a.ahk}.
 */
public final class DbgpUris {

    private static final String PREFIX = "file://";

    private DbgpUris() {
    }

    /** URI of an absolute Windows path (backslashes or slashes). */
    public static @NotNull String toUri(@NotNull String path) {
        String normalized = path.replace('\\', '/');
        StringBuilder uri = new StringBuilder(PREFIX);
        if (normalized.startsWith("//")) {
            normalized = normalized.substring(2);   // network path: host follows "file://"
        } else {
            uri.append('/');
        }
        for (byte b : normalized.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                    || c == '-' || c == '.' || c == '_' || c == '~' || c == '/') {
                uri.append((char) c);
            } else {
                uri.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return uri.toString();
    }

    /** Windows path of a file URI (percent-decoded, backslashes), or {@code null} if it is not a file URI. */
    public static @Nullable String toPath(@NotNull String uri) {
        if (!uri.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) return null;
        String rest = decode(uri.substring(PREFIX.length()));
        if (rest == null) return null;
        String path;
        if (rest.startsWith("/")) {
            path = rest.substring(1);                       // file:///C:/dir
        } else {
            path = "//" + rest;                             // file://server/share
        }
        return path.replace('/', '\\');
    }

    /** {@link #toPath} as a {@link Path}, or {@code null}. */
    public static @Nullable Path toNioPath(@NotNull String uri) {
        String path = toPath(uri);
        if (path == null) return null;
        try {
            return Path.of(path);
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** Percent-decoding (UTF-8); {@code null} on a malformed escape. */
    private static @Nullable String decode(@NotNull String text) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '%') {
                if (i + 2 >= text.length()) return null;
                int high = Character.digit(text.charAt(i + 1), 16);
                int low = Character.digit(text.charAt(i + 2), 16);
                if (high < 0 || low < 0) return null;
                bytes.write(high << 4 | low);
                i += 2;
            } else {
                byte[] encoded = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                bytes.write(encoded, 0, encoded.length);
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
