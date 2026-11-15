package de.kumpewo.autohotkey.debug;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import de.kumpewo.autohotkey.debug.dbgp.DbgpCommand;
import de.kumpewo.autohotkey.debug.dbgp.DbgpConnection;
import de.kumpewo.autohotkey.debug.dbgp.DbgpException;
import de.kumpewo.autohotkey.debug.dbgp.DbgpFramer;
import de.kumpewo.autohotkey.debug.dbgp.DbgpPacket;
import de.kumpewo.autohotkey.debug.dbgp.DbgpProperty;
import de.kumpewo.autohotkey.debug.dbgp.DbgpServer;
import de.kumpewo.autohotkey.debug.dbgp.DbgpUris;
import de.kumpewo.autohotkey.settings.AhkSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Tests of the DBGp protocol layer (debug/dbgp): framing, commands, parsing, URIs, the connection against a fake
 * engine and, if AutoHotkey v2 is installed, against the real engine. The packets are our own recordings of
 * AutoHotkey 2.0.31 (recorded with a maintainer tool).
 */
public class DbgpTest extends BasePlatformTestCase {

    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
    private static final String INIT = XML + "<init appid=\"AutoHotkey\" ide_key=\"\" session=\"\" thread=\"28100\" "
            + "parent=\"\" language=\"AutoHotkey\" protocol_version=\"1.0\" "
            + "fileuri=\"file:///C%3A/Temp/dbgp%20probe%20%C3%A4/probe%20script.ahk\"/>";

    /** Packet bytes: length NUL xml NUL. */
    private static byte[] packet(String xml) {
        byte[] body = xml.getBytes(StandardCharsets.UTF_8);
        byte[] length = Integer.toString(body.length).getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(length);
        out.write(0);
        out.writeBytes(body);
        out.write(0);
        return out.toByteArray();
    }

    private static String response(String command, int id, String attributes, String content) {
        return XML + "<response command=\"" + command + "\" transaction_id=\"" + id + "\"" + attributes + ">"
                + content + "</response>";
    }

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    public void testFramerSplitAndMergedPackets() throws Exception {
        byte[] first = packet(INIT);
        byte[] second = packet(response("status", 1, " status=\"break\" reason=\"ok\"", ""));
        DbgpFramer framer = new DbgpFramer();
        // byte by byte: one packet at the very end
        List<byte[]> packets = new ArrayList<>();
        for (int i = 0; i < first.length; i++) {
            List<byte[]> found = framer.feed(first, i, 1);
            if (i < first.length - 1) assertTrue(found.isEmpty());
            packets.addAll(found);
        }
        assertEquals(1, packets.size());
        assertEquals(INIT, new String(packets.get(0), StandardCharsets.UTF_8));
        // two packets and the start of a third in one read
        byte[] both = new byte[first.length + second.length + 3];
        System.arraycopy(first, 0, both, 0, first.length);
        System.arraycopy(second, 0, both, first.length, second.length);
        System.arraycopy(first, 0, both, first.length + second.length, 3);
        assertEquals(2, framer.feed(both, 0, both.length).size());
        assertEquals(3, framer.pending());
        assertEquals(1, framer.feed(first, 3, first.length - 3).size());
        assertEquals(0, framer.pending());
        // UTF-8: the length counts bytes, not characters
        String umlaut = XML + "<stream type=\"stdout\" encoding=\"base64\">" + base64("ä") + "</stream>";
        assertEquals(1, new DbgpFramer().feed(packet(umlaut), 0, packet(umlaut).length).size());
    }

    public void testFramerRejectsBrokenStream() {
        assertFramerError("12x\0".getBytes(StandardCharsets.US_ASCII));
        assertFramerError("3\0abcX".getBytes(StandardCharsets.US_ASCII));
        assertFramerError("12345678901".getBytes(StandardCharsets.US_ASCII));
    }

    private static void assertFramerError(byte[] bytes) {
        try {
            new DbgpFramer().feed(bytes, 0, bytes.length);
            fail("DbgpException expected");
        } catch (DbgpException expected) {
            // ok
        }
    }

