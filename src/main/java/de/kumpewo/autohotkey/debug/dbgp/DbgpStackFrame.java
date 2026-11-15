package de.kumpewo.autohotkey.debug.dbgp;

import org.jdom.Element;
import org.jetbrains.annotations.NotNull;

/**
 * A frame of stack_get.
 *
 * @param level   0 = current frame
 * @param type    {@code file}
 * @param fileUri URI of the file (percent-encoded, see {@link DbgpUris})
 * @param line    line, 1-based
 * @param where   function or thread, e.g. {@code Add()}, {@code Auto-execute thread}, {@code Sleep()}
 */
public record DbgpStackFrame(int level, @NotNull String type, @NotNull String fileUri, int line,
                             @NotNull String where) {

    /** Reads a {@code stack} element. */
    public static @NotNull DbgpStackFrame parse(@NotNull Element element) {
        return new DbgpStackFrame(DbgpPacket.intAttr(element, "level", 0), DbgpPacket.attr(element, "type"),
                DbgpPacket.attr(element, "filename"), DbgpPacket.intAttr(element, "lineno", 0),
                DbgpPacket.attr(element, "where"));
    }
}
