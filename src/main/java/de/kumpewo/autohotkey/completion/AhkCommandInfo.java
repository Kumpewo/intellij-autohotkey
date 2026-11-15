package de.kumpewo.autohotkey.completion;

import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.PsiElement;
import de.kumpewo.autohotkey.documentation.AhkDocComment;
import de.kumpewo.autohotkey.highlighting.AhkNames;
import de.kumpewo.autohotkey.include.AhkInclude;
import de.kumpewo.autohotkey.psi.AhkClass;
import de.kumpewo.autohotkey.psi.AhkDefinitions;
import de.kumpewo.autohotkey.psi.AhkRefExpr;
import de.kumpewo.autohotkey.types.AhkTypeDeclarations;
import org.jetbrains.annotations.Nullable;
import java.util.LinkedHashSet;
import java.util.Set;
import de.kumpewo.autohotkey.psi.AhkFunc;
import de.kumpewo.autohotkey.psi.AhkParameter;
import de.kumpewo.autohotkey.psi.AhkParameterList;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Signatures (names/signatures.txt and names/method-signatures.txt, generated from the help), parameter
 * values (names/values.txt) and options of option strings (names/options.txt), both maintained by hand, of the
 * built-in functions, methods and directives, plus the signatures of own functions and methods. Names are case-insensitive. A plain class with static tables, not an
 * extension class.
 * <p>
 * signatures.txt: {@code Name Param1, [Optional], ...}, one line per form (BlockInput has three).<br>
 * method-signatures.txt: {@code Class.Method Param1, [Optional], ...}.<br>
 * values.txt: {@code Name Position: Value Value ...} or {@code Class.Method Position: ...}, position from 1.<br>
 * options.txt: the same, plus groups {@code @Group: Option ...} used as {@code @Group} in a list; an option
 * ending with "*" takes a value (see the head of the file).
 */
public final class AhkCommandInfo {

    /**
     * A parameter of a signature; optional = marked as optional in the help (in the file in [ ]); description = text
     * of {@code @param} in the doc comment of an own function (Z.22), otherwise null.
     */
    public record Parameter(@NotNull String text, boolean optional, @Nullable String description) {
        /** Parameter without description (built-in forms). */
        public Parameter(@NotNull String text, boolean optional) {
            this(text, optional, null);
        }

        /** Text in the parameter info, optional parameters in [ ]. */
        public @NotNull String display() {
            return optional ? "[" + text + "]" : text;
        }
    }

    /**
     * One form of a command: name in the spelling of the file and parameters. For methods {@code owner} is the
     * class ("Menu", "Gui.ListView" or the name of an own class), otherwise null.
     */
    public record Signature(@NotNull String name, @NotNull List<Parameter> parameters, @Nullable String owner) {
        /** Form of a function or directive (no owner class). */
        public Signature(@NotNull String name, @NotNull List<Parameter> parameters) {
            this(name, parameters, null);
        }
    }

    private static final Map<String, List<Signature>> SIGNATURES = loadSignatures("signatures");
    /** Methods of the built-in classes (method-signatures.txt), key = method name in lower case. */
    private static final Map<String, List<Signature>> METHOD_SIGNATURES = loadSignatures("method-signatures");
    /** values.txt: single values of an argument. */
    private static final Table VALUE_TABLE = loadTable("values");
    /** options.txt: options inside an option string (Z.19 C11). */
    private static final Table OPTION_TABLE = loadTable("options");

    private AhkCommandInfo() {
    }

    /** All built-in forms of {@code name} (empty if unknown). */
    public static @NotNull List<Signature> signatures(@NotNull String name) {
        return SIGNATURES.getOrDefault(key(name), List.of());
    }

    /**
     * Forms for the parameter info: own functions with this name (the file itself and the files included
     * directly with #Include, not recursive, as for navigation; only functions outside of classes), otherwise the
     * built-in forms from signatures.txt.
     */
    public static @NotNull List<Signature> signatures(@NotNull PsiFile file, @NotNull String name) {
        List<Signature> own = ownSignatures(file, name, false);
        return own.isEmpty() ? signatures(name) : own;
    }