    public void testCommandLineAndQuoting() {
        assertEquals("run -i 7", DbgpCommand.of("run").toLine(7));
        assertEquals("breakpoint_set -i 3 -t line -f file:///C%3A/a%20b.ahk -n 12",
                DbgpCommand.of("breakpoint_set").arg('t', "line").arg('f', DbgpUris.toUri("C:\\a b.ahk"))
                        .arg('n', 12).toLine(3));
        // no whitespace: as it is (the engine accepts m["b"] unquoted)
        assertEquals("property_get -i 1 -n m[\"b\"] -c 1",
                DbgpCommand.of("property_get").arg('n', "m[\"b\"]").arg('c', 1).toLine(1));
        // whitespace: quoted, quote and backslash escaped
        assertEquals("property_get -i 1 -n \"m[\\\"a b\\\"]\"",
                DbgpCommand.of("property_get").arg('n', "m[\"a b\"]").toLine(1));
        assertEquals("property_get -i 1 -n \"\"", DbgpCommand.of("property_get").arg('n', "").toLine(1));
        assertEquals("property_set -i 2 -n x -t integer -- " + base64("ä 5"),
                DbgpCommand.of("property_set").arg('n', "x").arg('t', "integer").data("ä 5").toLine(2));
        byte[] bytes = DbgpCommand.of("status").toBytes(1);
        assertEquals(0, bytes[bytes.length - 1]);
        assertEquals("status -i 1", new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> DbgpCommand.of("run").arg('i', 1));
        assertThrows(IllegalArgumentException.class, () -> DbgpCommand.of("Run"));
        assertThrows(IllegalArgumentException.class, () -> DbgpCommand.of("run").arg('n', "a\0b"));
    }

