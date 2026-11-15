package de.kumpewo.autohotkey.debug.dbgp;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One DBGp session over a socket the engine connected to. A reader thread splits the incoming bytes into packets
 * ({@link DbgpFramer}) and dispatches them: the init packet completes {@link #init()}, a response completes the
 * future of the command with the same transaction id, streams and notifications go to the {@link Listener}.
 * <p>
 * {@link #send} may be called from any thread and at any time, also while a continuation command ({@code run},
 * {@code step_*}) is still waiting for its response (the engine answers it only at the next break): commands are
 * written in the order of the calls, each with the next transaction id. Malformed or unknown packets are logged and
 * skipped; a broken packet stream or a closed socket ends the session: pending futures fail with a
 * {@link DbgpException} and {@link Listener#closed} is called once. All traffic is logged at TRACE level.
 */
public final class DbgpConnection implements Disposable {

    private static final Logger LOG = Logger.getInstance(DbgpConnection.class);

    /** Receives packets that are not responses, and the end of the session (on the reader thread). */
    public interface Listener {
        /** Output copied by the engine (stdout/stderr, OutputDebug). */
        default void stream(@NotNull DbgpPacket.Stream stream) {
        }

        /** A notification of the engine. */
        default void notify(@NotNull DbgpPacket.Notify notify) {
        }

        /** The session ended: closed by the engine, a broken stream ({@code cause}) or {@link #close()}. */
        default void closed(@Nullable Throwable cause) {
        }
    }

    /** A command waiting for its response. */
    private record Pending(@NotNull DbgpCommand command, @NotNull CompletableFuture<DbgpPacket.Response> future) {
    }

    private final Socket socket;
    private final Listener listener;
    private final OutputStream output;
    private final CompletableFuture<DbgpPacket.Init> init = new CompletableFuture<>();
    private final Map<Integer, Pending> pending = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();
    private int nextTransactionId = 1;
    private volatile boolean closed;
    private boolean started;

    /** Connection over the socket; reading starts with {@link #start()}. */
    public DbgpConnection(@NotNull Socket socket, @NotNull Listener listener) throws IOException {
        this.socket = socket;
        this.listener = listener;
        this.output = socket.getOutputStream();
    }

    /** Starts the reader thread (once). */
    public synchronized void start() {
        if (started) return;
        started = true;
        AppExecutorUtil.getAppExecutorService().execute(this::read);
    }

    /** Completes with the init packet; fails if the session ends before it arrives. */
    public @NotNull CompletableFuture<DbgpPacket.Init> init() {
        return init;
    }

    /**
     * Sends the command; the future completes with its response (also if the engine reports an error, see
     * {@link DbgpPacket.Response#error()}) or fails if the session ends first or the command cannot be written.
     */
    public @NotNull CompletableFuture<DbgpPacket.Response> send(@NotNull DbgpCommand command) {
        CompletableFuture<DbgpPacket.Response> future = new CompletableFuture<>();
        synchronized (writeLock) {
            if (closed) {
                future.completeExceptionally(new DbgpException("DBGp connection closed: " + command.name()));
                return future;
            }
            int id = nextTransactionId++;
            pending.put(id, new Pending(command, future));
            byte[] bytes = command.toBytes(id);
            if (LOG.isTraceEnabled()) LOG.trace(">>> " + command.toLine(id));
            try {
                output.write(bytes);
                output.flush();
            } catch (IOException e) {
                pending.remove(id);
                future.completeExceptionally(new DbgpException("Cannot send DBGp command " + command.name(), e));
                closeWith(e);
            }
        }
        return future;
    }

    /** True after the session ended. */
    public boolean isClosed() {
        return closed;
    }

    /** Ends the session (closes the socket); pending commands fail. */
    public void close() {
        closeWith(null);
    }

    /** Same as {@link #close()}, for {@code Disposer}. */
    @Override
    public void dispose() {
        close();
    }

    /** Reader thread: reads until the socket closes. */
    private void read() {
        DbgpFramer framer = new DbgpFramer();
        byte[] buffer = new byte[64 * 1024];
        Throwable cause = null;
        try {
            InputStream input = socket.getInputStream();
            int count;
            while ((count = input.read(buffer)) > 0) {
                for (byte[] xml : framer.feed(buffer, 0, count)) dispatch(xml);
            }
        } catch (IOException | DbgpException e) {
            if (!closed) cause = e;
        } catch (RuntimeException e) {
            LOG.error("DBGp reader failed", e);
            cause = e;
        }
        closeWith(cause);
    }

    /** Parses one packet and hands it on; malformed and unexpected packets are logged. */
    private void dispatch(byte @NotNull [] xml) {
        if (LOG.isTraceEnabled()) LOG.trace("<<< " + new String(xml, StandardCharsets.UTF_8));
        DbgpPacket packet;
        try {
            packet = DbgpPacket.parse(xml);
        } catch (DbgpException e) {
            LOG.warn("Skipping malformed DBGp packet: " + new String(xml, StandardCharsets.UTF_8), e);
            return;
        }
        switch (packet) {
            case DbgpPacket.Init packetInit -> {
                if (!init.complete(packetInit)) LOG.warn("Second DBGp init packet ignored");
            }
            case DbgpPacket.Response response -> {
                Pending waiting = pending.remove(response.transactionId());
                if (waiting == null) {
                    LOG.warn("DBGp response without a waiting command: " + response.transactionId());
                } else {
                    waiting.future().complete(response);
                }
            }
            case DbgpPacket.Stream stream -> call(() -> listener.stream(stream));
            case DbgpPacket.Notify notify -> call(() -> listener.notify(notify));
            case DbgpPacket.Unknown unknown -> LOG.warn("Unknown DBGp packet: " + unknown.element().getName());
        }
    }

    /** Calls the listener; its exceptions must not stop the reader. */
    private static void call(@NotNull Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException e) {
            LOG.error("DBGp listener failed", e);
        }
    }

    /** Ends the session once: closes the socket, fails init and pending commands, tells the listener. */
    private void closeWith(@Nullable Throwable cause) {
        List<Pending> failed;
        synchronized (writeLock) {
            if (closed) return;
            closed = true;
            failed = new ArrayList<>(pending.values());
            pending.clear();
        }
        try {
            socket.close();
        } catch (IOException e) {
            LOG.debug("Closing the DBGp socket failed", e);
        }
        DbgpException closedException = new DbgpException("DBGp connection closed", cause);
        init.completeExceptionally(closedException);
        for (Pending waiting : failed) waiting.future().completeExceptionally(closedException);
        call(() -> listener.closed(cause));
    }
}