    /**
     * Forms for the parameter info of a method {@code qualifier.name(…)}: first the methods of the own classes
     * (file and direct #Include files), then the built-in forms from method-signatures.txt:
     * <ul>
     *   <li>a built-in class on the left ({@code Gui.}, {@code Gui.ListView.}): only its forms</li>
     *   <li>a name declared with ;@declare-type on the left: only the forms of its classes</li>
     *   <li>otherwise all forms of all classes</li>
     * </ul>
     * For a class the base classes count as well; the derived class wins (ListView.Add instead of
     * Gui.Control.Add). As in highlighting, a built-in name only gives way to an explicit declaration.
     * If the name on the left is declared as an own class, there are only the forms of that class and its base
     * classes (own and built-in), not the methods of all own classes.
     */
    public static @NotNull List<Signature> methodSignatures(@NotNull PsiFile file, @Nullable PsiElement qualifier,
                                                            @NotNull String name) {
        List<Signature> all = METHOD_SIGNATURES.getOrDefault(key(name), List.of());
        List<String> classes = qualifierClasses(file, qualifier);
        boolean ownDeclared = classes != null && classes.stream().anyMatch(c -> AhkNames.builtinClass(c) == null);
        List<Signature> result = new ArrayList<>(ownDeclared ? List.of() : ownSignatures(file, name, true));
        if (classes == null) {
            result.addAll(all);
            return result;
        }
        Set<Signature> seen = new LinkedHashSet<>();
        for (String className : classes) {
            for (AhkTypeDeclarations.Level level : AhkTypeDeclarations.hierarchy(file, className)) {
                List<Signature> forms = new ArrayList<>();
                AhkClass own = level.own();
                if (own != null) {
                    for (AhkFunc func : AhkTypeDeclarations.ownMethods(own)) {
                        if (name.equalsIgnoreCase(func.getName())) forms.add(signature(func, level.name()));
                    }
                } else {
                    String owner = level.name();
                    forms.addAll(all.stream().filter(s -> owner.equalsIgnoreCase(s.owner())).toList());
                }
                if (!forms.isEmpty()) {
                    seen.addAll(forms);
                    break;
                }
            }
        }
        result.addAll(seen);
        return result;
    }

    /**
     * Classes of the expression left of the dot: built-in class (also nested, "Gui.ListView") or a name declared
     * with ;@declare-type or with {@code @param {Type}} in the doc comment of the enclosing function (Z.22);
     * null = unknown (all forms).
     */
    public static @Nullable List<String> qualifierClasses(@NotNull PsiFile file, @Nullable PsiElement qualifier) {
        if (qualifier == null) return null;
        String text = qualifier.getText();
        if (!text.matches("\\w+(\\.\\w+)*")) return null;
        AhkNames.BuiltinClass builtin = AhkNames.builtinClass(text);
        AhkTypeDeclarations.Declared declared = qualifier instanceof AhkRefExpr
                ? AhkTypeDeclarations.lookup(qualifier, text) : null;
        if (declared != null && (declared.explicit() || builtin == null)) return declared.classes();
        return builtin == null ? null : List.of(builtin.name());
    }

    /**
     * Own functions ({@code methods} = false: outside of classes) or methods (true: directly in a class) with this
     * name from the file and the files included directly with #Include.
     */
    private static List<Signature> ownSignatures(PsiFile file, String name, boolean methods) {
        List<Signature> own = new ArrayList<>();
        List<PsiFile> files = new ArrayList<>();
        files.add(file);
        files.addAll(AhkInclude.includedFiles(file));
        for (PsiFile source : files) {
            for (AhkFunc func : PsiTreeUtil.findChildrenOfType(source, AhkFunc.class)) {
                AhkClass owner = AhkDefinitions.ownerClass(func);
                if ((owner != null) != methods || !name.equalsIgnoreCase(func.getName())) continue;
                own.add(signature(func, owner == null ? null : owner.getName()));
            }
        }
        return own;
    }

    /** Signature of an own function or method (owner = the name of its class, as in the parameter info). */
    public static @NotNull Signature ownSignature(@NotNull AhkFunc func) {
        AhkClass owner = AhkDefinitions.ownerClass(func);
        return signature(func, owner == null ? null : owner.getName());
    }

    /**
     * Signature of an own function from its parameter list: {@code a}, {@code &a}, {@code args*}, {@code *} as
     * written; optional ({@code a?}, {@code a := 1}) in [ ], without the question mark. The description of a
     * parameter comes from {@code @param} of the doc comment (Z.22).
     */
    private static Signature signature(AhkFunc func, @Nullable String owner) {
        List<Parameter> parameters = new ArrayList<>();
        AhkParameterList list = func.getParameterList();
        AhkDocComment doc = AhkDocComment.of(func);
        for (AhkParameter parameter : PsiTreeUtil.getChildrenOfTypeAsList(list, AhkParameter.class)) {
            boolean optional = parameter.getNode().findChildByType(AhkTypes.QUESTION) != null
                    || parameter.getNode().findChildByType(AhkTypes.ASSIGN_OP) != null;
            String text = parameter.getText().replaceAll("\\s+", " ").trim();
            if (text.endsWith("?")) text = text.substring(0, text.length() - 1);
            var id = parameter.getNode().findChildByType(AhkTypes.IDENTIFIER);
            AhkDocComment.Param param = doc == null || id == null ? null : doc.param(id.getText());
            String description = param == null || param.text().isEmpty() ? null : param.text();
            parameters.add(new Parameter(text, optional, description));
        }
        return new Signature(func.getName() == null ? "" : func.getName(), parameters, owner);
    }

