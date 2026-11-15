package de.kumpewo.autohotkey.debug.dbgp;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Problem of the DBGp connection: malformed packet, closed connection, command that could not be sent.
 * Error responses of the engine are not exceptions; they arrive as {@link DbgpPacket.Response#error()}.
 */
public final class DbgpException extends Exception {

    /** Exception with a message. */
    public DbgpException(@NotNull String message) {
        super(message);
    }

    /** Exception with a message and the cause. */
    public DbgpException(@NotNull String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
