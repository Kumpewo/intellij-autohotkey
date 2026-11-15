package de.kumpewo.autohotkey.psi;

import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.tree.IElementType;
import de.kumpewo.autohotkey.highlighting.AhkColors;
import de.kumpewo.autohotkey.highlighting.AhkNames;
import de.kumpewo.autohotkey.include.AhkInclude;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Local variables and parameters of functions (Z.27), resolved by the rules of AutoHotkey v2 ("Local and Global
 * Variables" and "Nested Functions" in {@code Functions.htm}). Used by navigation, Find Usages, Rename, the
 * highlighting of occurrences and completion; only inside functions, global variables are not resolved.
 * <p>
 * A <b>scope</b> is a function or method, an accessor ({@code get}/{@code set}), a property (its index parameters;
 * with {@code =>} also its body) and a fat-arrow function {@code (a) => a}. Scopes inside other scopes are nested
 * functions; a class ends the chain (methods do not see the variables around the class).
 * <p>
 * Local in a scope are its parameters, names declared with {@code local} or {@code static}, and names that are
 * assigned ({@code x := 1}, {@code x += 1}, {@code x++}), used with the reference operator ({@code &x}), or are
 * {@code for} or {@code catch ... as} variables. Not local: built-in variables, names declared {@code global},
 * names of nested functions (they stay functions), and in an assume-global function ({@code global} alone) the
 * names that are only assigned. A name that is only read is not local (it may be a global variable).
 * A name that is only assigned is also not local if it is the name of a built-in or own function or class.
 * <p>
 * A nested function shares ("captures") a variable of an enclosing function if that function has it as a parameter,
 * declares it or assigns it, unless the nested function declares the name itself or is {@code static} (then only
 * {@code static} variables of the enclosing function are shared). A {@code global} declaration in an enclosing
 * function also applies to its nested functions; an assume-global function hides the variables of the functions
 * around it.
 */
public final class AhkLocals {

    private AhkLocals() {
    }

    /**
     * A local variable or parameter: the scope that owns it, its name in lower case (AutoHotkey ignores case), and its
     * first definition (the parameter, the declaration or the first assignment target, an {@code IDENTIFIER} leaf).
     * Two locals are equal if they have the same scope and name.
     */
    public record Local(@NotNull PsiElement scope, @NotNull String key, @NotNull PsiElement definition,
                        boolean parameter) {

        /** The name as written at the definition. */
        public @NotNull String name() {
            return definition.getText();
        }

        /** Same scope and name. */
        @Override
        public boolean equals(Object o) {
            return o instanceof Local other && other.scope.equals(scope) && other.key.equals(key);
        }

        /** Hash of scope and name. */
        @Override
        public int hashCode() {
            return Objects.hash(scope, key);
        }
    }

    /** What one scope defines, without its nested scopes (keys in lower case). */
    private static final class Info {
        /** Parameters and their identifiers. */
        final Map<String, PsiElement> parameters = new LinkedHashMap<>();
        /** Names declared with local or static and their first identifier. */
        final Map<String, PsiElement> declared = new LinkedHashMap<>();
        /** Names declared with static. */
        final Set<String> statics = new HashSet<>();
        /** Names declared with global. */
        final Set<String> globals = new HashSet<>();
        /** Assigned names (assignment, ++/--, &amp;, for, catch ... as) and their first identifier. */
        final Map<String, PsiElement> assigned = new LinkedHashMap<>();
        /** Names of the functions nested directly in the scope. */
        final Set<String> functions = new HashSet<>();
        /** "global" alone: assume-global. */
        boolean assumeGlobal;
        /** "static" alone: assume-static. */
        boolean assumeStatic;
        /** The scope is a function declared static. */
        boolean isStatic;
    }

    /** Is the element a scope (see class comment)? */
    public static boolean isScope(@Nullable PsiElement element) {
        return element instanceof AhkFunc || element instanceof AhkAccessor || element instanceof AhkClassProperty
                || element instanceof AhkLambdaExpr;
    }

    /** The innermost scope that contains the element (not the element itself); {@code null} outside of functions. */
    public static @Nullable PsiElement scopeOf(@NotNull PsiElement element) {
        for (PsiElement e = element.getParent(); e != null && !(e instanceof PsiFile); e = e.getParent()) {
            if (e instanceof AhkClass) return null;
            if (isScope(e)) return e;
        }
        return null;
    }

    /**
     * Is the identifier at a place where it names a variable: a free-standing name, a command name, a parameter, a
     * declared name, a {@code for} or {@code catch ... as} variable, the parameter of {@code x => ...}?
     */
    public static boolean isVariablePosition(@NotNull PsiElement leaf) {
        ASTNode node = leaf.getNode();
        if (node == null || node.getElementType() != AhkTypes.IDENTIFIER) return false;
        PsiElement parent = leaf.getParent();
        if (parent instanceof AhkRefExpr || parent instanceof AhkParameter || parent instanceof AhkDeclItem
                || parent instanceof AhkForVars || parent instanceof AhkLambdaExpr) {
            return true;
        }
        if (parent instanceof AhkCommandStmt) return parent.getFirstChild() == leaf;
        if (parent instanceof AhkCatchClause clause) return clause.getIdentifier() == leaf;
        return false;
    }

    /** The local variable the identifier names, or {@code null} (not in a function, global, function, class ...). */
    public static @Nullable Local resolve(@NotNull PsiElement identifier) {
        if (!isVariablePosition(identifier)) return null;
        PsiElement scope = scopeOf(identifier);
        return scope == null ? null : resolve(identifier.getText(), scope);
    }

    /** The local variable that {@code name} names inside {@code scope}, or {@code null}. */
    public static @Nullable Local resolve(@NotNull String name, @NotNull PsiElement scope) {
        if (AhkNames.nonMember(name) == AhkColors.AHK_BUILTIN_VARIABLE) return null;   // never local
        String key = key(name);
        Info own = info(scope);
        PsiElement definition = own.parameters.get(key);
        if (definition != null) return new Local(scope, key, definition, true);
        definition = own.declared.get(key);
        if (definition != null) return new Local(scope, key, definition, false);
        if (own.globals.contains(key) || own.functions.contains(key)) return null;

        boolean globalMode = own.assumeGlobal;
        if (!own.assumeGlobal) {
            boolean staticOnly = own.isStatic;
            for (PsiElement outer = scopeOf(scope); outer != null; outer = scopeOf(outer)) {
                Info info = info(outer);
                if (info.globals.contains(key) || info.functions.contains(key)) return null;
                Local shared = staticOnly ? sharedStatic(outer, info, key, name) : shared(outer, info, key, name);
                if (shared != null) return shared;
                if (info.assumeGlobal) {
                    globalMode = true;   // nested functions behave as assume-global, outer variables are hidden
                    break;
                }
                staticOnly |= info.isStatic;
            }
        }
        definition = own.assigned.get(key);
        if (definition != null && !globalMode && !isFunctionOrClass(name, scope)) {
            return new Local(scope, key, definition, false);
        }
        return null;
    }

    /** A variable of an enclosing scope that a non-static nested function shares, or {@code null}. */
    private static @Nullable Local shared(PsiElement outer, Info info, String key, String name) {
        PsiElement definition = info.parameters.get(key);
        if (definition != null) return new Local(outer, key, definition, true);
        definition = info.declared.get(key);
        if (definition != null) return new Local(outer, key, definition, false);
        definition = info.assigned.get(key);
        if (definition != null && !info.assumeGlobal && !isFunctionOrClass(name, outer)) {
            return new Local(outer, key, definition, false);
        }
        return null;
    }

    /** A static variable of an enclosing scope that a static nested function sees, or {@code null}. */
    private static @Nullable Local sharedStatic(PsiElement outer, Info info, String key, String name) {
        if (info.statics.contains(key)) return new Local(outer, key, info.declared.get(key), false);
        PsiElement definition = info.assigned.get(key);
        if (definition != null && info.assumeStatic && !info.parameters.containsKey(key)
                && !info.declared.containsKey(key) && !isFunctionOrClass(name, outer)) {
            return new Local(outer, key, definition, false);
        }
        return null;
    }

    /**
     * The local variables visible at the identifier (completion): those of its scope, then those it shares with
     * enclosing functions; each name once. Empty outside of functions.
     */
    public static @NotNull List<Local> visible(@NotNull PsiElement identifier) {
        List<Local> result = new ArrayList<>();
        PsiElement scope = scopeOf(identifier);
        if (scope == null) return result;
        Set<String> seen = new HashSet<>();
        for (PsiElement s = scope; s != null; s = scopeOf(s)) {
            Info info = info(s);
            for (Map<String, PsiElement> names : List.of(info.parameters, info.declared, info.assigned)) {
                for (PsiElement definition : names.values()) {
                    String name = definition.getText();
                    if (!seen.add(key(name))) continue;
                    Local local = resolve(name, scope);
                    if (local != null) result.add(local);
                }
            }
        }
        return result;
    }

    /** All identifiers that name the local variable (definition and uses), in the order of the text. */
    public static @NotNull List<PsiElement> occurrences(@NotNull Local local) {
        List<PsiElement> result = new ArrayList<>();
        for (PsiElement leaf = PsiTreeUtil.firstChild(local.scope()); leaf != null; leaf = PsiTreeUtil.nextLeaf(leaf)) {
            if (!PsiTreeUtil.isAncestor(local.scope(), leaf, true)) break;
            if (leaf.getText().equalsIgnoreCase(local.key()) && local.equals(resolve(leaf))) result.add(leaf);
        }
        return result;
    }

    /** The definitions of the scope, computed once per modification of the PSI. */
    private static Info info(PsiElement scope) {
        return CachedValuesManager.getCachedValue(scope, () -> CachedValueProvider.Result.create(compute(scope),
                PsiModificationTracker.MODIFICATION_COUNT));
    }

    /** Collects the parameters and the names defined in the scope (without nested scopes). */
    private static Info compute(PsiElement scope) {
        Info info = new Info();
        if (scope instanceof AhkFunc func) {
            PsiElement decl = func.getKeywordDecl();
            info.isStatic = decl != null && decl.getText().equalsIgnoreCase("static");
            addParameters(func.getParameterList(), info);
        } else if (scope instanceof AhkLambdaExpr lambda) {
            if (lambda.getIdentifier() != null) put(info.parameters, lambda.getIdentifier());
            addParameters(lambda.getParameterList(), info);
        } else if (scope instanceof AhkClassProperty property) {
            addParameters(property.getIndexParameterList(), info);
        }
        visit(scope, info);
        return info;
    }

    /** Adds the parameters of a list ({@code (a, b)} or {@code [a, b]}). */
    private static void addParameters(@Nullable PsiElement list, Info info) {
        if (list == null) return;
        for (AhkParameter parameter : PsiTreeUtil.getChildrenOfTypeAsList(list, AhkParameter.class)) {
            if (parameter.getIdentifier() != null) put(info.parameters, parameter.getIdentifier());
        }
    }

    /** Walks the children of the element, without nested scopes and classes. */
    private static void visit(PsiElement element, Info info) {
        for (PsiElement child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof AhkParameterList || child instanceof AhkIndexParameterList) continue;
            if (isScope(child)) {
                if (child instanceof AhkFunc func && func.getName() != null) info.functions.add(key(func.getName()));
                continue;
            }
            if (child instanceof AhkClass) continue;
            if (child instanceof AhkDeclStmt decl) {
                addDeclaration(decl, info);
            } else if (child instanceof AhkAssignExpr assign) {
                List<AhkExpr> operands = assign.getExprList();
                if (!operands.isEmpty()) addAssigned(operands.get(0), info);
            } else if (child instanceof AhkUnaryExpr unary) {
                ASTNode first = unary.getNode().getFirstChildNode();
                IElementType type = first == null ? null : first.getElementType();
                if (type == AhkTypes.AMP || type == AhkTypes.INCREMENT_OP) addAssigned(unary.getExpr(), info);
            } else if (child instanceof AhkPostfixExpr postfix) {
                addAssigned(postfix.getExpr(), info);
            } else if (child instanceof AhkForVars) {
                for (ASTNode id : child.getNode().getChildren(null)) {
                    if (id.getElementType() == AhkTypes.IDENTIFIER) put(info.assigned, id.getPsi());
                }
            } else if (child instanceof AhkCatchClause clause && clause.getIdentifier() != null) {
                put(info.assigned, clause.getIdentifier());
            }
            visit(child, info);
        }
    }

    /** A local, static or global declaration; without names "global" (assume-global) or "static" (assume-static). */
    private static void addDeclaration(AhkDeclStmt decl, Info info) {
        String keyword = decl.getKeywordDecl().getText().toLowerCase(Locale.ROOT);
        List<AhkDeclItem> items = decl.getDeclItemList();
        if (items.isEmpty()) {
            if (keyword.equals("global")) info.assumeGlobal = true;
            if (keyword.equals("static")) info.assumeStatic = true;
            return;
        }
        for (AhkDeclItem item : items) {
            PsiElement id = item.getIdentifier();
            String key = key(id.getText());
            if (keyword.equals("global")) {
                info.globals.add(key);
            } else {
                put(info.declared, id);
                if (keyword.equals("static")) info.statics.add(key);
            }
        }
    }

    /** The operand is a plain name ({@code x}, not {@code obj.x}): it is assigned. */
    private static void addAssigned(@Nullable PsiElement operand, Info info) {
        if (!(operand instanceof AhkRefExpr)) return;
        PsiElement id = operand.getFirstChild();
        if (id != null && id.getNode().getElementType() == AhkTypes.IDENTIFIER) put(info.assigned, id);
    }

    /** Keeps the first identifier of a name. */
    private static void put(Map<String, PsiElement> map, PsiElement identifier) {
        map.putIfAbsent(key(identifier.getText()), identifier);
    }

    /**
     * Is the name a built-in function or class, or a function or class (outside of classes) of the file or of a file
     * it includes directly?
     */
    private static boolean isFunctionOrClass(String name, PsiElement context) {
        var role = AhkNames.nonMember(name);
        if (role == AhkColors.AHK_BUILTIN_FUNCTION || role == AhkColors.AHK_BUILTIN_CLASS) return true;
        PsiFile file = context.getContainingFile();
        return file != null && ownFunctionsAndClasses(file).contains(key(name));
    }

    /** Names of the functions and classes outside of classes in the file and its direct includes (lower case). */
    private static Set<String> ownFunctionsAndClasses(PsiFile file) {
        return CachedValuesManager.getCachedValue(file, () -> {
            Set<String> names = new HashSet<>();
            List<PsiFile> files = new ArrayList<>();
            files.add(file);
            files.addAll(AhkInclude.includedFiles(file));
            for (PsiFile f : files) {
                for (AhkFunc func : PsiTreeUtil.findChildrenOfType(f, AhkFunc.class)) {
                    if (func.getName() != null && AhkDefinitions.ownerClass(func) == null) names.add(key(func.getName()));
                }
                for (AhkClass cls : PsiTreeUtil.findChildrenOfType(f, AhkClass.class)) {
                    if (cls.getName() != null && AhkDefinitions.ownerClass(cls) == null) names.add(key(cls.getName()));
                }
            }
            return CachedValueProvider.Result.create(names, PsiModificationTracker.MODIFICATION_COUNT);
        });
    }

    /** Lower case, as AutoHotkey compares names. */
    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
