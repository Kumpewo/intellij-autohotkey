package de.kumpewo.autohotkey.module;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import de.kumpewo.autohotkey.include.AhkInclude;
import de.kumpewo.autohotkey.parser.AhkLanguageLevel;
import de.kumpewo.autohotkey.psi.AhkClass;
import de.kumpewo.autohotkey.psi.AhkDefinitions;
import de.kumpewo.autohotkey.psi.AhkDirective;
import de.kumpewo.autohotkey.AhkFile;
import de.kumpewo.autohotkey.psi.AhkFunc;
import de.kumpewo.autohotkey.psi.AhkImportItem;
import de.kumpewo.autohotkey.psi.AhkImportList;
import de.kumpewo.autohotkey.psi.AhkImportModule;
import de.kumpewo.autohotkey.psi.AhkImportSpec;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Modules of AutoHotkey v2.1 ({@code modules.htm}, {@code _Module.htm}, {@code _Import.htm}): which module a
 * position belongs to, which parts of which files make up a module, what {@code #Import} makes visible, and where
 * a name resolves to. Only in v2.1 files ({@link #isActive}); in v2.0 files everything stays as before (the file and
 * all files it includes are one namespace).
 * <p>
 * Model (as far as the plugin can know it without the main script):
 * <ul>
 *   <li>The <em>home</em> file is the file being looked at, or a module file loaded by {@code #Import}. Its
 *       <em>group</em> is the file and the files it includes directly (no recursion).</li>
 *   <li>A file is split into regions: {@code __Main} up to the first {@code #Module}, then each {@code #Module Name}
 *       up to the next one ({@link #regions}). A module can be reopened: all regions with its name belong to it.</li>
 *   <li>The content of an included file before its first {@code #Module} belongs to the module of the
 *       {@code #Include} line; its own {@code #Module} regions belong to the group ({@link #parts}).</li>
 *   <li>Precedence of a free name ({@link #resolve}): own definitions of the module, then explicit imports
 *       ({@code #Import X}, {@code as Alias}, {@code {Name [as Alias]}}), then wildcard imports ({@code {*}}, the
 *       last one first). An {@code #Import} inside a function applies only inside it.</li>
 *   <li>A module name ({@link #resolveModule}): {@code AHK} = the built-in module; a {@code #Module} of the group;
 *       {@code "Path:Name"}; otherwise a file in the search path ({@code Name}, {@code Name\__Init.ahk},
 *       {@code Name.ahk} in the folder of the file with the {@code #Import}, of the module's home file, then the
 *       folders of {@code AhkLibPath} or the user and standard library). {@code A_ScriptDir} is not known; as for
 *       {@code #Include} it is the folder of the home file.</li>
 *   <li>{@code X.Name} and {@code {Name}} ({@link #exports}) see the definitions and global variables of X and all
 *       names X imports explicitly (also without {@code Export}, checked with v2.1-alpha.39); {@code {*}} sees
 *       only names that do not start with {@code _} and imports with {@code Export}.</li>
 * </ul>
 */
public final class AhkModules {

    /** Name of the implicit main module. */
    public static final String MAIN = "__Main";
    /** Name of the built-in module. */
    public static final String BUILTIN = "AHK";

    private static final Key<com.intellij.psi.util.CachedValue<Map<String, List<Part>>>> PARTS = Key.create("ahk.module.parts");

    private AhkModules() {
    }

    /** A region of a file that belongs to one module: {@code directive} is the {@code #Module} that starts it. */
    public record Region(@NotNull String name, @NotNull TextRange range, @Nullable AhkDirective directive) {
    }

    /** A part of a module: a range in a file. */
    public record Part(@NotNull PsiFile file, @NotNull TextRange range) {
        /** Does the element start inside this part? */
        public boolean contains(@NotNull PsiElement element) {
            return element.getContainingFile() == file && range.contains(element.getTextRange().getStartOffset());
        }
    }

    /**
     * A module: its name in the group of its home file. The built-in module {@code AHK} has no home file. Names are
     * compared ignoring case.
     */
    public record Module(@Nullable PsiFile home, @NotNull String name) {
        /** The built-in module {@code AHK}. */
        public static final Module AHK = new Module(null, BUILTIN);

        /** Is it the built-in module {@code AHK}? */
        public boolean isBuiltin() {
            return home == null;
        }

        /** Same home file and same name (ignoring case). */
        @Override
        public boolean equals(Object o) {
            return o instanceof Module other && java.util.Objects.equals(home, other.home)
                    && name.equalsIgnoreCase(other.name);
        }

        /** Hash of the home file and the lower-case name. */
        @Override
        public int hashCode() {
            return java.util.Objects.hashCode(home) * 31 + key(name).hashCode();
        }
    }

    /**
     * An {@code #Import} directive: {@code module} is the module name without quotes, {@code alias} the identifier
     * after {@code as}, {@code scope} the block of the function it is in ({@code null} at the top level).
     */
    public record Import(@NotNull AhkDirective directive, boolean export, @NotNull String module, boolean quoted,
                         @Nullable PsiElement alias, @NotNull List<Item> items, boolean wildcard,
                         @Nullable PsiElement scope) {
        /**
         * The name the module itself gets in the importing module: the alias, otherwise the unquoted module name;
         * {@code null} for a quoted name without alias.
         */
        public @Nullable String localName() {
            if (alias != null) return alias.getText();
            return quoted ? null : module;
        }

        /** The element of the module name ({@code import_module}). */
        public @Nullable AhkImportModule moduleElement() {
            AhkImportSpec spec = directive.getImportSpec();
            return spec == null ? null : spec.getImportModule();
        }
    }

    /** A name in the list of an import: {@code name} as exported, {@code alias} the identifier after {@code as}. */
    public record Item(@NotNull PsiElement name, @Nullable PsiElement alias) {
        /** The name in the importing module (the alias or the name). */
        public @NotNull String localName() {
            return (alias != null ? alias : name).getText();
        }
    }

    /**
     * Result of resolving a name: the definitions; {@code module} if the name stands for a module (then
     * {@code targets} holds its definition, if any).
     */
    public record Result(@NotNull List<PsiElement> targets, @Nullable Module module) {
        static final Result NONE = new Result(List.of(), null);

        /** Nothing found. */
        public boolean isEmpty() {
            return targets.isEmpty() && module == null;
        }
    }

    /**
     * Finds the definitions with the wanted name and kind in a whole file (as AhkDefinitionReference does without
     * modules); the module logic keeps those inside the parts of a module.
     */
    public interface Finder extends Function<PsiFile, List<PsiElement>> {
    }

    // ---------------------------------------------------------------- regions and parts

    /** Are modules evaluated for this file? Only in v2.1 files (language level of the file). */
    public static boolean isActive(@Nullable PsiFile file) {
        return file instanceof AhkFile && AhkLanguageLevel.of(file) == AhkLanguageLevel.V2_1;
    }

    /** Is the directive {@code #Module}? */
    public static boolean isModuleDirective(@NotNull AhkDirective directive) {
        return "#Module".equalsIgnoreCase(directive.getDirectiveName().getText());
    }

    /** The module name of a {@code #Module} directive (trimmed argument), {@code ""} without argument. */
    public static @NotNull String moduleName(@NotNull AhkDirective directive) {
        PsiElement args = directive.getDirectiveArgs();
        return args == null ? "" : args.getText().trim();
    }

    /** Symbol for the name in a {@code #Module} directive, {@code null} without name. */
    public static @Nullable AhkModuleSymbol moduleSymbol(@NotNull AhkDirective directive) {
        PsiElement args = directive.getDirectiveArgs();
        String name = moduleName(directive);
        if (args == null || name.isEmpty()) return null;
        int start = args.getText().indexOf(name);
        return new AhkModuleSymbol(AhkModuleSymbol.Kind.MODULE, args, TextRange.from(start, name.length()));
    }

    /**
     * The regions of the file in order: {@code __Main} from the start (also if empty), then one per top-level
     * {@code #Module}. Files that are not v2.1 have one region.
     */
    public static @NotNull List<Region> regions(@NotNull PsiFile file) {
        return CachedValuesManager.getCachedValue(file, () -> {
            List<Region> result = new ArrayList<>();
            int length = file.getTextLength();
            String name = MAIN;
            int start = 0;
            AhkDirective directive = null;
            if (isActive(file)) {
                for (PsiElement child = file.getFirstChild(); child != null; child = child.getNextSibling()) {
                    if (!(child instanceof AhkDirective d) || !isModuleDirective(d)) continue;
                    int offset = d.getTextRange().getStartOffset();
                    result.add(new Region(name, new TextRange(start, offset), directive));
                    name = moduleName(d);
                    start = offset;
                    directive = d;
                }
            }
            result.add(new Region(name, new TextRange(start, length), directive));
            return CachedValueProvider.Result.create(List.copyOf(result), file, de.kumpewo.autohotkey.settings.AhkSettings.LANGUAGE_LEVEL_TRACKER);
        });
    }

    /** Does the file contain {@code #Module} or {@code #Import}? Then a v2.1 file has more than one namespace. */
    public static boolean usesModules(@NotNull PsiFile file) {
        return regions(file).size() > 1 || !imports(file).isEmpty();
    }

    /** Name of the region of the file that contains the offset. */
    public static @NotNull String moduleNameAt(@NotNull PsiFile file, int offset) {
        List<Region> regions = regions(file);
        for (int i = regions.size() - 1; i > 0; i--) {
            if (regions.get(i).range().getStartOffset() <= offset) return regions.get(i).name();
        }
        return regions.get(0).name();
    }

    /** The module that the element belongs to, seen from its own file. */
    public static @NotNull Module moduleOf(@NotNull PsiElement element) {
        PsiFile file = element.getContainingFile();
        return new Module(file, moduleNameAt(file, element.getTextRange().getStartOffset()));
    }

    /** The parts of all modules of the group of the home file, by lower-case module name. */
    private static Map<String, List<Part>> groupParts(@NotNull PsiFile home) {
        return CachedValuesManager.getCachedValue(home, PARTS, () -> {
            Map<String, List<Part>> result = new LinkedHashMap<>();
            for (Region region : regions(home)) {
                result.computeIfAbsent(key(region.name()), k -> new ArrayList<>()).add(new Part(home, region.range()));
            }
            for (AhkDirective directive : PsiTreeUtil.findChildrenOfType(home, AhkDirective.class)) {
                if (!AhkInclude.isInclude(directive)) continue;
                PsiFile included = includedFile(directive, home);
                if (included == null) continue;
                String including = moduleNameAt(home, directive.getTextRange().getStartOffset());
                List<Region> regions = regions(included);
                for (int i = 0; i < regions.size(); i++) {
                    String name = i == 0 ? including : regions.get(i).name();
                    List<Part> parts = result.computeIfAbsent(key(name), k -> new ArrayList<>());
                    Part part = new Part(included, regions.get(i).range());
                    if (!parts.contains(part)) parts.add(part);
                }
            }
            return CachedValueProvider.Result.create(result, PsiModificationTracker.MODIFICATION_COUNT, de.kumpewo.autohotkey.settings.AhkSettings.LANGUAGE_LEVEL_TRACKER);
        });
    }

    /** The file an {@code #Include} points to, {@code null} for directories, missing files and the file itself. */
    private static @Nullable PsiFile includedFile(@NotNull AhkDirective directive, @NotNull PsiFile home) {
        Path target = AhkInclude.resolve(directive);
        if (target == null || !Files.isRegularFile(target)) return null;
        PsiFile file = psiFile(home, target);
        return file == null || file.equals(home.getOriginalFile()) ? null : file;
    }

    /** The parts of the module: regions of its home file and of the files included by it. */
    public static @NotNull List<Part> parts(@NotNull Module module) {
        if (module.home() == null) return List.of();
        return groupParts(module.home()).getOrDefault(key(module.name()), List.of());
    }

    /** Names of the modules of the group (including {@code __Main}), in the order of their first region. */
    public static @NotNull List<String> moduleNames(@NotNull PsiFile home) {
        List<String> result = new ArrayList<>();
        for (List<Part> parts : groupParts(home).values()) {
            Part first = parts.get(0);
            result.add(moduleNameAt(first.file(), first.range().getStartOffset()));
        }
        // names of included files' first regions are those of the including module, already in the list
        return result;
    }

    /** Is the element inside one of the parts? */
    public static boolean inParts(@NotNull List<Part> parts, @NotNull PsiElement element) {
        PsiFile file = element.getContainingFile();
        int offset = element.getTextOffset();
        for (Part part : parts) {
            if (part.file().equals(file) && part.range().contains(offset)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- imports

    /** All {@code #Import} directives of the file, in the order of the code. */
    public static @NotNull List<Import> imports(@NotNull PsiFile file) {
        return CachedValuesManager.getCachedValue(file, () -> {
            List<Import> result = new ArrayList<>();
            if (isActive(file)) {
                for (AhkDirective directive : PsiTreeUtil.findChildrenOfType(file, AhkDirective.class)) {
                    Import parsed = parse(directive);
                    if (parsed != null) result.add(parsed);
                }
            }
            return CachedValueProvider.Result.create(List.copyOf(result), file, de.kumpewo.autohotkey.settings.AhkSettings.LANGUAGE_LEVEL_TRACKER);
        });
    }

    /** The import of an {@code #Import} directive, {@code null} for other directives. */
    public static @Nullable Import parse(@NotNull AhkDirective directive) {
        AhkImportSpec spec = directive.getImportSpec();
        if (spec == null) return null;
        AhkImportModule module = spec.getImportModule();
        PsiElement string = module.getString();
        String text = string != null ? unquote(string.getText()) : module.getText();
        List<Item> items = new ArrayList<>();
        boolean wildcard = false;
        AhkImportList list = spec.getImportList();
        if (list != null) {
            for (AhkImportItem item : list.getImportItemList()) {
                ASTNode first = item.getNode().getFirstChildNode();
                if (first == null) continue;
                if (first.getElementType() == AhkTypes.STAR) {
                    wildcard = true;
                    continue;
                }
                ASTNode[] ids = item.getNode().getChildren(com.intellij.psi.tree.TokenSet.create(AhkTypes.IDENTIFIER));
                if (ids.length == 0) continue;
                items.add(new Item(ids[0].getPsi(), ids.length > 1 ? ids[1].getPsi() : null));
            }
        }
        PsiElement parent = directive.getParent();
        return new Import(directive, spec.getKeywordExport() != null, text, string != null, spec.getIdentifier(),
                List.copyOf(items), wildcard, parent instanceof PsiFile ? null : parent);
    }

    /**
     * The imports of the module visible at {@code context}: those at the top level of its parts, and those in the
     * functions that contain {@code context} (with {@code context} = {@code null} only the top-level ones). Imports
     * of the home file first, then those of included files, each in the order of the code.
     */
    public static @NotNull List<Import> imports(@NotNull Module module, @Nullable PsiElement context) {
        List<Import> result = new ArrayList<>();
        for (Part part : parts(module)) {
            for (Import imp : imports(part.file())) {
                if (!part.contains(imp.directive())) continue;
                if (imp.scope() == null || context != null && PsiTreeUtil.isAncestor(imp.scope(), context, false)) {
                    result.add(imp);
                }
            }
        }
        result.sort(java.util.Comparator.comparing((Import imp) -> imp.directive().getContainingFile() == module.home() ? 0 : 1)
                .thenComparingInt(imp -> imp.directive().getTextOffset()));
        return result;
    }

    // ---------------------------------------------------------------- module resolution

    /**
     * The module an import loads, seen from the module {@code from} that contains it; {@link Module#AHK} for
     * {@code AHK}; {@code null} if not found (or an embedded resource {@code "*Name"}).
     */
    public static @Nullable Module resolveModule(@NotNull Import imp, @NotNull Module from) {
        String text = imp.module();
        if (text.equalsIgnoreCase(BUILTIN)) return Module.AHK;
        if (from.home() == null || text.isEmpty() || text.startsWith("*")) return null;
        int colon = text.lastIndexOf(':');
        if (colon > 1 && isIdentifier(text.substring(colon + 1))) {
            PsiFile file = findModuleFile(text.substring(0, colon), imp, from);
            if (file == null) return null;
            String name = text.substring(colon + 1);
            return new Module(file, name.equalsIgnoreCase("__Init") ? MAIN : name);
        }
        if (isIdentifier(text) && groupParts(from.home()).containsKey(key(text))) return new Module(from.home(), text);
        PsiFile file = findModuleFile(text, imp, from);
        return file == null ? null : new Module(file, MAIN);
    }

    /**
     * The module file for a name or path: absolute, or in the search directories ({@link #searchDirectories}), each
     * as {@code Name}, {@code Name\__Init.ahk}, {@code Name.ahk}.
     */
    private static @Nullable PsiFile findModuleFile(@NotNull String name, @NotNull Import imp, @NotNull Module from) {
        Path path = findModulePath(name, imp, from);
        return path == null ? null : psiFile(imp.directive().getContainingFile(), path);
    }

    /** The existing module file on disk for a name or path (see {@link #findModuleFile}), {@code null} if none. */
    private static @Nullable Path findModulePath(@NotNull String name, @NotNull Import imp, @NotNull Module from) {
        try {
            Path path = Path.of(name);
            List<Path> directories = path.isAbsolute() ? List.of(path.getRoot()) : searchDirectories(imp, from);
            for (Path directory : directories) {
                Path base = directory.resolve(path).normalize();
                for (Path candidate : List.of(base, base.resolve("__Init.ahk"), Path.of(base + ".ahk"))) {
                    if (Files.isRegularFile(candidate)) return candidate;
                }
            }
        } catch (InvalidPathException ignored) {
            // not a path
        }
        return null;
    }

    /**
     * Does the module of the import exist? Like {@link #resolveModule}, but a module file only has to exist on disk
     * (it need not be loaded by the IDE yet); an embedded resource ({@code "*Name"}) and the sub-module of
     * {@code "Path:Name"} are not checked.
     */
    public static boolean exists(@NotNull Import imp, @NotNull Module from) {
        String text = imp.module();
        if (text.startsWith("*") || resolveModule(imp, from) != null) return true;
        int colon = text.lastIndexOf(':');
        if (colon > 1 && isIdentifier(text.substring(colon + 1))) text = text.substring(0, colon);
        return findModulePath(text, imp, from) != null;
    }

    /**
     * Search path of {@code #Import} ({@code modules.htm}): the folder of the file containing the directive, the
     * folder of the module's home file (also {@code A_ScriptDir}, see class comment), then {@code AhkLibPath} or the
     * user and standard library ({@link AhkInclude#libPathDirectories}).
     */
    public static @NotNull List<Path> searchDirectories(@NotNull Import imp, @NotNull Module from) {
        List<Path> result = new ArrayList<>();
        Path file = AhkInclude.localPath(imp.directive().getContainingFile());
        Path home = from.home() == null ? null : AhkInclude.localPath(from.home());
        if (file != null && file.getParent() != null) result.add(file.getParent());
        if (home != null && home.getParent() != null && !result.contains(home.getParent())) result.add(home.getParent());
        Path base = home != null ? home : file;
        if (base != null && base.getParent() != null) {
            for (Path dir : AhkInclude.libPathDirectories(base.getParent(), base)) {
                if (!result.contains(dir)) result.add(dir);
            }
        }
        return result;
    }

    /** The definition of a module: the name in its first {@code #Module}, otherwise its home file. */
    public static @Nullable PsiElement moduleTarget(@NotNull Module module) {
        if (module.home() == null) return null;
        for (Part part : parts(module)) {
            for (Region region : regions(part.file())) {
                if (region.directive() != null && region.range().getStartOffset() == part.range().getStartOffset()) {
                    AhkModuleSymbol symbol = moduleSymbol(region.directive());
                    if (symbol != null) return symbol;
                }
            }
        }
        return module.home();
    }

    /** The target of the module name an import gives: the alias, or the definition of the loaded module. */
    private static Result moduleResult(@NotNull Import imp, @NotNull Module from) {
        Module module = resolveModule(imp, from);
        if (imp.alias() != null) return new Result(List.of(new AhkModuleSymbol(AhkModuleSymbol.Kind.ALIAS, imp.alias())), module);
        PsiElement target = module == null ? null : moduleTarget(module);
        return new Result(target == null ? List.of() : List.of(target), module);
    }

    // ---------------------------------------------------------------- name resolution

    /**
     * Resolves a free-standing name at {@code context} (see class comment for the precedence). {@code finder} gives
     * the matching definitions of a file; {@code variables}: also global variables of imported modules.
     */
    public static @NotNull Result resolve(@NotNull PsiElement context, @NotNull String name, @NotNull Finder finder,
                                          boolean variables) {
        Module module = moduleOf(context);
        List<PsiElement> own = definitions(module, finder);
        if (!own.isEmpty()) return new Result(own, null);
        return resolveImported(module, context, name, finder, variables, new HashSet<>());
    }

    /** Explicit, then wildcard imports of the module (see {@link #resolve}). */
    private static Result resolveImported(Module module, @Nullable PsiElement context, String name, Finder finder,
                                          boolean variables, Set<Module> visited) {
        List<Import> imports = imports(module, context);
        for (Import imp : imports) {
            if (name.equalsIgnoreCase(imp.localName())) return moduleResult(imp, module);
            for (Item item : imp.items()) {
                if (!name.equalsIgnoreCase(item.localName())) continue;
                if (item.alias() != null) {
                    return new Result(List.of(new AhkModuleSymbol(AhkModuleSymbol.Kind.ALIAS, item.alias())), null);
                }
                Module source = resolveModule(imp, module);
                return source == null ? Result.NONE : exports(source, item.name().getText(), finder, variables, visited);
            }
        }
        for (int i = imports.size() - 1; i >= 0; i--) {
            Import imp = imports.get(i);
            if (!imp.wildcard()) continue;
            Module source = resolveModule(imp, module);
            if (source == null) continue;
            Result result = wildcardExports(source, name, finder, variables, visited);
            if (!result.isEmpty()) return result;
        }
        return Result.NONE;
    }

    /**
     * What {@code X.Name} or {@code #Import X {Name}} gives: definitions and global variables of the module, then all
     * names it imports explicitly, then its wildcard imports with {@code Export}.
     */
    public static @NotNull Result exports(@NotNull Module module, @NotNull String name, @NotNull Finder finder,
                                          boolean variables) {
        return exports(module, name, finder, variables, new HashSet<>());
    }

    /** {@link #exports(Module, String, Finder, boolean)} with protection against import cycles. */
    private static Result exports(Module module, String name, Finder finder, boolean variables, Set<Module> visited) {
        if (module.isBuiltin() || !visited.add(module)) return Result.NONE;
        List<PsiElement> own = definitions(module, finder);
        if (own.isEmpty() && variables) {
            PsiElement variable = variable(module, name);
            if (variable != null) own = List.of(variable);
        }
        if (!own.isEmpty()) return new Result(own, null);
        List<Import> imports = imports(module, null);
        for (Import imp : imports) {
            if (name.equalsIgnoreCase(imp.localName())) return moduleResult(imp, module);
            for (Item item : imp.items()) {
                if (!name.equalsIgnoreCase(item.localName())) continue;
                if (item.alias() != null) {
                    return new Result(List.of(new AhkModuleSymbol(AhkModuleSymbol.Kind.ALIAS, item.alias())), null);
                }
                Module source = resolveModule(imp, module);
                return source == null ? Result.NONE : exports(source, item.name().getText(), finder, variables, visited);
            }
        }
        for (int i = imports.size() - 1; i >= 0; i--) {
            Import imp = imports.get(i);
            if (!imp.wildcard() || !imp.export()) continue;
            Module source = resolveModule(imp, module);
            if (source == null) continue;
            Result result = wildcardExports(source, name, finder, variables, visited);
            if (!result.isEmpty()) return result;
        }
        return Result.NONE;
    }

    /**
     * What {@code #Import X {*}} gives for a name: nothing for names starting with {@code _}; definitions and global
     * variables of the module, then only imports with {@code Export}.
     */
    private static Result wildcardExports(Module module, String name, Finder finder, boolean variables,
                                          Set<Module> visited) {
        if (name.startsWith("_") || module.isBuiltin() || !visited.add(module)) return Result.NONE;
        List<PsiElement> own = definitions(module, finder);
        if (own.isEmpty() && variables) {
            PsiElement variable = variable(module, name);
            if (variable != null) own = List.of(variable);
        }
        if (!own.isEmpty()) return new Result(own, null);
        List<Import> imports = imports(module, null);
        for (Import imp : imports) {
            if (!imp.export()) continue;
            if (name.equalsIgnoreCase(imp.localName())) return moduleResult(imp, module);
            for (Item item : imp.items()) {
                if (!name.equalsIgnoreCase(item.localName())) continue;
                if (item.alias() != null) {
                    return new Result(List.of(new AhkModuleSymbol(AhkModuleSymbol.Kind.ALIAS, item.alias())), null);
                }
                Module source = resolveModule(imp, module);
                return source == null ? Result.NONE : exports(source, item.name().getText(), finder, variables, visited);
            }
        }
        for (int i = imports.size() - 1; i >= 0; i--) {
            Import imp = imports.get(i);
            if (!imp.wildcard() || !imp.export()) continue;
            Module source = resolveModule(imp, module);
            if (source == null) continue;
            Result result = wildcardExports(source, name, finder, variables, visited);
            if (!result.isEmpty()) return result;
        }
        return Result.NONE;
    }

    /** The definitions that {@code finder} gives in the files of the module, inside its parts, in order. */
    public static @NotNull List<PsiElement> definitions(@NotNull Module module, @NotNull Finder finder) {
        List<PsiElement> result = new ArrayList<>();
        List<Part> parts = parts(module);
        Set<PsiFile> files = new java.util.LinkedHashSet<>();
        for (Part part : parts) files.add(part.file());
        for (PsiFile file : files) {
            for (PsiElement definition : finder.apply(file)) {
                if (inParts(parts, definition)) result.add(definition);
            }
        }
        return result;
    }

    /**
     * The module that a qualifier stands for ({@code X} in {@code X.Name}, {@code X.Y} in {@code X.Y.Name}), or
     * {@code null}. {@code finder} is used to see whether an own definition hides the name.
     */
    public static @Nullable Module qualifierModule(@NotNull PsiElement qualifier, @NotNull Function<String, Finder> finder) {
        PsiFile file = qualifier.getContainingFile();
        if (!isActive(file)) return null;
        IElementType type = qualifier.getNode().getElementType();
        if (type == AhkTypes.REF_EXPR) {
            String name = qualifier.getText();
            if (de.kumpewo.autohotkey.psi.AhkLocals.resolve(qualifier.getFirstChild()) != null) return null;
            return resolve(qualifier, name, finder.apply(name), false).module();
        }
        if (type == AhkTypes.MEMBER_EXPR) {
            PsiElement inner = qualifier.getFirstChild();
            ASTNode dot = qualifier.getNode().findChildByType(AhkTypes.DOT);
            ASTNode nameNode = dot == null ? null : dot.getTreeNext();
            if (inner == null || nameNode == null || nameNode.getElementType() != AhkTypes.IDENTIFIER) return null;
            if (nameNode.getTreeNext() != null) return null;
            Module outer = qualifierModule(inner, finder);
            if (outer == null) return null;
            String name = nameNode.getText();
            return exports(outer, name, finder.apply(name), false).module();
        }
        return null;
    }

    // ---------------------------------------------------------------- global variables

    /**
     * The global variable of the module with this name: its first assignment at the top level
     * ({@code Name := …}, also in a list {@code a := 1, Name := 2}) or {@code global} declaration (also in a
     * function), as an {@link AhkModuleSymbol}; {@code null} if there is none.
     */
    public static @Nullable PsiElement variable(@NotNull Module module, @NotNull String name) {
        for (PsiElement identifier : variables(module)) {
            if (identifier.getText().equalsIgnoreCase(name)) {
                return new AhkModuleSymbol(AhkModuleSymbol.Kind.VARIABLE, identifier);
            }
        }
        return null;
    }

    /**
     * The identifiers that define global variables of the module (first occurrence of each name, in the order of
     * the code; see {@link #variable}).
     */
    public static @NotNull List<PsiElement> variables(@NotNull Module module) {
        List<PsiElement> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Part part : parts(module)) {
            for (PsiElement identifier : fileVariables(part.file())) {
                if (part.range().contains(identifier.getTextOffset()) && seen.add(key(identifier.getText()))) {
                    result.add(identifier);
                }
            }
        }
        return result;
    }

    /**
     * The identifiers of the names the module declares with {@code global} (not plain assignments; first occurrence
     * of each name, in the order of the parts), see {@link AhkDefinitions#globalDeclarations}.
     */
    public static @NotNull List<PsiElement> declaredVariables(@NotNull Module module) {
        List<PsiElement> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Part part : parts(module)) {
            for (PsiElement identifier : AhkDefinitions.globalDeclarations(part.file())) {
                if (part.range().contains(identifier.getTextOffset()) && seen.add(key(identifier.getText()))) {
                    result.add(identifier);
                }
            }
        }
        return result;
    }

    /** Identifiers of global variable definitions in the whole file (see {@link #variable}), in order. */
    private static List<PsiElement> fileVariables(PsiFile file) {
        return CachedValuesManager.getCachedValue(file, () -> {
            List<PsiElement> result = new ArrayList<>();
            for (PsiElement child = file.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child.getNode().getElementType() == AhkTypes.EXPR_STMT) {
                    for (ASTNode item = child.getNode().getFirstChildNode(); item != null; item = item.getTreeNext()) {
                        PsiElement id = assignedName(item);
                        if (id != null) result.add(id);
                    }
                }
            }
            result.addAll(AhkDefinitions.globalDeclarations(file));
            result.sort(java.util.Comparator.comparingInt(PsiElement::getTextOffset));
            return CachedValueProvider.Result.create(List.copyOf(result), file);
        });
    }

    /** {@code name := …}: the identifier of the assigned name, otherwise {@code null}. */
    private static @Nullable PsiElement assignedName(ASTNode node) {
        if (node.getElementType() != AhkTypes.ASSIGN_EXPR) return null;
        ASTNode target = node.getFirstChildNode();
        if (target == null || target.getElementType() != AhkTypes.REF_EXPR) return null;
        ASTNode id = target.findChildByType(AhkTypes.IDENTIFIER);
        return id == null ? null : id.getPsi();
    }

    // ---------------------------------------------------------------- names for completion and checks

    /** A name that a module offers ({@link #exportedNames}, {@link #visibleNames}) and what it stands for. */
    public record Named(@NotNull String name, @NotNull PsiElement target, @Nullable Module module) {
    }

    /**
     * The top-level functions and classes of the module (outside of classes and functions), in order.
     */
    public static @NotNull List<PsiElement> topLevelDefinitions(@NotNull Module module) {
        return definitions(module, file -> {
            List<PsiElement> result = new ArrayList<>();
            for (PsiElement element : PsiTreeUtil.findChildrenOfAnyType(file, AhkFunc.class, AhkClass.class)) {
                if (element instanceof AhkFunc func && func.getName() != null && AhkDefinitions.ownerClass(func) == null
                        && !AhkDefinitions.isFunctionExpression(func)
                        && PsiTreeUtil.getParentOfType(func, AhkFunc.class) == null) {
                    result.add(func);
                } else if (element instanceof AhkClass cls && cls.getName() != null
                        && AhkDefinitions.ownerClass(cls) == null && PsiTreeUtil.getParentOfType(cls, AhkFunc.class) == null) {
                    result.add(cls);
                }
            }
            return result;
        });
    }

    /**
     * All names {@code X.} offers ({@code wildcard} = false) or {@code {*}} brings ({@code wildcard} = true): own
     * functions, classes and global variables, then imported names (see {@link #exports}). Each name once.
     */
    public static @NotNull List<Named> exportedNames(@NotNull Module module, boolean wildcard) {
        Map<String, Named> result = new LinkedHashMap<>();
        collectExports(module, wildcard, result, new HashSet<>());
        return new ArrayList<>(result.values());
    }

    /** Adds the names of {@link #exportedNames} (first occurrence wins). */
    private static void collectExports(Module module, boolean wildcard, Map<String, Named> result, Set<Module> visited) {
        if (module.isBuiltin() || !visited.add(module)) return;
        for (PsiElement definition : topLevelDefinitions(module)) {
            add(result, ((com.intellij.psi.PsiNamedElement) definition).getName(), definition, null, wildcard);
        }
        for (PsiElement identifier : variables(module)) {
            add(result, identifier.getText(), new AhkModuleSymbol(AhkModuleSymbol.Kind.VARIABLE, identifier), null, wildcard);
        }
        for (Import imp : imports(module, null)) {
            if (wildcard && !imp.export()) continue;
            String local = imp.localName();
            if (local != null) {
                Result target = moduleResult(imp, module);
                PsiElement element = target.targets().isEmpty() ? imp.directive() : target.targets().get(0);
                add(result, local, element, target.module(), wildcard);
            }
            for (Item item : imp.items()) {
                PsiElement element = item.alias() != null ? new AhkModuleSymbol(AhkModuleSymbol.Kind.ALIAS, item.alias()) : item.name();
                add(result, item.localName(), element, null, wildcard);
            }
            if (imp.wildcard() && imp.export()) {
                Module source = resolveModule(imp, module);
                if (source != null) collectExports(source, true, result, visited);
            }
        }
    }

    /** Puts the name unless it is already there (or starts with {@code _} for a wildcard import). */
    private static void add(Map<String, Named> result, @Nullable String name, PsiElement target, @Nullable Module module,
                            boolean wildcard) {
        if (name == null || name.isEmpty() || wildcard && name.startsWith("_")) return;
        result.putIfAbsent(key(name), new Named(name, target, module));
    }

    /**
     * The names a free-standing name at {@code context} can refer to beyond the own definitions: imported module
     * names, explicitly imported names and names of wildcard imports.
     */
    public static @NotNull List<Named> importedNames(@NotNull PsiElement context) {
        Module module = moduleOf(context);
        Map<String, Named> result = new LinkedHashMap<>();
        List<Import> imports = imports(module, context);
        for (Import imp : imports) {
            String local = imp.localName();
            if (local != null) {
                Result target = moduleResult(imp, module);
                PsiElement element = target.targets().isEmpty() ? imp.directive() : target.targets().get(0);
                add(result, local, element, target.module(), false);
            }
            for (Item item : imp.items()) {
                PsiElement element = item.alias() != null ? new AhkModuleSymbol(AhkModuleSymbol.Kind.ALIAS, item.alias()) : item.name();
                add(result, item.localName(), element, null, false);
            }
        }
        for (int i = imports.size() - 1; i >= 0; i--) {
            Import imp = imports.get(i);
            if (!imp.wildcard()) continue;
            Module source = resolveModule(imp, module);
            if (source == null) continue;
            for (Named named : exportedNames(source, true)) result.putIfAbsent(key(named.name()), named);
        }
        return new ArrayList<>(result.values());
    }

    /**
     * Lower-case names the module defines or imports explicitly (top-level functions and classes, module names
     * and names in import lists). Used for the highlighting of built-in names that a module hides.
     */
    public static @NotNull Set<String> ownNames(@NotNull Module module) {
        if (module.home() == null) return Set.of();
        Map<String, Set<String>> byModule = CachedValuesManager.getCachedValue(module.home(), () -> {
            Map<String, Set<String>> result = new java.util.HashMap<>();
            for (String moduleName : moduleNames(module.home())) {
                Module m = new Module(module.home(), moduleName);
                Set<String> names = new HashSet<>();
                for (PsiElement definition : topLevelDefinitions(m)) {
                    String name = ((com.intellij.psi.PsiNamedElement) definition).getName();
                    if (name != null) names.add(key(name));
                }
                for (Import imp : imports(m, null)) {
                    if (imp.localName() != null) names.add(key(imp.localName()));
                    for (Item item : imp.items()) names.add(key(item.localName()));
                }
                result.put(key(moduleName), Set.copyOf(names));
            }
            return CachedValueProvider.Result.create(result, PsiModificationTracker.MODIFICATION_COUNT, de.kumpewo.autohotkey.settings.AhkSettings.LANGUAGE_LEVEL_TRACKER);
        });
        return byModule.getOrDefault(key(module.name()), Collections.emptySet());
    }

    // ---------------------------------------------------------------- helpers

    /** The PSI file of a path (no VFS refresh), {@code null} if it is not in the VFS. */
    private static @Nullable PsiFile psiFile(@NotNull PsiFile context, @NotNull Path path) {
        VirtualFile virtualFile = LocalFileSystem.getInstance().findFileByNioFile(path);
        if (virtualFile == null) return null;
        PsiFile file = PsiManager.getInstance(context.getProject()).findFile(virtualFile);
        return file instanceof AhkFile ? file : null;
    }

    /** Removes the quotes of a string literal. */
    private static String unquote(String text) {
        if (text.length() >= 2 && (text.charAt(0) == '"' || text.charAt(0) == '\'') && text.endsWith(text.substring(0, 1))) {
            return text.substring(1, text.length() - 1);
        }
        return text.length() >= 1 && (text.charAt(0) == '"' || text.charAt(0) == '\'') ? text.substring(1) : text;
    }

    /** Valid AutoHotkey name: letters, digits, underscore and non-ASCII characters, not starting with a digit. */
    public static boolean isIdentifier(@NotNull String text) {
        if (text.isEmpty() || Character.isDigit(text.charAt(0))) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!(c == '_' || c >= 0x80 || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9')) {
                return false;
            }
        }
        return true;
    }

    /** Lower case, as AutoHotkey compares names. */
    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

}
