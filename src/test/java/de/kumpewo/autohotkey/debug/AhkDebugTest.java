package de.kumpewo.autohotkey.debug;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import de.kumpewo.autohotkey.debug.dbgp.DbgpConnection;
import de.kumpewo.autohotkey.debug.dbgp.DbgpPacket;
import de.kumpewo.autohotkey.debug.dbgp.DbgpProperty;
import de.kumpewo.autohotkey.debug.dbgp.DbgpServer;
import de.kumpewo.autohotkey.debug.dbgp.DbgpUris;
import de.kumpewo.autohotkey.settings.AhkSettings;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Tests of the debugger above the protocol layer: breakpoint lines, the {@link AhkDebugController} against a
 * scripted fake engine and, if AutoHotkey v2 is installed, against the real engine. The XDebugger UI itself is not
 * tested automatically.
 */
public class AhkDebugTest extends BasePlatformTestCase {

    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
    private static final String SCRIPT = "C:\\Temp\\dbg ä\\a b.ahk";

    public void testBreakpointOnlyOnLinesWithCode() {
        myFixture.configureByText("a.ahk", "x := 1\n; comment\n\n/* block\n*/\n  y := 2 ; comment\n}\n");
        AhkLineBreakpointType type = new AhkLineBreakpointType();
        var file = myFixture.getFile().getVirtualFile();
        assertTrue(type.canPutAt(file, 0, getProject()));
        assertFalse(type.canPutAt(file, 1, getProject()));
        assertFalse(type.canPutAt(file, 2, getProject()));
        assertFalse(type.canPutAt(file, 3, getProject()));
        assertFalse(type.canPutAt(file, 4, getProject()));
        assertTrue(type.canPutAt(file, 5, getProject()));
        assertTrue(type.canPutAt(file, 6, getProject()));    // "}" (the engine moves it if needed)
        assertFalse(type.canPutAt(file, 99, getProject()));
        myFixture.configureByText("b.ahk", "#Requires AutoHotkey v1.1\nx = 1\n");
        assertFalse(type.canPutAt(myFixture.getFile().getVirtualFile(), 1, getProject()));
        myFixture.configureByText("c.txt", "x := 1\n");
        assertFalse(type.canPutAt(myFixture.getFile().getVirtualFile(), 0, getProject()));
    }

    /** Callbacks that record everything. */
    private static final class Recorder implements AhkDebugController.Callbacks {
        final BlockingQueue<AhkDebugController.Suspension> suspensions = new LinkedBlockingQueue<>();
        final List<Object> verified = new CopyOnWriteArrayList<>();
        final Map<Object, String> invalid = new java.util.concurrent.ConcurrentHashMap<>();
        final List<String> output = new CopyOnWriteArrayList<>();
        final CompletableFuture<Void> ended = new CompletableFuture<>();

        @Override
        public void suspended(@NotNull AhkDebugController.Suspension suspension) {
            suspensions.add(suspension);
        }

        @Override
        public void breakpointVerified(@NotNull Object key) {
            verified.add(key);
        }

        @Override
        public void breakpointInvalid(@NotNull Object key, @NotNull String message) {
            invalid.put(key, message);
        }

        @Override
        public void debugOutput(@NotNull String text) {
            output.add(text);
        }

        @Override
        public void ended() {
            ended.complete(null);
        }

        AhkDebugController.Suspension next() throws InterruptedException {
            AhkDebugController.Suspension suspension = suspensions.poll(10, TimeUnit.SECONDS);
            assertNotNull("no suspension", suspension);
            return suspension;
        }
    }

    /**
     * Fake engine: answers every command with the packets of the responder (the command line without
     * {@code -i id}, the transaction id); records all command lines without the id.
     */
    private static final class FakeEngine {
        final List<String> commands = new CopyOnWriteArrayList<>();
        private final Socket socket;
        private final OutputStream out;

        FakeEngine(int port, Function<String, List<String>> responder) throws IOException {
            socket = new Socket(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), port);
            out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            Thread thread = new Thread(() -> {
                try {
                    while (true) {
                        ByteArrayOutputStream line = new ByteArrayOutputStream();
                        int b;
                        while ((b = in.read()) > 0) line.write(b);
                        if (b < 0) return;
                        String command = line.toString(StandardCharsets.UTF_8);
                        String id = command.replaceAll("^\\S+ -i (\\d+).*$", "$1");
                        String withoutId = command.replaceFirst(" -i \\d+", "");
                        commands.add(withoutId);
                        for (String xml : responder.apply(withoutId)) send(xml.replace("$ID", id));
                    }
                } catch (IOException ignored) {
                    // closed
                }
            }, "fake DBGp engine");
            thread.setDaemon(true);
            thread.start();
        }