    /** Values of an argument with the type text for completion (name of the command or Class.Method). */
    public record Values(@NotNull List<String> values, @NotNull String typeText) {
    }

    /** Entries of values.txt or options.txt: name (lower case) -> argument index -> values, and the spellings. */
    private record Table(@NotNull Map<String, Map<Integer, List<String>>> entries, @NotNull Map<String, String> names) {
    }

    /**
     * Values for argument {@code index} (from 0) of the owner: function/command by name, method
     * {@code obj.M(...)} by {@code Class.M} in values.txt. Classes as for the parameter info
     * ({@link #methodSignatures}): built-in class on the left or declared with ;@declare-type, including base
     * classes; along the chain an entry of the class wins, otherwise the class that defines the method ends the search
     * (its values, maybe none); an own method hides the built-in values. Unknown type = the values of all classes
     * with this method.
     */
    public static @NotNull Values values(@NotNull PsiFile file, @NotNull AhkArguments.Owner owner, int index) {
        return lookup(VALUE_TABLE, file, owner, index, null);
    }

    /**
     * Options for an option string in argument {@code index} (options.txt, Z.19 C11), found like {@link #values}.
     * Options ending with "*" take a value. With {@code firstArgument} (the text of a string literal as first
     * argument of a method call), an entry {@code Class.Method<firstArgument>} at {@code index - 1} is used
     * instead, if there is one ({@code MyGui.Add("Edit", "|")} = {@code Gui.AddEdit 1}).
     */
    public static @NotNull Values options(@NotNull PsiFile file, @NotNull AhkArguments.Owner owner, int index,
                                          @Nullable String firstArgument) {
        return lookup(OPTION_TABLE, file, owner, index, firstArgument);
    }

    /** Entries of the table for the owner and argument (see {@link #values} and {@link #options}). */
    private static Values lookup(Table table, PsiFile file, AhkArguments.Owner owner, int index,
                                 @Nullable String firstArgument) {
        if (!owner.method()) {
            Map<Integer, List<String>> byIndex = table.entries().get(key(owner.name()));
            return new Values(byIndex == null ? List.of() : byIndex.getOrDefault(index, List.of()), owner.name());
        }
        String method = key(owner.name());
        Set<String> owners = new LinkedHashSet<>();
        List<String> classes = qualifierClasses(file, owner.qualifier());
        if (classes == null) {
            for (String k : table.entries().keySet()) {
                if (k.endsWith("." + method)) owners.add(k);
            }
        } else {
            for (String className : classes) {
                for (AhkTypeDeclarations.Level level : AhkTypeDeclarations.hierarchy(file, className)) {
                    AhkClass own = level.own();
                    if (own != null) {
                        if (AhkTypeDeclarations.ownMethods(own).stream().anyMatch(f -> method.equalsIgnoreCase(f.getName()))) break;
                        continue;
                    }
                    String k = key(level.name() + "." + method);
                    if (table.entries().containsKey(k)) {   // an entry of this class (also of a derived class)
                        owners.add(k);
                        break;
                    }
                    AhkNames.BuiltinClass builtin = level.builtin();
                    boolean defines = builtin != null && (builtin.methods().stream().anyMatch(method::equalsIgnoreCase)
                            || builtin.statics().stream().anyMatch(method::equalsIgnoreCase));
                    if (defines) break;   // the class defines the method itself, without values
                }
            }
        }
        Set<String> result = new LinkedHashSet<>();
        String typeText = owner.name();
        for (String k : owners) {
            String entry = k;
            int position = index;
            if (firstArgument != null && index > 0 && table.entries().containsKey(k + key(firstArgument))) {
                entry = k + key(firstArgument);   // Gui.Add("Edit", ...) = Gui.AddEdit
                position = index - 1;
            }
            List<String> found = table.entries().getOrDefault(entry, Map.of()).getOrDefault(position, List.of());
            if (found.isEmpty()) continue;
            result.addAll(found);
            typeText = table.names().getOrDefault(entry, typeText);
        }
        return new Values(List.copyOf(result), typeText);
    }

    /** Values for argument {@code index} (from 0) of {@code name} (empty if none are known). */
    public static @NotNull List<String> values(@NotNull String name, int index) {
        Map<Integer, List<String>> byIndex = VALUE_TABLE.entries().get(key(name));
        return byIndex == null ? List.of() : byIndex.getOrDefault(index, List.of());
    }

