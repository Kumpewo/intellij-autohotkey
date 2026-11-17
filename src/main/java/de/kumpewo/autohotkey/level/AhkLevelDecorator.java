package de.kumpewo.autohotkey.level;

import com.intellij.ide.projectView.PresentationData;
import com.intellij.ide.projectView.ProjectView;
import com.intellij.ide.projectView.ProjectViewNode;
import com.intellij.ide.projectView.ProjectViewNodeDecorator;
import com.intellij.openapi.fileTypes.FileTypeRegistry;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import de.kumpewo.autohotkey.AhkFileType;
import de.kumpewo.autohotkey.settings.AhkSettings;
import org.jetbrains.annotations.NotNull;

/**
 * Project view: a grey {@code v2.0} / {@code v2.1} (location string) after the name of every AutoHotkey file, its
 * language level ({@link AhkLevelInfo}). Switched off with the setting <em>Show language level in the Project
 * view</em> ({@link AhkSettings#isShowLevelInProjectView()}).
 */
public final class AhkLevelDecorator implements ProjectViewNodeDecorator {

    /** Adds the level to the node of an AutoHotkey file, unless the node already has a location string. */
    @Override
    public void decorate(@NotNull ProjectViewNode<?> node, @NotNull PresentationData data) {
        if (!AhkSettings.getInstance().isShowLevelInProjectView()) return;
        VirtualFile file = node.getVirtualFile();
        Project project = node.getProject();
        if (file == null || project == null || file.isDirectory()
                || !FileTypeRegistry.getInstance().isFileOfType(file, AhkFileType.INSTANCE)
                || !StringUtil.isEmpty(data.getLocationString())) {
            return;
        }
        AhkLevelInfo info = AhkLevelInfo.of(project, file);
        if (info != null) data.setLocationString(info.shortLabel());
    }

    /** Updates the Project view of all open projects (after a change of the settings). */
    public static void refreshAll() {
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (!project.isDisposed()) ProjectView.getInstance(project).refresh();
        }
    }
}
