package de.kumpewo.autohotkey.debug;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectLocator;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.util.ThreeState;
import com.intellij.xdebugger.XDebuggerUtil;
import com.intellij.xdebugger.XExpression;
import com.intellij.xdebugger.frame.XInlineDebuggerDataCallback;
import com.intellij.xdebugger.frame.XCompositeNode;
import com.intellij.xdebugger.frame.XFullValueEvaluator;
import com.intellij.xdebugger.frame.XNamedValue;
import com.intellij.xdebugger.frame.XValueChildrenList;
import com.intellij.xdebugger.frame.XValueModifier;
import com.intellij.xdebugger.frame.XValueNode;
import com.intellij.xdebugger.frame.XValuePlace;
import com.intellij.xdebugger.frame.presentation.XNumericValuePresentation;
import com.intellij.xdebugger.frame.presentation.XRegularValuePresentation;
import com.intellij.xdebugger.frame.presentation.XStringValuePresentation;
import com.intellij.xdebugger.frame.presentation.XValuePresentation;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.debug.dbgp.DbgpProperty;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A variable, member or evaluated name path. Strings in quotes (a string shortened by the engine has a "full
 * value" link), numbers, {@code unset} for undefined values, objects with their class. Children are loaded page by
 * page ({@code property_get -p}); a full page gets a "more" link with the number of remaining children (for
 * {@code Array}/{@code Map} from {@code Length}/{@code Count}, because the engine's {@code numchildren} does not count
 * their entries). Values can be set ({@link AhkValueText} rules) and added to the watches ({@code fullName}).
 */
final class AhkValue extends XNamedValue {

    private final AhkDebugController controller;
    private final int epoch;
    private final int context;
    private final int level;
    private final DbgpProperty property;

    AhkValue(@NotNull AhkDebugController controller, int epoch, int context, int level,
             @NotNull DbgpProperty property) {
        this(property.name(), controller, epoch, context, level, property);
    }

    AhkValue(@NotNull String name, @NotNull AhkDebugController controller, int epoch, int context, int level,
             @NotNull DbgpProperty property) {
        super(name);
        this.controller = controller;
        this.epoch = epoch;
        this.context = context;
        this.level = level;
        this.property = property;
    }

    /**
     * Position of the frame for inline values ({@code name = value} in the editor); only set for the variables of a
     * frame, not for members or built-in variables.
     */
    private @Nullable VirtualFile inlineFile;
    private int inlineLine = -1;

    /** Enables inline values for this variable at the frame position (0-based line); returns this. */
    @NotNull AhkValue inline(@Nullable VirtualFile file, int line) {
        inlineFile = file;
        inlineLine = line;
        return this;
    }

    /** Address of an object (identity for "Mark Object"), otherwise empty. */
    @NotNull String address() {
        return "object".equals(property.type()) ? property.address() : "";
    }

    /** Lines where the editor shows the value: uses of the variable in the frame's scope ({@link AhkInlineValues}). */
    @Override
    public @NotNull ThreeState computeInlineDebuggerData(@NotNull XInlineDebuggerDataCallback callback) {
        VirtualFile file = inlineFile;
        if (file == null || inlineLine < 0) return ThreeState.NO;
        List<Integer> lines = ReadAction.compute(() -> {
            if (!file.isValid()) return List.<Integer>of();
            Project project = ProjectLocator.getInstance().guessProjectForFile(file);
            PsiFile psi = project == null ? null : PsiManager.getInstance(project).findFile(file);
            return psi == null ? List.<Integer>of() : AhkInlineValues.lines(psi, property.name(), inlineLine);
        });
        for (int line : lines) callback.computed(XDebuggerUtil.getInstance().createPosition(file, line));
        return ThreeState.YES;
    }

    /** Presentation according to the type. */
    @Override
    public void computePresentation(@NotNull XValueNode node, @NotNull XValuePlace place) {
        node.setPresentation(icon(property), presentation(property), property.hasChildren());
        if ("string".equals(property.type())
                && property.value().getBytes(StandardCharsets.UTF_8).length < property.size()) {
            node.setFullValueEvaluator(new XFullValueEvaluator() {
                @Override
                public void startEvaluation(@NotNull XFullValueEvaluationCallback callback) {
                    controller.propertyValue(epoch, property.fullName(), context, level)
                            .thenAccept(response -> callback.evaluated(response.text()))
                            .exceptionally(error -> {
                                callback.errorOccurred(AhkStackFrame.message(error));
                                return null;
                            });
                }
            });
        }
    }

    /** Value presentation of the type: string, number, unset, object, other. */
    static @NotNull XValuePresentation presentation(@NotNull DbgpProperty property) {
        return switch (property.type()) {
            case "string" -> new XStringValuePresentation(property.value());
            case "integer", "float" -> new XNumericValuePresentation(property.value());
            case "undefined" -> new XRegularValuePresentation("unset", null);
            case "object" -> new XRegularValuePresentation("", property.className().isEmpty() ? "Object"
                    : property.className());
            default -> new XRegularValuePresentation(property.value(), property.type());
        };
    }

    /** Icon: array for Array/Map, object, or primitive value. */
    private static @NotNull Icon icon(@NotNull DbgpProperty property) {
        if (!"object".equals(property.type())) return AllIcons.Debugger.Db_primitive;
        return "Array".equals(property.className()) || "Map".equals(property.className())
                ? AllIcons.Debugger.Db_array : AllIcons.Debugger.Value;
    }

    /** Path for "Add to Watches" and evaluation; none for the prototype {@code <base>}. */
    @Override
    public @Nullable String getEvaluationExpression() {
        return property.fullName().contains("<") ? null : property.fullName();
    }

    /** "Set Value" for everything except the prototype. */
    @Override
    public @Nullable XValueModifier getModifier() {
        if (property.fullName().isEmpty() || property.fullName().contains("<")) return null;
        return new XValueModifier() {
            @Override
            public @NotNull String getInitialValueEditorText() {
                return AhkValueText.editorText(property);
            }

            @Override
            public void setValue(@NotNull XExpression expression, @NotNull XModificationCallback callback) {
                AhkValueText.Typed typed = AhkValueText.parse(expression.getExpression());
                if (typed == null) {
                    callback.errorOccurred(AhkBundle.message("debug.set.invalid"));
                    return;
                }
                controller.propertySet(epoch, property.fullName(), context, level, typed)
                        .thenRun(callback::valueModified)
                        .exceptionally(error -> {
                            callback.errorOccurred(AhkStackFrame.message(error));
                            return null;
                        });
            }
        };
    }

    /** Loads the first page of children. */
    @Override
    public void computeChildren(@NotNull XCompositeNode node) {
        if (!property.hasChildren()) {
            node.addChildren(XValueChildrenList.EMPTY, true);
            return;
        }
        loadPage(node, 0, 0);
    }

    /** Adds one page; if it is full, offers the next page with the number of remaining children. */
    private void loadPage(@NotNull XCompositeNode node, int page, int loadedBefore) {
        controller.propertyGet(epoch, property.fullName(), context, level, page).thenCompose(response -> {
            List<DbgpProperty> children = response.properties().isEmpty() ? List.of()
                    : response.properties().get(0).children();
            int pageSize = response.properties().isEmpty() ? 0 : response.properties().get(0).pageSize();
            XValueChildrenList list = new XValueChildrenList();
            for (DbgpProperty child : children) list.add(new AhkValue(controller, epoch, context, level, child));
            int loaded = loadedBefore + children.size();
            if (pageSize <= 0 || children.size() < pageSize) {
                node.addChildren(list, true);
                return CompletableFuture.completedFuture(null);
            }
            return total().thenAccept(total -> {
                int remaining = total - loaded;
                node.addChildren(list, remaining <= 0);
                if (remaining > 0) node.tooManyChildren(remaining, () -> loadPage(node, page + 1, loaded));
            });
        }).exceptionally(error -> {
            node.setErrorMessage(AhkStackFrame.message(error));
            return null;
        });
    }

    /**
     * Number of children including {@code <base>}: for Array and Map {@code Length}/{@code Count} + 1, otherwise
     * {@code numchildren}.
     */
    private @NotNull CompletableFuture<Integer> total() {
        String countMember = switch (property.className()) {
            case "Array" -> "Length";
            case "Map" -> "Count";
            default -> null;
        };
        if (countMember == null) return CompletableFuture.completedFuture(property.numChildren());
        return controller.propertyGet(epoch, property.fullName() + "." + countMember, context, level)
                .thenApply(response -> {
                    try {
                        return Integer.parseInt(response.properties().get(0).value()) + 1;
                    } catch (RuntimeException e) {
                        return property.numChildren();
                    }
                });
    }
}
