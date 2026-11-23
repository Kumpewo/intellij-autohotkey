package de.kumpewo.autohotkey.install;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A file selector of {@code [SetVersion]} and {@code [AddBanner]}, written like a line of {@code .gitignore},
 * relative to the project root:
 * <ul>
 *   <li>{@code /} and {@code \} both separate folders; there is no escape character, no {@code !} and no {@code #}
 *       (the value of the line says include or exclude, {@code ;} starts a comment line of the INI).</li>
 *   <li>Without a separator (except at the end) the pattern applies at any depth: {@code *.ahk}, {@code test.ahk}.
 *       With a separator at the start or in the middle it is anchored at the project root: {@code /x.ahk},
 *       {@code lib/*.ahk}.</li>
 *   <li>A separator at the end names only folders, with everything below them: {@code scratch/} (any depth),
 *       {@code /scratch/} (only the one in the root). A pattern without it that names a folder also applies to
 *       everything below it.</li>
 *   <li>{@code *} matches any characters except a separator, {@code ?} one, {@code [a-z]} a range ({@code [!a]} or
 *       {@code [^a]} negated); {@code **} matches any number of folders: {@code **}{@code /x}, {@code a/**},
 *       {@code a/**}{@code /b}.</li>
 *   <li>Case is ignored (as on Windows).</li>
 * </ul>
 */
public final class AhkPathPattern {

    private final String text;
    private final Pattern regex;
    private final boolean folderOnly;

    private AhkPathPattern(String text, Pattern regex, boolean folderOnly) {
        this.text = text;
        this.regex = regex;
        this.folderOnly = folderOnly;
    }

    /** The pattern as written. */
    public @NotNull String text() {
        return text;
    }

    /**
     * The pattern, {@code null} if it is invalid: empty, a drive or {@code :}, a segment {@code ..} or {@code .}, or
     * an unclosed {@code [}.
     */
    public static @Nullable AhkPathPattern compile(@NotNull String written) {
        String text = written.strip().replace('\\', '/');
        if (text.isEmpty() || text.contains(":") || text.equals("/") || text.contains("//")) return null;
        boolean folderOnly = text.endsWith("/");
        if (folderOnly) text = text.substring(0, text.length() - 1);
        boolean anchored = text.startsWith("/") || text.contains("/");
        if (text.startsWith("/")) text = text.substring(1);
        if (text.isEmpty()) return null;
        for (String segment : text.split("/")) {
            if (segment.equals("..") || segment.equals(".")) return null;
        }
        StringBuilder regex = new StringBuilder(anchored ? "" : "(?:.*/)?");
        String[] segments = text.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            boolean last = i == segments.length - 1;
            if (segment.equals("**")) {
                // "**/" any number of folders (also none); a final "**" everything below
                regex.append(last ? ".*" : "(?:.*/)?");
                continue;
            }
            if (!appendSegment(regex, segment)) return null;
            if (!last) regex.append('/');
        }
        try {
            return new AhkPathPattern(written.strip(), Pattern.compile(regex.toString(),
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), folderOnly);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    /** One segment as regular expression; {@code false} if a {@code [} is not closed. */
    private static boolean appendSegment(StringBuilder regex, String segment) {
        int i = 0;
        while (i < segment.length()) {
            char c = segment.charAt(i);
            if (c == '*') {
                regex.append("[^/]*");
                while (i + 1 < segment.length() && segment.charAt(i + 1) == '*') i++;
            } else if (c == '?') {
                regex.append("[^/]");
            } else if (c == '[') {
                int end = segment.indexOf(']', i + 2);
                if (end < 0) return false;
                String set = segment.substring(i + 1, end);
                boolean negated = set.startsWith("!") || set.startsWith("^");
                if (negated) set = set.substring(1);
                regex.append(negated ? "[^/" : "[").append(set.replace("\\", "\\\\").replace("[", "\\[")).append(']');
                i = end;
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
            i++;
        }
        return true;
    }

    /**
     * Does the pattern name the file? {@code relative} is its path relative to the project root ({@code /} or
     * {@code \}). It matches if the pattern names the file itself (not for a folder pattern) or one of its folders.
     */
    public boolean matches(@NotNull String relative) {
        String path = relative.replace('\\', '/');
        while (path.startsWith("/")) path = path.substring(1);
        if (!folderOnly && regex.matcher(path).matches()) return true;
        int slash = path.indexOf('/');
        while (slash > 0) {
            if (regex.matcher(path.substring(0, slash)).matches()) return true;
            slash = path.indexOf('/', slash + 1);
        }
        return false;
    }
}
