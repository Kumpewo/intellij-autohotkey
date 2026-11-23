package de.kumpewo.autohotkey.documentation;

import de.kumpewo.autohotkey.AhkBundle;
import com.intellij.execution.ExecutionException;
import com.intellij.ide.BrowserUtil;
import de.kumpewo.autohotkey.parser.AhkLanguageLevel;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.util.execution.ParametersListUtil;
import de.kumpewo.autohotkey.settings.AhkSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * External help: Shift+F1 starts a program with the external key of the word under the caret ({@code path} or
 * {@code path#anchor} from the lookup file, e.g. {@code /docs/lib/File.htm#Close} or
 * {@code /extra/declare-type.htm}).
 * <ul>
 *   <li>Entries from {@code docs.txt}: command line from the settings, {@code %s} = key. Empty = automatic:
 *       {@code hh.exe "mk:@MSITStore:<AutoHotkey.chm>::%s"} with the {@code AutoHotkey.chm} of the AutoHotkey
 *       installation; for entries of language level v2.1 ({@link AhkHelpRepository.Topic#root} not empty) the one
 *       next to the v2.1 interpreter ({@link #findChmV21()}).</li>
 *   <li>Entries from {@code extra.txt}: always {@code hh.exe} with the plugin's own {@code /help/extra.chm}, which
 *       is copied to the temporary directory first ({@link #extraChm}); the command line from the settings does not
 *       apply.</li>
 * </ul>
 */
public final class AhkExternalHelp {

    public static final String PLACEHOLDER = "%s";
    public static final String CHM = "AutoHotkey.chm";
    /** The plugin's own CHM file with the pages from {@code help/extra/} (built from {@code help/extra.hhp}). */
    public static final String EXTRA_CHM_RESOURCE = "/help/extra.chm";

    private AhkExternalHelp() {}

    /**
     * One entry: start the external help directly; several: a chooser ({@code Gui.Add}, {@code Menu.Add} ...) at
     * the caret of the active editor (otherwise centered), then start the chosen one.
     */
    public static void choose(@Nullable Project project, @NotNull List<AhkHelpRepository.Topic> topics) {
        if (topics.isEmpty()) return;
        if (topics.size() == 1 || project == null) {
            open(project, topics.get(0));
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            com.intellij.openapi.ui.popup.JBPopup popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(topics)
                    .setRenderer(new de.kumpewo.autohotkey.settings.AhkTextRenderer<>(AhkHelpRepository.Topic::label))
                    .setTitle(AhkBundle.message("help.variants.choose"))
                    .setItemChosenCallback(topic -> open(project, topic))
                    .createPopup();
            com.intellij.openapi.editor.Editor editor =
                    com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getSelectedTextEditor();
            if (editor != null) popup.showInBestPositionFor(editor);
            else popup.showCenteredInCurrentWindow(project);
        });
    }

    /** Starts the external help for an entry of docs.txt or extra.txt; errors are shown as a message dialog. */
    public static void open(@Nullable Project project, @NotNull AhkHelpRepository.Topic topic) {
        if (topic.fromDocs()) {
            open(project, topic.externalKey(), !topic.root().isEmpty());
        } else {
            openExtra(project, topic.externalKey());
        }
    }

    /**
     * Opens a page of the plugin's own CHM file ({@code /extra/declare-type.htm}, {@code /extra/index.htm}) in
     * {@code hh.exe}; does nothing if the plugin has no CHM file, errors are shown as a message dialog.
     */
    public static void openExtra(@Nullable Project project, @NotNull String key) {
        Path chm;
        try {
            chm = extraChm();
        } catch (IOException e) {
            error(project, AhkBundle.message("help.external.extra.failed", String.valueOf(e.getMessage())));
            return;
        }
        if (chm == null) return;
        start(project, defaultCommandLine(chm, key));
    }

    /** Starts the external help of AutoHotkey v2.0 (docs.txt) for the key; errors are shown as a message dialog. */
    public static void open(@Nullable Project project, @NotNull String key) {
        open(project, key, false);
    }

    /**
     * Starts the external help of AutoHotkey (docs.txt) for the key, with {@code v21} the {@code AutoHotkey.chm} of
     * the v2.1 interpreter; a command line from the settings applies to both. Errors are shown as a message dialog.
     */
    public static void open(@Nullable Project project, @NotNull String key, boolean v21) {
        List<String> command;
        String template = AhkSettings.getInstance().getExternalHelpCommand();
        if (!template.isEmpty()) {
            command = commandLine(template, key);
        } else {
            Path chm = v21 ? findChmV21() : findChm();
            if (chm == null) {
                error(project, v21 ? AhkBundle.message("help.external.chm.v21.not.found", CHM)
                        : AhkBundle.message("help.external.chm.not.found", CHM));
                return;
            }
            command = defaultCommandLine(chm, key);
        }
        start(project, command);
    }

    /** Key of the start page of the AutoHotkey help, for a command line from the settings. */
    public static final String DOCS_START = "/docs/index.htm";

    /**
     * Opens the AutoHotkey help of a version itself (menu Help &gt; AutoHotkey v2.0 Help / AutoHotkey v2.1 Help),
     * like Shift+F1: with a command line from the settings that one with {@link #DOCS_START} for {@code %s};
     * otherwise {@code hh.exe} with the {@code AutoHotkey.chm} of the installation ({@link #findChm()}) or of the
     * v2.1 interpreter ({@link #findChmV21()}), which opens at its own start page; without one the help shipped with
     * the plugin in the default browser ({@link AhkBundledDocs}). Runs in the background; errors are shown as a
     * message dialog.
     */
    public static void openDocs(@Nullable Project project, @NotNull AhkLanguageLevel level) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String template = AhkSettings.getInstance().getExternalHelpCommand();
            if (!template.isEmpty()) {
                start(project, commandLine(template, DOCS_START));
                return;
            }
            Path chm = level.dataLevel() == AhkLanguageLevel.V2_1 ? findChmV21() : findChm();
            if (chm != null) {
                start(project, List.of("hh.exe", chm.toString()));
                return;
            }
            try {
                Path start = AhkBundledDocs.extract(level);
                if (start == null) {
                    error(project, AhkBundle.message("help.external.chm.not.found", CHM));
                } else {
                    BrowserUtil.browse(start);
                }
            } catch (IOException e) {
                error(project, AhkBundle.message("help.bundled.failed", String.valueOf(e.getMessage())));
            }
        });
    }

    /** Starts the program of the command line; errors are shown as a message dialog. */
    private static void start(@Nullable Project project, @NotNull List<String> command) {
        if (command.isEmpty()) return;
        try {
            new GeneralCommandLine(command).createProcess();
        } catch (ExecutionException e) {
            error(project, AhkBundle.message("help.external.failed", String.valueOf(e.getMessage())));
        }
    }

    /** Command line from the template: split at spaces (quotes keep parts together), %s replaced. */
    public static @NotNull List<String> commandLine(@NotNull String template, @NotNull String key) {
        List<String> result = new ArrayList<>();
        for (String part : ParametersListUtil.parse(template)) result.add(part.replace(PLACEHOLDER, key));
        return result;
    }

    /** Automatic command line: {@code hh.exe "mk:@MSITStore:<chm>::<key>"}. */
    public static @NotNull List<String> defaultCommandLine(@NotNull Path chm, @NotNull String key) {
        return List.of("hh.exe", "mk:@MSITStore:" + chm + "::" + key);
    }

    /** Does the plugin ship its own CHM file ({@link #EXTRA_CHM_RESOURCE})? Without it extra.txt has no external help. */
    public static boolean hasExtraChm() {
        return AhkExternalHelp.class.getResource(EXTRA_CHM_RESOURCE) != null;
    }

    /**
     * Name of the plugin's folder in the temporary directory (copy of the CHM file, extracted manual), the same as
     * the name of the repository and of the plugin ZIP.
     */
    public static final String TEMP_FOLDER = "intellij-autohotkey";

    /**
     * The plugin's CHM file as a real file, because {@code hh.exe} cannot read from the plugin JAR: a copy in
     * {@code <temp>/intellij-autohotkey/extra-<checksum>.chm} ({@link #TEMP_FOLDER}). The checksum of the content is part of the name, so that a
     * new plugin version gets a new file and an existing copy can be reused; written via a temporary file and moved,
     * so that a viewer never sees a half-written file. {@code null} if the plugin has no CHM file.
     */
    public static @Nullable Path extraChm() throws IOException {
        byte[] content;
        try (InputStream in = AhkExternalHelp.class.getResourceAsStream(EXTRA_CHM_RESOURCE)) {
            if (in == null) return null;
            content = in.readAllBytes();
        }
        CRC32 crc = new CRC32();
        crc.update(content);
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), TEMP_FOLDER);
        Path target = dir.resolve(String.format("extra-%08x.chm", crc.getValue()));
        if (Files.isRegularFile(target) && Files.size(target) == content.length) return target;
        Files.createDirectories(dir);
        Path part = Files.createTempFile(dir, "extra-", ".tmp");
        try {
            Files.write(part, content);
            try {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // e.g. the viewer has the old copy open: keep using it if it is complete
                if (!Files.isRegularFile(target) || Files.size(target) != content.length) throw e;
            }
        } finally {
            Files.deleteIfExists(part);
        }
        return target;
    }

    /**
     * {@code AutoHotkey.chm} next to the AutoHotkey program from the settings (or the automatically found one), for
     * the launcher {@code UX\AutoHotkeyUX.exe} in {@code ..\v2} or {@code ..}; {@code null} if there is none.
     */
    public static @Nullable Path findChm() {
        String program = AhkSettings.getInstance().findAutoHotkey();
        if (program == null) return null;
        Path dir;
        try {
            dir = Path.of(program).toAbsolutePath().getParent();
        } catch (RuntimeException e) {
            return null;
        }
        if (dir == null) return null;
        List<Path> candidates = new ArrayList<>(List.of(dir.resolve(CHM)));
        Path parent = dir.getParent();
        if (parent != null) {
            candidates.add(parent.resolve("v2").resolve(CHM));
            candidates.add(parent.resolve(CHM));
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    /**
     * {@code AutoHotkey.chm} next to the v2.1 interpreter ({@link AhkSettings#findAutoHotkeyV21(int)}); {@code null}
     * if there is none.
     */
    public static @Nullable Path findChmV21() {
        String program = AhkSettings.getInstance().findAutoHotkeyV21(0);
        if (program == null) return null;
        try {
            Path dir = Path.of(program).toAbsolutePath().getParent();
            Path chm = dir == null ? null : dir.resolve(CHM);
            return chm != null && Files.isRegularFile(chm) ? chm : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Shows an error dialog on the UI thread. */
    private static void error(@Nullable Project project, @NotNull String message) {
        ApplicationManager.getApplication().invokeLater(
                () -> Messages.showErrorDialog(project, message, AhkBundle.message("help.external.title")));
    }
}
