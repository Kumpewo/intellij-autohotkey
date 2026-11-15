package de.kumpewo.autohotkey.debug;

import com.intellij.execution.ExecutionException;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugProcessStarter;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.debug.dbgp.DbgpServer;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Action "Attach to AutoHotkey Script..." (Run menu, Windows only): lists the running AutoHotkey v2 scripts
 * ({@link AhkAttach#scripts()}), opens the DBGp socket, asks the chosen script to connect and starts a debug
 * session with {@link AhkAttachedProcessHandler} (Stop detaches, the script keeps running).
 */
public class AhkAttachAction extends DumbAwareAction {

    /** Visible on Windows with a project. */
    @Override
    public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabledAndVisible(SystemInfo.isWindows && event.getProject() != null);
    }

    /** {@link #update} reads no data. */
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    /** Shows the list of running scripts. */
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) return;
        List<AhkAttach.Script> scripts = AhkAttach.scripts();
        if (scripts.isEmpty()) {
            Messages.showInfoMessage(project, AhkBundle.message("debug.attach.none"), AhkBundle.message("debug.attach.title"));
            return;
        }
        JBPopupFactory.getInstance().createPopupChooserBuilder(scripts)
                .setTitle(AhkBundle.message("debug.attach.title"))
                .setRenderer(new com.intellij.ui.SimpleListCellRenderer<>() {
                    @Override
                    public void customize(@NotNull javax.swing.JList<? extends AhkAttach.Script> list,
                                          AhkAttach.Script script, int index, boolean selected, boolean focus) {
                        setText(new File(script.path()).getName() + "  (" + script.path() + ", PID "
                                + script.processId() + ")");
                    }
                })
                .setItemChosenCallback(script -> attach(project, script))
                .createPopup()
                .showCenteredInCurrentWindow(project);
    }

    /** Opens the socket, asks the script to connect and starts the debug session. */
    static void attach(@NotNull Project project, @NotNull AhkAttach.Script script) {
        DbgpServer server;
        try {
            server = DbgpServer.open();
        } catch (IOException e) {
            Messages.showErrorDialog(project, AhkBundle.message("debug.socket.failed", e.getMessage()),
                    AhkBundle.message("debug.attach.title"));
            return;
        }
        AhkAttachedProcessHandler handler = new AhkAttachedProcessHandler();
        String name = new File(script.path()).getName();
        try {
            XDebuggerManager.getInstance(project).startSessionAndShowTab(name, null, new XDebugProcessStarter() {
                @Override
                public @NotNull XDebugProcess start(@NotNull XDebugSession session) {
                    return new AhkDebugProcess(session, server, handler,
                            AhkBundle.message("debug.attach.started", script.path(), script.processId()),
                            () -> AhkAttach.requestConnection(script, server.port()));
                }
            });
        } catch (ExecutionException e) {
            try {
                server.close();
            } catch (IOException ignored) {
                // nothing to do
            }
            Messages.showErrorDialog(project, e.getMessage(), AhkBundle.message("debug.attach.title"));
        }
    }
}
