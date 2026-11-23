package de.kumpewo.autohotkey.types;

import de.kumpewo.autohotkey.AhkBundle;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.PsiTreeUtil;
import de.kumpewo.autohotkey.highlighting.AhkNames;
import de.kumpewo.autohotkey.include.AhkInclude;
import de.kumpewo.autohotkey.psi.AhkClass;
import de.kumpewo.autohotkey.psi.AhkClassProperty;
import de.kumpewo.autohotkey.psi.AhkDefinitions;
import de.kumpewo.autohotkey.psi.AhkFunc;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Type declaration and private members via comment pragmas. There is no symbol table; the pragmas apply to the whole
 * file, wherever they are. Across #Include they apply only from a directly included file named {@code pragmas.ahk}
 * (one place for the pragmas of a whole project); otherwise only in their own file.
 * <ul>
 *   <li>{@code ;@declare-type Pattern as Class, ...}: pattern = name, {@code prefix*} or {@code *suffix}
 *       (wildcard = any characters, also none; prefix/suffix not empty). Class = built-in class from members.txt
 *       (also {@code Gui.Edit}) or an own class from the file or the files included directly with #Include (also
 *       nested, {@code Outer.Inner}). Built-in takes precedence (AutoHotkey does not allow an own class with the
 *       name of a built-in class).</li>
 *   <li>{@code ;@declare-auto-type ClassPattern, ...}: at most one wildcard anywhere in the class name
 *       ({@code Gui*}, {@code *Box}, {@code Gui.*Box}, {@code *} = all); every matching class K becomes {@code *K as K}, for {@code Gui.X} it becomes
 *       {@code *X as Gui.X}. Only built-in classes, not own ones.</li>
 *   <li>{@code ;@declare-private Pattern, ...}: member names (methods, properties, fields) shown in the color for
 *       private members ({@link #privacy}).</li>
 * </ul>
 * A name can belong to several classes. It is "explicit" if at least one pattern without a wildcard matches
 * exactly; only then does the declaration take precedence over a built-in name in highlighting.
 */
public final class AhkTypeDeclarations {

    public static final String DECLARE_TYPE = ";@declare-type";
    public static final String DECLARE_AUTO_TYPE = ";@declare-auto-type";
    public static final String DECLARE_PRIVATE = ";@declare-private";
    /** File whose pragmas also apply in the files that include it directly (one place for a whole project). */
    public static final String PRAGMA_FILE = "pragmas.ahk";

    /** How a member name is declared private: not, only through a pattern with a wildcard, or by its exact name. */
    public enum Privacy {NONE, WILDCARD, EXPLICIT}

    // Messages in AhkBundle (types.*); constants = keys
    private static final String UNKNOWN_CLASS = "types.unknown.class";
    private static final String INVALID_PATTERN = "types.invalid.pattern";
    private static final String AS_EXPECTED = "types.as.expected";
    private static final String CLASS_EXPECTED = "types.class.expected";
    private static final String COMMA_EXPECTED = "types.comma.expected";

    /**
     * Name with at most one wildcard at the start or end (group 1/3 = wildcard, group 2 = fixed part). Name characters
     * as in AutoHotkey: letters, digits, underscore and every non-ASCII character (e.g. a prefix like "§").
     */
    private static final Pattern NAME_PATTERN = Pattern.compile("(\\*?)([A-Za-z0-9_\\x{80}-\\x{FFFF}]+)(\\*?)");
    /**
     * Class pattern: name with a dot (Gui.Edit) and at most one wildcard anywhere (Gui*, *Box, Gui.*Box, "*" alone).
     * With a wildcard: group 1 = part before, group 2 = part after (each may be empty); without: group 3 = name.
     */
    private static final Pattern CLASS_PATTERN =
            Pattern.compile("([\\p{L}\\p{N}_.]*)\\*([\\p{L}\\p{N}_.]*)|([\\p{L}\\p{N}_.]+)");
    private static final Pattern WORD = Pattern.compile("\\S+");

    /** Kind of a pattern: exact name, prefix* or *suffix. */
    private enum Kind {EXACT, PREFIX, SUFFIX}

    /**
     * A pattern with its class; {@code text} in lower case, without the wildcard. {@code className} null = a pattern of
     * {@code ;@declare-private}.
     */
    private record Entry(Kind kind, String text, @Nullable String className) {
        /** Does the pattern match the (lower-case) name? */
        boolean matches(String lowerName) {
            return switch (kind) {
                case EXACT -> lowerName.equals(text);
                case PREFIX -> lowerName.startsWith(text);
                case SUFFIX -> lowerName.endsWith(text);
            };
        }
    }

    /** Error in a pragma, range absolute in the file. */
    public record Problem(@NotNull TextRange range, @NotNull String key) {
        /** Text of the message (English, AhkBundle). */
        public @NotNull String message() {
            return AhkBundle.message(key);
        }
    }

    /**
     * Declared classes of a name (spelling from members.txt or the definition, nested with a dot; without
     * duplicates) and whether it is declared explicitly.
     */
    public record Declared(@NotNull List<String> classes, boolean explicit) {
    }

    /** Result of parsing one comment. */
    private record Parsed(List<Entry> entries, List<Problem> problems) {
    }

    /**
     * One level of the class hierarchy: exactly one of {@code own} (own class) and {@code builtin} is set.
     * {@code name} = name with the outer classes ({@code Outer.Inner}, {@code Gui.Edit}).
     */
    public record Level(@NotNull String name, @Nullable AhkClass own, @Nullable AhkNames.BuiltinClass builtin) {
    }

    private AhkTypeDeclarations() {
    }

    /**
     * Declaration of the name at this place: the pragmas of the file ({@link #lookup(PsiFile, String)}) plus the
     * types of {@code @param {Type} name} in the doc comment of the enclosing function (the innermost function
     * that has a parameter with this name decides, only within it). A type from a doc comment counts as explicit.
     */
    public static @Nullable Declared lookup(@NotNull PsiElement context, @NotNull String name) {
        PsiFile file = context.getContainingFile();
        Declared fromFile = file == null ? null : lookup(file, name);
        List<String> fromDoc = file == null ? List.of() : parameterTypes(context, name, file);
        if (fromDoc.isEmpty()) return fromFile;
        Set<String> classes = new LinkedHashSet<>(fromDoc);
        if (fromFile != null) classes.addAll(fromFile.classes());
        return new Declared(List.copyOf(classes), true);
    }

    /**
     * Classes of {@code @param {A|B} name} of the innermost enclosing function with a parameter of this name (spelling
     * as resolved; unknown classes are left out); empty if that function has no such tag.
     */
    private static List<String> parameterTypes(PsiElement context, String name, PsiFile file) {
        for (AhkFunc func = PsiTreeUtil.getParentOfType(context, AhkFunc.class); func != null;
             func = PsiTreeUtil.getParentOfType(func, AhkFunc.class)) {
            boolean isParameter = de.kumpewo.autohotkey.documentation.AhkDocComment.parameterNames(func).stream()
                    .anyMatch(name::equalsIgnoreCase);
            if (!isParameter) continue;
            var doc = de.kumpewo.autohotkey.documentation.AhkDocComment.of(func);
            var param = doc == null ? null : doc.param(name);
            if (param == null) return List.of();
            List<String> result = new ArrayList<>();
            for (var type : param.types()) {
                Level level = resolve(file, type.name());
                if (level != null) result.add(level.name());
            }
            return result;
        }
        return List.of();
    }

    /** Is the class name known (built-in or own class of the file and its direct #Include files)? */
    public static boolean isKnownClass(@NotNull PsiFile file, @NotNull String name) {
        return resolve(file, name) != null;
    }

    /** Declaration of the name in its file, null = not declared. Case-insensitive. */
    public static @Nullable Declared lookup(@NotNull PsiFile file, @NotNull String name) {
        List<Entry> entries = entries(file);
        if (entries.isEmpty()) return null;
        String lower = name.toLowerCase(Locale.ROOT);
        Set<String> classes = new LinkedHashSet<>();
        boolean explicit = false;
        for (Entry entry : entries) {
            if (entry.className() == null || !entry.matches(lower)) continue;
            classes.add(entry.className());
            explicit |= entry.kind() == Kind.EXACT;
        }
        return classes.isEmpty() ? null : new Declared(List.copyOf(classes), explicit);
    }

    /**
     * Is the member name declared private with {@code ;@declare-private} (in the file or a directly included
     * {@code pragmas.ahk})? {@link Privacy#EXPLICIT} if a pattern without wildcard matches. Case-insensitive.
     */
    public static @NotNull Privacy privacy(@NotNull PsiFile file, @NotNull String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        Privacy result = Privacy.NONE;
        for (Entry entry : entries(file)) {
            if (entry.className() != null || !entry.matches(lower)) continue;
            if (entry.kind() == Kind.EXACT) return Privacy.EXPLICIT;
            result = Privacy.WILDCARD;
        }
        return result;
    }

    /**
     * Instance members of all classes including base classes (own and built-in), each name once, in the order of
     * the classes; within a chain the derived class wins. Own classes: methods, properties and fields without
     * {@code static}, no nested classes. {@code depth} = level in the chain (0 = declared class); if a name is in
     * several classes, the smallest level counts.
     */
    public static @NotNull List<AhkNames.Member> members(@NotNull PsiFile file, @NotNull Declared declared) {
        Map<String, AhkNames.Member> result = new LinkedHashMap<>();
        for (String className : declared.classes()) {
            int depth = 0;
            for (Level level : hierarchy(file, className)) {
                AhkNames.BuiltinClass builtin = level.builtin();
                AhkClass own = level.own();
                if (builtin != null) {
                    for (String m : builtin.methods()) add(result, new AhkNames.Member(m, true, level.name(), depth));
                    for (String p : builtin.properties()) add(result, new AhkNames.Member(p, false, level.name(), depth));
                } else if (own != null) {
                    for (AhkFunc func : ownMethods(own)) {
                        String name = func.getName();
                        if (name != null) add(result, new AhkNames.Member(name, true, level.name(), depth));
                    }
                    for (AhkClassProperty property : PsiTreeUtil.findChildrenOfType(own, AhkClassProperty.class)) {
                        ASTNode id = property.getNode().findChildByType(AhkTypes.IDENTIFIER);
                        if (id == null || AhkDefinitions.ownerClass(property) != own || isStatic(property)) continue;
                        add(result, new AhkNames.Member(id.getText(), false, level.name(), depth));
                    }
                    for (AhkDefinitions.Field field : AhkDefinitions.fields(own)) {   // instance fields (count := 0)
                        if (!field.isStatic()) add(result, new AhkNames.Member(field.name(), false, level.name(), depth));
                    }
                }
                depth++;
            }
        }
        return new ArrayList<>(result.values());
    }

    /** The first entry per name wins, unless a later one is on a smaller level. */
    private static void add(Map<String, AhkNames.Member> result, AhkNames.Member member) {
        String key = member.name().toLowerCase(Locale.ROOT);
        AhkNames.Member old = result.get(key);
        if (old == null || member.depth() < old.depth()) result.put(key, member);
    }

    /** Methods directly in the own class without {@code static} (instance methods). */
    public static @NotNull List<AhkFunc> ownMethods(@NotNull AhkClass cls) {
        List<AhkFunc> result = new ArrayList<>();
        for (AhkFunc func : PsiTreeUtil.findChildrenOfType(cls, AhkFunc.class)) {
            if (AhkDefinitions.ownerClass(func) == cls && !isStatic(func)) result.add(func);
        }
        return result;
    }

    /** Definition with {@code static} before it (token KEYWORD_DECL at the start). */
    private static boolean isStatic(PsiElement definition) {
        ASTNode first = definition.getNode().getFirstChildNode();
        return first != null && first.getElementType() == AhkTypes.KEYWORD_DECL
                && first.getText().equalsIgnoreCase("static");
    }

    /** Is at least one of the classes an own class (not a built-in class at the language level of the file)? */
    public static boolean hasOwnClass(@NotNull PsiFile file, @NotNull Declared declared) {
        AhkNames names = AhkNames.of(file);
        for (String className : declared.classes()) {
            if (names.builtinClass(className) == null) return true;
        }
        return false;
    }

    /**
     * The class and its base classes via {@code extends}, the derived one first. Own base classes are searched in
     * the file and its direct #Include files, otherwise in the file of the class and its direct #Include files. An
     * own class without {@code extends} inherits from {@code Object}. Unknown class: empty; the chain ends at an
     * unknown base class.
     */
    public static @NotNull List<Level> hierarchy(@NotNull PsiFile file, @NotNull String className) {
        List<Level> result = new ArrayList<>();
        Set<String> visited = new java.util.HashSet<>();
        Level level = resolve(file, className);
        while (level != null && visited.add(level.name().toLowerCase(Locale.ROOT))) {
            result.add(level);
            AhkNames.BuiltinClass builtin = level.builtin();
            AhkClass own = level.own();
            if (builtin != null) {
                level = builtin.base() == null ? null : resolve(file, builtin.base());
            } else if (own != null) {
                String base = extendsPath(own);
                if (base == null) base = "Object";   // without extends a class inherits from Object
                Level next = resolve(file, base);
                PsiFile definedIn = own.getContainingFile();
                level = next != null || definedIn == null || definedIn == file ? next : resolve(definedIn, base);
            } else {
                break;
            }
        }
        return result;
    }

    /** Built-in class (takes precedence) or own class from the file and its direct #Include files. */
    private static @Nullable Level resolve(PsiFile file, String name) {
        AhkNames.BuiltinClass builtin = AhkNames.of(file).builtinClass(name);
        if (builtin != null) return new Level(builtin.name(), null, builtin);
        AhkClass own = ownClass(file, name);
        return own == null ? null : new Level(qualifiedName(own), own, null);
    }

    /**
     * Own class for the name ({@code K} at the top level, {@code Outer.Inner} nested), case-insensitive; searched
     * in the file, then in the direct #Include files (the first one wins).
     */
    public static @Nullable AhkClass ownClass(@NotNull PsiFile file, @NotNull String name) {
        String[] parts = name.split("\\.", -1);
        List<PsiFile> files = new ArrayList<>();
        files.add(file);
        files.addAll(AhkInclude.includedFiles(file));
        for (PsiFile source : files) {
            for (AhkClass top : PsiTreeUtil.findChildrenOfType(source, AhkClass.class)) {
                if (PsiTreeUtil.getParentOfType(top, AhkClass.class, AhkFunc.class) != null) continue;
                if (!parts[0].equalsIgnoreCase(top.getName())) continue;
                AhkClass found = top;
                for (int i = 1; i < parts.length && found != null; i++) found = nestedClass(found, parts[i]);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Class directly nested in {@code outer} with this name, or null. */
    private static @Nullable AhkClass nestedClass(AhkClass outer, String name) {
        for (AhkClass inner : PsiTreeUtil.findChildrenOfType(outer, AhkClass.class)) {
            if (AhkDefinitions.ownerClass(inner) == outer && name.equalsIgnoreCase(inner.getName())) return inner;
        }
        return null;
    }

    /** Name with the outer classes in the spelling of the definition ({@code Outer.Inner}). */
    public static @NotNull String qualifiedName(@NotNull AhkClass cls) {
        StringBuilder name = new StringBuilder(String.valueOf(cls.getName()));
        for (AhkClass outer = AhkDefinitions.ownerClass(cls); outer != null; outer = AhkDefinitions.ownerClass(outer)) {
            name.insert(0, outer.getName() + ".");
        }
        return name.toString();
    }

    /** Text of the base class after {@code extends} ({@code A.B}), null = none. */
    private static @Nullable String extendsPath(AhkClass cls) {
        ASTNode node = cls.getNode().findChildByType(AhkTypes.KEYWORD_EXTENDS);
        if (node == null) return null;
        StringBuilder path = new StringBuilder();
        for (node = node.getTreeNext(); node != null; node = node.getTreeNext()) {
            if (node.getElementType() == AhkTypes.IDENTIFIER || node.getElementType() == AhkTypes.DOT) {
                path.append(node.getText());
            } else if (!path.isEmpty() || node.getElementType() != TokenType.WHITE_SPACE) {
                break;
            }
        }
        return path.isEmpty() ? null : path.toString();
    }

    /**
     * Errors in the pragma of this comment (empty if it is not a pragma or everything is correct). In a
     * {@code pragmas.ahk} an unknown class is not reported: its own classes come from the files that include it.
     */
    public static @NotNull List<Problem> problems(@NotNull PsiComment comment) {
        PsiFile file = comment.getContainingFile();
        Function<String, String> classes = classResolver(file);
        if (isPragmaFile(file)) {
            Function<String, String> known = classes;
            classes = name -> {
                String resolved = known.apply(name);
                return resolved != null ? resolved : name;
            };
        }
        return parse(comment.getText(), comment.getTextRange().getStartOffset(), classes, AhkNames.of(file)).problems();
    }

    /** Is the file a {@code pragmas.ahk} (case-insensitive)? */
    public static boolean isPragmaFile(@Nullable PsiFile file) {
        return file != null && PRAGMA_FILE.equalsIgnoreCase(file.getName());
    }

    /**
     * All pattern entries of the file (cached): its own pragmas and those of the files named {@code pragmas.ahk}
     * that it includes directly ({@code #Include pragmas.ahk}, {@code <pragmas>}, {@code <v2\pragmas>}, relative
     * paths ...). Class names of those are resolved in this file (an unknown class is skipped silently there).
     */
    private static List<Entry> entries(PsiFile file) {
        return CachedValuesManager.getCachedValue(file, () -> {
            List<Entry> entries = new ArrayList<>();
            Function<String, String> classes = classResolver(file);
            AhkNames names = AhkNames.of(file);
            addEntries(file, classes, names, entries);
            for (PsiFile included : AhkInclude.includedFiles(file)) {
                if (isPragmaFile(included)) addEntries(included, classes, names, entries);
            }
            // own classes and pragmas can come from #Include files: recompute on every PSI change
            return CachedValueProvider.Result.create(List.copyOf(entries), file,
                    PsiModificationTracker.getInstance(file.getProject()));
        });
    }

    /** Adds the entries of all pragma comments of {@code source}; built-in classes from {@code names}. */
    private static void addEntries(PsiFile source, Function<String, String> classes, AhkNames names,
                                   List<Entry> entries) {
        for (PsiComment comment : PsiTreeUtil.findChildrenOfType(source, PsiComment.class)) {
            if (comment.getNode().getElementType() != AhkTypes.LINE_COMMENT) continue;
            entries.addAll(parse(comment.getText(), comment.getTextRange().getStartOffset(), classes, names).entries());
        }
    }

    /** Maps the class name after "as" to the spelling of the class, null = unknown. */
    private static Function<String, String> classResolver(PsiFile file) {
        return name -> {
            Level level = resolve(file, name);
            return level == null ? null : level.name();
        };
    }

    /** Length of the marker including leading spaces, 0 = not a pragma. */
    private static int markerLength(String text) {
        int start = 0;
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++;
        for (String marker : new String[]{DECLARE_TYPE, DECLARE_AUTO_TYPE, DECLARE_PRIVATE}) {
            int end = start + marker.length();
            if (StringUtil.startsWithIgnoreCase(text.substring(start), marker)
                    && (end == text.length() || Character.isWhitespace(text.charAt(end)))) {
                return end;
            }
        }
        return 0;
    }

    /** Parses one comment: the comma-separated sections after the marker; built-in classes from {@code names}. */
    private static Parsed parse(String text, int offset, Function<String, String> classes, AhkNames names) {
        List<Entry> entries = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();
        int bodyStart = markerLength(text);
        if (bodyStart == 0) return new Parsed(entries, problems);
        boolean auto = StringUtil.startsWithIgnoreCase(text.trim(), DECLARE_AUTO_TYPE);
        boolean priv = StringUtil.startsWithIgnoreCase(text.trim(), DECLARE_PRIVATE);
        TextRange whole = TextRange.from(offset, text.length());

        if (text.substring(bodyStart).isBlank()) {
            problems.add(new Problem(whole, INVALID_PATTERN));
            return new Parsed(entries, problems);
        }
        int segmentStart = bodyStart;
        while (segmentStart <= text.length()) {
            int comma = text.indexOf(',', segmentStart);
            int segmentEnd = comma < 0 ? text.length() : comma;
            List<TextRange> words = new ArrayList<>();
            Matcher m = WORD.matcher(text).region(segmentStart, segmentEnd);
            while (m.find()) words.add(new TextRange(m.start(), m.end()));
            if (words.isEmpty()) {
                // empty section (",," or a comma at the end): report at the comma
                TextRange at = comma >= 0 ? TextRange.from(comma, 1) : TextRange.from(segmentStart - 1, 1);
                problems.add(new Problem(at.shiftRight(offset), INVALID_PATTERN));
            } else if (auto) {
                parseAuto(text, words, offset, entries, problems, names);
            } else if (priv) {
                parsePrivate(text, words, offset, entries, problems);
            } else {
                parseType(text, words, offset, entries, problems, classes);
            }
            if (comma < 0) break;
            segmentStart = comma + 1;
        }
        return new Parsed(entries, problems);
    }

    /** "Pattern as Class". */
    private static void parseType(String text, List<TextRange> words, int offset, List<Entry> entries,
                                  List<Problem> problems, Function<String, String> classes) {
        TextRange patternRange = words.get(0);
        Matcher pattern = NAME_PATTERN.matcher(patternRange.substring(text));
        if (!pattern.matches() || !pattern.group(1).isEmpty() && !pattern.group(3).isEmpty()) {
            problems.add(new Problem(patternRange.shiftRight(offset), INVALID_PATTERN));
            return;
        }
        if (words.size() < 2 || !words.get(1).substring(text).equalsIgnoreCase("as")) {
            TextRange at = words.size() < 2 ? patternRange : words.get(1);
            problems.add(new Problem(at.shiftRight(offset), AS_EXPECTED));
            return;
        }
        if (words.size() < 3) {
            problems.add(new Problem(words.get(1).shiftRight(offset), CLASS_EXPECTED));
            return;
        }
        String cls = classes.apply(words.get(2).substring(text));
        if (cls == null) {
            problems.add(new Problem(words.get(2).shiftRight(offset), UNKNOWN_CLASS));
            return;
        }
        if (words.size() > 3) {
            problems.add(new Problem(words.get(3).shiftRight(offset), COMMA_EXPECTED));
            return;
        }
        Kind kind = !pattern.group(1).isEmpty() ? Kind.SUFFIX : !pattern.group(3).isEmpty() ? Kind.PREFIX : Kind.EXACT;
        entries.add(new Entry(kind, pattern.group(2).toLowerCase(Locale.ROOT), cls));
    }

    /** ";@declare-private": one name pattern per section (name, prefix* or *suffix). */
    private static void parsePrivate(String text, List<TextRange> words, int offset, List<Entry> entries,
                                     List<Problem> problems) {
        TextRange patternRange = words.get(0);
        if (words.size() > 1) {
            problems.add(new Problem(words.get(1).shiftRight(offset), COMMA_EXPECTED));
            return;
        }
        Matcher pattern = NAME_PATTERN.matcher(patternRange.substring(text));
        if (!pattern.matches() || !pattern.group(1).isEmpty() && !pattern.group(3).isEmpty()) {
            problems.add(new Problem(patternRange.shiftRight(offset), INVALID_PATTERN));
            return;
        }
        Kind kind = !pattern.group(1).isEmpty() ? Kind.SUFFIX : !pattern.group(3).isEmpty() ? Kind.PREFIX : Kind.EXACT;
        entries.add(new Entry(kind, pattern.group(2).toLowerCase(Locale.ROOT), null));
    }

    /** Class pattern: every matching class K becomes "*K as K" (for Gui.X "*X as Gui.X"). */
    private static void parseAuto(String text, List<TextRange> words, int offset, List<Entry> entries,
                                  List<Problem> problems, AhkNames names) {
        TextRange patternRange = words.get(0);
        if (words.size() > 1) {
            problems.add(new Problem(words.get(1).shiftRight(offset), COMMA_EXPECTED));
            return;
        }
        Matcher pattern = CLASS_PATTERN.matcher(patternRange.substring(text));
        if (!pattern.matches()) {
            problems.add(new Problem(patternRange.shiftRight(offset), INVALID_PATTERN));
            return;
        }
        boolean wildcard = pattern.group(3) == null;
        String before = (wildcard ? pattern.group(1) : pattern.group(3)).toLowerCase(Locale.ROOT);
        String after = wildcard ? pattern.group(2).toLowerCase(Locale.ROOT) : "";
        boolean found = false;
        for (AhkNames.BuiltinClass cls : names.builtinClasses()) {
            if (!matchesClassPattern(cls.name().toLowerCase(Locale.ROOT), wildcard, before, after)) continue;
            found = true;
            String simple = cls.name().substring(cls.name().lastIndexOf('.') + 1);
            entries.add(new Entry(Kind.SUFFIX, simple.toLowerCase(Locale.ROOT), cls.name()));
        }
        if (!found) problems.add(new Problem(patternRange.shiftRight(offset), UNKNOWN_CLASS));
    }

    /**
     * Does the (lower-case) class name match the class pattern? Without wildcard: equal to {@code before}. With
     * wildcard: starts with {@code before} and ends with {@code after}, the wildcard standing for any characters
     * (also none) between them, so the two parts must not overlap.
     */
    private static boolean matchesClassPattern(String lowerName, boolean wildcard, String before, String after) {
        if (!wildcard) return lowerName.equals(before);
        return lowerName.length() >= before.length() + after.length()
                && lowerName.startsWith(before) && lowerName.endsWith(after);
    }
}
