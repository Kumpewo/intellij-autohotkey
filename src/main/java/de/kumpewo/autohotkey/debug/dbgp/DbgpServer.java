package de.kumpewo.autohotkey.debug.dbgp;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.function.BooleanSupplier;

/**
 * Listening socket the engine connects to: only on the loopback address 127.0.0.1, on a free port. AutoHotkey is
 * started with {@code /Debug=127.0.0.1:port} ({@link #debugSwitch()}).
 */
public final class DbgpServer implements Closeable {

    /** Interval for checking {@code stop} while waiting for the engine. */
    private static final int POLL_MS = 100;

    private final ServerSocket serverSocket;

    private DbgpServer(@NotNull ServerSocket serverSocket) {
        this.serverSocket = serverSocket;
    }

    /** Opens the socket on 127.0.0.1 with a free port. */
    public static @NotNull DbgpServer open() throws IOException {
        InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        return new DbgpServer(new ServerSocket(0, 1, loopback));
    }

    /** The port. */
    public int port() {
        return serverSocket.getLocalPort();
    }

    /** Command line switch for AutoHotkey: {@code /Debug=127.0.0.1:port}. */
    public @NotNull String debugSwitch() {
        return "/Debug=127.0.0.1:" + port();
    }

    /**
     * Waits for the engine to connect, at most {@code timeoutMs} and only while {@code stop} is false (e.g. the
     * process has ended because of a syntax error). Returns {@code null} if nobody connected.
     */
    public @Nullable Socket accept(long timeoutMs, @NotNull BooleanSupplier stop) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!stop.getAsBoolean()) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) return null;
            serverSocket.setSoTimeout((int) Math.min(POLL_MS, left));
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                return socket;
            } catch (SocketTimeoutException e) {
                // check stop and the deadline again
            }
        }
        return null;
    }

    /** Closes the listening socket (not an accepted connection). */
    @Override
    public void close() throws IOException {
        serverSocket.close();
    }
}
