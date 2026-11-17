package de.kumpewo.autohotkey.level;

import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.impl.status.EditorBasedWidget;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.util.Consumer;
import com.intellij.util.concurrency.AppExecutorUtil;
import de.kumpewo.autohotkey.settings.AhkSettingsConfigurable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Component;
import java.awt.event.MouseEvent;

/**
 * Status bar widget "AHK v2.0" / "AHK v2.1": the language level of the AutoHotkey file in the selected editor
 * ({@link AhkLevelInfo}); empty (invisible) for other files. The tooltip says where the level comes from; a click
 * goes to the deciding {@code #Requires} line or, for the default level, opens the AutoHotkey settings (setting
 * <em>Language level</em>). Updated when another editor is selected and after every PSI change (in the background,
 * coalesced).
 */
final class AhkLevelWidget extends EditorBasedWidget implements StatusBarWidget.TextPresentation {

    private volatile @Nullable VirtualFile file;
    private volatile @Nullable AhkLevelInfo info;

    /** Widget for the project. */
    AhkLevelWidget(@NotNull Project project) {
        super(project);
    }

    /** ID of the widget, the same as that of its factory. */
    @Override
    public @NotNull String ID() {
        return AhkLevelWidgetFactory.ID;
    }

    /** The widget is its own text presentation. */
    @Override
    public @NotNull WidgetPresentation getPresentation() {
        return this;
    }

    /** Listens to PSI changes (a typed {@code #Requires}, another default level) and computes the first state. */
    @Override
    public void install(@NotNull StatusBar statusBar) {
        super.install(statusBar);
        myConnection.subscribe(PsiModificationTracker.TOPIC, (PsiModificationTracker.Listener) this::update);
        myConnection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
            /** Another editor was selected. */
            @Override
            public void selectionChanged(@NotNull FileEditorManagerEvent event) {
                update();
            }
        });
        update();
    }

    /** Computes the info of the selected file in a non-blocking read action and repaints the widget. */
    private void update() {
        if (isDisposed()) return;
        VirtualFile selected = getSelectedFile();
        Project project = getProject();
        ReadAction.nonBlocking(() -> AhkLevelInfo.of(project, selected))
                .expireWith(this)
                .coalesceBy(this)
                .finishOnUiThread(ModalityState.any(), result -> {
                    file = selected;
                    info = result;
                    if (myStatusBar != null) myStatusBar.updateWidget(ID());
                })
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    /** {@code AHK v2.1}, empty if the selected file is not an AutoHotkey file. */
    @Override
    public @NotNull String getText() {
        AhkLevelInfo current = info;
        return current == null ? "" : current.statusText();
    }

    /** Centered like the other text widgets. */
    @Override
    public float getAlignment() {
        return Component.CENTER_ALIGNMENT;
    }

    /** Level, its origin and what a click does. */
    @Override
    public @Nullable String getTooltipText() {
        AhkLevelInfo current = info;
        return current == null ? null : current.tooltip();
    }

    /** Click: go to the deciding {@code #Requires}, or open the settings for the default level. */
    @Override
    public @Nullable Consumer<MouseEvent> getClickConsumer() {
        return event -> {
            AhkLevelInfo current = info;
            VirtualFile target = file;
            Project project = getProject();
            if (current == null || project.isDisposed()) return;
            if (current.fromRequires() && target != null && target.isValid()) {
                new OpenFileDescriptor(project, target, current.requiresOffset()).navigate(true);
            } else {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, AhkSettingsConfigurable.class);
            }
        };
    }
}
