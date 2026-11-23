package de.kumpewo.autohotkey.settings;

import com.intellij.ui.SimpleListCellRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JList;
import java.util.function.Function;

/**
 * Renderer for combo boxes and popup lists that shows a text for each value (no text for {@code null}). A subclass,
 * because the factory methods {@code SimpleListCellRenderer.create} are scheduled for removal from the platform.
 *
 * @param <T> the values of the list
 */
public final class AhkTextRenderer<T> extends SimpleListCellRenderer<T> {
    private final Function<? super T, String> text;

    /** A renderer that shows {@code text} of each value. */
    public AhkTextRenderer(@NotNull Function<? super T, String> text) {
        this.text = text;
    }

    /** Sets the text of the value. */
    @Override
    public void customize(@NotNull JList<? extends T> list, @Nullable T value, int index, boolean selected,
                          boolean focus) {
        setText(value == null ? "" : text.apply(value));
    }
}
