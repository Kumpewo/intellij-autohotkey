package de.kumpewo.autohotkey.completion;

import com.intellij.codeInsight.AutoPopupController;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.InsertHandler;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.AhkIcons;
import de.kumpewo.autohotkey.include.AhkInclude;
import de.kumpewo.autohotkey.psi.AhkDirective;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Completion of the argument of {@code #Include}/{@code #IncludeAgain}:
 * <ul>
 *   <li>{@code #Include <Na|}: the {@code .ahk} files (without extension) and subdirectories of the Lib folders
 *       ({@link AhkInclude#libraryDirectories}: {@code A_ScriptDir\Lib}, user Lib, standard Lib); a file gets the
 *       closing {@code >}.</li>
 *   <li>{@code #Include Na|}, also in quotes and after {@code *i }: the {@code .ahk} files and subdirectories of
 *       the directory that relative paths refer to ({@link AhkInclude#includeDirectory}: the directory of the file
 *       or of the last earlier {@code #Include Directory}). A typed directory part ({@code sub\}, {@code ..\},
 *       {@code %A_ScriptDir%\}, absolute paths) is followed. A file gets the closing quote if one was opened.</li>
 * </ul>
 * A directory is inserted with a trailing {@code \} and opens the popup again. Only for local files.
 */
public final class AhkIncludeCompletion {

    /** Start of a line with an include directive, used for the auto popup. */
    private static final Pattern INCLUDE_LINE = Pattern.compile("(?i)\\s*#include(again)?\\s.*");

    private AhkIncludeCompletion() {
    }

    /**
     * The text of the line up to and including the typed character belongs to the argument of an include
     * directive (auto popup after {@code <}, quotes, {@code \} and {@code /}).
     */
    public static boolean isIncludeArgument(@NotNull CharSequence lineBeforeCaret) {
        return INCLUDE_LINE.matcher(lineBeforeCaret).matches();
    }

    /**
     * Adds the paths for the caret at {@code offset} in the argument {@code args} of {@code directive}. Returns
     * {@code false} if the directive is not an include.
     */
    static boolean addPaths(@NotNull AhkDirective directive, @NotNull PsiElement args, int offset,
                            @NotNull CompletionResultSet result) {
        if (!AhkInclude.isInclude(directive)) return false;
        PsiFile file = directive.getContainingFile();
        Path script = AhkInclude.localPath(file);
        if (script == null) return true;
        String before = args.getText().substring(0, offset - args.getTextRange().getStartOffset()).stripLeading();
        if (before.startsWith("<")) {
            addLibrary(before.substring(1), script, result);
        } else {
            addRelative(directive, before, file, script, result);
        }
        return true;
    }

    /** {@code <dir\prefix}: files and subdirectories of the Lib folders. */
    private static void addLibrary(String typed, Path script, CompletionResultSet result) {
        if (typed.indexOf('>') >= 0) return;
        int split = lastSeparator(typed) + 1;
        String directoryPart = typed.substring(0, split);
        CompletionResultSet out = result.withPrefixMatcher(typed.substring(split)).caseInsensitive();
        Set<String> seen = new HashSet<>();
        for (Path lib : AhkInclude.libraryDirectories(script)) {
            Path directory;
            try {
                directory = lib.resolve(directoryPart).normalize();
            } catch (InvalidPathException e) {
                continue;
            }
            String typeText = AhkBundle.message("completion.include.library", lib);
            for (Path entry : entries(directory)) {
                String name = String.valueOf(entry.getFileName());
                if (Files.isDirectory(entry)) {
                    if (seen.add(key(name) + "\\")) out.addElement(directoryItem(name));
                } else if (isAhk(name)) {
                    String base = name.substring(0, name.length() - 4);
                    if (seen.add(key(base))) {
                        out.addElement(LookupElementBuilder.create(base).withIcon(AhkIcons.FILE).withTypeText(typeText)
                                .withInsertHandler(closing('>')));
                    }
                }
            }
        }
    }

    /** File or directory name relative to the include directory, optionally quoted and after {@code *i }. */
    private static void addRelative(AhkDirective directive, String typed, PsiFile file, Path script,
                                    CompletionResultSet result) {
        char quote = 0;
        if (typed.startsWith("\"") || typed.startsWith("'")) {
            quote = typed.charAt(0);
            typed = typed.substring(1);
        }
        if (typed.regionMatches(true, 0, "*i ", 0, 3)) typed = typed.substring(3).stripLeading();
        if (quote == 0 && (typed.startsWith("\"") || typed.startsWith("'"))) {
            quote = typed.charAt(0);
            typed = typed.substring(1);
        }
        if (typed.indexOf('"') >= 0 || typed.indexOf('\'') >= 0) return;
        int split = lastSeparator(typed) + 1;
        String prefix = typed.substring(split);
        if (prefix.indexOf('%') >= 0) return;   // typing a variable
        Path base = AhkInclude.includeDirectory(directive);
        if (base == null) return;
        Path directory = split == 0 ? base : AhkInclude.typedDirectory(typed.substring(0, split), base, file);
        if (directory == null) return;
        CompletionResultSet out = result.withPrefixMatcher(prefix).caseInsensitive();
        InsertHandler<LookupElement> closing = quote == 0 ? null : closing(quote);
        for (Path entry : entries(directory)) {
            String name = String.valueOf(entry.getFileName());
            if (Files.isDirectory(entry)) {
                out.addElement(directoryItem(name));
            } else if (isAhk(name) && !entry.equals(script) && !name.toLowerCase(Locale.ROOT).contains(".~validate.")) {
                out.addElement(LookupElementBuilder.create(name).withIcon(AhkIcons.FILE)
                        .withTypeText(AhkBundle.message("completion.include.file")).withInsertHandler(closing));
            }
        }
    }

    /** Directory: inserted with a trailing backslash, then the popup opens again for its content. */
    private static LookupElementBuilder directoryItem(String name) {
        return LookupElementBuilder.create(name + "\\").withPresentableText(name).withIcon(AllIcons.Nodes.Folder)
                .withTypeText(AhkBundle.message("completion.include.directory"))
                .withInsertHandler((context, item) ->
                        AutoPopupController.getInstance(context.getProject()).scheduleAutoPopup(context.getEditor()));
    }

    /** Appends {@code closing} after the inserted name unless it is already there; caret after it. */
    private static InsertHandler<LookupElement> closing(char closing) {
        return (context, item) -> {
            Document doc = context.getDocument();
            int tail = context.getTailOffset();
            if (tail >= doc.getTextLength() || doc.getCharsSequence().charAt(tail) != closing) {
                doc.insertString(tail, String.valueOf(closing));
            }
            context.getEditor().getCaretModel().moveToOffset(tail + 1);
        };
    }

    /** Entries of the directory, empty if it cannot be read. */
    private static List<Path> entries(@Nullable Path directory) {
        if (directory == null || !Files.isDirectory(directory)) return List.of();
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.toList();
        } catch (IOException | SecurityException e) {
            return List.of();
        }
    }

    /** Index of the last {@code \} or {@code /}, -1 without one. */
    private static int lastSeparator(String text) {
        return Math.max(text.lastIndexOf('\\'), text.lastIndexOf('/'));
    }

    /** File name with the extension {@code .ahk} (any case). */
    private static boolean isAhk(String name) {
        return name.length() > 4 && name.toLowerCase(Locale.ROOT).endsWith(".ahk");
    }

    /** Key for duplicates: lower case. */
    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
