package de.kumpewo.autohotkey.debug;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ColoredTextContainer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.xdebugger.XDebuggerUtil;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import com.intellij.xdebugger.frame.XCompositeNode;
import com.intellij.xdebugger.frame.XStackFrame;
import com.intellij.xdebugger.frame.XValueChildrenList;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.debug.dbgp.DbgpPacket;
import de.kumpewo.autohotkey.debug.dbgp.DbgpProperty;
import de.kumpewo.autohotkey.debug.dbgp.DbgpStackFrame;
import de.kumpewo.autohotkey.debug.dbgp.DbgpUris;
import de.kumpewo.autohotkey.highlighting.AhkNames;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * A frame of the stack: function or thread ({@code where}) with file and line. Its variables: the first context
 * of the engine ({@code Local}) directly, every other context ({@code Global}) as a group, expanded at once if there
 * are no local variables (top level, so that inline values appear). The frame's own variables show inline values.
 */
final class AhkStackFrame extends XStackFrame {

    private final AhkDebugController controller;
    private final int epoch;
    private final DbgpStackFrame frame;
    private final @Nullable VirtualFile file;

    AhkStackFrame(@NotNull AhkDebugController controller, int epoch, @NotNull DbgpStackFrame frame) {
        this.controller = controller;
        this.epoch = epoch;
        this.frame = frame;
        String path = DbgpUris.toPath(frame.fileUri());
        this.file = path == null ? null
                : LocalFileSystem.getInstance().findFileByPath(FileUtil.toSystemIndependentName(path));
    }

    /** Position in the file, or {@code null} if the file is not in the VFS. */
    @Override
    public @Nullable XSourcePosition getSourcePosition() {
        return file == null ? null : XDebuggerUtil.getInstance().createPosition(file, frame.line() - 1);
    }

    /** Function or thread, then file and line. */
    @Override
    public void customizePresentation(@NotNull ColoredTextContainer component) {
        component.append(frame.where(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
        String path = DbgpUris.toPath(frame.fileUri());
        String name = path == null ? frame.fileUri() : new File(path).getName();
        component.append(" " + name + ":" + frame.line(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
        component.setIcon(AllIcons.Debugger.Frame);
    }

    /** Evaluator for watches, Evaluate and hover in this frame (name paths only). */
    @Override
    public @NotNull XDebuggerEvaluator getEvaluator() {
        return new AhkDebuggerEvaluator(controller, epoch, frame.level());
    }

    /** Frames are equal if level and function are (keeps the selection after a step). */
    @Override
    public @Nullable Object getEqualityObject() {
        return frame.level() + ":" + frame.where();
    }

    /** Local variables directly, the other contexts as groups. */
    @Override
    public void computeChildren(@NotNull XCompositeNode node) {
        controller.contexts().thenCompose(contexts -> {
            if (contexts.isEmpty()) {
                node.addChildren(XValueChildrenList.EMPTY, true);
                return CompletableFuture.completedFuture(null);
            }
            DbgpPacket.DbgpContext first = contexts.get(0);
            return controller.contextGet(epoch, first.id(), frame.level()).thenAccept(response -> {
                XValueChildrenList list = new XValueChildrenList();
                for (DbgpProperty property : response.properties()) {
                    list.add(new AhkValue(controller, epoch, first.id(), frame.level(), property)
                            .inline(file, frame.line() - 1));
                }
                // without local variables (top level) the globals are expanded, so that inline values appear
                boolean expand = response.properties().isEmpty();
                for (DbgpPacket.DbgpContext context : contexts.subList(1, contexts.size())) {
                    list.addBottomGroup(new AhkContextGroup(controller, epoch, context, frame.level(), expand,
                            file, frame.line() - 1));
                }
                node.addChildren(list, true);
            });
        }).exceptionally(error -> {
            node.setErrorMessage(message(error));
            return null;
        });
    }

    /**
     * True for a built-in variable, class or function: facet {@code Builtin}, or a name from the built-in lists
     * ({@link AhkNames#nonMember}, of any language level); inside a function the engine lists e.g. {@code Array}, {@code Gui} and
     * {@code A_Args} as globals without that facet.
     */
    static boolean isBuiltIn(@NotNull DbgpProperty property) {
        if ("Builtin".equals(property.facet())) return true;
        for (de.kumpewo.autohotkey.parser.AhkLanguageLevel level : de.kumpewo.autohotkey.parser.AhkLanguageLevel.selectable()) {
            if (AhkNames.get(level).nonMember(property.name()) != null) return true;
        }
        return false;
    }

    /** Message of a failed request. */
    static @NotNull String message(@NotNull Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        String message = cause.getMessage();
        return message == null ? AhkBundle.message("debug.frame.unavailable") : message;
    }

    /** Variables of a context other than the first, with built-in variables in a sub-group. */
    static final class AhkContextGroup extends com.intellij.xdebugger.frame.XValueGroup {

        private final AhkDebugController controller;
        private final int epoch;
        private final DbgpPacket.DbgpContext context;
        private final int level;
        private final boolean autoExpand;
        private final @Nullable VirtualFile file;
        private final int line;

        AhkContextGroup(@NotNull AhkDebugController controller, int epoch, @NotNull DbgpPacket.DbgpContext context,
                        int level, boolean autoExpand, @Nullable VirtualFile file, int line) {
            super(context.name());
            this.controller = controller;
            this.epoch = epoch;
            this.context = context;
            this.level = level;
            this.autoExpand = autoExpand;
            this.file = file;
            this.line = line;
        }

        /** Expanded at once in a frame without local variables (top level). */
        @Override
        public boolean isAutoExpand() {
            return autoExpand;
        }

        /** Own variables (with inline values), then the group "Built-in" ({@link #isBuiltIn}). */
        @Override
        public void computeChildren(@NotNull XCompositeNode node) {
            controller.contextGet(epoch, context.id(), level).thenAccept(response -> {
                XValueChildrenList list = new XValueChildrenList();
                XValueChildrenList builtIn = new XValueChildrenList();
                for (DbgpProperty property : response.properties()) {
                    AhkValue value = new AhkValue(controller, epoch, context.id(), level, property);
                    if (isBuiltIn(property)) {
                        builtIn.add(value);
                    } else {
                        list.add(value.inline(file, line));
                    }
                }
                if (builtIn.size() > 0) list.addBottomGroup(new AhkFixedGroup(AhkBundle.message("debug.group.builtin"), builtIn));
                node.addChildren(list, true);
            }).exceptionally(error -> {
                node.setErrorMessage(message(error));
                return null;
            });
        }
    }

    /** A group with children that are already known. */
    static final class AhkFixedGroup extends com.intellij.xdebugger.frame.XValueGroup {

        private final XValueChildrenList children;

        AhkFixedGroup(@NotNull String name, @NotNull XValueChildrenList children) {
            super(name);
            this.children = children;
        }

        /** The known children. */
        @Override
        public void computeChildren(@NotNull XCompositeNode node) {
            node.addChildren(children, true);
        }
    }
}
