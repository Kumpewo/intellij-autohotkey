package de.kumpewo.autohotkey.install;

import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * Common parts of the dialogs of Deploy and Set Version: the size of a dialog is remembered for all projects
 * ({@link #sizeKey}), texts are wrapped ({@link #wrap}), lists show one entry per line and only the first ones
 * ({@link #list}), and content that does not fit into the window scrolls ({@link #scrollable}).
 */
final class AhkDialogs {

    /** At most this many entries of a list are shown in a dialog. */
    static final int MAX_ITEMS = 5;

    /** At most this many errors are shown in a message. */
    static final int MAX_ERRORS = 10;

    /** At most this many entries of a list are shown in its tooltip. */
    private static final int MAX_TOOLTIP = 40;

    /** Texts are wrapped, and the entries of lists shortened, to this many characters. */
    static final int COLUMNS = 110;

    private AhkDialogs() {
    }

    /** The key under which the platform remembers the size of a dialog, with its prefix for all projects. */
    static @NotNull String sizeKey(@NotNull String name) {
        return "APPLICATION_WIDE_STORE_AutoHotkey." + name;
    }

    /** The first {@code max} entries, then a line {@code … (+n)} with the number of the others. */
    static @NotNull List<String> limit(@NotNull List<String> items, int max) {
        if (items.size() <= max) return items;
        List<String> result = new ArrayList<>(items.subList(0, max));
        result.add("… (+" + (items.size() - max) + ")");
        return result;
    }

    /** A text longer than {@link #COLUMNS} with an ellipsis in the middle (the end, the file name, stays longer). */
    static @NotNull String shorten(@NotNull String text) {
        return StringUtil.shortenTextWithEllipsis(text, COLUMNS, COLUMNS * 2 / 3, true);
    }

    /** Wraps each line of the text between words at the given column; a longer word stays whole. */
    static @NotNull String wrap(@NotNull String text, int columns) {
        StringBuilder result = new StringBuilder();
        boolean first = true;
        for (String line : text.split("\n", -1)) {
            if (!first) result.append('\n');
            first = false;
            int width = 0;
            for (String word : line.split(" ", -1)) {
                if (width > 0 && width + 1 + word.length() > columns) {
                    result.append('\n');
                    width = 0;
                } else if (width > 0) {
                    result.append(' ');
                    width++;
                }
                result.append(word);
                width += word.length();
            }
        }
        return result.toString();
    }

    /** A wrapped label in the given color. */
    static @NotNull JBLabel text(@NotNull String text, @NotNull Color color) {
        JBLabel label = new JBLabel(html(wrap(text, COLUMNS)));
        label.setForeground(color);
        return label;
    }

    /**
     * A heading with the entries below it, one per line, indented: at most {@link #MAX_ITEMS}, long ones shortened; the
     * tooltip shows what is missing.
     */
    static @NotNull JBLabel list(@NotNull String heading, @NotNull List<String> items, @NotNull Color color) {
        StringBuilder text = new StringBuilder(wrap(heading, COLUMNS));
        for (String item : limit(items, MAX_ITEMS)) text.append("\n    ").append(shorten(item));
        JBLabel label = new JBLabel(html(text.toString()));
        label.setForeground(color);
        if (items.size() > MAX_ITEMS || items.stream().anyMatch(item -> item.length() > COLUMNS)) {
            label.setToolTipText(html(String.join("\n", limit(items, MAX_TOOLTIP))));
        }
        return label;
    }

    /** Plain text as HTML with its line breaks. */
    private static @NotNull String html(@NotNull String text) {
        return "<html>" + StringUtil.escapeXmlEntities(text).replace("\n", "<br>") + "</html>";
    }

    /**
     * The content in a scroll pane without border: as large as the content at first (up to a limit); the content fills
     * a larger window, and scrolls in a smaller one.
     */
    static @NotNull JComponent scrollable(@NotNull JComponent content) {
        JBScrollPane scroll = new JBScrollPane(new Filling(content));
        scroll.setBorder(JBUI.Borders.empty());
        Dimension size = content.getPreferredSize();
        scroll.setPreferredSize(new Dimension(Math.min(size.width + JBUI.scale(24), JBUI.scale(1100)),
                Math.min(size.height + JBUI.scale(16), JBUI.scale(750))));
        return scroll;
    }

    /** A panel that is never smaller than the visible part of its scroll pane. */
    private static final class Filling extends JPanel implements Scrollable {

        /** Puts the content into the panel. */
        Filling(@NotNull JComponent content) {
            super(new BorderLayout());
            add(content, BorderLayout.CENTER);
            setBorder(JBUI.Borders.empty(4));
        }

        /** The size of the content. */
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        /** One line of text. */
        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return JBUI.scale(16);
        }

        /** The visible part. */
        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return orientation == SwingConstants.VERTICAL ? visible.height : visible.width;
        }

        /** Fills the width if the content is narrower. */
        @Override
        public boolean getScrollableTracksViewportWidth() {
            return getParent() instanceof JViewport viewport && viewport.getWidth() > getPreferredSize().width;
        }

        /** Fills the height if the content is lower. */
        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() instanceof JViewport viewport && viewport.getHeight() > getPreferredSize().height;
        }
    }
}
