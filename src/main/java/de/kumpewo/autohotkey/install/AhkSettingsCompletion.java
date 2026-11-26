package de.kumpewo.autohotkey.install;

import com.intellij.codeInsight.AutoPopupController;
import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.codeInsight.lookup.CharFilter;
import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import de.kumpewo.autohotkey.include.AhkFolderVariables;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Completion (Ctrl+Space and while typing) in {@code settings.ahk-v2-plugin.ini}, whatever plugin handles INI files; also in
 * commented-out lines ({@code ;Destination=}), not in comments:
 * <ul>
 *   <li>left of {@code =} in {@code [Install]}, {@code [InstallIfMissing]}, {@code [AddBanner]} and
 *       {@code [SetVersion]}: files and folders of the project;</li>
 *   <li>{@code Destination}: drives, folders and variables;</li>
 *   <li>targets of the copy sections: in a library project the target of a source with a version
 *       ({@link AhkVersionedName}), as long as the target is empty or {@value AhkDeployConfig#TARGET_PLACEHOLDER};
 *       otherwise, and for the folders of {@code [Cleanup]}, variables after {@code %}, and folders after a variable
 *       or an absolute path;</li>
 *   <li>{@code Message}: variables after {@code %};</li>
 *   <li>the values of the switches, of {@code Banner} and of the rules.</li>
 * </ul>
 * Sections and the names of options are not completed. All decisions are in {@link #suggest}, which works on the text
 * and the file system alone. {@link Typing} opens the popup after the characters that start something new,
 * {@link Filter} decides which characters continue it.
 */
public final class AhkSettingsCompletion extends CompletionContributor implements DumbAware {

    /** What a suggestion is; decides about its icon and what else its insertion changes. */
    enum Kind {
        /** A file. */
        FILE,
        /** A folder, ends with {@code \}. */
        FOLDER,
        /** A variable, gets the closing {@code %}. */
        VARIABLE,
        /** A value that is complete or continued by hand. */
        VALUE,
        /** A value after which more can be completed ({@code find:}). */
        START,
        /** Replaces the whole right side of the line. */
        WHOLE_VALUE
    }

    /**
     * A suggestion.
     *
     * @param text   the inserted text
     * @param kind   what it is
     * @param detail shown next to it (value of a variable), {@code null} = nothing
     */
    record Item(@NotNull String text, @NotNull Kind kind, @Nullable String detail) {
        /** A suggestion without detail. */
        Item(@NotNull String text, @NotNull Kind kind) {
            this(text, kind, null);
        }
    }

    /**
     * The suggestions for a position.
     *
     * @param prefix the text before the caret that a suggestion replaces
     * @param items  the suggestions
     */
    record Result(@NotNull String prefix, @NotNull List<Item> items) {
        /** A position where nothing can be suggested. */
        static final Result NONE = new Result("", List.of());
    }

    /** Opens the popup after {@code =}, {@code %}, {@code \} and {@code /}; the platform does it only for letters. */
    public static final class Typing extends TypedHandlerDelegate {

        /** Schedules the popup in a settings file. */
        @Override
        public @NotNull Result checkAutoPopup(char c, @NotNull Project project, @NotNull Editor editor,
                                              @NotNull PsiFile file) {
            if ("=%\\/".indexOf(c) >= 0 && isSettings(file)) {
                AutoPopupController.getInstance(project).scheduleAutoPopup(editor);
            }
            return Result.CONTINUE;
        }
    }

    /** In a settings file no typed character inserts a suggestion: file names contain {@code .} and spaces. */
    public static final class Filter extends CharFilter {

        /** Separators close the popup, other characters narrow it; {@code null} in other files. */
        @Override
        public @Nullable Result acceptChar(char c, int prefixLength, @NotNull Lookup lookup) {
            PsiFile file = lookup.getPsiFile();
            if (file == null || !isSettings(file)) return null;
            return "=%\\/\"*?;".indexOf(c) >= 0 || Character.isISOControl(c) ? Result.HIDE_LOOKUP
                    : Result.ADD_TO_PREFIX;
        }
    }

    /** Is it a settings file? */
    private static boolean isSettings(@NotNull PsiFile file) {
        VirtualFile virtual = file.getOriginalFile().getVirtualFile();
        return virtual != null && AhkSettingsNotification.isSettings(virtual);
    }

    /** Adds the suggestions; nothing else is suggested in a line {@code key=value}. */
    @Override
    public void fillCompletionVariants(@NotNull CompletionParameters parameters, @NotNull CompletionResultSet result) {
        VirtualFile file = parameters.getOriginalFile().getVirtualFile();
        if (file == null || !AhkSettingsNotification.isSettings(file)) return;
        Editor editor = parameters.getEditor();
        int offset = parameters.getOffset();
        Path path = file.getFileSystem().getNioPath(file);
        Result found = suggest(editor.getDocument().getImmutableCharSequence(), offset,
                path == null ? null : path.getParent(), AhkSettingsNotification.isLibrary(file),
                AhkDeployPlan.variables());
        if (found == null) return;
        CompletionResultSet out = result.withPrefixMatcher(found.prefix()).caseInsensitive();
        for (Item item : found.items()) {
            LookupElementBuilder element = LookupElementBuilder.create(item.text())
                    .withInsertHandler((context, chosen) -> insert(context, item));
            if (item.detail() != null) element = element.withTypeText(item.detail());
            element = switch (item.kind()) {
                case FOLDER -> element.withIcon(AllIcons.Nodes.Folder);
                case FILE, WHOLE_VALUE -> element.withIcon(AllIcons.FileTypes.Any_type);
                case VARIABLE -> element.withIcon(AllIcons.Nodes.Variable);
                default -> element;
            };
            out.addElement(element);
        }
        result.stopHere();
    }

    /** Completes the line after the insertion of the item. */
    private static void insert(@NotNull InsertionContext context, @NotNull Item item) {
        Document document = context.getDocument();
        CharSequence text = document.getImmutableCharSequence();
        int tail = context.getTailOffset();
        int line = document.getLineNumber(tail);
        int lineEnd = document.getLineEndOffset(line);
        int caret = tail;
        switch (item.kind()) {
            case WHOLE_VALUE -> {
                int start = document.getLineStartOffset(line);
                while (start < tail && text.charAt(start) != '=') start++;
                document.replaceString(start + 1, lineEnd, item.text());
                caret = start + 1 + item.text().length();
            }
            case VARIABLE -> {
                int end = tail;
                while (end < lineEnd && isNamePart(text.charAt(end))) end++;
                // the rest of a name that is already closed is replaced, too
                if (end < lineEnd && text.charAt(end) == '%') {
                    document.deleteString(tail, end);
                } else {
                    document.insertString(tail, "%");
                }
                caret = tail + 1;
            }
            default -> {
                if (context.getCompletionChar() == Lookup.REPLACE_SELECT_CHAR) {
                    int end = tail;
                    while (end < lineEnd && "\\/=%\"".indexOf(text.charAt(end)) < 0) end++;
                    if (item.kind() == Kind.FOLDER && end < lineEnd && "\\/".indexOf(text.charAt(end)) >= 0) end++;
                    while (end > tail && Character.isWhitespace(text.charAt(end - 1))) end--;
                    document.deleteString(tail, end);
                }
            }
        }
        Editor editor = context.getEditor();
        editor.getCaretModel().moveToOffset(caret);
        if (item.kind() == Kind.FOLDER || item.kind() == Kind.START) {
            AutoPopupController.getInstance(context.getProject()).scheduleAutoPopup(editor);
        }
    }

    /** A character of the name of a variable. */
    private static boolean isNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * The suggestions for the caret at {@code offset}; {@code null} if it is in a comment, in a section header or in
     * no known section: the position is none of ours.
     *
     * @param root    the project root, {@code null} = not on the local file system
     * @param library is it the settings file of a library project?
     * @param env     the variables ({@link AhkDeployPlan#variables()})
     */
    static @Nullable Result suggest(@NotNull CharSequence text, int offset, @Nullable Path root, boolean library,
                                    @NotNull Function<String, String> env) {
        String all = text.toString();
        int start = Math.max(all.lastIndexOf('\n', offset - 1), all.lastIndexOf('\r', offset - 1)) + 1;
        int end = offset;
        while (end < all.length() && all.charAt(end) != '\n' && all.charAt(end) != '\r') end++;
        int key = start;
        while (key < offset && Character.isWhitespace(all.charAt(key))) key++;
        if (key < offset && all.charAt(key) == ';') {
            key++;
            // "; text" and ";;" are comments
            if (key < end && (all.charAt(key) == ';' || Character.isWhitespace(all.charAt(key)))) return null;
        }
        if (key < end && all.charAt(key) == '[') return null;
        String section = section(all.substring(0, start));
        String line = all.substring(key, end);
        int equals = line.indexOf('=');
        if (equals < 0 || offset <= key + equals) {
            return switch (section) {
                case "install", "installifmissing", "addbanner", "setversion" ->
                        root == null ? Result.NONE : projectFiles(unquoted(all.substring(key, offset)), root);
                case "deploy", "cleanup" -> Result.NONE;
                default -> null;
            };
        }
        String name = AhkDeployConfig.unquote(line.substring(0, equals).strip());
        String typed = unquoted(all.substring(key + equals + 1, offset));
        String value = line.substring(equals + 1).strip();
        Path destination = destination(all, root, env);
        return switch (section) {
            case "deploy" -> switch (name.toLowerCase(Locale.ROOT)) {
                case "destination" -> folders(typed, true, env, null);
                case "message" -> openVariable(typed) ? variables(typed, env, destination) : Result.NONE;
                case "banner" -> banner(typed, root, env);
                case "requiresadmin", "generateoverview", "compile", "embedorigin" ->
                        values(typed, List.of("yes", "no"));
                default -> Result.NONE;
            };
            case "install", "installifmissing" -> {
                String target = library ? AhkVersionedName.target(name) : null;
                boolean open = value.isEmpty() || value.equalsIgnoreCase(AhkDeployConfig.TARGET_PLACEHOLDER);
                if (target != null && (open || value.equals(typed)
                        && target.regionMatches(true, 0, typed, 0, typed.length()))) {
                    yield new Result(open ? "" : typed, List.of(new Item(target, Kind.WHOLE_VALUE)));
                }
                yield folders(typed, false, env, destination);
            }
            case "cleanup" -> folders(typed, false, env, destination);
            case "addbanner", "setversion" -> values(typed, List.of("yes", "no", "line:", "block:"));
            default -> null;
        };
    }

    /** The lower-case name of the last section in the text, {@code ""} without one. */
    private static @NotNull String section(@NotNull String before) {
        String result = "";
        for (String line : before.split("\\r\\n|\\n|\\r")) {
            // a byte order mark may precede the first header
            String stripped = !line.isEmpty() && line.charAt(0) == 0xFEFF ? line.substring(1).strip() : line.strip();
            if (stripped.startsWith("[") && stripped.endsWith("]")) {
                result = stripped.substring(1, stripped.length() - 1).strip().toLowerCase(Locale.ROOT);
            }
        }
        return result;
    }

    /** The text without leading spaces and an opening quote. */
    private static @NotNull String unquoted(@NotNull String typed) {
        String result = typed.stripLeading();
        return result.startsWith("\"") ? result.substring(1) : result;
    }

    /** The resolved {@code Destination} of the file, {@code null} if it is missing or wrong. */
    private static @Nullable Path destination(@NotNull String text, @Nullable Path root,
                                              @NotNull Function<String, String> env) {
        String written = AhkDeployConfig.parse(text).destination();
        if (written == null || root == null) return null;
        String expanded = AhkDeployPlan.expand(written, env, null, new ArrayList<>(), "Destination");
        return expanded == null ? null : absolute(expanded);
    }

    /** Fixed values; after one with {@code :} nothing more is suggested. */
    private static @NotNull Result values(@NotNull String typed, @NotNull List<String> values) {
        if (typed.indexOf(':') >= 0) return Result.NONE;
        return new Result(typed, values.stream().map(value -> new Item(value, Kind.VALUE)).toList());
    }

    /** The value of {@code Banner}. */
    private static @NotNull Result banner(@NotNull String typed, @Nullable Path root,
                                          @NotNull Function<String, String> env) {
        if (typed.regionMatches(true, 0, "find:", 0, 5)) {
            return new Result(typed.substring(5).stripLeading(), root == null ? List.of() : filesUpwards(root));
        }
        if (typed.regionMatches(true, 0, "file:", 0, 5)) {
            String path = typed.substring(5).stripLeading();
            if (openVariable(path)) return variables(path, env, null);
            int split = lastSeparator(path) + 1;
            String folder = AhkDeployPlan.expand(path.substring(0, split), env, null, new ArrayList<>(), "Banner");
            Path base = folder == null || root == null || isNetwork(folder) ? null : resolve(root, folder);
            return entries(path.substring(split), base, true, true);
        }
        return new Result(typed, List.of(new Item("@standard", Kind.VALUE), new Item("@library", Kind.VALUE),
                new Item("find:", Kind.START), new Item("file:", Kind.START)));
    }

    /** The files in the folder and the folders above it that can be a banner, the nearest of a name. */
    private static @NotNull List<Item> filesUpwards(@NotNull Path root) {
        Map<String, Item> found = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Path folder = root.toAbsolutePath().normalize(); folder != null; folder = folder.getParent()) {
            for (Path entry : list(folder)) {
                String name = String.valueOf(entry.getFileName());
                if (AhkFileRules.isBinary(name) || AhkDeployConfig.isSettingsName(name) || name.indexOf('%') >= 0
                        || !Files.isRegularFile(entry)) continue;
                found.putIfAbsent(name, new Item(name, Kind.FILE, folder.toString()));
            }
        }
        return List.copyOf(found.values());
    }

    /** Files and folders of the project for the left side; nothing outside of the root. */
    private static @NotNull Result projectFiles(@NotNull String typed, @NotNull Path root) {
        int split = lastSeparator(typed) + 1;
        Path dir = resolve(root, typed.substring(0, split).replaceFirst("^[\\\\/]+", ""));
        if (dir == null || !dir.startsWith(root.normalize())) return Result.NONE;
        Result result = entries(typed.substring(split), dir, true, false);
        List<Item> items = new ArrayList<>(result.items());
        // what cannot be written as a key, and what is never deployed
        items.removeIf(item -> item.text().indexOf('=') >= 0
                || split == 0 && (item.text().startsWith(";") || item.text().startsWith("["))
                || item.kind() == Kind.FOLDER && item.text().startsWith(".")
                || item.kind() == Kind.FILE && AhkDeployConfig.isSettingsName(item.text()));
        return new Result(result.prefix(), items);
    }

    /**
     * A folder on the right side: a variable after {@code %}, the folders below a variable or an absolute path.
     *
     * @param start       also the start of an absolute path: drives and variables
     * @param destination {@code %DESTINATION%}, {@code null} = not available
     */
    private static @NotNull Result folders(@NotNull String typed, boolean start, @NotNull Function<String, String> env,
                                           @Nullable Path destination) {
        if (openVariable(typed)) return variables(typed, env, destination);
        int split = lastSeparator(typed) + 1;
        if (typed.endsWith("%") && !typed.endsWith("%%")) {
            // directly after a variable
            List<Item> items = new ArrayList<>();
            for (Item item : entries("", folder(typed, env, destination), false, true).items()) {
                items.add(new Item("\\" + item.text(), Kind.FOLDER));
            }
            return new Result("", items);
        }
        if (split > 0) {
            return entries(typed.substring(split), folder(typed.substring(0, split), env, destination), false, true);
        }
        if (!start) return Result.NONE;
        List<Item> items = new ArrayList<>();
        known(env, null).forEach((name, value) -> items.add(new Item("%" + name + "%", Kind.VALUE, value)));
        File[] drives = File.listRoots();
        if (drives != null) {
            for (File drive : drives) items.add(new Item(drive.getPath(), Kind.FOLDER));
        }
        return new Result(typed, items);
    }

    /** The absolute folder that is written, {@code null} for anything else. */
    private static @Nullable Path folder(@NotNull String written, @NotNull Function<String, String> env,
                                         @Nullable Path destination) {
        String expanded = AhkDeployPlan.expand(written, env, destination, new ArrayList<>(), "");
        return expanded == null ? null : absolute(expanded);
    }

    /** Is it a network path? Reading one may hang. */
    private static boolean isNetwork(@NotNull String path) {
        return path.startsWith("\\\\") || path.startsWith("//");
    }

    /** The text as an absolute local path, {@code null} if it is none. */
    private static @Nullable Path absolute(@NotNull String text) {
        if (isNetwork(text)) return null;
        try {
            Path path = Path.of(text);
            return path.isAbsolute() ? path.normalize() : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** The path below the base, {@code null} if it is not valid. */
    private static @Nullable Path resolve(@NotNull Path base, @NotNull String path) {
        try {
            return base.resolve(path).normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** Is the caret inside of {@code %...%} (an odd number of percent signs before it)? */
    private static boolean openVariable(@NotNull String typed) {
        return typed.chars().filter(c -> c == '%').count() % 2 == 1;
    }

    /** The variables for a caret after a single {@code %}. */
    private static @NotNull Result variables(@NotNull String typed, @NotNull Function<String, String> env,
                                             @Nullable Path destination) {
        List<Item> items = new ArrayList<>();
        known(env, destination).forEach((name, value) -> items.add(new Item(name, Kind.VARIABLE, value)));
        return new Result(typed.substring(typed.lastIndexOf('%') + 1), items);
    }

    /** The variables that have a value, with it. */
    private static @NotNull Map<String, String> known(@NotNull Function<String, String> env,
                                                      @Nullable Path destination) {
        Map<String, String> result = new LinkedHashMap<>();
        if (destination != null) result.put("DESTINATION", destination.toString());
        List<String> names = new ArrayList<>(List.of(AhkDeployPlan.LIBRARY_PROJECTS));
        names.addAll(AhkFolderVariables.NAMES);
        for (String name : names) {
            String value = env.apply(name);
            if (value != null) result.put(name, value);
        }
        return result;
    }

    /**
     * The content of a folder ({@code null}: nothing).
     *
     * @param files   with files (otherwise only folders)
     * @param escaped {@code %} in names is written {@code %%}
     */
    private static @NotNull Result entries(@NotNull String prefix, @Nullable Path dir, boolean files, boolean escaped) {
        if (dir == null) return Result.NONE;
        List<Item> items = new ArrayList<>();
        for (Path entry : list(dir)) {
            String name = String.valueOf(entry.getFileName());
            if (escaped) name = name.replace("%", "%%");
            if (Files.isDirectory(entry)) {
                items.add(new Item(name + "\\", Kind.FOLDER));
            } else if (files) {
                items.add(new Item(name, Kind.FILE));
            }
        }
        return new Result(prefix, items);
    }

    /** The content of the folder sorted by name, empty if it cannot be read. */
    private static @NotNull List<Path> list(@NotNull Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.sorted().toList();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /** Index of the last {@code \} or {@code /}, -1 without one. */
    private static int lastSeparator(@NotNull String text) {
        return Math.max(text.lastIndexOf('\\'), text.lastIndexOf('/'));
    }
}
