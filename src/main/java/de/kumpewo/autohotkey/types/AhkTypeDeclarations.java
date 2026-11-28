package de.kumpewo.autohotkey.types;

import de.kumpewo.autohotkey.AhkBundle;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
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
 *       (also {@code Gui.Edit}) or an own class from the file or the files it includes (also
 *       nested, {@code Outer.Inner}). Built-in takes precedence (AutoHotkey does not allow an own class with the
 *       name of a built-in class).</li>
 *   <li>{@code ;@declare-auto-type ClassPattern, ...}: at most one wildcard anywhere in the class name
 *       ({@code Gui*}, {@code *Box}, {@code Gui.*Box}, {@code *} = all); every matching class K becomes {@code *K as K}, for {@code Gui.X} it becomes
 *       {@code *X as Gui.X}. Only built-in classes, not own ones.</li>
 *   <li>{@code ;@declare-private Pattern, ...}: member names (methods, properties, fields) shown in the color for
 *       private members ({@link #privacy}).</li>
 *   <li>{@code ;@declare-member-type Class}: type of the instance field or property defined in the next code line
 *       (blank lines and comments in between are skipped) or behind which it stands ({@link #memberTargets}), as
 *       {@code ;@declare-type Owner.member as Class} for each name of that line. Exactly one class.</li>
 * </ul>
 * A name can belong to several classes. It is "explicit" if at least one pattern without a wildcard matches
 * exactly; only then does the declaration take precedence over a built-in name in highlighting.
 */
public final class AhkTypeDeclarations {

    public static final String DECLARE_TYPE = ";@declare-type";
    public static final String DECLARE_AUTO_TYPE = ";@declare-auto-type";
    public static final String DECLARE_PRIVATE = ";@declare-private";
    public static final String DECLARE_MEMBER_TYPE = ";@declare-member-type";
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
    private static final String ONE_CLASS = "types.one.class";
    private static final String MEMBER_EXPECTED = "types.member.expected";

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
    /** Class before the member name in {@code Class.member} ({@code Gui.Edit}, {@code Outer.Inner}), no wildcard. */
    private static final Pattern MEMBER_OWNER = Pattern.compile("[\\p{L}\\p{N}_]+(\\.[\\p{L}\\p{N}_]+)*");

    /** Kind of a pattern: exact name, prefix* or *suffix. */
    private enum Kind {EXACT, PREFIX, SUFFIX}

    /**
     * A pattern with its class; {@code text} in lower case, without the wildcard. {@code className} null = a pattern of
     * {@code ;@declare-private}. {@code owner} set = declaration of the instance member {@code text} of this class
     * ({@code Owner.member as Class}, spelling as resolved in the declaring file), null = declaration of a name.
     */
    private record Entry(Kind kind, String text, @Nullable String className, @Nullable String owner) {
        /** Entry for a name (not a member). */
        Entry(Kind kind, String text, @Nullable String className) {
            this(kind, text, className, null);
        }

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

    /**
     * Result of parsing one comment. {@code memberClass} = the class of a correct {@code ;@declare-member-type}
     * (spelling as resolved), whose entries need the PSI ({@link #memberTargets}); null otherwise.
     */
    private record Parsed(List<Entry> entries, List<Problem> problems, @Nullable String memberClass) {
        /** Result of a pragma other than {@code ;@declare-member-type}. */
        Parsed(List<Entry> entries, List<Problem> problems) {
            this(entries, problems, null);
        }
    }

    /** A member named by {@code ;@declare-member-type}: the class that defines it and its name as written. */
    public record MemberTarget(@NotNull AhkClass owner, @NotNull String name) {
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

    /** Is the class name known (built-in or own class of the file and the files it includes)? */
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
            if (entry.className() == null || entry.owner() != null || !entry.matches(lower)) continue;
            classes.add(entry.className());
            explicit |= entry.kind() == Kind.EXACT;
        }
        return classes.isEmpty() ? null : new Declared(List.copyOf(classes), explicit);
    }

    /**
     * Declared classes of an expression left of a dot, null = unknown:
     * <ul>
     *   <li>a name: {@link #lookup(PsiElement, String)};</li>
     *   <li>{@code this} in an instance method, property or field initializer of a class: that class;</li>
     *   <li>{@code a.b}: the classes declared for the member {@code b} of the classes of {@code a}
     *       ({@link #memberType}), step by step along the chain ({@code myGui.log.view}).</li>
     * </ul>
     */
    public static @Nullable Declared typeOf(@Nullable PsiElement expr) {
        if (expr == null) return null;
        IElementType type = expr.getNode().getElementType();
        if (type == AhkTypes.REF_EXPR) return lookup(expr, expr.getText());
        if (type == AhkTypes.LITERAL_EXPR && expr.getText().equalsIgnoreCase("this")) {
            AhkClass cls = thisClass(expr);
            return cls == null ? null : new Declared(List.of(qualifiedName(cls)), true);
        }
        if (type != AhkTypes.MEMBER_EXPR) return null;
        ASTNode name = expr.getNode().getLastChildNode();
        ASTNode dot = name == null ? null : name.getTreePrev();
        // only a plain name after the dot (not obj.%x%, obj.1, obj.a%b%)
        if (dot == null || dot.getElementType() != AhkTypes.DOT || name.getFirstChildNode() != null
                || name.getElementType() == AhkTypes.NUMBER) {
            return null;
        }
        Declared receiver = typeOf(expr.getFirstChild());
        PsiFile file = expr.getContainingFile();
        return receiver == null || file == null ? null : memberType(file, receiver, name.getText());
    }

    /**
     * The class whose instance {@code this} is at this place: the enclosing class, if the element is in one of its
     * methods (also in a function nested in it), properties or field initializers without {@code static}; null
     * otherwise (outside of classes, static members).
     */
    public static @Nullable AhkClass thisClass(@NotNull PsiElement element) {
        PsiElement member = null;
        for (PsiElement p = element.getParent(); p != null && !(p instanceof PsiFile); p = p.getParent()) {
            if (p instanceof AhkClass cls) return member == null || isStatic(member) ? null : cls;
            PsiElement parent = p.getParent();
            boolean inClassBody = parent != null && parent.getNode().getElementType() == AhkTypes.BLOCK
                    && parent.getParent() instanceof AhkClass;
            if (inClassBody || p instanceof AhkClassProperty
                    || p instanceof AhkFunc && !AhkDefinitions.isFunctionExpression(p)) {
                member = p;   // the outermost one before the class counts
            }
        }
        return null;
    }

    /**
     * Classes declared for the instance member of the receiver's classes ({@code ;@declare-type Class.member as
     * Type}), including base classes (the most derived class with a declaration wins). Declarations count from the
     * file and from the file that defines the own class (a library declares the members of its classes for every
     * user). Null = not declared.
     */
    public static @Nullable Declared memberType(@NotNull PsiFile file, @NotNull Declared receiver,
                                                @NotNull String member) {
        String lower = member.toLowerCase(Locale.ROOT);
        Set<String> classes = new LinkedHashSet<>();
        for (String className : receiver.classes()) {
            for (Level level : hierarchy(file, className)) {
                Set<String> found = new LinkedHashSet<>();
                addMemberTypes(entries(file), level, lower, found);
                PsiFile home = level.own() == null ? null : level.own().getContainingFile();
                if (home != null && home != file) addMemberTypes(entries(home), level, lower, found);
                if (!found.isEmpty()) {
                    classes.addAll(found);
                    break;
                }
            }
        }
        return classes.isEmpty() ? null : new Declared(List.copyOf(classes), true);
    }

    /** Adds the classes of the entries that declare the (lower-case) member of this class. */
    private static void addMemberTypes(List<Entry> entries, Level level, String lowerMember, Set<String> found) {
        for (Entry entry : entries) {
            if (entry.owner() != null && entry.className() != null && entry.text().equals(lowerMember)
                    && entry.owner().equalsIgnoreCase(level.name())) {
                found.add(entry.className());
            }
        }
    }

    /**
     * Is the member name declared private with {@code ;@declare-private} (in the file or a directly included
     * {@code pragmas.ahk})? {@link Privacy#EXPLICIT} if a pattern without wildcard matches. Case-insensitive.
     */
    public static @NotNull Privacy privacy(@NotNull PsiFile file, @NotNull String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        Privacy result = Privacy.NONE;
        for (Entry entry : entries(file)) {
            if (entry.className() != null || entry.owner() != null || !entry.matches(lower)) continue;
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

    /**
     * The instance field or property without parameters named {@code name} (case-insensitive) defined directly in
     * the class: an {@link AhkClassProperty} or the identifier of the field ({@link AhkDefinitions.Field}); the first
     * one in the class wins. {@code null} if there is none.
     */
    public static @Nullable Object instanceMember(@NotNull AhkClass cls, @NotNull String name) {
        for (AhkClassProperty property : PsiTreeUtil.findChildrenOfType(cls, AhkClassProperty.class)) {
            if (AhkDefinitions.ownerClass(property) == cls && !isStatic(property)
                    && name.equalsIgnoreCase(property.getName())
                    && property.getNode().findChildByType(AhkTypes.INDEX_PARAMETER_LIST) == null) {
                return property;
            }
        }
        for (AhkDefinitions.Field field : AhkDefinitions.fields(cls)) {
            if (!field.isStatic() && name.equalsIgnoreCase(field.name())) return field;
        }
        return null;
    }

    /** Definition with {@code static} before it (token KEYWORD_DECL at the start). */
    public static boolean isStatic(PsiElement definition) {
        ASTNode first = definition.getNode().getFirstChildNode();
        return first != null && first.getElementType() == AhkTypes.KEYWORD_DECL
                && first.getText().equalsIgnoreCase("static");
    }

    /**
     * The class and its base classes via {@code extends}, the derived one first. Own base classes are searched in
     * the file and the files it includes, otherwise in the file of the class and the files that one includes. An own
     * class without {@code extends} inherits from {@code Object}. Unknown class: empty; the chain ends at an unknown
     * base class.
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

    /** Built-in class (takes precedence) or own class from the file and the files it includes. */
    private static @Nullable Level resolve(PsiFile file, String name) {
        AhkNames.BuiltinClass builtin = AhkNames.of(file).builtinClass(name);
        if (builtin != null) return new Level(builtin.name(), null, builtin);
        AhkClass own = ownClass(file, name);
        return own == null ? null : new Level(qualifiedName(own), own, null);
    }

    /**
     * Own class for the name ({@code K} at the top level, {@code Outer.Inner} nested), case-insensitive; searched
     * in the file, then in the files it includes (the first one wins).
     */
    public static @Nullable AhkClass ownClass(@NotNull PsiFile file, @NotNull String name) {
        String[] parts = name.split("\\.", -1);
        List<PsiFile> files = new ArrayList<>();
        files.add(file);
        files.addAll(AhkInclude.visibleFiles(file));
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
        Parsed parsed = parse(comment.getText(), comment.getTextRange().getStartOffset(), classes, AhkNames.of(file));
        if (parsed.memberClass() == null || !memberTargets(comment).isEmpty()) return parsed.problems();
        List<Problem> problems = new ArrayList<>(parsed.problems());
        problems.add(new Problem(markerRange(comment), MEMBER_EXPECTED));
        return problems;
    }

    /**
     * A name in a type pragma that can refer to a definition, range absolute in the file: a class name
     * ({@code owner} null; a nested name such as {@code Outer.Inner} or {@code Gui.Edit} as one range), or the member
     * of {@code Class.member} ({@code owner} = the class as written).
     */
    public record NameOccurrence(@NotNull TextRange range, @Nullable String owner) {
    }

    /**
     * The names in the pragma of this line comment that can refer to definitions: the class after {@code as} and
     * the class and member of {@code Class.member} in {@code ;@declare-type}, the class of
     * {@code ;@declare-member-type}. Names before {@code as} (variables, patterns) and the other pragmas have none.
     * Only the form is checked, not whether the class exists. Empty for other comments.
     */
    public static @NotNull List<NameOccurrence> nameOccurrences(@NotNull PsiComment comment) {
        List<NameOccurrence> result = new ArrayList<>();
        if (comment.getNode().getElementType() != AhkTypes.LINE_COMMENT) return result;
        String text = comment.getText();
        int offset = comment.getTextRange().getStartOffset();
        int bodyStart = markerLength(text);
        if (bodyStart == 0) return result;
        String marker = text.trim();
        boolean memberType = StringUtil.startsWithIgnoreCase(marker, DECLARE_MEMBER_TYPE);
        if (!memberType && !StringUtil.startsWithIgnoreCase(marker, DECLARE_TYPE)) return result;
        int segmentStart = bodyStart;
        while (segmentStart <= text.length()) {
            int comma = memberType ? -1 : text.indexOf(',', segmentStart);
            int segmentEnd = comma < 0 ? text.length() : comma;
            List<TextRange> words = new ArrayList<>();
            Matcher m = WORD.matcher(text).region(segmentStart, segmentEnd);
            while (m.find()) words.add(new TextRange(m.start(), m.end()));
            if (memberType) {
                if (!words.isEmpty()) addClassName(text, words.get(0), offset, result);
            } else if (!words.isEmpty()) {
                TextRange pattern = words.get(0);
                String patternText = pattern.substring(text);
                int dot = patternText.lastIndexOf('.');
                if (dot > 0 && MEMBER_OWNER.matcher(patternText).matches()) {
                    TextRange owner = TextRange.from(pattern.getStartOffset(), dot);
                    addClassName(text, owner, offset, result);
                    TextRange member = new TextRange(pattern.getStartOffset() + dot + 1, pattern.getEndOffset());
                    result.add(new NameOccurrence(member.shiftRight(offset), owner.substring(text)));
                }
                if (words.size() >= 3 && words.get(1).substring(text).equalsIgnoreCase("as")) {
                    addClassName(text, words.get(2), offset, result);
                }
            }
            if (comma < 0) break;
            segmentStart = comma + 1;
        }
        return result;
    }

    /** Adds the word as a class name if it has the form of one ({@code Name}, {@code Outer.Inner}). */
    private static void addClassName(String text, TextRange word, int offset, List<NameOccurrence> result) {
        if (MEMBER_OWNER.matcher(word.substring(text)).matches()) {
            result.add(new NameOccurrence(word.shiftRight(offset), null));
        }
    }

    /** Absolute range of the marker ({@code ;@declare-member-type}) in the comment, without leading whitespace. */
    private static TextRange markerRange(PsiComment comment) {
        String text = comment.getText();
        int end = markerLength(text);
        int start = end - DECLARE_MEMBER_TYPE.length();
        return new TextRange(start, end).shiftRight(comment.getTextRange().getStartOffset());
    }

    /**
     * The instance members defined in the code line after the comment (blank lines and comments in between are
     * skipped), or in its own line if the comment follows code there: the property of a {@code Name { get ... }} or
     * {@code Name => ...} line without parameters, or the instance fields of a statement in the class body
     * ({@code a := 1, b := 2} gives both). Empty if that line is something else (method, static member, property
     * with parameters, code outside of a class body).
     */
    public static @NotNull List<MemberTarget> memberTargets(@NotNull PsiComment comment) {
        // behind code in the same line: that line
        PsiElement before = PsiTreeUtil.prevLeaf(comment);
        while (before != null && before.getText().isBlank() && !before.getText().contains("\n")) {
            before = PsiTreeUtil.prevLeaf(before);
        }
        boolean sameLine = before != null && !before.getText().isBlank() && !(before instanceof PsiComment);
        PsiElement leaf = sameLine ? before : PsiTreeUtil.nextLeaf(comment);
        while (!sameLine && leaf != null && (leaf instanceof PsiComment || leaf.getText().isBlank())) {
            leaf = PsiTreeUtil.nextLeaf(leaf);
        }
        if (leaf == null) return List.of();
        // the statement directly in a class body that contains the leaf
        PsiElement statement = leaf;
        while (statement.getParent() != null && !(statement.getParent() instanceof PsiFile)
                && !(statement.getParent().getNode().getElementType() == AhkTypes.BLOCK
                     && statement.getParent().getParent() instanceof AhkClass)) {
            statement = statement.getParent();
        }
        if (!(statement.getParent() != null && statement.getParent().getParent() instanceof AhkClass cls)) {
            return List.of();
        }
        if (!sameLine && firstCodeLeaf(statement) != leaf) return List.of();   // the pragma must stand before the line
        if (statement instanceof AhkClassProperty property) {
            ASTNode id = property.getNode().findChildByType(AhkTypes.IDENTIFIER);
            if (id == null || isStatic(property)
                    || property.getNode().findChildByType(AhkTypes.INDEX_PARAMETER_LIST) != null) {
                return List.of();
            }
            return List.of(new MemberTarget(cls, id.getText()));
        }
        List<MemberTarget> result = new ArrayList<>();
        for (AhkDefinitions.Field field : AhkDefinitions.fields(cls)) {
            if (!field.isStatic() && PsiTreeUtil.isAncestor(statement, field.identifier(), false)) {
                result.add(new MemberTarget(cls, field.name()));
            }
        }
        return result;
    }

    /** First leaf of the element that is neither whitespace, a line break nor a comment. */
    private static @Nullable PsiElement firstCodeLeaf(PsiElement element) {
        PsiElement leaf = PsiTreeUtil.firstChild(element);
        while (leaf != null && PsiTreeUtil.isAncestor(element, leaf, false)
                && (leaf instanceof PsiComment || leaf.getText().isBlank())) {
            leaf = PsiTreeUtil.nextLeaf(leaf);
        }
        return leaf;
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
            Parsed parsed = parse(comment.getText(), comment.getTextRange().getStartOffset(), classes, names);
            entries.addAll(parsed.entries());
            if (parsed.memberClass() == null) continue;
            // ;@declare-member-type: like "Owner.member as Class" for each member of the next line
            for (MemberTarget target : memberTargets(comment)) {
                entries.add(new Entry(Kind.EXACT, target.name().toLowerCase(Locale.ROOT), parsed.memberClass(),
                        qualifiedName(target.owner())));
            }
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
        for (String marker : new String[]{DECLARE_TYPE, DECLARE_AUTO_TYPE, DECLARE_PRIVATE, DECLARE_MEMBER_TYPE}) {
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

        if (StringUtil.startsWithIgnoreCase(text.trim(), DECLARE_MEMBER_TYPE)) {
            return parseMemberType(text, bodyStart, offset, classes);
        }
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

    /**
     * {@code ;@declare-member-type Class}: exactly one known class after the marker. The entries come later from the
     * PSI ({@link #addEntries}); the result only carries the class.
     */
    private static Parsed parseMemberType(String text, int bodyStart, int offset, Function<String, String> classes) {
        List<Problem> problems = new ArrayList<>();
        List<TextRange> words = new ArrayList<>();
        Matcher m = WORD.matcher(text).region(bodyStart, text.length());
        while (m.find()) words.add(new TextRange(m.start(), m.end()));
        if (words.isEmpty()) {
            problems.add(new Problem(TextRange.from(offset, text.length()), CLASS_EXPECTED));
            return new Parsed(List.of(), problems);
        }
        if (words.size() > 1) {
            problems.add(new Problem(words.get(1).shiftRight(offset), ONE_CLASS));
            return new Parsed(List.of(), problems);
        }
        String cls = classes.apply(words.get(0).substring(text));
        if (cls == null) {
            problems.add(new Problem(words.get(0).shiftRight(offset), UNKNOWN_CLASS));
            return new Parsed(List.of(), problems);
        }
        return new Parsed(List.of(), problems, cls);
    }

    /** "Pattern as Class"; pattern = name pattern or {@code Class.member} (instance member, without wildcards). */
    private static void parseType(String text, List<TextRange> words, int offset, List<Entry> entries,
                                  List<Problem> problems, Function<String, String> classes) {
        TextRange patternRange = words.get(0);
        String patternText = patternRange.substring(text);
        int dot = patternText.lastIndexOf('.');
        String owner = null;
        Matcher pattern = NAME_PATTERN.matcher(dot < 0 ? patternText : patternText.substring(dot + 1));
        if (!pattern.matches() || !pattern.group(1).isEmpty() && !pattern.group(3).isEmpty()) {
            problems.add(new Problem(patternRange.shiftRight(offset), INVALID_PATTERN));
            return;
        }
        if (dot >= 0) {   // Class.member: no wildcard at all, the class must be known
            String ownerText = patternText.substring(0, dot);
            if (!pattern.group(1).isEmpty() || !pattern.group(3).isEmpty()
                    || !MEMBER_OWNER.matcher(ownerText).matches()) {
                problems.add(new Problem(patternRange.shiftRight(offset), INVALID_PATTERN));
                return;
            }
            owner = classes.apply(ownerText);
            if (owner == null) {
                problems.add(new Problem(TextRange.from(patternRange.getStartOffset(), dot).shiftRight(offset),
                        UNKNOWN_CLASS));
                return;
            }
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
        entries.add(new Entry(kind, pattern.group(2).toLowerCase(Locale.ROOT), cls, owner));
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
