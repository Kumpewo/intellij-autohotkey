package de.kumpewo.autohotkey.debug.dbgp;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Splits the byte stream of the engine into packets. A packet is {@code length NUL xml NUL}, the length in ASCII
 * decimal digits and counted in bytes of the XML. One read may contain part of a packet or several packets, so the
 * framer keeps the rest until the next {@link #feed}. Not thread-safe (used by the reader thread only).
 */
public final class DbgpFramer {

    /** Longest accepted length prefix (digits); longer means the stream is broken. */
    private static final int MAX_DIGITS = 10;

    private byte[] buffer = new byte[0];
    private int size;

    /**
     * Adds the bytes and returns the XML of all packets that are now complete (possibly none).
     *
     * @throws DbgpException if the stream does not follow the packet format
     */
    public @NotNull List<byte[]> feed(byte @NotNull [] data, int offset, int length) throws DbgpException {
        ensureCapacity(size + length);
        System.arraycopy(data, offset, buffer, size, length);
        size += length;
        List<byte[]> packets = new ArrayList<>();
        int start = 0;
        while (true) {
            int nul = indexOfNul(start);
            if (nul < 0) {
                if (size - start > MAX_DIGITS) throw new DbgpException("Missing length of a DBGp packet");
                break;
            }
            int xmlLength = parseLength(start, nul);
            int end = nul + 1 + xmlLength;
            if (end >= size) break;
            if (buffer[end] != 0) throw new DbgpException("DBGp packet does not end with NUL");
            packets.add(Arrays.copyOfRange(buffer, nul + 1, end));
            start = end + 1;
        }
        if (start > 0) {
            System.arraycopy(buffer, start, buffer, 0, size - start);
            size -= start;
        }
        return packets;
    }

    /** Number of bytes waiting for the rest of a packet. */
    public int pending() {
        return size;
    }

    /** Position of the next NUL byte from {@code from}, or -1. */
    private int indexOfNul(int from) {
        for (int i = from; i < size; i++) {
            if (buffer[i] == 0) return i;
        }
        return -1;
    }

    /** The decimal length between {@code from} and {@code nul}. */
    private int parseLength(int from, int nul) throws DbgpException {
        if (nul == from || nul - from > MAX_DIGITS) throw new DbgpException("Invalid length of a DBGp packet");
        long value = 0;
        for (int i = from; i < nul; i++) {
            int digit = buffer[i] - '0';
            if (digit < 0 || digit > 9) throw new DbgpException("Invalid length of a DBGp packet");
            value = value * 10 + digit;
        }
        if (value > Integer.MAX_VALUE - 2) throw new DbgpException("DBGp packet too large");
        return (int) value;
    }

    /** Grows the buffer if needed. */
    private void ensureCapacity(int capacity) {
        if (capacity > buffer.length) buffer = Arrays.copyOf(buffer, Math.max(capacity, buffer.length * 2));
    }
}
