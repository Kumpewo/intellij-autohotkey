package de.kumpewo.autohotkey.debug.dbgp;

import org.jdom.Element;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * A variable or member as the engine describes it ({@code property} element of property_get and context_get).
 * Objects have {@code children}; how many of them are included depends on {@code max_depth} and the page
 * ({@code max_children} per page). AutoHotkey names array elements {@code [1]}, {@code [2]} … and the prototype
 * {@code <base>}.
 *
 * @param name        name relative to the parent ({@code key}, {@code [1]})
 * @param fullName    path for property_get/property_set ({@code obj.key}, {@code arr[1]})
 * @param type        {@code integer}, {@code string}, {@code object}, {@code undefined} …
 * @param className   class of an object ({@code Array}, {@code Map}), otherwise empty
 * @param facet       e.g. {@code Builtin}, otherwise empty
 * @param address     identity of an object, otherwise empty
 * @param hasChildren true if the value can be expanded
 * @param numChildren number of children as reported by the engine
 * @param page        page of the included children
 * @param pageSize    children per page
 * @param size        length of the full value (the value itself may be shortened, see property_get {@code -m})
 * @param value       decoded value (empty for objects)
 * @param children    included children (may be fewer than {@code numChildren})
 */
public record DbgpProperty(@NotNull String name, @NotNull String fullName, @NotNull String type,
                           @NotNull String className, @NotNull String facet, @NotNull String address,
                           boolean hasChildren, int numChildren, int page, int pageSize, int size,
                           @NotNull String value, @NotNull List<DbgpProperty> children) {

    /** Reads a {@code property} element with its included children. */
    public static @NotNull DbgpProperty parse(@NotNull Element element) {
        return new DbgpProperty(DbgpPacket.attr(element, "name"), DbgpPacket.attr(element, "fullname"),
                DbgpPacket.attr(element, "type"), DbgpPacket.attr(element, "classname"),
                DbgpPacket.attr(element, "facet"), DbgpPacket.attr(element, "address"),
                "1".equals(DbgpPacket.attr(element, "children")), DbgpPacket.intAttr(element, "numchildren", 0),
                DbgpPacket.intAttr(element, "page", 0), DbgpPacket.intAttr(element, "pagesize", 0),
                DbgpPacket.intAttr(element, "size", 0),
                "object".equals(DbgpPacket.attr(element, "type")) ? "" : DbgpPacket.decode(element),
                children(element));
    }

    /** The {@code property} children of an element. */
    public static @NotNull List<DbgpProperty> children(@NotNull Element element) {
        List<DbgpProperty> result = new ArrayList<>();
        for (Element child : DbgpPacket.children(element, "property")) result.add(parse(child));
        return result;
    }
}
