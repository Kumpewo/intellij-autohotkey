package de.kumpewo.autohotkey.include;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import de.kumpewo.autohotkey.highlighting.AhkColors;
import de.kumpewo.autohotkey.highlighting.AhkNames;
import de.kumpewo.autohotkey.parser.AhkLanguageLevel;
import de.kumpewo.autohotkey.psi.AhkDirective;
import de.kumpewo.autohotkey.psi.AhkTypes;
import de.kumpewo.autohotkey.settings.AhkSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates {@code #Include}/{@code #IncludeAgain} as described in the AutoHotkey help ({@code _Include.htm} and
 * "Script Library Folders" in {@code Scripts.htm}):
 * <ul>
 *   <li>{@code #Include <Name>}: {@code Name.ahk} in {@code A_ScriptDir\Lib}, {@code A_MyDocuments\AutoHotkey\Lib},
 *       {@code directory-of-AutoHotkey.exe\Lib}; if not found and the name contains {@code _}: again with the part
 *       before the first {@code _}. In v2.1 files the directory of the file first, the exact name before
 *       {@code Name.ahk} and no {@code _} rule ({@link #library}).</li>
 *   <li>{@code #Include File/Directory}: optionally in quotes, optionally with {@code *i } before it,
 *       {@code %A_…%} variables. Relative to the directory of the file, unless an earlier
 *       {@code #Include Directory} in the same file has set another one.</li>
 * </ul>
 * Without knowledge of the main script, {@code A_ScriptDir} = directory of the file containing the {@code #Include}.
 * Used by navigation, completion, parameter info and type declarations (all of them look at the directly
 * included files, see {@link #includedFiles}).
 */
public final class AhkInclude {

    private static final Pattern VARIABLE = Pattern.compile("%(\\w+)%");

    private AhkInclude() {
    }

    /** Is the directive {@code #Include} or {@code #IncludeAgain}? */
    public static boolean isInclude(@NotNull AhkDirective directive) {
        ASTNode name = directive.getNode().findChildByType(AhkTypes.DIRECTIVE_NAME);
        if (name == null) return false;
        String text = name.getText().toLowerCase(Locale.ROOT);
        return text.equals("#include") || text.equals("#includeagain");
    }

    /** Range of the argument (without whitespace before and after) in the element, {@code null} without argument. */
    public static @Nullable TextRange argumentRange(@NotNull AhkDirective directive) {
        ASTNode args = directive.getNode().findChildByType(AhkTypes.DIRECTIVE_ARGS);
        if (args == null) return null;
        String text = args.getText();
        int start = 0, end = text.length();
        while (start < end && Character.isWhitespace(text.charAt(start))) start++;
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
        if (start == end) return null;
        int offset = args.getStartOffset() - directive.getTextRange().getStartOffset();
        return new TextRange(offset + start, offset + end);
    }

    /**
     * Existing file or directory the {@code #Include} points to; {@code null} if it is not an include, the file is
     * not local, a variable is unknown or nothing is found.
     */
    public static @Nullable Path resolve(@NotNull AhkDirective directive) {
        if (!isInclude(directive)) return null;
        Path file = localPath(directive.getContainingFile());
        Path directory = includeDirectory(directive);
        return file == null || directory == null ? null : resolveIn(directive, file, directory);
    }

    /**
     * Directory that relative paths in {@code directive} refer to: the directory of the file, or the target of the
     * last earlier {@code #Include Directory} in the same file. {@code null} for files that are not local.
     */
    public static @Nullable Path includeDirectory(@NotNull AhkDirective directive) {
        Path file = localPath(directive.getContainingFile());
        if (file == null || file.getParent() == null) return null;
        Path directory = file.getParent();
        for (AhkDirective previous : PsiTreeUtil.findChildrenOfType(directive.getContainingFile(), AhkDirective.class)) {
            if (previous == directive) break;
            if (!isInclude(previous)) continue;
            Path target = resolveIn(previous, file, directory);
            if (target != null && Files.isDirectory(target)) directory = target;
        }
        return directory;
    }

    /**
     * Directory that the typed part {@code typed} of a path names (text up to its last {@code \} or {@code /},
     * with {@code %A_…%} variables), relative to {@code base}; {@code null} if it does not exist or cannot be
     * determined. Used by the completion of {@code #Include} arguments.
     */
    public static @Nullable Path typedDirectory(@NotNull String typed, @NotNull Path base, @NotNull PsiFile file) {
        Path script = localPath(file);
        if (script == null) return null;
        String expanded = expand(typed.replace("`;", ";"), script);
        if (expanded == null) return null;
        try {
            Path path = Path.of(expanded);
            Path directory = (path.isAbsolute() ? path : base.resolve(path)).normalize();
            return Files.isDirectory(directory) ? directory : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }


    /**
     * Files that {@code file} includes with a direct {@code #Include}/{@code #IncludeAgain} (not recursive), in the
     * order of the directives, without duplicates and without the file itself. Directories, files not found and
     * non-local files are left out. No VFS refresh (may run in a read action).
     */
    public static @NotNull List<PsiFile> includedFiles(@NotNull PsiFile file) {
        List<PsiFile> result = new ArrayList<>();
        PsiFile original = file.getOriginalFile();
        for (AhkDirective directive : PsiTreeUtil.findChildrenOfType(file, AhkDirective.class)) {
            Path target = resolve(directive);
            if (target == null || !Files.isRegularFile(target)) continue;
            VirtualFile virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByNioFile(target);
            if (virtualFile == null) continue;
            PsiFile included = com.intellij.psi.PsiManager.getInstance(file.getProject()).findFile(virtualFile);
            if (included != null && !included.equals(original) && !result.contains(included)) result.add(included);
        }
        return result;
    }

    /** Resolves one directive relative to {@code directory} (the current include directory). */
    private static @Nullable Path resolveIn(AhkDirective directive, Path file, Path directory) {
        TextRange range = argumentRange(directive);
        if (range == null) return null;
        String argument = range.substring(directive.getText());
        if (argument.length() > 2 && argument.startsWith("<") && argument.endsWith(">")) {
            return library(argument.substring(1, argument.length() - 1).trim(), file, AhkLanguageLevel.of(directive));
        }
        argument = unquote(argument);
        if (argument.regionMatches(true, 0, "*i ", 0, 3)) argument = argument.substring(3).trim();
        argument = argument.replace("`;", ";");
        String expanded = expand(argument, file);
        if (expanded == null || expanded.isEmpty()) return null;
        try {
            Path path = Path.of(expanded);
            Path target = (path.isAbsolute() ? path : directory.resolve(path)).normalize();
            return Files.exists(target) ? target : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /**
     * Library {@code <Name>}: first existing file in the Lib folders ({@link #libraryDirectories}). The search depends
     * on the language level of the file with the {@code #Include} (AutoHotkey: on the mode in effect there):
     * <ul>
     *   <li>v2.0: {@code Name.ahk} in every folder; if not found and the name contains {@code _}, again with the part
     *       before the first {@code _}.</li>
     *   <li>v2.1: in each folder the exact name (a file, e.g. {@code Name} or {@code Name.txt}) before
     *       {@code Name.ahk}; {@code _} has no special meaning.</li>
     * </ul>
     */
    static @Nullable Path library(@NotNull String name, @NotNull Path file, @NotNull AhkLanguageLevel level) {
        if (name.isEmpty()) return null;
        List<Path> directories = libraryDirectories(file, level);
        if (level == AhkLanguageLevel.V2_1) {
            for (Path dir : directories) {
                Path exact = existingFile(dir, name);
                if (exact != null) return exact;
                Path ahk = existingFile(dir, name + ".ahk");
                if (ahk != null) return ahk;
            }
            return null;
        }
        List<String> candidates = new ArrayList<>();
        candidates.add(name);
        int underscore = name.indexOf('_');
        if (underscore > 0) candidates.add(name.substring(0, underscore));
        for (String candidate : candidates) {
            for (Path dir : directories) {
                Path target = existingFile(dir, candidate + ".ahk");
                if (target != null) return target;
            }
        }
        return null;
    }

    /** The file {@code name} in {@code dir} if it exists (not a directory), otherwise {@code null}. */
    private static @Nullable Path existingFile(@NotNull Path dir, @NotNull String name) {
        try {
            Path target = dir.resolve(name).normalize();
            return Files.isRegularFile(target) ? target : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /**
     * Folders for {@code #Include <Name>} in search order: for v2.1 first the directory of the file itself, then
     * local Lib ({@code A_ScriptDir\Lib}, here the directory of the file), user and standard Lib
     * ({@link #globalLibraryDirectories(AhkLanguageLevel)}); for v2.1 the folders of {@code AhkLibPath} instead of
     * user and standard Lib if it is set ({@link #libPathDirectories}).
     */
    public static @NotNull List<Path> libraryDirectories(@NotNull Path file, @NotNull AhkLanguageLevel level) {
        List<Path> result = new ArrayList<>();
        if (file.getParent() != null) {
            if (level == AhkLanguageLevel.V2_1) result.add(file.getParent());
            result.add(file.getParent().resolve("Lib"));
        }
        if (level == AhkLanguageLevel.V2_1 && file.getParent() != null) {
            result.addAll(libPathDirectories(file.getParent(), file));
        } else {
            result.addAll(globalLibraryDirectories(level));
        }
        return result;
    }

    /** Reads environment variables; replaced in tests. */
    public static java.util.function.Function<String, String> environment = System::getenv;

    /**
     * The folders that AutoHotkey v2.1 searches after the local ones for {@code #Import} and {@code #Include <Name>}
     * ("AhkLibPath Environment Variable" in {@code Scripts.htm}): the environment variable {@code AhkLibPath} of the
     * IDE (a list separated by {@code ;}, with {@code %A_…%} variables, relative items relative to
     * {@code moduleDirectory}); without it the user and the standard library
     * ({@link #globalLibraryDirectories(AhkLanguageLevel)}). {@code file} gives the values of {@code A_ScriptDir} and
     * {@code A_LineFile}. Whether the folders exist is not checked.
     */
    public static @NotNull List<Path> libPathDirectories(@NotNull Path moduleDirectory, @NotNull Path file) {
        String value = environment.apply("AhkLibPath");
        if (value == null) return globalLibraryDirectories(AhkLanguageLevel.V2_1);
        List<Path> result = new ArrayList<>();
        for (String item : value.split(";")) {
            String expanded = expand(item.trim(), file);
            if (expanded == null || expanded.isEmpty()) continue;
            try {
                Path path = Path.of(expanded);
                Path dir = (path.isAbsolute() ? path : moduleDirectory.resolve(path)).normalize();
                if (!result.contains(dir)) result.add(dir);
            } catch (InvalidPathException ignored) {
                // not a path: AutoHotkey skips it as well
            }
        }
        return result;
    }

    /**
     * Global Lib folders of all language levels without duplicates (for the library "AutoHotkey Lib"). Whether they
     * exist is not checked.
     */
    public static @NotNull List<Path> globalLibraryDirectories() {
        List<Path> result = new ArrayList<>();
        for (AhkLanguageLevel level : AhkLanguageLevel.selectable()) {
            for (Path dir : globalLibraryDirectories(level)) {
                if (!result.contains(dir)) result.add(dir);
            }
        }
        return result;
    }

    /**
     * Lib folders that don't depend on the script: user ({@code A_MyDocuments\AutoHotkey\Lib}) and standard (next
     * to the interpreter: for v2.1 the v2.1 interpreter, {@link AhkSettings#findAutoHotkeyV21(int)}; otherwise
     * AutoHotkey.exe from the settings, for the launcher {@code UX\..\v2\Lib}). Whether they exist is not checked.
     */
    public static @NotNull List<Path> globalLibraryDirectories(@NotNull AhkLanguageLevel level) {
        List<Path> result = new ArrayList<>();
        Path documents = myDocuments();
        if (documents != null) result.add(documents.resolve("AutoHotkey").resolve("Lib"));
        boolean v21 = level == AhkLanguageLevel.V2_1;
        String exe = v21 ? AhkSettings.getInstance().findAutoHotkeyV21(0) : AhkSettings.getInstance().findAutoHotkey();
        if (exe != null) {
            try {
                Path exePath = Path.of(exe);
                Path dir = exePath.getParent();
                if (dir != null) {
                    if (!v21 && AhkSettings.launcherScript(exePath) != null && dir.getParent() != null) {
                        // Launcher (UX\AutoHotkeyUX.exe): the standard library is next to the v2 interpreter
                        result.add(dir.getParent().resolve("v2").resolve("Lib"));
                    } else {
                        result.add(dir.resolve("Lib"));
                    }
                }
            } catch (InvalidPathException ignored) {
                // invalid path in the settings
            }
        }
        return result;
    }

    /** Built-in variable of any language level (the path of an #Include does not depend on the level). */
    private static boolean isBuiltinVariable(String name) {
        for (de.kumpewo.autohotkey.parser.AhkLanguageLevel level : de.kumpewo.autohotkey.parser.AhkLanguageLevel.selectable()) {
            if (AhkNames.get(level).nonMember(name) == AhkColors.AHK_BUILTIN_VARIABLE) return true;
        }
        return false;
    }

    /**
     * Replaces {@code %A_…%}. Unknown built-in variable: {@code null} (path cannot be determined); other
     * {@code %…%} stay as they are (literal according to the help).
     */
    private static @Nullable String expand(String text, Path file) {
        Map<String, String> values = variables(file);
        Matcher matcher = VARIABLE.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            String name = matcher.group(1).toLowerCase(Locale.ROOT);
            String value = values.get(name);
            if (value == null && isBuiltinVariable(matcher.group(1))) return null;
            out.append(text, last, matcher.start()).append(value != null ? value : matcher.group());
            last = matcher.end();
        }
        return out.append(text.substring(last)).toString();
    }

    /** Values of the built-in variables supported in {@code #Include} (lower-case names). */
    private static Map<String, String> variables(Path file) {
        Map<String, String> values = new HashMap<>();
        values.put("a_scriptdir", String.valueOf(file.getParent()));
        values.put("a_linefile", file.toString());
        values.put("a_scriptfullpath", file.toString());
        values.put("a_scriptname", String.valueOf(file.getFileName()));
        values.put("a_space", " ");
        values.put("a_tab", "\t");
        values.putAll(AhkFolderVariables.values());
        putEnv(values, "a_comspec", "ComSpec");
        String exe = AhkSettings.getInstance().findAutoHotkey();
        if (exe != null) values.put("a_ahkpath", exe);
        return values;
    }

    /** Puts the environment variable under {@code name} if it is set. */
    private static void putEnv(Map<String, String> values, String name, String variable) {
        String value = System.getenv(variable);
        if (value != null && !value.isEmpty()) values.put(name, value);
    }

    /** {@code A_MyDocuments} (also for a redirected "Documents" folder). */
    private static @Nullable Path myDocuments() {
        return AhkFolderVariables.myDocuments();
    }

    /** Removes matching double or single quotes around the text. */
    private static String unquote(String text) {
        if (text.length() >= 2) {
            char first = text.charAt(0), last = text.charAt(text.length() - 1);
            if ((first == '"' || first == '\'') && first == last) return text.substring(1, text.length() - 1).trim();
        }
        return text;
    }

    /** Path of the file in the local file system, {@code null} for other files (e.g. in memory). */
    public static @Nullable Path localPath(@Nullable PsiFile file) {
        if (file == null) return null;
        VirtualFile virtualFile = file.getOriginalFile().getVirtualFile();
        if (virtualFile == null || !virtualFile.isInLocalFileSystem()) return null;
        try {
            return virtualFile.toNioPath();
        } catch (UnsupportedOperationException | InvalidPathException e) {
            return null;
        }
    }
}