    public void testParsePackets() throws Exception {
        DbgpPacket.Init init = (DbgpPacket.Init) parse(INIT);
        assertEquals("AutoHotkey", init.language());
        assertEquals("28100", init.thread());
        assertEquals("C:\\Temp\\dbgp probe ä\\probe script.ahk", DbgpUris.toPath(init.fileUri()));

        DbgpPacket.Response error = (DbgpPacket.Response) parse(
                response("breakpoint_set", 56, "", "<error code=\"202\"/>"));
        assertTrue(error.isError());
        assertEquals(202, error.error().code());
        assertEquals(56, error.transactionId());

        DbgpPacket.Response run = (DbgpPacket.Response) parse(
                response("run", 63, " status=\"break\" reason=\"ok\"", ""));
        assertFalse(run.isError());
        assertEquals("break", run.status());
        assertEquals("ok", run.reason());

        // the answer to stop has an empty command
        DbgpPacket.Response stop = (DbgpPacket.Response) parse(response("", 116, " status=\"stopped\" reason=\"ok\"", ""));
        assertEquals("", stop.command());
        assertEquals("stopped", stop.status());

        DbgpPacket.Response stack = (DbgpPacket.Response) parse(response("stack_get", 76, "",
                "<stack level=\"0\" type=\"file\" filename=\"file:///C%3A/Temp/a%20b.ahk\" lineno=\"23\" where=\"Add()\"/>"
                        + "<stack level=\"1\" type=\"file\" filename=\"file:///C%3A/Temp/a%20b.ahk\" lineno=\"12\" "
                        + "where=\"Auto-execute thread\"/>"));
        assertEquals(2, stack.frames().size());
        assertEquals(23, stack.frames().get(0).line());
        assertEquals("Add()", stack.frames().get(0).where());
        assertEquals("Auto-execute thread", stack.frames().get(1).where());

        DbgpPacket.Response contexts = (DbgpPacket.Response) parse(response("context_names", 67, "",
                "<context name=\"Local\" id=\"0\"/><context name=\"Global\" id=\"1\"/>"));
        assertEquals(List.of(new DbgpPacket.DbgpContext(0, "Local"), new DbgpPacket.DbgpContext(1, "Global")),
                contexts.contexts());

        DbgpPacket.Response breakpoints = (DbgpPacket.Response) parse(response("breakpoint_list", 61, "",
                "<breakpoint id=\"1\" type=\"line\" state=\"enabled\" filename=\"file:///C%3A/a.ahk\" lineno=\"4\"/>"
                        + "<breakpoint id=\"9\" type=\"exception\" state=\"disabled\" exception=\"Any\"/>"));
        assertEquals(4, breakpoints.breakpoints().get(0).line());
        assertEquals("Any", breakpoints.breakpoints().get(1).exception());
        assertEquals("disabled", breakpoints.breakpoints().get(1).state());

        DbgpPacket.Response properties = (DbgpPacket.Response) parse(response("property_get", 80, "",
                "<property name=\"arr\" fullname=\"arr\" type=\"object\" facet=\"\" classname=\"Array\" address=\"1310160\" "
                        + "size=\"0\" page=\"1\" pagesize=\"50\" children=\"1\" numchildren=\"2\">"
                        + "<property name=\"[50]\" fullname=\"arr[50]\" type=\"integer\" facet=\"\" children=\"0\" "
                        + "encoding=\"base64\" size=\"3\">MTAw</property></property>"));
        DbgpProperty arr = properties.properties().get(0);
        assertEquals("Array", arr.className());
        assertTrue(arr.hasChildren());
        assertEquals(1, arr.page());
        assertEquals(50, arr.pageSize());
        assertEquals("", arr.value());
        assertEquals("arr[50]", arr.children().get(0).fullName());
        assertEquals("100", arr.children().get(0).value());

        DbgpPacket.Response string = (DbgpPacket.Response) parse(response("property_get", 86, "",
                "<property name=\"obj.key\" fullname=\"obj.key\" type=\"string\" facet=\"\" children=\"0\" "
                        + "encoding=\"base64\" size=\"7\">d2VydCDDpA==</property>"));
        assertEquals("wert ä", string.properties().get(0).value());
        assertEquals(7, string.properties().get(0).size());

        DbgpPacket.Response value = (DbgpPacket.Response) parse(XML
                + "<response command=\"property_value\" transaction_id=\"92\" encoding=\"base64\" size=\"7\">"
                + "d2VydCDDpA==</response>");
        assertEquals("wert ä", value.text());
        DbgpPacket.Response feature = (DbgpPacket.Response) parse(response("feature_get", 7,
                " feature_name=\"breakpoint_types\" supported=\"1\"", "line exception"));
        assertEquals("line exception", feature.text());
        assertEquals("1", feature.attribute("supported"));

        // OutputDebug arrives as stderr stream, the data ends with NUL
        DbgpPacket.Stream stream = (DbgpPacket.Stream) parse(XML
                + "<stream type=\"stderr\">ZGVidWcgbWVzc2FnZQA=</stream>");
        assertEquals("stderr", stream.type());
        assertEquals("debug message", stream.text());

        assertTrue(parse(XML + "<something/>") instanceof DbgpPacket.Unknown);
        try {
            parse("<response");
            fail("DbgpException expected");
        } catch (DbgpException expected) {
            // ok
        }
    }

    private static DbgpPacket parse(String xml) throws DbgpException {
        return DbgpPacket.parse(xml.getBytes(StandardCharsets.UTF_8));
    }

    public void testUris() {
        assertEquals("file:///C%3A/Temp/dbgp%20probe%20%C3%A4/probe%20script.ahk",
                DbgpUris.toUri("C:\\Temp\\dbgp probe ä\\probe script.ahk"));
        assertEquals("C:\\Temp\\dbgp probe ä\\probe script.ahk",
                DbgpUris.toPath("file:///C%3A/Temp/dbgp%20probe%20%C3%A4/probe%20script.ahk"));
        // unencoded form (accepted by the engine as well)
        assertEquals("C:\\Temp\\a b.ahk", DbgpUris.toPath("file:///C:/Temp/a b.ahk"));
        assertEquals("file://server/share/a%2Bb.ahk", DbgpUris.toUri("\\\\server\\share\\a+b.ahk"));
        assertEquals("\\\\server\\share\\a+b.ahk", DbgpUris.toPath("file://server/share/a%2Bb.ahk"));
        assertNull(DbgpUris.toPath("dbgp://something"));
        assertNull(DbgpUris.toPath("file:///C%3"));
        String path = "C:\\Users\\Jörg Müller\\Lib\\x#1 (copy).ahk";
        assertEquals(path, DbgpUris.toPath(DbgpUris.toUri(path)));
    }

