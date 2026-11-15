package de.kumpewo.autohotkey.debug;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.io.FileUtil;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.debug.dbgp.DbgpBreakpoint;
import de.kumpewo.autohotkey.debug.dbgp.DbgpCommand;
import de.kumpewo.autohotkey.debug.dbgp.DbgpConnection;
import de.kumpewo.autohotkey.debug.dbgp.DbgpException;
import de.kumpewo.autohotkey.debug.dbgp.DbgpPacket;
import de.kumpewo.autohotkey.debug.dbgp.DbgpProperty;
import de.kumpewo.autohotkey.debug.dbgp.DbgpStackFrame;
import de.kumpewo.autohotkey.debug.dbgp.DbgpUris;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Logic of a debug session, independent of the XDebugger UI (AhkDebugProcess is a thin adapter), so that it can be
 * tested against a fake engine.
 * <ul>
 *     <li>Start ({@link #attach}, also for a script attached later): {@code feature_set max_children},
 *     {@code stderr -c 1} (for {@code OutputDebug}), all breakpoints registered so far (line and exception), and
 *     only when they are confirmed the first {@code run}.</li>
 *     <li>Exception breakpoint: the engine stops at every error with the reason {@code exception} (caught) or
 *     {@code error} (uncaught); the stop then carries the exception breakpoint as hit breakpoint.</li>
 *     <li>Breakpoints: the key is the IDE breakpoint. The engine moves a breakpoint on a line without code to the
 *     next line with code and gives two breakpoints on the same target line the same id, so the table counts the
 *     keys per engine id and asks {@code breakpoint_get} for the real line.</li>
 *     <li>Continuation commands ({@code run}, {@code step_*}): the response arrives at the next break; then
 *     {@code stack_get}, and the hit breakpoint is the one whose engine line is the line of the top frame.</li>
 *     <li>Every resume increases the epoch; values requested for an older epoch are refused
 *     ({@link #isCurrent}), so late answers never fill a newer view.</li>
 *     <li>Value requests are chained, because {@code max_depth} is a global feature: 0 for {@code context_get}
 *     (otherwise the global context is huge), 1 for expanding a value (one page per request).</li>
 *     <li>Name paths ({@link #resolve}) are looked up locally, then globally; {@link #propertySet} sets values.</li>
 * </ul>
 * Callbacks run on the reader thread of the connection.
 */
public final class AhkDebugController {

    private static final Logger LOG = Logger.getInstance(AhkDebugController.class);

    /** Children per page of a value. */
    public static final int MAX_CHILDREN = 100;

    /** What the controller reports to the UI. */
    public interface Callbacks {
        /** The script stopped; {@code breakpoint} = key of the hit breakpoint or {@code null}. */
        void suspended(@NotNull Suspension suspension);

        /** The engine accepted the breakpoint. */
        void breakpointVerified(@NotNull Object key);

        /** The engine rejected the breakpoint. */
        void breakpointInvalid(@NotNull Object key, @NotNull String message);

        /** Text of {@code OutputDebug} (stream stderr of the engine). */
        void debugOutput(@NotNull String text);

        /** The session ended (connection closed). */
        void ended();
    }

    /**
     * A stop of the script.
     *
     * @param epoch      epoch of this stop (for value requests)
     * @param frames     stack, top frame first
     * @param breakpoint key of the hit breakpoint (also the exception breakpoint), or {@code null}
     * @param reason     reason of the engine: {@code ok}, {@code exception} (caught error), {@code error}
     *                   (uncaught error)
     */
    public record Suspension(int epoch, @NotNull List<DbgpStackFrame> frames, @Nullable Object breakpoint,
                             @NotNull String reason) {

        /** True for a stop at an error (exception breakpoint). */
        public boolean isException() {
            return REASON_CAUGHT.equals(reason) || REASON_UNCAUGHT.equals(reason);
        }
    }

    /** Reason of a stop at a caught error. */
    public static final String REASON_CAUGHT = "exception";

    /** Reason of a stop at an uncaught error. */
    public static final String REASON_UNCAUGHT = "error";

    /** A line breakpoint of the IDE: key, file path (Windows), line (1-based). */
    public record LineBreakpoint(@NotNull Object key, @NotNull String path, int line) {
    }

    /** A breakpoint the engine accepted. */
    private record Entry(@NotNull String engineId, @NotNull String path, int line) {
    }

    private final Callbacks callbacks;
    private @Nullable DbgpConnection connection;
    private final Map<Object, LineBreakpoint> requested = new LinkedHashMap<>();
    private final Map<Object, Entry> entries = new HashMap<>();
    private final Map<String, Set<Object>> keysById = new HashMap<>();
    private @Nullable String temporaryId;
    private @Nullable Object exceptionKey;
    private @Nullable String exceptionId;
    private int epoch;
    private boolean suspended;
    private boolean ended;
    private CompletableFuture<?> valueChain = CompletableFuture.completedFuture(null);
    private volatile int engineDepth = -1;
    private @Nullable CompletableFuture<List<DbgpPacket.DbgpContext>> contexts;

    /** Controller reporting to the callbacks. */
    public AhkDebugController(@NotNull Callbacks callbacks) {
        this.callbacks = callbacks;
    }

    /** Listener for the {@link DbgpConnection}: OutputDebug and the end of the session. */
    public @NotNull DbgpConnection.Listener listener() {
        return new DbgpConnection.Listener() {
            @Override
            public void stream(@NotNull DbgpPacket.Stream stream) {
                if ("stderr".equals(stream.type())) callbacks.debugOutput(stream.text());
            }

            @Override
            public void closed(@Nullable Throwable cause) {
                end();
            }
        };
    }

    /**
     * Starts the session on the connection (after the init packet): features, breakpoints registered so far, then
     * {@code run}. The returned future completes when {@code run} has been sent.
     */
    public @NotNull CompletableFuture<Void> attach(@NotNull DbgpConnection connection) {
        List<LineBreakpoint> initial;
        Object exception;
        synchronized (this) {
            this.connection = connection;
            initial = new ArrayList<>(requested.values());
            exception = exceptionKey;
        }
        connection.send(DbgpCommand.of("feature_set").arg('n', "max_children").arg('v', MAX_CHILDREN));
        connection.send(DbgpCommand.of("stderr").arg('c', 1));
        List<CompletableFuture<?>> confirmations = new ArrayList<>();
        for (LineBreakpoint breakpoint : initial) confirmations.add(setBreakpoint(connection, breakpoint));
        if (exception != null) confirmations.add(sendExceptionBreakpoint(connection, exception));
        return CompletableFuture.allOf(confirmations.toArray(CompletableFuture[]::new))
                .handle((result, error) -> null)
                .thenRun(this::resume);
    }

    /** Registers a breakpoint; before {@link #attach} it is sent at the start, afterwards at once. */
    public void addBreakpoint(@NotNull LineBreakpoint breakpoint) {
        DbgpConnection current;
        synchronized (this) {
            requested.put(breakpoint.key(), breakpoint);
            current = connection;
        }
        if (current != null) setBreakpoint(current, breakpoint);
    }

    /** Removes a breakpoint; the engine breakpoint is removed when no other key uses it. */
    public void removeBreakpoint(@NotNull Object key) {
        String removeId;
        DbgpConnection current;
        synchronized (this) {
            requested.remove(key);
            removeId = forget(key);
            current = connection;
        }
        if (removeId != null && current != null) {
            current.send(DbgpCommand.of("breakpoint_remove").arg('d', removeId));
        }
    }

    /** Removes the key from the table; returns the engine id if no other key uses it any more. */
    private @Nullable String forget(@NotNull Object key) {
        Entry entry = entries.remove(key);
        if (entry == null) return null;
        Set<Object> keys = keysById.get(entry.engineId());
        if (keys != null) {
            keys.remove(key);
            if (!keys.isEmpty()) return null;
            keysById.remove(entry.engineId());
        }
        return entry.engineId();
    }

    /** breakpoint_set, then breakpoint_get for the real line; reports verified or invalid. */
    private @NotNull CompletableFuture<?> setBreakpoint(@NotNull DbgpConnection connection,
                                                        @NotNull LineBreakpoint breakpoint) {
        return connection.send(DbgpCommand.of("breakpoint_set").arg('t', "line")
                        .arg('f', DbgpUris.toUri(breakpoint.path())).arg('n', breakpoint.line()))
                .thenCompose(response -> {
                    if (response.isError()) {
                        DbgpPacket.DbgpError error = response.error();
                        callbacks.breakpointInvalid(breakpoint.key(), error.code() == 202
                                ? AhkBundle.message("debug.breakpoint.no.code")
                                : AhkBundle.message("debug.breakpoint.error", error.code(), error.message()));
                        return CompletableFuture.completedFuture(null);
                    }
                    String id = response.attribute("id");
                    return connection.send(DbgpCommand.of("breakpoint_get").arg('d', id))
                            .thenAccept(details -> confirmed(connection, breakpoint, id, details));
                });
    }

    /** Stores the confirmed breakpoint (unless it was removed meanwhile) and reports it verified. */
    private void confirmed(@NotNull DbgpConnection connection, @NotNull LineBreakpoint breakpoint,
                           @NotNull String id, @NotNull DbgpPacket.Response details) {
        int line = breakpoint.line();
        String path = breakpoint.path();
        List<DbgpBreakpoint> list = details.breakpoints();
        if (!details.isError() && !list.isEmpty()) {
            line = list.get(0).line();
            String reported = DbgpUris.toPath(list.get(0).fileUri());
            if (reported != null) path = reported;
        }
        boolean stillWanted;
        synchronized (this) {
            stillWanted = requested.get(breakpoint.key()) == breakpoint;
            if (stillWanted) {
                forget(breakpoint.key());
                entries.put(breakpoint.key(), new Entry(id, path, line));
                keysById.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(breakpoint.key());
            } else if (keysById.containsKey(id)) {
                return;   // another key still uses this engine breakpoint
            }
        }
        if (stillWanted) {
            callbacks.breakpointVerified(breakpoint.key());
        } else {
            connection.send(DbgpCommand.of("breakpoint_remove").arg('d', id));
        }
    }

    /** Continue until the next breakpoint. */
    public void resume() {
        continueWith(DbgpCommand.of("run"));
    }

    /** Step into. */
    public void stepInto() {
        continueWith(DbgpCommand.of("step_into"));
    }

    /** Step over. */
    public void stepOver() {
        continueWith(DbgpCommand.of("step_over"));
    }

    /** Step out. */
    public void stepOut() {
        continueWith(DbgpCommand.of("step_out"));
    }

    /** Run to the line (temporary breakpoint, removed at the next stop). */
    public void runTo(@NotNull String path, int line) {
        DbgpConnection current = connection();
        if (current == null) return;
        current.send(DbgpCommand.of("breakpoint_set").arg('t', "line").arg('f', DbgpUris.toUri(path))
                        .arg('n', line).arg('r', 1))
                .thenAccept(response -> {
                    if (!response.isError()) {
                        synchronized (this) {
                            temporaryId = response.attribute("id");
                        }
                    }
                    resume();
                });
    }

    /**
     * Registers the exception breakpoint ({@code breakpoint_set -t exception}, the engine supports only "Any"): the
     * engine then stops at every error, caught or not; the caller filters by {@link Suspension#reason()}.
     */
    public void setExceptionBreakpoint(@NotNull Object key) {
        DbgpConnection current;
        synchronized (this) {
            exceptionKey = key;
            current = connection;
        }
        if (current != null) sendExceptionBreakpoint(current, key);
    }

    /** Removes the exception breakpoint. */
    public void removeExceptionBreakpoint() {
        String id;
        DbgpConnection current;
        synchronized (this) {
            exceptionKey = null;
            id = exceptionId;
            exceptionId = null;
            current = connection;
        }
        if (id != null && current != null) current.send(DbgpCommand.of("breakpoint_remove").arg('d', id));
    }

    /** breakpoint_set for the exception breakpoint; reports it verified or invalid. */
    private @NotNull CompletableFuture<?> sendExceptionBreakpoint(@NotNull DbgpConnection connection,
                                                                 @NotNull Object key) {
        return connection.send(DbgpCommand.of("breakpoint_set").arg('t', "exception")).thenAccept(response -> {
            if (response.isError()) {
                callbacks.breakpointInvalid(key, AhkBundle.message("debug.breakpoint.error", response.error().code(),
                        response.error().message()));
                return;
            }
            String id = response.attribute("id");
            boolean wanted;
            synchronized (this) {
                wanted = exceptionKey == key;
                if (wanted) exceptionId = id;
            }
            if (wanted) {
                callbacks.breakpointVerified(key);
            } else {
                connection.send(DbgpCommand.of("breakpoint_remove").arg('d', id));
            }
        });
    }

    /**
     * Detaches: the script continues without the debugger ({@code detach}); completes when the engine answered or
     * the connection is gone, then the connection is closed.
     */
    public @NotNull CompletableFuture<?> detach() {
        DbgpConnection current = connection();
        if (current == null || current.isClosed()) return CompletableFuture.completedFuture(null);
        return current.send(DbgpCommand.of("detach")).handle((response, error) -> {
            current.close();
            return null;
        });
    }

    /** Pause a running script ({@code break}); the waiting continuation command then reports the stop. */
    public void pause() {
        DbgpConnection current = connection();
        if (current != null) current.send(DbgpCommand.of("break"));
    }

    /** Ends the script ({@code stop}); completes when the engine answered or the connection is gone. */
    public @NotNull CompletableFuture<?> stop() {
        DbgpConnection current = connection();
        if (current == null || current.isClosed()) return CompletableFuture.completedFuture(null);
        return current.send(DbgpCommand.of("stop")).handle((response, error) -> {
            current.close();
            return null;
        });
    }

    /** True while the values of {@code epoch} are valid (the script has not continued since). */
    public synchronized boolean isCurrent(int epoch) {
        return suspended && this.epoch == epoch && !ended;
    }

    /** The contexts of the engine ({@code Local}, {@code Global}), asked once per session. */
    public synchronized @NotNull CompletableFuture<List<DbgpPacket.DbgpContext>> contexts() {
        if (contexts == null) {
            DbgpConnection current = connection;
            contexts = current == null ? CompletableFuture.failedFuture(new DbgpException("Not connected"))
                    : current.send(DbgpCommand.of("context_names")).thenApply(DbgpPacket.Response::contexts);
        }
        return contexts;
    }

    /** Variables of a context in a frame (without their children). */
    public @NotNull CompletableFuture<DbgpPacket.Response> contextGet(int epoch, int context, int level) {
        return valueRequest(epoch, 0, DbgpCommand.of("context_get").arg('c', context).arg('d', level));
    }

    /** A value with its first page of children. */
    public @NotNull CompletableFuture<DbgpPacket.Response> propertyGet(int epoch, @NotNull String fullName,
                                                                        int context, int level) {
        return propertyGet(epoch, fullName, context, level, 0);
    }

    /**
     * A value with one page of children: page 0 holds {@code <base>} and the first {@code MAX_CHILDREN - 1}
     * entries, page n ≥ 1 the next {@link #MAX_CHILDREN}.
     */
    public @NotNull CompletableFuture<DbgpPacket.Response> propertyGet(int epoch, @NotNull String fullName,
                                                                        int context, int level, int page) {
        DbgpCommand command = DbgpCommand.of("property_get").arg('n', fullName).arg('c', context).arg('d', level);
        if (page > 0) command.arg('p', page);
        return valueRequest(epoch, 1, command);
    }

    /** A resolved name path: the context it was found in and its value. */
    public record Resolved(int context, @NotNull DbgpProperty property) {
    }

    /**
     * Value of a name path ({@link AhkNamePath}) in a frame, in the context its first name belongs to: the first
     * context ({@code Local}) if the frame lists that name among its local variables, otherwise the second
     * ({@code Global}). The engine would also find a global the function only reads in the local context, but
     * {@code property_set} through the local context does not change the global variable (observed with 2.0.31).
     */
    public @NotNull CompletableFuture<Resolved> resolve(int epoch, @NotNull String path, int level) {
        String root = rootName(path);
        return contexts().thenCompose(contexts -> {
            int local = contexts.isEmpty() ? 0 : contexts.get(0).id();
            if (contexts.size() < 2) return get(epoch, path, local, level);
            int global = contexts.get(1).id();
            return contextGet(epoch, local, level).thenCompose(locals -> {
                boolean isLocal = locals.properties().stream().anyMatch(p -> p.name().equalsIgnoreCase(root));
                return get(epoch, path, isLocal ? local : global, level);
            });
        });
    }

    /** property_get of the path in the context, as {@link Resolved}. */
    private @NotNull CompletableFuture<Resolved> get(int epoch, @NotNull String path, int context, int level) {
        return propertyGet(epoch, path, context, level).thenApply(response -> new Resolved(context, first(response)));
    }

    /** First name of a path: {@code obj} of {@code obj.key} or {@code m["k"]}. */
    static @NotNull String rootName(@NotNull String path) {
        int end = 0;
        while (end < path.length() && path.charAt(end) != '.' && path.charAt(end) != '[') end++;
        return path.substring(0, end);
    }

    /** The property of a property_get response; fails with the engine error. */
    private static @NotNull DbgpProperty first(@NotNull DbgpPacket.Response response) {
        if (response.isError() || response.properties().isEmpty()) {
            throw new java.util.concurrent.CompletionException(new DbgpException(
                    AhkBundle.message("debug.engine.error", response.isError() ? response.error().code() : 0)));
        }
        return response.properties().get(0);
    }

    /** Sets a value ({@code property_set} with type and data); fails with the engine error. */
    public @NotNull CompletableFuture<Void> propertySet(int epoch, @NotNull String fullName, int context, int level,
                                                       @NotNull AhkValueText.Typed value) {
        return valueRequest(epoch, -1, DbgpCommand.of("property_set").arg('n', fullName).arg('c', context)
                .arg('d', level).arg('t', value.type()).data(value.data())).thenAccept(response -> {
            if (response.isError() || !"1".equals(response.attribute("success"))) {
                throw new java.util.concurrent.CompletionException(new DbgpException(AhkBundle.message(
                        "debug.engine.error", response.isError() ? response.error().code() : 0)));
            }
        });
    }

    /** The full value of a shortened string. */
    public @NotNull CompletableFuture<DbgpPacket.Response> propertyValue(int epoch, @NotNull String fullName,
                                                                          int context, int level) {
        return valueRequest(epoch, -1, DbgpCommand.of("property_value").arg('n', fullName).arg('c', context)
                .arg('d', level));
    }

    /**
     * Sends a value command after the previous ones, with {@code max_depth} set to {@code depth} first if needed
     * ({@code -1} = any). Fails if the epoch is no longer current.
     */
    private synchronized @NotNull CompletableFuture<DbgpPacket.Response> valueRequest(int epoch, int depth,
                                                                                    @NotNull DbgpCommand command) {
        DbgpConnection current = connection;
        if (current == null || !isCurrent(epoch)) {
            return CompletableFuture.failedFuture(new DbgpException(AhkBundle.message("debug.frame.unavailable")));
        }
        CompletableFuture<DbgpPacket.Response> result = valueChain.handle((value, error) -> null).thenCompose(ignored -> {
            if (!isCurrent(epoch)) {
                return CompletableFuture.failedFuture(new DbgpException(AhkBundle.message("debug.frame.unavailable")));
            }
            CompletableFuture<?> prepared = CompletableFuture.completedFuture(null);
            if (depth >= 0 && depth != engineDepth) {
                prepared = current.send(DbgpCommand.of("feature_set").arg('n', "max_depth").arg('v', depth))
                        .thenRun(() -> engineDepth = depth);
            }
            return prepared.thenCompose(done -> current.send(command));
        });
        valueChain = result;
        return result;
    }

    /** Sends a continuation command and handles the status in its response. */
    private void continueWith(@NotNull DbgpCommand command) {
        DbgpConnection current;
        synchronized (this) {
            current = connection;
            if (current == null || ended) return;
            epoch++;
            suspended = false;
        }
        current.send(command).thenAccept(response -> status(current, response)).exceptionally(error -> {
            LOG.debug("Continuation command failed: " + command.name(), error);
            return null;
        });
    }

    /** Status of a continuation response: break = fetch the stack; stopping = let the script end. */
    private void status(@NotNull DbgpConnection current, @NotNull DbgpPacket.Response response) {
        switch (response.status()) {
            case "break" -> current.send(DbgpCommand.of("stack_get"))
                    .thenAccept(stack -> suspend(current, stack, response.reason()));
            case "stopping" -> current.send(DbgpCommand.of("stop")).whenComplete((stop, error) -> current.close());
            case "stopped" -> current.close();
            default -> LOG.warn("Unexpected status after " + response.command() + ": " + response.status());
        }
    }

    /**
     * Reports the stop with the stack and the hit breakpoint (the exception breakpoint for the reasons
     * {@code exception}/{@code error}); removes a temporary breakpoint. A paused idle script has no frames.
     */
    private void suspend(@NotNull DbgpConnection current, @NotNull DbgpPacket.Response stack, @NotNull String reason) {
        List<DbgpStackFrame> frames = stack.frames();
        Object hit = null;
        String temporary;
        int stopEpoch;
        synchronized (this) {
            if (ended) return;
            suspended = true;
            stopEpoch = epoch;
            temporary = temporaryId;
            temporaryId = null;
            if (REASON_CAUGHT.equals(reason) || REASON_UNCAUGHT.equals(reason)) {
                hit = exceptionKey;
            } else if (!frames.isEmpty()) {
                hit = breakpointAt(frames.get(0));
            }
        }
        if (temporary != null) current.send(DbgpCommand.of("breakpoint_remove").arg('d', temporary));
        callbacks.suspended(new Suspension(stopEpoch, frames, hit, reason));
    }

    /** Key of the first breakpoint whose engine line is the line of the frame (same file), or {@code null}. */
    private @Nullable Object breakpointAt(@NotNull DbgpStackFrame frame) {
        String path = DbgpUris.toPath(frame.fileUri());
        if (path == null) return null;
        String normalized = normalize(path);
        for (Map.Entry<Object, Entry> entry : entries.entrySet()) {
            Entry value = entry.getValue();
            if (value.line() == frame.line() && normalize(value.path()).equals(normalized)) return entry.getKey();
        }
        return null;
    }

    /** Path for comparing (Windows paths are case-insensitive). */
    private static @NotNull String normalize(@NotNull String path) {
        return FileUtil.toSystemIndependentName(path).toLowerCase(Locale.ROOT);
    }

    /** The connection, or {@code null} before {@link #attach} and after the end. */
    private synchronized @Nullable DbgpConnection connection() {
        return ended ? null : connection;
    }

    /** Ends the session once. */
    private void end() {
        synchronized (this) {
            if (ended) return;
            ended = true;
            suspended = false;
        }
        callbacks.ended();
    }
}
