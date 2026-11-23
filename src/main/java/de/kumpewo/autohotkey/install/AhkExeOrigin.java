package de.kumpewo.autohotkey.install;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * The origin of a deployed executable ({@code EmbedOrigin} in {@code [Deploy]}): a block of text appended to the copy,
 * so that a tool can find the project of an executable. Windows ignores data behind the last section of an executable,
 * also behind one compressed with UPX, so the program and its version information stay as they are. UTF-8, lines end
 * with CR LF:
 * <pre>
 * [ahk-v2-plugin]
 * ProjectPath=C:\Projects\MyTool
 * ProjectComputer=MYCOMPUTER
 * </pre>
 * A line break comes before the block; the last block of a file counts. A signed executable is never changed: the
 * block would invalidate its signature.
 */
public final class AhkExeOrigin {

    /** First line of the block. */
    static final String HEADER = "[ahk-v2-plugin]";
    /** The part of a file that contains the headers looked at by {@link #isSigned(Path)}. */
    private static final int HEADER_BYTES = 8192;
    /** Index of the certificate table in the data directories of the optional header. */
    private static final int CERTIFICATE_TABLE = 4;

    private AhkExeOrigin() {
    }

    /** The block for a project, without the line of the computer if its name is unknown. */
    static @NotNull String block(@NotNull Path project, @Nullable String computer) {
        String text = "\r\n" + HEADER + "\r\nProjectPath=" + project + "\r\n";
        return computer == null || computer.isEmpty() ? text : text + "ProjectComputer=" + computer + "\r\n";
    }

    /** The content with the block at its end; a signed executable and a file that is no executable stay unchanged. */
    static byte @NotNull [] append(byte @NotNull [] content, @NotNull String block) {
        int certificates = certificateTableSize(content);
        if (certificates != 0) return content;
        byte[] bytes = block.getBytes(StandardCharsets.UTF_8);
        byte[] result = Arrays.copyOf(content, content.length + bytes.length);
        System.arraycopy(bytes, 0, result, content.length, bytes.length);
        return result;
    }

    /** Is the existing file a signed executable? False if it cannot be read or is no executable. */
    static boolean isSigned(@NotNull Path file) {
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // read until the buffer is full or the file ends
            }
            return certificateTableSize(Arrays.copyOf(buffer.array(), buffer.position())) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Size of the certificate table of an executable (PE32 or PE32+): 0 = not signed, -1 = no executable. The headers
     * are needed only.
     */
    static int certificateTableSize(byte @NotNull [] content) {
        ByteBuffer pe = ByteBuffer.wrap(content).order(ByteOrder.LITTLE_ENDIAN);
        try {
            if (pe.getShort(0) != 0x5A4D) return -1;            // "MZ"
            int header = pe.getInt(60);
            if (pe.getInt(header) != 0x4550) return -1;         // "PE\0\0"
            int optional = header + 24;
            int magic = pe.getShort(optional) & 0xFFFF;
            if (magic != 0x10B && magic != 0x20B) return -1;
            int directories = optional + (magic == 0x20B ? 112 : 96);
            int count = pe.getInt(directories - 4);
            if (count <= CERTIFICATE_TABLE) return 0;
            return pe.getInt(directories + CERTIFICATE_TABLE * 8 + 4);
        } catch (IndexOutOfBoundsException e) {
            return -1;
        }
    }
}