    /** Fake engine: connects to the server, then answers like the recordings. */
    private static final class FakeEngine {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        FakeEngine(int port) throws IOException {
            socket = new Socket(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), port);
            in = socket.getInputStream();
            out = socket.getOutputStream();
        }

        /** Next command line (up to NUL). */
        String readCommand() throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) > 0) line.write(b);
            return line.toString(StandardCharsets.UTF_8);
        }

        void send(byte[] bytes) throws IOException {
            out.write(bytes);
            out.flush();
        }
    }

    public void testConnectionWithFakeEngine() throws Exception {
        List<String> streams = new CopyOnWriteArrayList<>();
        CompletableFuture<Throwable> closed = new CompletableFuture<>();
        DbgpConnection.Listener listener = new DbgpConnection.Listener() {
            @Override
            public void stream(@NotNull DbgpPacket.Stream stream) {
                streams.add(stream.type() + ":" + stream.text());
            }

            @Override
            public void closed(@Nullable Throwable cause) {
                closed.complete(cause);
            }
        };
        try (DbgpServer server = DbgpServer.open()) {
            assertEquals("/Debug=127.0.0.1:" + server.port(), server.debugSwitch());
            CompletableFuture<FakeEngine> engineFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return new FakeEngine(server.port());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            Socket socket = server.accept(5000, () -> false);
            assertNotNull(socket);
            FakeEngine engine = engineFuture.get(5, TimeUnit.SECONDS);
            DbgpConnection connection = new DbgpConnection(socket, listener);
            connection.start();

            // init split into single bytes
            for (byte b : packet(INIT)) engine.send(new byte[]{b});
            assertEquals("AutoHotkey", connection.init().get(5, TimeUnit.SECONDS).appId());

            // run is answered only at the next break; break and breakpoint_set may be sent meanwhile
            CompletableFuture<DbgpPacket.Response> run = connection.send(DbgpCommand.of("run"));
            assertEquals("run -i 1", engine.readCommand());
            CompletableFuture<DbgpPacket.Response> breakpoint = connection.send(DbgpCommand.of("breakpoint_set")
                    .arg('t', "line").arg('f', "file:///C%3A/a.ahk").arg('n', 5));
            assertEquals("breakpoint_set -i 2 -t line -f file:///C%3A/a.ahk -n 5", engine.readCommand());
            engine.send(packet(response("breakpoint_set", 2, " state=\"enabled\" id=\"4\"", "")));
            assertEquals("4", breakpoint.get(5, TimeUnit.SECONDS).attribute("id"));
            assertFalse(run.isDone());
            CompletableFuture<DbgpPacket.Response> pause = connection.send(DbgpCommand.of("break"));
            assertEquals("break -i 3", engine.readCommand());
            // stream, run response and break response in one write
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            all.writeBytes(packet(XML + "<stream type=\"stdout\" encoding=\"base64\">" + base64("text\n\0") + "</stream>"));
            all.writeBytes(packet(response("run", 1, " status=\"break\" reason=\"ok\"", "")));
            all.writeBytes(packet(response("break", 3, "", "")));
            all.writeBytes(packet(XML + "<nonsense"));                  // malformed: logged and skipped
            all.writeBytes(packet(response("status", 99, "", "")));      // nobody waits: logged
            engine.send(all.toByteArray());
            assertEquals("break", run.get(5, TimeUnit.SECONDS).status());
            assertFalse(pause.get(5, TimeUnit.SECONDS).isError());
            assertEquals(List.of("stdout:text\n"), streams);

            // the engine closes: pending commands fail, the listener is told once
            CompletableFuture<DbgpPacket.Response> status = connection.send(DbgpCommand.of("status"));
            assertEquals("status -i 4", engine.readCommand());
            engine.socket.close();
            assertNull(closed.get(5, TimeUnit.SECONDS));
            assertFailsWithDbgpException(status);
            assertTrue(connection.isClosed());
            assertFailsWithDbgpException(connection.send(DbgpCommand.of("status")));
            connection.close();
        }
    }

    public void testAcceptStopsWhenTheProcessEnds() throws Exception {
        try (DbgpServer server = DbgpServer.open()) {
            long start = System.currentTimeMillis();
            assertNull(server.accept(10_000, () -> System.currentTimeMillis() - start > 300));
            assertTrue(System.currentTimeMillis() - start < 5_000);
            assertNull(server.accept(200, () -> false));
        }
    }

    private static void assertFailsWithDbgpException(CompletableFuture<?> future) throws Exception {
        try {
            future.get(5, TimeUnit.SECONDS);
            fail("DbgpException expected");
        } catch (ExecutionException e) {
            assertTrue(String.valueOf(e.getCause()), e.getCause() instanceof DbgpException);
        }
    }

    public void testRealEngine() throws Exception {
        Path detected = AhkSettings.detectAutoHotkey();
        if (detected == null) return;
        Path interpreter = AhkSettings.launcherScript(detected) != null
                ? AhkSettings.v2Interpreter(detected, 64) : detected;
        if (interpreter == null) return;
        System.out.println("testRealEngine with " + interpreter);
        Path dir = Files.createTempDirectory("dbgp test ä ");
        Path script = dir.resolve("a b.ahk");
        Files.writeString(script, "#Requires AutoHotkey v2.0\r\nm := Map(\"a b\", 7)\r\nx := 1\r\nExitApp 0\r\n",
                StandardCharsets.UTF_8);
        CompletableFuture<Throwable> closed = new CompletableFuture<>();
        try (DbgpServer server = DbgpServer.open()) {
            Process process = new ProcessBuilder(interpreter.toString(), "/ErrorStdOut", server.debugSwitch(),
                    script.toString()).start();
            try {
                Socket socket = server.accept(10_000, () -> !process.isAlive());
                assertNotNull("AutoHotkey did not connect", socket);
                DbgpConnection connection = new DbgpConnection(socket, new DbgpConnection.Listener() {
                    @Override
                    public void closed(@Nullable Throwable cause) {
                        closed.complete(cause);
                    }
                });
                connection.start();
                DbgpPacket.Init init = connection.init().get(5, TimeUnit.SECONDS);
                assertEquals(script.toString(), DbgpUris.toPath(init.fileUri()));
                DbgpPacket.Response breakpoint = connection.send(DbgpCommand.of("breakpoint_set").arg('t', "line")
                        .arg('f', DbgpUris.toUri(script.toString())).arg('n', 3)).get(5, TimeUnit.SECONDS);
                assertFalse(breakpoint.isError());
                DbgpPacket.Response run = connection.send(DbgpCommand.of("run")).get(10, TimeUnit.SECONDS);
                assertEquals("break", run.status());
                DbgpPacket.Response stack = connection.send(DbgpCommand.of("stack_get")).get(5, TimeUnit.SECONDS);
                assertEquals(3, stack.frames().get(0).line());
                assertEquals(script.toString(), DbgpUris.toPath(stack.frames().get(0).fileUri()));
                // quoted name with a space and quotes
                DbgpPacket.Response value = connection.send(DbgpCommand.of("property_get").arg('n', "m[\"a b\"]")
                        .arg('c', 1)).get(5, TimeUnit.SECONDS);
                assertFalse(value.isError());
                assertEquals("7", value.properties().get(0).value());
                DbgpPacket.Response stop = connection.send(DbgpCommand.of("stop")).get(5, TimeUnit.SECONDS);
                assertEquals("stopped", stop.status());
                closed.get(5, TimeUnit.SECONDS);
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            } finally {
                process.destroyForcibly();
            }
        }
    }
}
