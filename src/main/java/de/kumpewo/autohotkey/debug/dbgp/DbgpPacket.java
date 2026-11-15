package de.kumpewo.autohotkey.debug.dbgp;

import com.intellij.openapi.util.JDOMUtil;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * A packet from the engine: {@link Init} (first packet of a session), {@link Response} (answer to a command, same
 * transaction id), {@link Stream} (copied output), {@link Notify}, or {@link Unknown}. {@link #parse} reads the XML;
 * element names are compared without namespace.
 */
public sealed interface DbgpPacket permits DbgpPacket.Init, DbgpPacket.Response, DbgpPacket.Stream,
        DbgpPacket.Notify, DbgpPacket.Unknown {

    /**
     * First packet after the engine connected.
     *
     * @param fileUri  URI of the main script
     * @param language language name ({@code AutoHotkey})
     * @param appId    application id ({@code AutoHotkey})
     * @param thread   thread id of the engine
     */
    record Init(@NotNull String fileUri, @NotNull String language, @NotNull String appId, @NotNull String thread,
                @NotNull Element element) implements DbgpPacket {
    }

    /**
     * Answer to a command.
     *
     * @param command       name of the command (empty for the answer to {@code stop} of AutoHotkey)
     * @param transactionId transaction id of the command
     * @param status        execution status ({@code starting}, {@code break}, {@code running}, {@code stopping},
     *                      {@code stopped}) or empty
     * @param reason        reason of the status ({@code ok}, {@code error} …) or empty
     * @param error         error of the engine, or {@code null}
     * @param element       the whole {@code response} element for details
     */
    record Response(@NotNull String command, int transactionId, @NotNull String status, @NotNull String reason,
                    @Nullable DbgpError error, @NotNull Element element) implements DbgpPacket {

        /** True if the engine reported an error. */
        public boolean isError() {
            return error != null;
        }

        /** Attribute of the response element, empty if missing. */
        public @NotNull String attribute(@NotNull String name) {
            return attr(element, name);
        }

        /** The {@code property} children (property_get, context_get). */
        public @NotNull List<DbgpProperty> properties() {
            return DbgpProperty.children(element);
        }

        /** The {@code stack} children (stack_get). */
        public @NotNull List<DbgpStackFrame> frames() {
            List<DbgpStackFrame> frames = new ArrayList<>();
            for (Element child : children(element, "stack")) frames.add(DbgpStackFrame.parse(child));
            return frames;
        }

        /** The {@code breakpoint} children (breakpoint_get, breakpoint_list). */
        public @NotNull List<DbgpBreakpoint> breakpoints() {
            List<DbgpBreakpoint> breakpoints = new ArrayList<>();
            for (Element child : children(element, "breakpoint")) breakpoints.add(DbgpBreakpoint.parse(child));
            return breakpoints;
        }

        /** The {@code context} children (context_names) as id and name. */
        public @NotNull List<DbgpContext> contexts() {
            List<DbgpContext> contexts = new ArrayList<>();
            for (Element child : children(element, "context")) {
                contexts.add(new DbgpContext(intAttr(child, "id", 0), attr(child, "name")));
            }
            return contexts;
        }

        /** Text content of the response, decoded if {@code encoding="base64"} (feature_get, property_value). */
        public @NotNull String text() {
            return decode(element);
        }
    }

    /**
     * Output copied by the engine ({@code stdout -c 1}, {@code stderr -c 1}); AutoHotkey sends {@code OutputDebug}
     * as type {@code stderr}.
     *
     * @param type {@code stdout} or {@code stderr}
     * @param text decoded text (Base64, also without an {@code encoding} attribute, as AutoHotkey sends it) without
     *             the NUL character AutoHotkey appends
     */
    record Stream(@NotNull String type, @NotNull String text) implements DbgpPacket {
    }

    /** Notification of the engine (not sent by AutoHotkey 2.0, which does not support {@code notify_ok}). */
    record Notify(@NotNull String name, @NotNull Element element) implements DbgpPacket {
    }

    /** Any other packet (logged and ignored). */
    record Unknown(@NotNull Element element) implements DbgpPacket {
    }

    /** Error of a response. */
    record DbgpError(int code, @NotNull String message) {
    }

    /** A context of {@code context_names}: id ({@code -c}) and name ({@code Local}, {@code Global}). */
    record DbgpContext(int id, @NotNull String name) {
    }

    /**
     * Reads the XML of one packet.
     *
     * @throws DbgpException if the XML is malformed or a required attribute is missing
     */
    static @NotNull DbgpPacket parse(byte @NotNull [] xml) throws DbgpException {
        Element root;
        try {
            root = JDOMUtil.load(new ByteArrayInputStream(xml));
        } catch (JDOMException | IOException e) {
            throw new DbgpException("Malformed DBGp packet", e);
        }
        switch (root.getName()) {
            case "init" -> {
                return new Init(attr(root, "fileuri"), attr(root, "language"), attr(root, "appid"),
                        attr(root, "thread"), root);
            }
            case "response" -> {
                String id = attr(root, "transaction_id");
                int transactionId;
                try {
                    transactionId = Integer.parseInt(id);
                } catch (NumberFormatException e) {
                    throw new DbgpException("DBGp response without valid transaction_id: " + id);
                }
                DbgpError error = null;
                Element errorElement = child(root, "error");
                if (errorElement != null) {
                    Element message = child(errorElement, "message");
                    error = new DbgpError(intAttr(errorElement, "code", 0),
                            message == null ? "" : message.getTextTrim());
                }
                return new Response(attr(root, "command"), transactionId, attr(root, "status"),
                        attr(root, "reason"), error, root);
            }
            case "stream" -> {
                // AutoHotkey sends stream data Base64-encoded without an encoding attribute
                String text = root.getAttributeValue("encoding") == null ? decodeBase64(root.getText())
                        : decode(root);
                if (text.endsWith("\0")) text = text.substring(0, text.length() - 1);
                return new Stream(attr(root, "type"), text);
            }
            case "notify" -> {
                return new Notify(attr(root, "name"), root);
            }
            default -> {
                return new Unknown(root);
            }
        }
    }

    /** Attribute value, empty if missing. */
    static @NotNull String attr(@NotNull Element element, @NotNull String name) {
        String value = element.getAttributeValue(name);
        return value == null ? "" : value;
    }

    /** Integer attribute, {@code fallback} if missing or not a number. */
    static int intAttr(@NotNull Element element, @NotNull String name, int fallback) {
        try {
            return Integer.parseInt(attr(element, name));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** First child element with the local name, or {@code null}. */
    static @Nullable Element child(@NotNull Element element, @NotNull String name) {
        for (Element child : element.getChildren()) {
            if (child.getName().equals(name)) return child;
        }
        return null;
    }

    /** All child elements with the local name. */
    static @NotNull List<Element> children(@NotNull Element element, @NotNull String name) {
        List<Element> result = new ArrayList<>();
        for (Element child : element.getChildren()) {
            if (child.getName().equals(name)) result.add(child);
        }
        return result;
    }

    /** Text of the element (own text only), decoded as Base64 (UTF-8) if {@code encoding="base64"}. */
    static @NotNull String decode(@NotNull Element element) {
        String text = element.getText();
        if (!"base64".equals(element.getAttributeValue("encoding"))) return text;
        return decodeBase64(text);
    }

    /** Base64 (UTF-8) decoded text; the text itself if it is not valid Base64. */
    static @NotNull String decodeBase64(@NotNull String text) {
        try {
            return new String(Base64.getMimeDecoder().decode(text.trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return text;
        }
    }
}
