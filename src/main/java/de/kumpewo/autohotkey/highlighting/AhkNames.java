package de.kumpewo.autohotkey.highlighting;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.psi.PsiElement;
import de.kumpewo.autohotkey.AhkResources;
import de.kumpewo.autohotkey.parser.AhkLanguageLevel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Name lists for highlighting (AhkAnnotator), completion and case adjustment, one set per language level
 * ({@link #of}, {@link #get}). The lists are text files under src/main/resources/names/ (one name per line or
 * separated by spaces/commas, ";" starts a comment, case does not matter); for v2.1 the files in names/v2.1/ hold
 * only the differences (a name is added, "-Name" removes it, see {@link AhkResources#words}).
 * <p>
 * Each file belongs to a group; the group determines the context in which a name is recognized:
 * <ul>
 *   <li>directives.txt: token DIRECTIVE_NAME (with "#")</li>
 *   <li>variables.txt, classes.txt, functions.txt: only outside of a member access</li>
 *   <li>methods.txt, properties.txt: only as a member (after the dot)</li>
 *   <li>nested-classes.txt (entries "Outer.Inner"): name after the dot if exactly the outer class is on the left
 *       (Gui.Button); takes precedence over methods/properties (Gui.Text is a class, ctrl.Text a property)</li>
 * </ul>
 * If a name is in several files of the same group, the file named first wins (order below).
 * <p>
 * Also loads members.txt (built-in class, base class and own members), used by the type declarations; it is
 * generated from the help, so names/v2.1/members.txt is a complete copy.
 */
public final class AhkNames {

    /** The loaded sets per data level (V2_0, V2_1); loaded on first use. */
    private static final Map<AhkLanguageLevel, AhkNames> SETS = Collections.synchronizedMap(new EnumMap<>(AhkLanguageLevel.class));

    /** Words directly after "Loop" that are colored as a subcommand (Loop Files/Parse/Read/Reg). */
    private static final List<String> LOOP_SUBCOMMAND_NAMES = List.of("Files", "Parse", "Read", "Reg");
    private static final Set<String> LOOP_SUBCOMMANDS = Set.of("files", "parse", "read", "reg");

    /** The language level of this set. */
    private final AhkLanguageLevel level;
    private final Map<String, TextAttributesKey> directives;
    private final Map<String, TextAttributesKey> nonMember;
    private final Map<String, TextAttributesKey> member;
    /** Keys "outer.inner" (lower case). */
    private final Map<String, TextAttributesKey> nestedClasses;
    /** Names per file in their original spelling (for completion), without duplicates (case-insensitive). */
    private final Map<String, List<String>> original;
    /** Per file: lower-case name -> original spelling (from {@link #original}). */
    private final Map<String, Map<String, String>> spelling;
    /**
     * Built-in classes from members.txt (lower-case keys, order of the file): base class and own members.
     * Inherited members are provided by {@link #instanceMembers}.
     */
    private final Map<String, BuiltinClass> classes;

    /** A built-in class from members.txt; names spelled as in the file, {@code base} is null for Any. */
    public record BuiltinClass(@NotNull String name, @Nullable String base, @NotNull List<String> statics,
                               @NotNull List<String> methods, @NotNull List<String> properties) {
    }

    /**
     * Instance member (method or property) with the class that defines it and that class's level in the chain of
     * base classes ({@code depth} 0 = the class itself, 1 = its base class ...).
     */
    public record Member(@NotNull String name, boolean method, @NotNull String owner, int depth) {
    }

    /** Loads all lists of the level. */
    private AhkNames(AhkLanguageLevel level) {
        this.level = level;
        directives = load(new String[]{"directives"}, new TextAttributesKey[]{AhkColors.AHK_DIRECTIVE});
        nonMember = load(new String[]{"variables", "classes", "functions"},
                new TextAttributesKey[]{AhkColors.AHK_BUILTIN_VARIABLE, AhkColors.AHK_BUILTIN_CLASS,
                        AhkColors.AHK_BUILTIN_FUNCTION});
        member = load(new String[]{"methods", "properties"},
                new TextAttributesKey[]{AhkColors.AHK_BUILTIN_METHOD, AhkColors.AHK_BUILTIN_PROPERTY});
        nestedClasses = load(new String[]{"nested-classes"}, new TextAttributesKey[]{AhkColors.AHK_BUILTIN_CLASS});
        original = loadOriginal("directives", "variables", "classes", "functions", "methods", "properties",
                "nested-classes", "keys");
        spelling = spellings(original);
        classes = loadClasses();
    }

    /** The names of the level ({@link AhkLanguageLevel#OTHER} = those of v2.0). */
    public static @NotNull AhkNames get(@NotNull AhkLanguageLevel level) {
        return SETS.computeIfAbsent(level.dataLevel(), AhkNames::new);
    }

    /** The names of the language level of the element's file ({@link AhkLanguageLevel#of}). */
    public static @NotNull AhkNames of(@Nullable PsiElement element) {
        return get(AhkLanguageLevel.of(element));
    }

    /** Language level of this set. */
    public @NotNull AhkLanguageLevel level() {
        return level;
    }

    /** Built-in class (also nested, "Gui.Edit"), case-insensitive; otherwise null. */
    public @Nullable BuiltinClass builtinClass(@NotNull String name) {
        return classes.get(key(name));
    }

    /** All built-in classes from members.txt in the order of the file. */
    public @NotNull Collection<BuiltinClass> builtinClasses() {
        return classes.values();
    }

    /**
     * Instance members (methods/properties, without static) of the class including its base classes (Gui.Edit,
     * Gui.Control, Object, Any). Each name only once, the derived class wins. Unknown class: empty.
     */
    public @NotNull List<Member> instanceMembers(@NotNull String className) {
        List<Member> result = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        Set<String> visited = new java.util.HashSet<>();
        int depth = 0;
        for (BuiltinClass c = builtinClass(className); c != null && visited.add(key(c.name()));
             c = c.base() == null ? null : builtinClass(c.base()), depth++) {
            for (String m : c.methods()) if (seen.add(key(m))) result.add(new Member(m, true, c.name(), depth));
            for (String p : c.properties()) if (seen.add(key(p))) result.add(new Member(p, false, c.name(), depth));
        }
        return result;
    }

    /** Color of a known directive (text with "#"), otherwise null. */
    public @Nullable TextAttributesKey directive(@NotNull String name) {
        return directives.get(key(name));
    }

    /** Built-in variable, class or function (name not after a dot), otherwise null. */
    public @Nullable TextAttributesKey nonMember(@NotNull String name) {
        return nonMember.get(key(name));
    }

    /** Built-in method or property (name after a dot), otherwise null. */
    public @Nullable TextAttributesKey member(@NotNull String name) {
        return member.get(key(name));
    }

    /** Nested built-in class "outer.inner" (Gui.Button), otherwise null. */
    public @Nullable TextAttributesKey nestedClass(@NotNull String outer, @NotNull String inner) {
        return nestedClasses.get(key(outer + "." + inner));
    }

    /** {@code Files}, {@code Parse}, {@code Read} or {@code Reg} (case-insensitive). */
    public static boolean isLoopSubcommand(@NotNull String name) {
        return LOOP_SUBCOMMANDS.contains(key(name));
    }

    /**
     * All names of a list (file name without ".txt", e.g. "variables") spelled as in the file.
     * Used by completion; highlighting keeps working with the lower-case keys.
     */
    public @NotNull List<String> names(@NotNull String file) {
        List<String> names = original.get(file);
        if (names == null) throw new IllegalArgumentException("Unknown name list: " + file);
        return names;
    }

    /**
     * Spelling of a name in the first of the lists (file name without ".txt") that contains it; otherwise null.
     * Used for adjusting the case when formatting.
     */
    public @Nullable String spelling(@NotNull String name, @NotNull String... files) {
        for (String file : files) {
            Map<String, String> spellings = spelling.get(file);
            if (spellings == null) throw new IllegalArgumentException("Unknown name list: " + file);
            String s = spellings.get(key(name));
            if (s != null) return s;
        }
        return null;
    }

    /** Inner names of the nested classes of {@code outer} (Gui: Button, Edit ...), original spelling. */
    public @NotNull List<String> nestedClassesOf(@NotNull String outer) {
        String prefix = key(outer) + ".";
        List<String> result = new ArrayList<>();
        for (String name : names("nested-classes")) {
            if (key(name).startsWith(prefix)) result.add(name.substring(prefix.length()));
        }
        return result;
    }

    /** The loop subcommands in their display spelling ({@code Files Parse Read Reg}). */
    public static @NotNull List<String> loopSubcommands() {
        return LOOP_SUBCOMMAND_NAMES;
    }

    /** Builds the lower-case-to-spelling table for each list. */
    private static Map<String, Map<String, String>> spellings(Map<String, List<String>> original) {
        Map<String, Map<String, String>> map = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : original.entrySet()) {
            Map<String, String> byKey = new java.util.HashMap<>();
            for (String name : e.getValue()) byKey.put(key(name), name);
            map.put(e.getKey(), Collections.unmodifiableMap(byKey));
        }
        return Collections.unmodifiableMap(map);
    }

    /** Reads the given lists in their original spelling; the first spelling of a name wins. */
    private Map<String, List<String>> loadOriginal(String... files) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (String file : files) {
            Map<String, String> unique = new LinkedHashMap<>();
            for (String name : readNames(file)) unique.putIfAbsent(key(name), name);
            map.put(file, List.copyOf(unique.values()));
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * Reads members.txt of the level (complete file, see the class comment): "Class : Base" (Any without a base),
     * followed by "Class static: ...", "Class methods: ...", "Class properties: ...".
     */
    private Map<String, BuiltinClass> loadClasses() {
        String path = AhkResources.complete(level, "/names", "members.txt");
        String text = AhkResources.read(path);
        if (text == null) throw new IllegalStateException("Name list missing: " + path);
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, String> bases = new LinkedHashMap<>();
        Map<String, Map<String, List<String>>> members = new LinkedHashMap<>();
        Pattern classLine = Pattern.compile("(\\S+)\\s*:\\s*(\\S*)");
        Pattern memberLine = Pattern.compile("(\\S+)\\s+(static|methods|properties):\\s*(.*)");
        for (String line : AhkResources.splitLines(text)) {
            Matcher m = memberLine.matcher(line);
            if (m.matches()) {
                List<String> list = members.computeIfAbsent(key(m.group(1)), k -> new LinkedHashMap<>())
                        .computeIfAbsent(m.group(2), k -> new ArrayList<>());
                for (String name : m.group(3).trim().split("\\s+")) if (!name.isEmpty()) list.add(name);
                continue;
            }
            m = classLine.matcher(line);
            if (!m.matches()) throw new IllegalStateException("Unreadable line in " + path + ": " + line);
            names.put(key(m.group(1)), m.group(1));
            bases.put(key(m.group(1)), m.group(2).isEmpty() ? null : m.group(2));
        }
        Map<String, BuiltinClass> map = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : names.entrySet()) {
            Map<String, List<String>> own = members.getOrDefault(e.getKey(), Map.of());
            map.put(e.getKey(), new BuiltinClass(e.getValue(), bases.get(e.getKey()),
                    List.copyOf(own.getOrDefault("static", List.of())),
                    List.copyOf(own.getOrDefault("methods", List.of())),
                    List.copyOf(own.getOrDefault("properties", List.of()))));
        }
        return Collections.unmodifiableMap(map);
    }

    /** Lookup key: the name in lower case. */
    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /** Reads the files of a group into one table name -> color; the first file wins. */
    private Map<String, TextAttributesKey> load(String[] files, TextAttributesKey[] colors) {
        Map<String, TextAttributesKey> map = new LinkedHashMap<>();
        for (int i = 0; i < files.length; i++) {
            for (String name : readNames(files[i])) {
                map.putIfAbsent(key(name), colors[i]);
            }
        }
        return Collections.unmodifiableMap(map);
    }

    /** Reads a name list of the level (/names/&lt;file&gt;.txt plus the difference); a missing file is an error. */
    @NotNull List<String> readNames(String file) {
        return AhkResources.words(level, "/names", file + ".txt");
    }
}
