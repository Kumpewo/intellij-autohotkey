package de.kumpewo.autohotkey.install;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.settings.AhkSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The default {@code settings.ahk-v2-plugin.ini} ({@link AhkDeployConfig}): a file with all options commented out (like
 * the configuration files of many Linux programs; a switch shows the value that changes its default), from the resource
 * {@value #TEMPLATE}, with the explanations of {@link AhkSettingsExplain#restore} unless the settings page turns them
 * off. {@code {tool}} in the resource is replaced by the project name, the example lines of the sections come from the
 * files in the project root (also in a project without AutoHotkey files). Created in the project root by the action
 * "Create Settings..." ({@link AhkCreateSettingsAction}) and offered by "Deploy to Local Target..." when the file is
 * missing.
 */
public final class AhkSettingsFile {

    /** Resource with the default file. */
    static final String TEMPLATE = "/install/settings.ahk-v2-plugin.ini";

    private AhkSettingsFile() {
    }

    /**
     * The default file for the tool (CR LF line ends, as usual for INI files on Windows). With {@code sharedBanner}
     * the line {@code Banner=find:banner.txt} is active, otherwise commented out ({@code $find-banner$} in
     * the template becomes nothing or {@code ;}).
     */
    public static @NotNull String text(@NotNull String tool, boolean sharedBanner) {
        return text(tool, sharedBanner, List.of(), true);
    }

    /**
     * The default file for the tool with example lines taken from the project: {@code rootFiles} are the names of
     * the files directly in the project root, {@code autoHotkey} says whether the project contains AutoHotkey files
     * (an AutoHotkey file among {@code rootFiles} counts too). The commented-out examples of the sections
     * ({@code $install$}, {@code $install-if-missing$}, {@code $banner$}, {@code $version$}, {@code $cleanup$} in the template)
     * come from {@link #examples}.
     */
    static @NotNull String text(@NotNull String tool, boolean sharedBanner, @NotNull Collection<String> rootFiles,
                                boolean autoHotkey) {
        Examples examples = examples(tool, rootFiles, autoHotkey);
        String text = AhkSettingsExplain.template(TEMPLATE)
                .replace("$install$\n", lines(examples.install(), "."))
                .replace("$install-if-missing$\n", lines(examples.ifMissing(), "%A_AppData%\\{tool}\\"))
                .replace("$banner$\n", lines(examples.banner(), "yes"))
                .replace("$version$\n", lines(examples.version(), "yes"))
                .replace("$cleanup$\n", lines(List.of(examples.cleanup()), "."))
                .replace("{tool}", tool).replace("$find-banner$", sharedBanner ? "" : ";");
        return complete(text, false, tool);
    }

    /**
     * The text of a new file (CR LF): with the explanations, unless the settings page turns them off.
     *
     * @param text the filled template ({@code \n} as separator)
     */
    static @NotNull String complete(@NotNull String text, boolean library, @NotNull String name) {
        if (AhkSettings.getInstance().isExplainNewSettings()) text = AhkSettingsExplain.restore(text, library, name);
        return text.replace("\n", "\r\n");
    }

    /** Commented-out lines {@code ;key=value}, each ending with {@code \n}. */
    private static @NotNull String lines(@NotNull List<String> keys, @NotNull String value) {
        StringBuilder result = new StringBuilder();
        for (String key : keys) result.append(';').append(key).append('=').append(value).append('\n');
        return result.toString();
    }

    /** At most this many example lines are generated for one section. */
    static final int MAX_EXAMPLES = 10;

    /** Lower-case extensions of configuration files: examples for {@code [InstallIfMissing]} instead of {@code [Install]}. */
    private static final List<String> CONFIG_TYPES = List.of("ini", "cfg", "conf", "yaml", "yml", "toml",
            "properties");

    /**
     * The keys of the example lines of a new file.
     *
     * @param install   sources for {@code [Install]} (target {@code .})
     * @param ifMissing sources for {@code [InstallIfMissing]} (target {@code %A_AppData%\{tool}\})
     * @param banner    selectors for {@code [AddBanner]} (value {@code yes})
     * @param version   selectors for {@code [SetVersion]} (value {@code yes})
     * @param cleanup   the file for {@code [Cleanup]} (folder {@code .})
     */
    record Examples(@NotNull List<String> install, @NotNull List<String> ifMissing, @NotNull List<String> banner,
                    @NotNull List<String> version, @NotNull String cleanup) {
    }

    /**
     * The example lines, taken from the files of a known type directly in the project root (a built-in comment style,
     * {@link AhkFileRules#builtinStyle}, or {@code .exe}; not this settings file, not names starting with a dot),
     * sorted by name, at most {@link #MAX_EXAMPLES} per section:
     * <ul>
     *   <li>{@code [Install]}: in an AutoHotkey project first {@code <tool>.ahk} and {@code <tool>.exe}, then the
     *       files except configuration files; without any {@code <tool>.cmd};</li>
     *   <li>{@code [InstallIfMissing]}: the configuration files ({@link #CONFIG_TYPES}); without any {@code <tool>.ini};</li>
     *   <li>{@code [AddBanner]}: {@code *.<ext>} for each type with a comment style except the AutoHotkey types
     *       (which are included anyway); without any {@code *.cmd};</li>
     *   <li>{@code [SetVersion]}: the types as for the banner (AutoHotkey files get the version anyway); without any
     *       {@code *.cmd};</li>
     *   <li>{@code [Cleanup]}: {@code <tool>-old.exe} in an AutoHotkey project, otherwise {@code <name>-old.<ext>}
     *       of the first {@code [Install]} example.</li>
     * </ul>
     */
    static @NotNull Examples examples(@NotNull String tool, @NotNull Collection<String> rootFiles, boolean autoHotkey) {
        TreeMap<String, String> known = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String name : rootFiles) {
            if (name.startsWith(".") || AhkDeployConfig.isSettingsName(name)) continue;
            String extension = AhkFileRules.extension(name);
            if (AhkFileRules.builtinStyle(name) == null && !extension.equals("exe")) continue;
            known.putIfAbsent(name, name);
            if (AhkFileRules.isAutoHotkey(name)) autoHotkey = true;
        }
        List<String> install = new ArrayList<>();
        if (autoHotkey) {
            install.add(tool + ".ahk");
            install.add(tool + ".exe");
        }
        List<String> ifMissing = new ArrayList<>();
        TreeSet<String> types = new TreeSet<>();
        for (String name : known.values()) {
            String extension = AhkFileRules.extension(name);
            if (CONFIG_TYPES.contains(extension)) {
                if (ifMissing.size() < MAX_EXAMPLES) ifMissing.add(name);
            } else if (install.size() < MAX_EXAMPLES && install.stream().noneMatch(name::equalsIgnoreCase)) {
                install.add(name);
            }
            if (!extension.equals("exe") && !AhkFileRules.isAutoHotkey(name)) types.add("*." + extension);
        }
        if (install.isEmpty()) install.add(tool + ".cmd");
        if (ifMissing.isEmpty()) ifMissing.add(tool + ".ini");
        List<String> banner = new ArrayList<>(types.stream().limit(MAX_EXAMPLES).toList());
        List<String> version = new ArrayList<>(types.stream().limit(MAX_EXAMPLES).toList());
        if (banner.isEmpty()) banner.add("*.cmd");
        if (version.isEmpty()) version.add("*.cmd");
        String cleanup;
        if (autoHotkey) {
            cleanup = tool + "-old.exe";
        } else {
            String first = install.get(0);
            int dot = first.lastIndexOf('.');
            cleanup = dot > 0 ? first.substring(0, dot) + "-old" + first.substring(dot) : first + "-old";
        }
        return new Examples(install, ifMissing, banner, version, cleanup);
    }

    /**
     * Creates the default file in the project root and opens it (call on the EDT); an existing file is only opened,
     * never overwritten.
     */
    public static void create(@NotNull Project project) {
        create(project, (String) null);
    }

    /**
     * Creates the default file in the project root and opens it, with the caret at the header {@code [section]}
     * ({@code null}: at the start); an existing file is only opened (call on the EDT).
     */
    public static void create(@NotNull Project project, @Nullable String section) {
        VirtualFile dir = AhkProjectRoot.root(project);
        if (dir != null) create(project, dir, section);
    }

    /**
     * Creates the default file in {@code dir} and opens it; an existing file (also the {@code settings.ahk-v2-plugin.ini}
     * of version 2.1) is only opened. {@code Banner} is
     * active if {@code dir} or a folder above it contains {@code banner.txt} ({@link AhkBanner#find}). Below the folder
     * of the library projects ({@link AhkLibraryProjects#isBelow}) the file is the one of a library project
     * ({@link AhkNewLibraryProject#existing}).
     */
    static void create(@NotNull Project project, @NotNull VirtualFile dir) {
        create(project, dir, null);
    }

    /** Like {@link #create(Project, VirtualFile)}, then the caret goes to the header {@code [section]} if given. */
    static void create(@NotNull Project project, @NotNull VirtualFile dir, @Nullable String section) {
        String title = AhkBundle.message("settings.file.title");
        VirtualFile existing = dir.findChild(AhkDeployConfig.FILE_NAME);
        if (existing == null) existing = dir.findChild(AhkDeployConfig.LEGACY_FILE_NAME);
        if (existing != null) {
            open(project, existing, section);
            return;
        }
        java.nio.file.Path path = dir.getFileSystem().getNioPath(dir);
        boolean sharedBanner = path != null && AhkBanner.find(path, AhkNewLibraryProject.SHARED_BANNER) != null;
        List<String> rootFiles = new ArrayList<>();
        for (VirtualFile child : dir.getChildren()) {
            if (!child.isDirectory()) rootFiles.add(child.getName());
        }
        VirtualFile[] created = new VirtualFile[1];
        try {
            String text = path != null && AhkLibraryProjects.isBelow(path, AhkLibraryLayout.projectsFolder())
                    ? AhkNewLibraryProject.existing(dir.getName(), rootFiles, sharedBanner)
                    : text(project.getName(), sharedBanner, rootFiles, AhkProjectRoot.containsAhkFiles(project));
            WriteCommandAction.writeCommandAction(project).withName(title).run(() -> {
                VirtualFile target = dir.createChildData(AhkSettingsFile.class, AhkDeployConfig.FILE_NAME);
                VfsUtil.saveText(target, text);
                created[0] = target;
            });
        } catch (IOException e) {
            Messages.showErrorDialog(project, AhkBundle.message("settings.file.failed", e.getMessage()), title);
            return;
        }
        if (created[0] != null) open(project, created[0], section);
    }

    /** Opens the settings file, with the caret at the header {@code [section]} if given and present. */
    static void open(@NotNull Project project, @NotNull VirtualFile file, @Nullable String section) {
        Document document = section == null ? null : FileDocumentManager.getInstance().getDocument(file);
        int line = document == null ? -1 : AhkSetVersion.sectionLine(document.getText(), section);
        if (line < 0) {
            FileEditorManager.getInstance(project).openFile(file, true);
        } else {
            new OpenFileDescriptor(project, file, line, 0).navigate(true);
        }
    }
}