    /** Lookup key: the name in lower case. */
    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * signatures.txt ({@code Name Parameters…}) or method-signatures.txt ({@code Class.Method Parameters…};
     * class = everything before the last dot, key = method name).
     */
    private static Map<String, List<Signature>> loadSignatures(String file) {
        boolean methods = file.equals("method-signatures");
        Map<String, List<Signature>> map = new HashMap<>();
        for (String line : readLines(file)) {
            int space = line.indexOf(' ');
            String name = space < 0 ? line : line.substring(0, space);
            String owner = null;
            if (methods) {
                int dot = name.lastIndexOf('.');
                if (dot <= 0) throw new IllegalStateException(file + ".txt: 'Class.Method' expected: " + line);
                owner = name.substring(0, dot);
                name = name.substring(dot + 1);
            }
            List<Parameter> params = new ArrayList<>();
            if (space >= 0) {
                for (String p : splitParameters(line.substring(space + 1))) {
                    boolean optional = p.startsWith("[") && p.endsWith("]") && p.length() >= 2;
                    params.add(new Parameter(optional ? p.substring(1, p.length() - 1) : p, optional));
                }
            }
            map.computeIfAbsent(key(name), k -> new ArrayList<>()).add(new Signature(name, List.copyOf(params), owner));
        }
        map.replaceAll((k, v) -> List.copyOf(v));
        return Collections.unmodifiableMap(map);
    }

    /** Splits parameters at commas, but not inside quotes ("CDecl ReturnType"). */
    private static List<String> splitParameters(String text) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (char c : text.toCharArray()) {
            if (c == '"') quoted = !quoted;
            if (c == ',' && !quoted) {
                add(result, current);
            } else {
                current.append(c);
            }
        }
        add(result, current);
        return result;
    }

    /** Adds the trimmed parameter (if not empty) and clears the buffer. */
    private static void add(List<String> result, StringBuilder current) {
        String p = current.toString().trim();
        if (!p.isEmpty()) result.add(p);
        current.setLength(0);
    }

    /**
     * Reads values.txt or options.txt: lines {@code Name Position: Value ...}; in options.txt also groups
     * {@code @Group: Value ...}, inserted where {@code @Group} appears in a list (also in other groups).
     */
    private static Table loadTable(String file) {
        Map<String, List<String>> groups = new HashMap<>();
        List<String> entries = new ArrayList<>();
        for (String line : readLines(file)) {
            int colon = line.indexOf(':');
            if (colon < 0) throw new IllegalStateException(file + ".txt: ':' missing in: " + line);
            String head = line.substring(0, colon).trim();
            if (head.startsWith("@")) {
                groups.put(key(head), List.of(line.substring(colon + 1).trim().split("[\\s,]+")));
            } else {
                entries.add(line);
            }
        }
        Map<String, Map<Integer, List<String>>> map = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        for (String line : entries) {
            int colon = line.indexOf(':');
            String[] head = line.substring(0, colon).trim().split("\\s+");
            if (head.length != 2) throw new IllegalStateException(file + ".txt: 'Name Position:' expected: " + line);
            int index = Integer.parseInt(head[1]) - 1;
            List<String> values = new ArrayList<>();
            expand(file, List.of(line.substring(colon + 1).trim().split("[\\s,]+")), groups, values, new ArrayList<>());
            map.computeIfAbsent(key(head[0]), k -> new HashMap<>()).put(index, List.copyOf(values));
            names.putIfAbsent(key(head[0]), head[0]);
        }
        return new Table(Collections.unmodifiableMap(map), Collections.unmodifiableMap(names));
    }

    /** Adds the values, replacing {@code @Group} by the values of the group (no duplicates, no cycles). */
    private static void expand(String file, List<String> items, Map<String, List<String>> groups, List<String> result,
                               List<String> open) {
        for (String item : items) {
            if (item.isEmpty()) continue;
            if (!item.startsWith("@")) {
                if (!result.contains(item)) result.add(item);
                continue;
            }
            List<String> group = groups.get(key(item));
            if (group == null) throw new IllegalStateException(file + ".txt: unknown group " + item);
            if (open.contains(key(item))) throw new IllegalStateException(file + ".txt: group uses itself: " + item);
            open.add(key(item));
            expand(file, group, groups, result, open);
            open.remove(key(item));
        }
    }

    /** Non-empty lines without comment (";" to the end of the line) from /names/&lt;file&gt;.txt. */
    private static List<String> readLines(String file) {
        String path = "/names/" + file + ".txt";
        List<String> lines = new ArrayList<>();
        try (InputStream in = AhkCommandInfo.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("File missing: " + path);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                int comment = line.indexOf(';');
                if (comment >= 0) line = line.substring(0, comment);
                line = line.trim();
                if (!line.isEmpty()) lines.add(line);
            }
        } catch (IOException e) {
            throw new IllegalStateException("File not readable: " + path, e);
        }
        return lines;
    }
}
