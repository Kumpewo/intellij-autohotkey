package de.kumpewo.autohotkey.documentation;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import de.kumpewo.autohotkey.AhkBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The user manual of the plugin: the pages in {@code help/extra/} (start page {@code extra/index.htm}).
 * <ul>
 *   <li>With the plugin's own CHM file ({@code help/extra.chm}): {@code hh.exe} with a copy of the CHM file in the
 *       temporary directory, like the external help of the pragmas ({@link AhkExternalHelp#openExtra}).</li>
 *   <li>Without it: the files listed in the {@code [FILES]} section of {@code help/extra.hhp} (pages and styles) are
 *       copied to {@code <temp>/intellij-autohotkey/manual/} and the start page is opened in the default browser.</li>
 * </ul>
 * The HTML Help project is the single list of the manual's files, so a new page only has to be added there.
 */
public final class AhkManual {

    /** Key of the start page inside the CHM file and path under {@code help/}. */
    public static final String START = "/extra/index.htm";
    /** HTML Help project with the list of files ({@code [FILES]}), relative to {@code help/}. */
    private static final String PROJECT = "/help/extra.hhp";

    private AhkManual() {}

    /** Opens the manual in the background (CHM viewer or browser); errors are shown as a message dialog. */
    public static void open(@Nullable Project project) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            if (AhkExternalHelp.hasExtraChm()) {
                AhkExternalHelp.openExtra(project, START);
                return;
            }
            try {
                Path start = extract(Path.of(System.getProperty("java.io.tmpdir"), AhkExternalHelp.TEMP_FOLDER, "manual"));
                if (start == null) {
                    error(project, AhkBundle.message("manual.missing"));
                } else {
                    BrowserUtil.browse(start);
                }
            } catch (IOException e) {
                error(project, AhkBundle.message("manual.failed", String.valueOf(e.getMessage())));
            }
        });
    }

    /**
     * The files of the manual from the {@code [FILES]} section of {@code help/extra.hhp}, as paths relative to
     * {@code help/} with {@code /} as separator ({@code extra/index.htm}, {@code styles/theme.css}); empty if the
     * project file is missing. Empty lines and lines starting with {@code ;} are skipped.
     */
    public static @NotNull List<String> files() {
        List<String> result = new ArrayList<>();
        String text;
        try (InputStream in = AhkManual.class.getResourceAsStream(PROJECT)) {
            if (in == null) return result;
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return result;
        }
        boolean inFiles = false;
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("[")) {
                inFiles = line.equalsIgnoreCase("[FILES]");
            } else if (inFiles && !line.isEmpty() && !line.startsWith(";")) {
                result.add(line.replace('\\', '/'));
            }
        }
        return result;
    }

    /**
     * Copies the files of the manual ({@link #files}) from the plugin resources into {@code dir} (existing files are
     * replaced, files missing in the resources are skipped) and returns the start page, or {@code null} if it is
     * missing.
     */
    public static @Nullable Path extract(@NotNull Path dir) throws IOException {
        for (String file : files()) {
            try (InputStream in = AhkManual.class.getResourceAsStream("/help/" + file)) {
                if (in == null) continue;
                Path target = dir.resolve(file);
                Files.createDirectories(target.getParent());
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path start = dir.resolve(START.substring(1));
        return Files.isRegularFile(start) ? start : null;
    }

    /** Shows an error dialog on the UI thread. */
    private static void error(@Nullable Project project, @NotNull String message) {
        ApplicationManager.getApplication().invokeLater(
                () -> Messages.showErrorDialog(project, message, AhkBundle.message("manual.title")));
    }
}