        synchronized void send(String xml) throws IOException {
            byte[] body = xml.getBytes(StandardCharsets.UTF_8);
            out.write(Integer.toString(body.length).getBytes(StandardCharsets.US_ASCII));
            out.write(0);
            out.write(body);
            out.write(0);
            out.flush();
        }

        /** Index of the first recorded command starting with the prefix. */
        int indexOf(String prefix) {
            for (int i = 0; i < commands.size(); i++) {
                if (commands.get(i).startsWith(prefix)) return i;
            }
            return -1;
        }
    }

    private static String response(String command, String attributes, String content) {
        return XML + "<response command=\"" + command + "\" transaction_id=\"$ID\"" + attributes + ">" + content
                + "</response>";
    }

    private static String stack(int line) {
        return response("stack_get", "", "<stack level=\"0\" type=\"file\" filename=\"" + DbgpUris.toUri(SCRIPT)
                + "\" lineno=\"" + line + "\" where=\"Add()\"/><stack level=\"1\" type=\"file\" filename=\""
                + DbgpUris.toUri(SCRIPT) + "\" lineno=\"12\" where=\"Auto-execute thread\"/>");
    }

    /** Responses like AutoHotkey 2.0: lines 2 and 3 move to line 4 (same id), line 99 has no code. */
    private static List<String> respond(String command) {
        String name = command.split(" ")[0];
        Map<String, String> args = new HashMap<>();
        String[] parts = command.split(" ");
        for (int i = 1; i + 1 < parts.length; i += 2) args.put(parts[i], parts[i + 1]);
        return switch (name) {
            case "feature_set" -> List.of(response(name, " success=\"1\"", ""));
            case "stderr" -> List.of(response(name, " success=\"1\"", ""),
                    XML + "<stream type=\"stderr\">" + java.util.Base64.getEncoder().encodeToString(
                            "debug message\0".getBytes(StandardCharsets.UTF_8)) + "</stream>");
            case "breakpoint_set" -> {
                if ("exception".equals(args.get("-t"))) yield List.of(response(name, " state=\"enabled\" id=\"9\"", ""));
                String line = args.get("-n");
                if ("99".equals(line)) yield List.of(response(name, "", "<error code=\"202\"/>"));
                String id = "1".equals(args.get("-r")) ? "7" : "2".equals(line) || "3".equals(line) ? "1" : "5";
                yield List.of(response(name, " state=\"enabled\" id=\"" + id + "\"", ""));
            }
            case "breakpoint_get" -> {
                String id = args.get("-d");
                String line = "1".equals(id) ? "4" : "10";
                yield List.of(response(name, "", "<breakpoint id=\"" + id + "\" type=\"line\" state=\"enabled\" "
                        + "filename=\"" + DbgpUris.toUri(SCRIPT) + "\" lineno=\"" + line + "\"/>"));
            }
            case "breakpoint_remove", "break" -> List.of(response(name, "", ""));
            case "run", "step_over" -> List.of(response(name, " status=\"break\" reason=\"ok\"", ""));
            case "step_into" -> List.of(response(name, " status=\"stopping\" reason=\"ok\"", ""));
            case "step_out" -> List.of(response(name, " status=\"break\" reason=\"error\"", ""));
            case "detach" -> List.of(response(name, " status=\"stopped\" reason=\"ok\"", ""));
            case "stack_get" -> List.of(stack(4));
            case "context_names" -> List.of(response(name, "",
                    "<context name=\"Local\" id=\"0\"/><context name=\"Global\" id=\"1\"/>"));
            case "context_get" -> List.of(response(name, "", "<property name=\"a\" fullname=\"a\" type=\"integer\" "
                    + "facet=\"\" children=\"0\" encoding=\"base64\" size=\"1\">Mw==</property>"));
            case "property_get" -> {
                // g: unset locally (-c 0), 5 globally (-c 1)
                if ("g".equals(args.get("-n"))) {
                    yield List.of(response(name, "", "0".equals(args.get("-c"))
                            ? "<property name=\"g\" fullname=\"g\" type=\"undefined\" children=\"0\" size=\"0\"/>"
                            : "<property name=\"g\" fullname=\"g\" type=\"integer\" children=\"0\" "
                            + "encoding=\"base64\" size=\"1\">NQ==</property>"));
                }
                yield List.of(response(name, "", "<property name=\"obj\" fullname=\"obj\" type=\"object\" "
                        + "classname=\"Object\" children=\"1\" numchildren=\"1\" page=\"0\" pagesize=\"100\">"
                        + "<property name=\"key\" fullname=\"obj.key\" type=\"string\" children=\"0\" "
                        + "encoding=\"base64\" size=\"1\">eA==</property></property>"));
            }
            case "property_set" -> List.of("readonly".equals(args.get("-n"))
                    ? response(name, "", "<error code=\"300\"/>") : response(name, " success=\"1\"", ""));
            case "stop" -> List.of(response("", " status=\"stopped\" reason=\"ok\"", ""));
            default -> List.of(response(name, "", "<error code=\"4\"/>"));
        };
    }

    public void testControllerWithFakeEngine() throws Exception {
        Recorder recorder = new Recorder();
        AhkDebugController controller = new AhkDebugController(recorder);
        // registered before the engine connects: sent at the start, before run
        controller.addBreakpoint(new AhkDebugController.LineBreakpoint("A", SCRIPT, 2));
        controller.addBreakpoint(new AhkDebugController.LineBreakpoint("B", SCRIPT, 3));
        controller.addBreakpoint(new AhkDebugController.LineBreakpoint("X", SCRIPT, 99));
        try (DbgpServer server = DbgpServer.open()) {
            CompletableFuture<FakeEngine> engineFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return new FakeEngine(server.port(), AhkDebugTest::respond);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            Socket socket = server.accept(5000, () -> false);
            FakeEngine engine = engineFuture.get(5, TimeUnit.SECONDS);
            DbgpConnection connection = new DbgpConnection(socket, controller.listener());
            connection.start();
            controller.attach(connection).get(10, TimeUnit.SECONDS);

            AhkDebugController.Suspension stop = recorder.next();
            List<String> commands = engine.commands;
            assertEquals("feature_set -n max_children -v 100", commands.get(0));
            assertEquals("stderr -c 1", commands.get(1));
            int run = engine.indexOf("run");
            assertTrue(commands.toString(), run > engine.indexOf("breakpoint_get"));
            assertTrue(commands.toString(), run > engine.indexOf("breakpoint_set -t line -f " + DbgpUris.toUri(SCRIPT)
                    + " -n 99"));
            assertEquals(List.of("A", "B"), recorder.verified);
            assertEquals("No code at or after this line.", recorder.invalid.get("X"));
            assertEquals(List.of("debug message"), recorder.output);

            // the stop is at line 4, where the engine moved A and B
            assertEquals("A", stop.breakpoint());
            assertEquals(2, stop.frames().size());
            assertTrue(controller.isCurrent(stop.epoch()));

            // values: max_depth 0 for context_get, 1 for property_get (set once)
            assertEquals(List.of(new DbgpPacket.DbgpContext(0, "Local"), new DbgpPacket.DbgpContext(1, "Global")),
                    controller.contexts().get(5, TimeUnit.SECONDS));
            DbgpProperty a = controller.contextGet(stop.epoch(), 0, 0).get(5, TimeUnit.SECONDS).properties().get(0);
            assertEquals("3", a.value());
            controller.propertyGet(stop.epoch(), "obj", 1, 0).get(5, TimeUnit.SECONDS);
            DbgpProperty obj = controller.propertyGet(stop.epoch(), "obj", 1, 0).get(5, TimeUnit.SECONDS)
                    .properties().get(0);
            assertEquals("x", obj.children().get(0).value());
            int contextGet = engine.indexOf("context_get");
            assertEquals("feature_set -n max_depth -v 0", commands.get(contextGet - 1));
            assertEquals("context_get -c 0 -d 0", commands.get(contextGet));
            assertEquals("feature_set -n max_depth -v 1", commands.get(contextGet + 1));
            assertEquals("property_get -n obj -c 1 -d 0", commands.get(contextGet + 2));
            assertEquals("property_get -n obj -c 1 -d 0", commands.get(contextGet + 3));

            // paging: page n > 0 with -p
            controller.propertyGet(stop.epoch(), "obj", 1, 0, 2).get(5, TimeUnit.SECONDS);
            assertTrue(commands.contains("property_get -n obj -c 1 -d 0 -p 2"));

            // name paths: not among the local variables (only "a") = global context
            AhkDebugController.Resolved g = controller.resolve(stop.epoch(), "g", 0).get(5, TimeUnit.SECONDS);
            assertEquals(1, g.context());
            assertEquals("5", g.property().value());
            assertTrue(commands.contains("property_get -n g -c 1 -d 0"));
            assertEquals(0, controller.resolve(stop.epoch(), "a", 0).get(5, TimeUnit.SECONDS).context());
            assertEquals("m", AhkDebugController.rootName("m[\"k\"].x"));

            // property_set with type and Base64 data; an engine error fails the future
            controller.propertySet(stop.epoch(), "obj.key", 1, 0, new AhkValueText.Typed("string", "ä"))
                    .get(5, TimeUnit.SECONDS);
            assertTrue(commands.toString(), commands.contains("property_set -n obj.key -c 1 -d 0 -t string -- "
                    + java.util.Base64.getEncoder().encodeToString("ä".getBytes(StandardCharsets.UTF_8))));
            assertFailure(controller.propertySet(stop.epoch(), "readonly", 1, 0,
                    new AhkValueText.Typed("integer", "1")));

            // removing: the engine breakpoint stays while another key uses it
            controller.removeBreakpoint("A");
            controller.removeBreakpoint("B");
            waitFor(() -> engine.indexOf("breakpoint_remove -d 1") >= 0);
            assertEquals(1, commands.stream().filter(c -> c.startsWith("breakpoint_remove")).count());

            // step over: a new epoch, the old values are no longer valid
            controller.stepOver();
            AhkDebugController.Suspension second = recorder.next();
            assertTrue(second.epoch() > stop.epoch());
            assertFalse(controller.isCurrent(stop.epoch()));
            assertNull(second.breakpoint());   // A and B are removed
            assertFailure(controller.contextGet(stop.epoch(), 0, 0));

            // run to a line: temporary breakpoint, removed at the next stop
            controller.runTo(SCRIPT, 20);
            recorder.next();
            assertTrue(commands.contains("breakpoint_set -t line -f " + DbgpUris.toUri(SCRIPT) + " -n 20 -r 1"));
            waitFor(() -> engine.indexOf("breakpoint_remove -d 7") >= 0);

            // stopping: the controller lets the script end
            controller.stepInto();
            recorder.ended.get(10, TimeUnit.SECONDS);
            assertTrue(commands.contains("stop"));
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long end = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > end) fail("condition not reached");
            Thread.sleep(20);
        }
    }

    private static void assertFailure(CompletableFuture<?> future) throws Exception {
        try {
            future.get(5, TimeUnit.SECONDS);
            fail("failure expected");
        } catch (ExecutionException expected) {
            // ok
        }
    }

    /** Connects a controller to a fake engine (after registering what {@code before} registers). */
    private static FakeEngine connectFake(DbgpServer server, AhkDebugController controller) throws Exception {
        CompletableFuture<FakeEngine> engineFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return new FakeEngine(server.port(), AhkDebugTest::respond);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        Socket socket = server.accept(5000, () -> false);
        FakeEngine engine = engineFuture.get(5, TimeUnit.SECONDS);
        DbgpConnection connection = new DbgpConnection(socket, controller.listener());
        connection.start();
        controller.attach(connection).get(10, TimeUnit.SECONDS);
        return engine;
    }

    public void testExceptionBreakpointAndDetachWithFakeEngine() throws Exception {
        Recorder recorder = new Recorder();
        AhkDebugController controller = new AhkDebugController(recorder);
        controller.setExceptionBreakpoint("E");
        try (DbgpServer server = DbgpServer.open()) {
            FakeEngine engine = connectFake(server, controller);
            AhkDebugController.Suspension first = recorder.next();
            assertTrue(engine.indexOf("breakpoint_set -t exception") < engine.indexOf("run"));
            assertEquals(List.of("E"), recorder.verified);
            assertEquals("ok", first.reason());
            assertFalse(first.isException());

            // a stop with reason "error" (uncaught) carries the exception breakpoint
            controller.stepOut();
            AhkDebugController.Suspension error = recorder.next();
            assertTrue(error.isException());
            assertEquals(AhkDebugController.REASON_UNCAUGHT, error.reason());
            assertEquals("E", error.breakpoint());

            controller.removeExceptionBreakpoint();
            waitFor(() -> engine.indexOf("breakpoint_remove -d 9") >= 0);

            controller.detach().get(5, TimeUnit.SECONDS);
            assertTrue(engine.commands.contains("detach"));
            recorder.ended.get(5, TimeUnit.SECONDS);
        }
    }

    public void testExceptionBreakpointProperties() {
        AhkExceptionBreakpointType.Properties properties = new AhkExceptionBreakpointType.Properties();
        assertFalse(properties.caught);
        assertTrue(properties.uncaught);
        assertFalse(AhkExceptionBreakpointType.suspends(properties, AhkDebugController.REASON_CAUGHT));
        assertTrue(AhkExceptionBreakpointType.suspends(properties, AhkDebugController.REASON_UNCAUGHT));
        properties.caught = true;
        properties.uncaught = false;
        assertTrue(AhkExceptionBreakpointType.suspends(properties, AhkDebugController.REASON_CAUGHT));
        assertFalse(AhkExceptionBreakpointType.suspends(properties, AhkDebugController.REASON_UNCAUGHT));
    }

    public void testInlineValueLines() {
        String code = "x := 1\n"          // 0
                + "y := x + 1\n"            // 1
                + "F(x) {\n"                // 2
                + "    z := x\n"            // 3
                + "    G() {\n"             // 4
                + "        x := 5\n"        // 5
                + "    }\n"                 // 6
                + "    return X\n"          // 7
                + "}\n"                     // 8
                + "class C {\n"             // 9
                + "    M() => x\n"          // 10
                + "}\n"                     // 11
                + "MsgBox x\n"              // 12
                + "s := \"x\" ; x\n"        // 13
                + "x := 2\n";               // 14
        myFixture.configureByText("a.ahk", code);
        var file = myFixture.getFile();
        // top level: uses outside functions and classes, up to the current line
        assertEquals(List.of(0, 1, 12), AhkInlineValues.lines(file, "x", 13));
        assertEquals(List.of(0, 1, 12, 14), AhkInlineValues.lines(file, "X", 14));
        // in F: the parameter and the uses in F, not in the nested function G
        assertEquals(List.of(2, 3, 7), AhkInlineValues.lines(file, "x", 7));
        assertEquals(List.of(2, 3), AhkInlineValues.lines(file, "x", 3));
        assertEquals(List.of(5), AhkInlineValues.lines(file, "x", 5));
        assertEquals(List.of(), AhkInlineValues.lines(file, "x", 99));
    }

    public void testAttachToRunningScript() throws Exception {
        String exe = AhkSettings.getInstance().findInterpreter(null);
        if (exe == null || !com.intellij.openapi.util.SystemInfo.isWindows) return;
        Path dir = Files.createTempDirectory("dbg attach ");
        Path script = dir.resolve("loop.ahk");
        Files.writeString(script, "#Requires AutoHotkey v2.0\r\nPersistent\r\nLoop {\r\n    x := A_Index\r\n"
                + "    Sleep 50\r\n}\r\n", StandardCharsets.UTF_8);
        Process process = new ProcessBuilder(exe, script.toString()).start();
        try {
            AhkAttach.Script target = null;
            long end = System.currentTimeMillis() + 10_000;
            while (target == null && System.currentTimeMillis() < end) {
                target = AhkAttach.scripts().stream().filter(s -> s.processId() == process.pid()).findFirst()
                        .orElse(null);
                if (target == null) Thread.sleep(100);
            }
            assertNotNull("script window not found", target);
            assertEquals(script.toString(), target.path());
            Recorder recorder = new Recorder();
            AhkDebugController controller = new AhkDebugController(recorder);
            try (DbgpServer server = DbgpServer.open()) {
                AhkAttach.requestConnection(target, server.port());
                Socket socket = server.accept(10_000, () -> !process.isAlive());
                assertNotNull("the script did not connect", socket);
                DbgpConnection connection = new DbgpConnection(socket, controller.listener());
                connection.start();
                assertEquals(script.toString(), DbgpUris.toPath(connection.init().get(5, TimeUnit.SECONDS).fileUri()));
                controller.attach(connection).get(5, TimeUnit.SECONDS);
                Thread.sleep(200);
                controller.pause();
                AhkDebugController.Suspension stop = recorder.next();
                assertFalse(stop.frames().isEmpty());
                assertTrue(Integer.parseInt(controller.resolve(stop.epoch(), "x", 0).get(5, TimeUnit.SECONDS)
                        .property().value()) > 0);
                controller.detach().get(5, TimeUnit.SECONDS);
                recorder.ended.get(5, TimeUnit.SECONDS);
                Thread.sleep(300);
                assertTrue("the script must keep running after detach", process.isAlive());
            }
        } finally {
            process.destroyForcibly();
        }
    }

    public void testExceptionsWithRealEngine() throws Exception {
        String exe = AhkSettings.getInstance().findInterpreter(null);
        if (exe == null) return;
        Path dir = Files.createTempDirectory("dbg exceptions ");
        Path script = dir.resolve("errors.ahk");
        Files.writeString(script, "#Requires AutoHotkey v2.0\r\n"
                + "try\r\n"
                + "    x := [][5]\r\n"                 // 3: caught
                + "catch\r\n"
                + "    y := 1\r\n"
                + "z := [][7]\r\n"                     // 6: uncaught
                + "ExitApp 0\r\n", StandardCharsets.UTF_8);
        Recorder recorder = new Recorder();
        AhkDebugController controller = new AhkDebugController(recorder);
        controller.setExceptionBreakpoint("E");
        try (DbgpServer server = DbgpServer.open()) {
            Process process = new ProcessBuilder(exe, "/ErrorStdOut", server.debugSwitch(), script.toString()).start();
            try {
                Socket socket = server.accept(10_000, () -> !process.isAlive());
                assertNotNull(socket);
                DbgpConnection connection = new DbgpConnection(socket, controller.listener());
                connection.start();
                connection.init().get(5, TimeUnit.SECONDS);
                controller.attach(connection);
                AhkDebugController.Suspension caught = recorder.next();
                assertEquals(AhkDebugController.REASON_CAUGHT, caught.reason());
                assertEquals("E", caught.breakpoint());
                assertEquals(3, caught.frames().get(caught.frames().size() - 1).line());
                controller.resume();
                AhkDebugController.Suspension uncaught = recorder.next();
                assertEquals(AhkDebugController.REASON_UNCAUGHT, uncaught.reason());
                assertEquals(6, uncaught.frames().get(uncaught.frames().size() - 1).line());
                controller.stop().get(5, TimeUnit.SECONDS);
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            } finally {
                process.destroyForcibly();
            }
        }
    }

    public void testNamePaths() {
        assertEquals("obj", AhkNamePath.normalize(" obj "));
        assertEquals("obj.nested.deep[2]", AhkNamePath.normalize("obj.nested.deep[ 2 ]"));
        assertEquals("m[\"a b\"]", AhkNamePath.normalize("m['a b']"));
        assertEquals("m[\"k\"].x", AhkNamePath.normalize("m[\"k\"].x"));
        assertEquals("arr[-1]", AhkNamePath.normalize("arr[-1]"));
        assertEquals("arr[31]", AhkNamePath.normalize("arr[0x1F]"));
        assertEquals("m[2.5]", AhkNamePath.normalize("m[2.5]"));
        assertEquals("this.x", AhkNamePath.normalize("this.x"));
        assertEquals("Grüße", AhkNamePath.normalize("Grüße"));
        for (String no : List.of("", "a + b", "f()", "obj.M()", "1abc", "obj.", "obj..x", "m[x]", "m[\"a", "%x%",
                "a b", "arr[1", "m[\"a`\"b\"]", "obj.%name%")) {
            assertNull(no, AhkNamePath.normalize(no));
        }
    }

    public void testValueText() {
        assertEquals(new AhkValueText.Typed("integer", "42"), AhkValueText.parse(" 42 "));
        assertEquals(new AhkValueText.Typed("integer", "-3"), AhkValueText.parse("-3"));
        assertEquals(new AhkValueText.Typed("integer", "31"), AhkValueText.parse("0x1F"));
        assertEquals(new AhkValueText.Typed("float", "1.5"), AhkValueText.parse("1.5"));
        assertEquals(new AhkValueText.Typed("integer", "1"), AhkValueText.parse("True"));
        assertEquals(new AhkValueText.Typed("integer", "0"), AhkValueText.parse("false"));
        assertEquals(new AhkValueText.Typed("string", "a \"q\"\nb`"), AhkValueText.parse("\"a `\"q`\"`nb``\""));
        assertEquals(new AhkValueText.Typed("string", "it's"), AhkValueText.parse("'it`'s'"));
        assertEquals(new AhkValueText.Typed("string", ""), AhkValueText.parse("\"\""));
        assertNull(AhkValueText.parse("plain"));
        assertNull(AhkValueText.parse("\"a\"b\""));
        assertNull(AhkValueText.parse("\"a`\""));

        DbgpProperty string = property("string", "a\"`\n");
        assertEquals("\"a`\"```n\"", AhkValueText.editorText(string));
        assertEquals("a\"`\n", AhkValueText.parse(AhkValueText.editorText(string)).data());
        assertEquals("1.5", AhkValueText.editorText(property("float", "1.5")));
        assertEquals("", AhkValueText.editorText(property("object", "")));

        assertFalse(AhkValueText.isTrue(property("undefined", "")));
        assertFalse(AhkValueText.isTrue(property("integer", "0")));
        assertFalse(AhkValueText.isTrue(property("float", "0.0")));
        assertFalse(AhkValueText.isTrue(property("string", "")));
        assertFalse(AhkValueText.isTrue(property("string", "0")));
        assertTrue(AhkValueText.isTrue(property("string", "no")));
        assertTrue(AhkValueText.isTrue(property("integer", "-1")));
        assertTrue(AhkValueText.isTrue(property("object", "")));
    }

    private static DbgpProperty property(String type, String value) {
        return new DbgpProperty("x", "x", type, "object".equals(type) ? "Map" : "", "", "", false, 0, 0, 0,
                value.length(), value, List.of());
    }

    public void testHoverRange() {
        String code = "obj.nested.deep := Add(counter, m[\"k\"])\nMsgBox A_ScriptName\nx := this.y\n"
                + "Add(a, b) {\n    return a + Gui.Prototype\n}\n; comment obj\ns := \"obj\"\nobj.Method()\n";
        myFixture.configureByText("a.ahk", code);
        var file = myFixture.getFile();
        assertHover(file, code, "obj", 0, "obj");
        assertHover(file, code, "nested", 0, "obj.nested");
        assertHover(file, code, "deep", 0, "obj.nested.deep");
        assertHover(file, code, "Add(counter", 0, null);          // call
        assertHover(file, code, "counter", 0, "counter");
        assertHover(file, code, "m[", 0, "m");
        assertHover(file, code, "MsgBox", 0, null);               // command name
        assertHover(file, code, "A_ScriptName", 0, "A_ScriptName"); // built-in variable
        assertHover(file, code, "this", 0, "this");
        assertHover(file, code, "y\n", 0, "this.y");
        assertHover(file, code, "Add(a", 0, null);                // definition
        assertHover(file, code, "a, b", 0, "a");                  // parameter
        assertHover(file, code, "a + ", 0, "a");
        assertHover(file, code, "Gui.", 0, null);                 // built-in class
        assertHover(file, code, "obj\ns", 0, null);               // comment
        assertHover(file, code, "obj\"", 0, null);                // string
        assertHover(file, code, "Method", 0, null);               // method call
        assertHover(file, code, ":=", 0, null);
    }

    private static void assertHover(com.intellij.psi.PsiFile file, String code, String marker, int delta,
                                    @org.jetbrains.annotations.Nullable String expected) {
        int offset = code.indexOf(marker) + delta;
        assertTrue(marker, offset >= 0);
        var range = AhkDebuggerEvaluator.pathRange(file, offset);
        assertEquals(marker, expected, range == null ? null : range.substring(code));
    }

    public void testValuesWithRealEngine() throws Exception {
        String exe = AhkSettings.getInstance().findInterpreter(null);
        if (exe == null) return;
        Path dir = Files.createTempDirectory("dbg values ");
        Path script = dir.resolve("values.ahk");
        Files.writeString(script, "#Requires AutoHotkey v2.0\r\n"
                + "arr := []\r\n"
                + "Loop 250\r\n"
                + "    arr.Push(A_Index)\r\n"
                + "m := Map(\"a b\", \"x\", 5, \"five\")\r\n"
                + "g := 1, obj := {key: \"k\", num: 42}\r\n"
                + "F()\r\n"
                + "ExitApp 0\r\n"
                + "F() {\r\n"
                + "    loc := \"text\"\r\n"
                + "    return\r\n"                                     // 11
                + "}\r\n", StandardCharsets.UTF_8);
        Recorder recorder = new Recorder();
        AhkDebugController controller = new AhkDebugController(recorder);
        controller.addBreakpoint(new AhkDebugController.LineBreakpoint("stop", script.toString(), 11));
        try (DbgpServer server = DbgpServer.open()) {
            Process process = new ProcessBuilder(exe, "/ErrorStdOut", server.debugSwitch(), script.toString()).start();
            try {
                Socket socket = server.accept(10_000, () -> !process.isAlive());
                assertNotNull("AutoHotkey did not connect", socket);
                DbgpConnection connection = new DbgpConnection(socket, controller.listener());
                connection.start();
                connection.init().get(5, TimeUnit.SECONDS);
                controller.attach(connection);
                int epoch = recorder.next().epoch();

                // paging: page 0 = <base> + 99 entries, page 1 = entries 100..199, page 2 = 200..250
                List<DbgpProperty> page0 = controller.propertyGet(epoch, "arr", 1, 0).get(5, TimeUnit.SECONDS)
                        .properties().get(0).children();
                assertEquals(AhkDebugController.MAX_CHILDREN, page0.size());
                assertEquals("<base>", page0.get(0).name());
                assertEquals("[99]", page0.get(page0.size() - 1).name());
                List<DbgpProperty> page2 = controller.propertyGet(epoch, "arr", 1, 0, 2).get(5, TimeUnit.SECONDS)
                        .properties().get(0).children();
                assertEquals("[200]", page2.get(0).name());
                assertEquals("[250]", page2.get(page2.size() - 1).name());

                // name paths: local, global from inside a function, map keys
                AhkDebugController.Resolved loc = controller.resolve(epoch, "loc", 0).get(5, TimeUnit.SECONDS);
                assertEquals("text", loc.property().value());
                // a global the function only reads: resolved in the global context (setting through the local
                // context would not change it)
                AhkDebugController.Resolved g = controller.resolve(epoch, "g", 0).get(5, TimeUnit.SECONDS);
                assertEquals("1", g.property().value());
                assertEquals(1, g.context());
                assertEquals("42", controller.resolve(epoch, "obj.num", 0).get(5, TimeUnit.SECONDS).property().value());
                // setting through the context it was found in changes the global variable
                controller.propertySet(epoch, "g", g.context(), 0, AhkValueText.parse("7")).get(5, TimeUnit.SECONDS);
                assertEquals("7", controller.propertyGet(epoch, "g", 1, 0).get(5, TimeUnit.SECONDS)
                        .properties().get(0).value());
                assertEquals("x", controller.resolve(epoch, AhkNamePath.normalize("m['a b']"), 0)
                        .get(5, TimeUnit.SECONDS).property().value());
                assertEquals("five", controller.resolve(epoch, "m[5]", 0).get(5, TimeUnit.SECONDS).property().value());

                // set: number (hex), float, string with escapes, member of a global object
                controller.propertySet(epoch, "g", 1, 0, AhkValueText.parse("0x10")).get(5, TimeUnit.SECONDS);
                assertEquals("16", controller.resolve(epoch, "g", 0).get(5, TimeUnit.SECONDS).property().value());
                controller.propertySet(epoch, "loc", 0, 0, AhkValueText.parse("2.5")).get(5, TimeUnit.SECONDS);
                DbgpProperty floatValue = controller.resolve(epoch, "loc", 0).get(5, TimeUnit.SECONDS).property();
                assertEquals("float", floatValue.type());
                controller.propertySet(epoch, "loc", 0, 0, AhkValueText.parse("\"a`tb\"")).get(5, TimeUnit.SECONDS);
                assertEquals("a\tb", controller.resolve(epoch, "loc", 0).get(5, TimeUnit.SECONDS).property().value());
                controller.propertySet(epoch, "arr[3]", 1, 0, AhkValueText.parse("99")).get(5, TimeUnit.SECONDS);
                assertEquals("99", controller.resolve(epoch, "arr[3]", 0).get(5, TimeUnit.SECONDS).property().value());

                controller.stop().get(5, TimeUnit.SECONDS);
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            } finally {
                process.destroyForcibly();
            }
        }
    }

    public void testControllerWithRealEngine() throws Exception {
        String exe = AhkSettings.getInstance().findInterpreter(null);
        if (exe == null) return;
        Path dir = Files.createTempDirectory("dbg ä ");
        Path script = dir.resolve("a b.ahk");
        Files.writeString(script, "#Requires AutoHotkey v2.0\r\n"     // 1
                + "global counter := 5\r\n"                              // 2
                + "obj := {key: \"wert ä\"}\r\n"                         // 3
                + "OutputDebug \"hello\"\r\n"                            // 4
                + "result := Add(3, 4)\r\n"                              // 5
                + "ExitApp 0\r\n"                                         // 6
                + "\r\n"                                                  // 7
                + "Add(a, b) {\r\n"                                       // 8
                + "    ; comment\r\n"                                     // 9
                + "    sum := a + b\r\n"                                  // 10
                + "    return sum\r\n"                                    // 11
                + "}\r\n", StandardCharsets.UTF_8);
        Recorder recorder = new Recorder();
        AhkDebugController controller = new AhkDebugController(recorder);
        controller.addBreakpoint(new AhkDebugController.LineBreakpoint("in Add", script.toString(), 9));
        try (DbgpServer server = DbgpServer.open()) {
            Process process = new ProcessBuilder(exe, "/ErrorStdOut", server.debugSwitch(), script.toString()).start();
            try {
                Socket socket = server.accept(10_000, () -> !process.isAlive());
                assertNotNull("AutoHotkey did not connect", socket);
                DbgpConnection connection = new DbgpConnection(socket, controller.listener());
                connection.start();
                connection.init().get(5, TimeUnit.SECONDS);
                controller.attach(connection);
                AhkDebugController.Suspension stop = recorder.next();
                // line 9 is a comment: the engine moved the breakpoint to line 10, where the script stopped
                assertEquals(List.of("in Add"), recorder.verified);
                assertEquals("in Add", stop.breakpoint());
                assertEquals(10, stop.frames().get(0).line());
                assertEquals("Add()", stop.frames().get(0).where());
                assertEquals(2, stop.frames().size());
                List<DbgpProperty> locals = controller.contextGet(stop.epoch(), 0, 0).get(5, TimeUnit.SECONDS)
                        .properties();
                assertEquals("3", locals.stream().filter(p -> p.name().equals("a")).findFirst().orElseThrow().value());
                List<DbgpProperty> globals = controller.contextGet(stop.epoch(), 1, 0).get(5, TimeUnit.SECONDS)
                        .properties();
                assertTrue(globals.stream().anyMatch(p -> p.name().equals("counter") && p.value().equals("5")));
                // inside a function the engine also lists built-in classes and functions, without facet
                List<String> own = globals.stream().filter(p -> !AhkStackFrame.isBuiltIn(p)).map(DbgpProperty::name)
                        .sorted().toList();
                assertEquals(List.of("Add", "counter", "obj", "result"), own);
                DbgpProperty obj = controller.propertyGet(stop.epoch(), "obj", 1, 0).get(5, TimeUnit.SECONDS)
                        .properties().get(0);
                assertEquals("Object", obj.className());
                assertTrue(obj.children().stream().anyMatch(p -> p.name().equals("key") && p.value().equals("wert ä")));
                assertEquals(List.of("hello"), recorder.output);
                controller.stepOut();
                AhkDebugController.Suspension back = recorder.next();
                assertEquals(1, back.frames().size());
                controller.stop().get(5, TimeUnit.SECONDS);
                recorder.ended.get(5, TimeUnit.SECONDS);
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            } finally {
                process.destroyForcibly();
            }
        }
    }
}
