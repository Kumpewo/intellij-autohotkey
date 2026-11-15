package de.kumpewo.autohotkey.debug.dbgp;

import org.jdom.Element;
import org.jetbrains.annotations.NotNull;

/**
 * A breakpoint as the engine reports it (breakpoint_get, breakpoint_list). AutoHotkey moves a line breakpoint on a
 * line without code to the next line with code; {@code line} is that line.
 *
 * @param id        id given by the engine
 * @param type      {@code line} or {@code exception}
 * @param state     {@code enabled} or {@code disabled}
 * @param fileUri   URI of the file (line breakpoints), otherwise empty
 * @param line      line, 1-based (line breakpoints), otherwise 0
 * @param exception exception name ({@code Any}), otherwise empty
 */
public record DbgpBreakpoint(@NotNull String id, @NotNull String type, @NotNull String state,
                             @NotNull String fileUri, int line, @NotNull String exception) {

    /** Reads a {@code breakpoint} element. */
    public static @NotNull DbgpBreakpoint parse(@NotNull Element element) {
        return new DbgpBreakpoint(DbgpPacket.attr(element, "id"), DbgpPacket.attr(element, "type"),
                DbgpPacket.attr(element, "state"), DbgpPacket.attr(element, "filename"),
                DbgpPacket.intAttr(element, "lineno", 0), DbgpPacket.attr(element, "exception"));
    }
}
