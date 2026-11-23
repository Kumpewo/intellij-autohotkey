package de.kumpewo.autohotkey.install;

import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import de.kumpewo.autohotkey.AhkBundle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Tests of Deploy and of "Set Version" (one tool = one project, {@code root} plays the project root): reading
 * {@code settings.ahk-v2-plugin.ini}, resolving and checking the copies, the executables compiled before copying,
 * copying, the script of the elevated process, the overview, the lines {@code ;@Ahk2Exe-SetVersion} of all scripts,
 * the next version and the Git steps (with a temporary repository; skipped without Git).
 */
public class AhkDeployTest extends BasePlatformTestCase {

    private Path root;

    /** Timeout for external programs in tests: their first start is slow on a busy CI machine. */
    private static final int SLOW_START_MS = 5 * 60_000;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        root = Files.createTempDirectory("ahk-deploy-test").toRealPath();
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            FileUtil.delete(root);
        } finally {
            super.tearDown();
        }
    }

    /**
     * Is Git installed? Waits long enough for its first start, which can take minutes on a busy CI machine (the timeout
     * of {@link AhkDeployGit} is meant for users).
     */
    private static boolean gitAvailable() {
        try {
            var line = new com.intellij.execution.configurations.GeneralCommandLine("git", "--version");
            var output = new com.intellij.execution.process.CapturingProcessHandler(line).runProcess(SLOW_START_MS);
            assertFalse("git --version: timeout", output.isTimeout());
            return output.getExitCode() == 0;
        } catch (com.intellij.execution.ExecutionException e) {
            return false;
        }
    }

    private static Function<String, String> env(String... pairs) {
        Map<String, String> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return map::get;
    }

    private static Path write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    /** Sections, keys, defaults, comments, quotes and the mistakes of the file. */
    public void testParse() {
        AhkDeployConfig config = AhkDeployConfig.parse("""
                ; comment
                [deploy]
                  ; indented comment
                Destination = "C:\\Tools"
                message=Text; not a comment
                RequiresAdmin=TRUE
                [Install]
                tool.exe=tool_new.exe
                pictures\\*.png=pictures
                [InstallIfMissing]
                tool.ini=%APPDATA%\\tool\\
                """);
        assertEquals(List.of(), config.errors());
        assertEquals("C:\\Tools", config.destination());
        assertEquals("Text; not a comment", config.message());
        assertTrue(config.requiresAdmin());
        assertTrue(config.generateOverview());
        assertTrue(config.compile());
        assertEquals(3, config.entries().size());
        assertEquals(new AhkDeployConfig.Entry("tool.exe", "tool_new.exe", false, 8), config.entries().get(0));
        assertTrue(config.entries().get(2).ifMissing());

        config = AhkDeployConfig.parse("""
                x=1
                [Deploy]
                Foo=1
                RequiresAdmin=maybe
                GenerateOverview=no
                [Install]
                a.exe=x
                A.EXE=y
                b.exe
                c.exe=
                [Other]
                anything
                [Compile]
                helper.ahk=true
                [Version]
                TagPrefix=x
                """);
        List<String> errors = config.errors();
        assertEquals(errors.toString(), 9, errors.size());
        assertTrue(errors.get(0), errors.get(0).contains("line 1"));
        assertTrue(errors.get(1), errors.get(1).contains("Foo"));
        assertTrue(errors.get(2), errors.get(2).contains("maybe") && errors.get(2).contains("yes or no"));
        assertTrue(errors.get(3), errors.get(3).contains("twice"));
        assertTrue(errors.get(4), errors.get(4).contains("b.exe"));
        assertTrue(errors.get(5), errors.get(5).contains("no target"));
        assertTrue(errors.get(6), errors.get(6).contains("[Other]"));
        // the sections of the former tool folders are unknown now
        assertTrue(errors.get(7), errors.get(7).contains("[Compile]"));
        assertTrue(errors.get(8), errors.get(8).contains("[Version]"));
        assertFalse(config.generateOverview());
        // yes/no; true/false of older files still read
        config = AhkDeployConfig.parse("[Deploy]\nRequiresAdmin=Yes\nGenerateOverview=FALSE\nCompile=no\n");
        assertEquals(List.of(), config.errors());
        assertTrue(config.requiresAdmin());
        assertFalse(config.generateOverview());
        assertFalse(config.compile());
        // the rule sections take only yes/no
        config = AhkDeployConfig.parse("[SetVersion]\na.ahk=include\nb.ahk=true\nc.ahk=YES\n");
        assertEquals(config.versionErrors().toString(), 2, config.versionErrors().size());
    }

    /** Targets and sources resolved: Destination, variables, %%, folders, wildcards, compiled files. */
    public void testPlan() throws Exception {
        Path tool = root.resolve("tool");
        Path dest = root.resolve("dest");
        write(tool.resolve("tool.exe"), "exe");
        write(tool.resolve("pictures/a.png"), "a");
        write(tool.resolve("pictures/b.PNG"), "b");
        write(tool.resolve("pictures/c.txt"), "c");
        write(tool.resolve("tool.ini"), "ini");
        Files.createDirectories(dest.resolve("existing"));
        AhkDeployConfig config = AhkDeployConfig.parse("[Deploy]\nDestination=" + dest
                + "\nMessage=Copy to %DESTINATION%`nUse 100%% of %USER%\n"
                + "[Install]\ntool.exe=tool_new.exe\npictures\\*.png=pictures\ntool.ini=existing\nnew.exe=bin\\\n"
                + "[InstallIfMissing]\ntool.ex?=%APPDATA%\\cfg\\\n");
        AhkDeployPlan plan = AhkDeployPlan.create(config, tool, env("APPDATA", root.resolve("appdata").toString(),
                "user", "me"), List.of(tool.resolve("new.exe")));
        assertEquals(List.of(), plan.errors());
        assertEquals("Copy to " + dest + "\nUse 100% of me", plan.message());
        List<String> steps = new ArrayList<>();
        for (AhkDeployPlan.Step step : plan.steps()) {
            steps.add(tool.relativize(step.source()) + " > " + root.relativize(step.target())
                    + (step.ifMissing() ? " keep" : ""));
        }
        assertEquals(List.of(
                "tool.exe > dest\\tool_new.exe",
                "pictures\\a.png > dest\\pictures\\a.png",
                "pictures\\b.PNG > dest\\pictures\\b.PNG",
                "tool.ini > dest\\existing\\tool.ini",
                "new.exe > dest\\bin\\new.exe",
                "tool.exe > appdata\\cfg\\tool.exe keep"), steps);
    }

    /** Destination is a folder even before it exists: targets ".", "%DESTINATION%", "x\..", "x\.". */
    public void testPlanDestinationNotYetCreated() throws Exception {
        Path tool = root.resolve("tool");
        Path dest = root.resolve("new dest");
        write(tool.resolve("a.exe"), "a");
        write(tool.resolve("b.exe"), "b");
        write(tool.resolve("c.exe"), "c");
        write(tool.resolve("d.exe"), "d");
        AhkDeployConfig config = AhkDeployConfig.parse("[Deploy]\nDestination=" + dest
                + "\n[Install]\na.exe=.\nb.exe=%DESTINATION%\nc.exe=sub\\..\nd.exe=sub\\.\n");
        AhkDeployPlan plan = AhkDeployPlan.create(config, tool, env(), List.of());
        assertEquals(List.of(), plan.errors());
        List<String> steps = new ArrayList<>();
        for (AhkDeployPlan.Step step : plan.steps()) steps.add(root.relativize(step.target()).toString());
        assertEquals(List.of("new dest\\a.exe", "new dest\\b.exe", "new dest\\c.exe", "new dest\\sub\\d.exe"), steps);
        assertFalse(Files.exists(dest));
    }

    /** Every mistake is found before the start. */
    public void testPlanErrors() throws Exception {
        Path tool = root.resolve("tool");
        write(tool.resolve("tool.exe"), "exe");
        write(tool.resolve("sub/x.txt"), "x");
        write(root.resolve("dest/file.txt"), "f");
        AhkDeployConfig config = AhkDeployConfig.parse("[Install]\n"
                + "tool.exe=relative\n"
                + "..\\other.exe=" + root + "\\a\n"
                + "C:\\abs.exe=" + root + "\\a\n"
                + "missing.exe=" + root + "\\a\n"
                + "sub=" + root + "\\a\n"
                + "*.dll=" + root + "\\a\n"
                + "sub\\*.txt=" + root.resolve("dest/file.txt") + "\n"
                + "s*\\x.txt=" + root + "\\a\n"
                + "sub\\x.txt=%NOPE%\\x\n"
                + "tool.ex?=%DESTINATION%\\x\n"
                + "tool.e?e=50%\n");
        AhkDeployPlan plan = AhkDeployPlan.create(config, tool, env(), List.of());
        List<String> errors = plan.errors();
        assertEquals(errors.toString(), 11, errors.size());
        assertTrue(errors.get(0), errors.get(0).contains("no Destination"));
        assertTrue(errors.get(1), errors.get(1).contains("relative to the project root"));
        assertTrue(errors.get(2), errors.get(2).contains("relative to the project root"));
        assertTrue(errors.get(3), errors.get(3).contains("does not exist"));
        assertTrue(errors.get(4), errors.get(4).contains("is a folder"));
        assertTrue(errors.get(5), errors.get(5).contains("no file matches"));
        assertTrue(errors.get(6), errors.get(6).contains("must be a folder"));
        assertTrue(errors.get(7), errors.get(7).contains("relative to the project root"));
        assertTrue(errors.get(8), errors.get(8).contains("%NOPE%"));
        assertTrue(errors.get(9), errors.get(9).contains("no Destination"));
        assertTrue(errors.get(10), errors.get(10).contains("single %"));
        assertTrue(plan.steps().isEmpty());
        plan = AhkDeployPlan.create(AhkDeployConfig.parse("[Deploy]\nDestination=relative\n"), tool, env(), List.of());
        assertTrue(plan.errors().toString(), plan.errors().get(0).contains("absolute"));
    }

    /** Copying: new files and folders, overwriting, [InstallIfMissing], modification time. */
    public void testCopy() throws Exception {
        Path a = write(root.resolve("src/a.txt"), "new a");
        Path b = write(root.resolve("src/b.txt"), "new b");
        Files.setLastModifiedTime(a, FileTime.fromMillis(1_600_000_000_000L));
        Path existing = write(root.resolve("dest/a.txt"), "old content that is longer");
        Path kept = write(root.resolve("dest/b.txt"), "old b");
        List<AhkDeployCopier.StepResult> results = AhkDeployCopier.copy(List.of(
                new AhkDeployPlan.Step(a, existing, false),
                new AhkDeployPlan.Step(a, root.resolve("dest/new/deep/a.txt"), false),
                new AhkDeployPlan.Step(b, kept, true),
                new AhkDeployPlan.Step(root.resolve("src/missing.txt"), root.resolve("dest/m.txt"), false),
                new AhkDeployPlan.Step(b, root.resolve("dest/never.txt"), false)));
        assertEquals(4, results.size());
        assertEquals(AhkDeployCopier.Outcome.COPIED, results.get(0).outcome());
        assertEquals(AhkDeployCopier.Outcome.COPIED, results.get(1).outcome());
        assertEquals(AhkDeployCopier.Outcome.SKIPPED, results.get(2).outcome());
        assertEquals(AhkDeployCopier.Outcome.FAILED, results.get(3).outcome());
        assertEquals("new a", Files.readString(existing));
        assertEquals("new a", Files.readString(root.resolve("dest/new/deep/a.txt")));
        assertEquals(1_600_000_000_000L, Files.getLastModifiedTime(existing).toMillis());
        assertEquals("old b", Files.readString(kept));
        assertFalse(Files.exists(root.resolve("dest/never.txt")));
        if (!com.intellij.openapi.util.SystemInfo.isWindows) return;
        // [Install] leaves its targets read-only and overwrites them again; [InstallIfMissing] never sets it
        assertTrue(isReadOnly(existing));
        assertTrue(isReadOnly(root.resolve("dest/new/deep/a.txt")));
        assertFalse(isReadOnly(kept));
        Path newKept = root.resolve("dest/k.txt");
        results = AhkDeployCopier.copy(List.of(new AhkDeployPlan.Step(b, existing, false),
                new AhkDeployPlan.Step(b, newKept, true)));
        assertEquals(AhkDeployCopier.Outcome.COPIED, results.get(0).outcome());
        assertEquals(AhkDeployCopier.Outcome.COPIED, results.get(1).outcome());
        assertEquals("new b", Files.readString(existing));
        assertTrue(isReadOnly(existing));
        assertFalse(isReadOnly(newKept));
    }

    /** Has the file the read-only attribute? */
    private static boolean isReadOnly(Path file) throws IOException {
        return Boolean.TRUE.equals(Files.getAttribute(file, "dos:readonly"));
    }

    /** Selectors written like .gitignore: depth, anchoring, folders, wildcards, case, invalid patterns. */
    public void testPathPattern() {
        AhkPathPattern any = AhkPathPattern.compile("*.ahk");
        assertTrue(any.matches("a.ahk"));
        assertTrue(any.matches("lib\\deep\\B.AHK"));
        assertFalse(any.matches("a.ahk2"));
        AhkPathPattern name = AhkPathPattern.compile("test.ahk");
        assertTrue(name.matches("x/test.ahk"));
        AhkPathPattern anchored = AhkPathPattern.compile("/x.ahk");
        assertTrue(anchored.matches("x.ahk"));
        assertFalse(anchored.matches("sub/x.ahk"));
        AhkPathPattern middle = AhkPathPattern.compile("lib\\*.ahk");
        assertTrue(middle.matches("lib/a.ahk"));
        assertFalse(middle.matches("lib/sub/a.ahk"));
        assertFalse(middle.matches("x/lib/a.ahk"));
        AhkPathPattern folder = AhkPathPattern.compile("scratch/");
        assertTrue(folder.matches("scratch/a.ahk"));
        assertTrue(folder.matches("lib/scratch/deep/b.ahk"));
        assertFalse(folder.matches("scratch"));
        AhkPathPattern rootFolder = AhkPathPattern.compile("/scratch/");
        assertFalse(rootFolder.matches("lib/scratch/a.ahk"));
        assertTrue(AhkPathPattern.compile("old").matches("old/a.ahk"));
        assertTrue(AhkPathPattern.compile("**/gen/*.js").matches("a/b/gen/x.js"));
        assertTrue(AhkPathPattern.compile("**/gen/*.js").matches("gen/x.js"));
        assertTrue(AhkPathPattern.compile("docs/**").matches("docs/a/b.htm"));
        assertTrue(AhkPathPattern.compile("a/**/b.txt").matches("a/b.txt"));
        assertTrue(AhkPathPattern.compile("a/**/b.txt").matches("a/x/y/b.txt"));
        assertTrue(AhkPathPattern.compile("v?.[0-9]").matches("v1.5"));
        assertFalse(AhkPathPattern.compile("x[!a].txt").matches("xa.txt"));
        for (String invalid : new String[]{"", "/", "..\\x.ahk", "C:\\x", "a/./b", "x[a"}) {
            assertNull(invalid, AhkPathPattern.compile(invalid));
        }
    }

    /**
     * The comment of a banner: line style (with end), block style (with lines of their own for start and end), the
     * built-in templates and placeholders.
     */
    public void testBannerText() throws Exception {
        AhkFileRules.Style semicolon = new AhkFileRules.Style(";", null, false);
        AhkBanner.Values json = new AhkBanner.Values("Json", "C:\\P\\Json", "Json.ahk", null, "1.2",
                null, "2026-11-20 10:00", null, null);
        AhkBanner.Values empty = new AhkBanner.Values("p", "P", "f", null, null, null, "", null, null);
        List<String> lines = AhkBanner.lines(List.of("Copy of {source-relative} {version} \u2013 {project-name}", "Do not edit"),
                semicolon, json);
        assertEquals(List.of("; Copy of Json.ahk 1.2 \u2013 Json", "; Do not edit"), lines);
        assertEquals(List.of("<!-- x -->", "<!-- -->"), AhkBanner.lines(List.of("x", ""),
                new AhkFileRules.Style("<!--", "-->", false), empty));
        assertEquals(List.of("/* a", "b */"), AhkBanner.lines(List.of("a", "b"),
                new AhkFileRules.Style("/*", "*/", true), empty));
        assertEquals(List.of("/*", "a", "*/"), AhkBanner.lines(List.of("", "a", ""),
                new AhkFileRules.Style("/*", "*/", true), empty));
        assertEquals(List.of("/* one */"), AhkBanner.lines(List.of("one"),
                new AhkFileRules.Style("/*", "*/", true), empty));
        // a line whose placeholders are all empty is left out, also the last one of a block
        assertEquals(List.of("; Version 1.2", "; text"),
                AhkBanner.lines(List.of("Version {version} {commit}", "Commit {commit}", "text"), semicolon,
                        json));
        assertEquals(List.of("/* a */"), AhkBanner.lines(List.of("a", "{commit}"),
                new AhkFileRules.Style("/*", "*/", true), empty));
        assertEquals(List.of(), AhkBanner.lines(List.of("{version}"), semicolon, empty));
        // text: value with `n, built-in template, file
        List<String> errors = new ArrayList<>();
        assertEquals(List.of("a", "b"), AhkBanner.text("a`nb", root, env(), errors));
        assertEquals(AhkBanner.LIBRARY, AhkBanner.text("@Library", root, env(), errors));
        assertEquals(AhkBanner.STANDARD, AhkBanner.text("@Standard", root, env(), errors));
        // the templates: lines as long as those of the Ahk2Exe head, values from its column
        AhkBanner.Values gui = new AhkBanner.Values("Gui", "C:\\Projects\\Gui", "src\\Gui.ahk",
                "C:\\Projects\\Gui\\src\\Gui.ahk", "1.2.0", "a1b2c3d+", "2026-11-20 14:30", "#Include <v2\\Gui>",
                "PC1");
        List<String> library = AhkBanner.lines(AhkBanner.LIBRARY, semicolon, gui);
        assertEquals(List.of(
                "; ---------------------------------------------------------------------------",
                "; INSTALLED LIBRARY  -  Do not modify here. Edit the source and deploy again.",
                ";",
                "; Project     Gui [C:\\Projects\\Gui]",
                "; Source      src\\Gui.ahk",
                "; Version     1.2.0",
                "; Commit      a1b2c3d+",
                "; Deployed    2026-11-20 14:30 on PC1",
                "; Use         #Include <v2\\Gui>",
                "; ---------------------------------------------------------------------------"), library);
        assertEquals("; Deployed    ".length(), library.get(5).indexOf("1.2.0"));
        assertEquals(library.get(0).length(), library.get(1).length());
        List<String> standard = AhkBanner.lines(AhkBanner.builtin(AhkBanner.STANDARD, true, false),
                AhkFileRules.builtinStyle("x.cmd"),
                new AhkBanner.Values("Gui", "C:\\Projects\\Gui", "Gui.cmd", "C:\\Projects\\Gui\\Gui.cmd",
                        null, null, "2026-11-20 14:30", "#Include <Gui>", null));
        assertEquals(List.of(
                ":: ---------------------------------------------------------------------------",
                ":: INSTALLED ARTIFACT -- Do not modify here. Edit the source and deploy again.",
                "::",
                ":: Project     Gui [C:\\Projects\\Gui]",
                ":: Source      Gui.cmd",
                ":: Deployed    2026-11-20 14:30",
                ":: ---------------------------------------------------------------------------"), standard);
        assertEquals(standard.get(0).length(), standard.get(1).length());
        // the parts of project and source
        assertEquals(List.of("; Gui.ahk | src\\Gui.ahk | C:\\Projects\\Gui\\src\\Gui.ahk | Gui | C:\\Projects\\Gui"),
                AhkBanner.lines(List.of("{source-name} | {source-relative} | {source-path} | {project-name}"
                        + " | {project-path}"), semicolon, gui));
        write(root.resolve("shared/notice.txt"), "\uFEFFFirst {project-name}\r\nSecond\r\n\r\n");
        assertEquals(List.of("First {project-name}", "Second"),
                AhkBanner.text("file:%LIB%\\notice.txt", root, env("LIB", root.resolve("shared").toString()), errors));
        assertEquals(List.of(), errors);
        assertNull(AhkBanner.text("@other", root, env(), errors));
        assertNull(AhkBanner.text("file:missing.txt", root, env(), errors));
        assertEquals(errors.toString(), 2, errors.size());
        // find: the nearest folder upwards from the project root wins
        errors.clear();
        Path project = root.resolve("group/sub/project");
        write(project.resolve("a.ahk"), "x");
        write(root.resolve("group/notice.txt"), "Group");
        write(root.resolve("notice.txt"), "Top");
        assertEquals(List.of("Group"), AhkBanner.text("find:notice.txt", project, env(), errors));
        write(project.resolve("notice.txt"), "Own");
        assertEquals(List.of("Own"), AhkBanner.text(" FIND: notice.txt ", project, env(), errors));
        assertEquals(List.of(), errors);
        // not found: the message names the solutions; a name with a folder or a variable is a mistake
        assertNull(AhkBanner.text("find:nowhere-12345.txt", project, env(), errors));
        assertTrue(errors.toString(), errors.get(0).contains("@library") && errors.get(0).contains(project.toString()));
        assertNull(AhkBanner.text("find:group\\notice.txt", project, env(), errors));
        assertNull(AhkBanner.text("find:%LIB%", project, env(), errors));
        assertNull(AhkBanner.text("find:", project, env(), errors));
        assertEquals(errors.toString(), 4, errors.size());
        errors.clear();
        // no byte order mark: ASCII, LF as in the file
        byte[] plain = "a := 1\nb := 2\n".getBytes(StandardCharsets.UTF_8);
        assertEquals("; Copy of Json.ahk 1.2 _ Json\n; Do not edit\na := 1\nb := 2\n",
                new String(AhkBanner.apply(plain, lines), StandardCharsets.US_ASCII));
        // CR LF, and a file without line break gets CR LF
        assertEquals("; x\r\na\r\nb", new String(AhkBanner.apply("a\r\nb".getBytes(StandardCharsets.UTF_8),
                List.of("; x")), StandardCharsets.US_ASCII));
        assertEquals("; x\r\na", new String(AhkBanner.apply("a".getBytes(StandardCharsets.UTF_8), List.of("; x")),
                StandardCharsets.US_ASCII));
        assertEquals("; x\r\n", new String(AhkBanner.apply(new byte[0], List.of("; x")), StandardCharsets.US_ASCII));
        // UTF-8 with byte order mark: the text stays Unicode, the mark stays in front
        byte[] utf8 = ("\uFEFF\u00e4 := 1\n").getBytes(StandardCharsets.UTF_8);
        assertEquals("\uFEFF; Copy of Json.ahk 1.2 \u2013 Json\n; Do not edit\n\u00e4 := 1\n",
                new String(AhkBanner.apply(utf8, lines), StandardCharsets.UTF_8));
        // UTF-16 LE with byte order mark
        byte[] utf16 = ("\uFEFFx\r\n").getBytes(StandardCharsets.UTF_16LE);
        assertEquals("\uFEFF; \u2013\r\nx\r\n",
                new String(AhkBanner.apply(utf16, List.of("; \u2013")), StandardCharsets.UTF_16LE));
        // a first line <?xml or #! stays first
        assertEquals("<?xml version=\"1.0\"?>\r\n<!-- x -->\r\n<a/>\r\n", new String(AhkBanner.apply(
                "<?xml version=\"1.0\"?>\r\n<a/>\r\n".getBytes(StandardCharsets.UTF_8), List.of("<!-- x -->")),
                StandardCharsets.US_ASCII));
        assertEquals("#!x\r\n# n\r\n", new String(AhkBanner.apply("#!x".getBytes(StandardCharsets.UTF_8),
                List.of("# n")), StandardCharsets.US_ASCII));
        assertEquals("a_b_", AhkBanner.ascii("a\u00e4b\uD83D\uDE00"));
        // a line left out (the own @SetVersion), also after a first line <?xml
        assertEquals("# v1\r\na\r\nc\r\n", new String(AhkBanner.apply("a\r\n# @SetVersion 1\r\nc\r\n"
                .getBytes(StandardCharsets.UTF_8), List.of("# v1"), 1), StandardCharsets.US_ASCII));
        assertEquals("<?xml?>\n<!-- n -->\n<a/>\n", new String(AhkBanner.apply(
                "<?xml?>\n<!-- @SetVersion 1 -->\n<a/>\n".getBytes(StandardCharsets.UTF_8), List.of("<!-- n -->"), 1),
                StandardCharsets.US_ASCII));
        assertEquals("; n\r\nx", new String(AhkBanner.apply("x".getBytes(StandardCharsets.UTF_8),
                List.of("; n"), 3), StandardCharsets.US_ASCII));
    }

    /**
     * After a deployment the VFS knows new copies (also in new folders), the new content of overwritten ones and
     * that removed files are gone, without waiting for the file watcher.
     */
    public void testRefreshFilesAfterDeploy() throws Exception {
        com.intellij.openapi.vfs.LocalFileSystem fs = com.intellij.openapi.vfs.LocalFileSystem.getInstance();
        Path old = root.resolve("Lib\\Old.ahk");
        write(old, "a := 1\n");
        com.intellij.openapi.vfs.VirtualFile dir = fs.refreshAndFindFileByNioFile(root);
        assertNotNull(dir);
        com.intellij.openapi.vfs.VfsUtil.markDirtyAndRefresh(false, true, true, dir);
        com.intellij.openapi.vfs.VirtualFile oldFile = fs.findFileByNioFile(old);
        assertNotNull(oldFile);
        Path added = root.resolve("Lib\\v2\\New.ahk");
        write(added, "b := 1\n");
        write(old, "a := 2\n");
        // the VFS sees a change by time stamp and length: the same length needs a later time
        Files.setLastModifiedTime(old, FileTime.fromMillis(Files.getLastModifiedTime(old).toMillis() + 10_000));
        Path gone = root.resolve("Lib\\Gone.ahk");
        write(gone, "c := 1\n");
        com.intellij.openapi.vfs.VirtualFile goneFile = fs.refreshAndFindFileByNioFile(gone);
        assertNotNull(goneFile);
        Files.delete(gone);
        AhkDeployer.refreshFiles(List.of(added, old, gone));
        com.intellij.openapi.vfs.VirtualFile addedFile = fs.findFileByNioFile(added);
        assertNotNull(addedFile);
        assertEquals("b := 1\n", com.intellij.openapi.vfs.VfsUtilCore.loadText(addedFile));
        assertEquals("a := 2\n", com.intellij.openapi.vfs.VfsUtilCore.loadText(oldFile));
        assertFalse(goneFile.isValid());
    }

    /**
     * {@code {include}}: the target relative to a Lib folder (any case, also in a subfolder), only for {@code .ahk};
     * {@code {commit}}: the short hash of HEAD, {@code +} for uncommitted changes, empty for an untracked file and
     * without Git.
     */
    public void testBannerIncludeAndCommit() throws Exception {
        Path lib = root.resolve("Docs\\AutoHotkey\\Lib");
        List<Path> libraries = List.of(lib);
        assertEquals("#Include <Gui>", AhkDeployPlan.include(lib.resolve("Gui.ahk"), libraries));
        assertEquals("#Include <v2\\Gui>", AhkDeployPlan.include(lib.resolve("v2\\Gui.AHK"), libraries));
        assertEquals("#Include <v2\\Gui>", AhkDeployPlan.include(
                Path.of(lib.toString().toUpperCase(java.util.Locale.ROOT)).resolve("v2\\Gui.ahk"), libraries));
        assertNull(AhkDeployPlan.include(lib.resolve("readme.txt"), libraries));
        assertNull(AhkDeployPlan.include(root.resolve("Docs\\AutoHotkey\\Library\\Gui.ahk"), libraries));
        assertNull(AhkDeployPlan.include(root.resolve("Gui.ahk"), libraries));
        assertEquals(List.of(root.resolve("Docs\\AutoHotkey\\Lib")),
                AhkDeployPlan.libraries(env("A_MyDocuments", root.resolve("Docs").toString())).subList(0, 1));
        if (!gitAvailable()) return;
        // {commit}: without a repository empty
        Path project = root.resolve("Tool");
        write(project.resolve("tool.ahk"), "x := 1\n");
        write(project.resolve("new.ahk"), "y := 1\n");
        Function<Path, String> commit = AhkDeployPlan.gitCommit(project);
        assertNull(commit.apply(project.resolve("tool.ahk")));
        AhkDeployGit git = new AhkDeployGit(project);
        AhkDeployGit.Result init = git.run("init", "-q");
        assertTrue(init.text(), init.ok());
        git.run("-c", "user.name=t", "-c", "user.email=t@t", "add", "tool.ahk");
        assertTrue(git.run("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "x").ok());
        String head = git.run("rev-parse", "--short", "HEAD").stdout().strip();
        commit = AhkDeployPlan.gitCommit(project);
        assertEquals(head, commit.apply(project.resolve("tool.ahk")));
        assertNull(commit.apply(project.resolve("new.ahk")));
        write(project.resolve("tool.ahk"), "x := 2\n");
        assertEquals(head + "+", commit.apply(project.resolve("tool.ahk")));
    }

    /**
     * Banner in the plan: AutoHotkey files implicitly, other files only by a line, the last matching line wins,
     * comment styles of the line or the file type, binary files only a warning; [AddBanner] without Banner is
     * a warning; the same target from two lines is an error.
     */
    public void testBannerPlan() throws Exception {
        Path tool = root.resolve("Json");
        Path dest = root.resolve("lib");
        write(tool.resolve("Json.ahk"), ";@Ahk2Exe-SetVersion 1.4.0\nx := 1\n");
        write(tool.resolve("test.ahk"), "t := 1\n");
        write(tool.resolve("run.cmd"), "echo\r\n");
        write(tool.resolve("style.css"), "a {}\n");
        write(tool.resolve("readme.txt"), "text");
        write(tool.resolve("logo.png"), "png");
        AhkDeployConfig config = AhkDeployConfig.parse("[Deploy]\nDestination=" + dest
                + "\nBanner=Copy of {source-relative} {version} from {project-name} at {timestamp}\n"
                + "[Install]\n*.ahk=.\nrun.cmd=.\nstyle.css=.\nreadme.txt=.\nlogo.png=.\n"
                + "[AddBanner]\n*.cmd=yes\nTEST.ahk=no\n*.css=yes\nreadme.txt=line:# <br>\n*.png=yes\n");
        assertEquals(List.of(), config.errors());
        AhkDeployPlan plan = AhkDeployPlan.create(config, tool, env(), List.of(), "T");
        assertEquals(List.of(), plan.errors());
        assertEquals(1, plan.warnings().size());
        assertTrue(plan.warnings().get(0), plan.warnings().get(0).contains("logo.png"));
        assertEquals(List.of("[Install]", "*.ahk=.", "run.cmd=.", "style.css=.", "readme.txt=.", "logo.png=.",
                "[AddBanner]", "*.cmd=yes", "TEST.ahk=no", "*.css=yes", "readme.txt=line:# <br>", "*.png=yes"),
                AhkDeployer.overview(config, tool, List.of()));
        Map<String, List<String>> banners = new TreeMap<>();
        for (AhkDeployPlan.Step step : plan.steps()) {
            banners.put(step.source().getFileName().toString(), step.banner());
        }
        assertEquals(List.of("; Copy of Json.ahk 1.4.0 from Json at T"), banners.get("Json.ahk"));
        assertNull(banners.get("test.ahk"));
        assertEquals(List.of(":: Copy of run.cmd  from Json at T"), banners.get("run.cmd"));
        assertEquals(List.of("/* Copy of style.css  from Json at T */"), banners.get("style.css"));
        assertEquals(List.of("# Copy of readme.txt  from Json at T <br>"), banners.get("readme.txt"));
        assertNull(banners.get("logo.png"));
        // {version} in the banner: other files lose their own @SetVersion line, AutoHotkey files keep theirs
        write(tool.resolve("page.htm"), "<!-- @SetVersion 2.0 -->\n<p/>\n");
        config = AhkDeployConfig.parse("[Deploy]\nDestination=" + dest + "\nBanner=v{version}\n"
                + "[Install]\nJson.ahk=.\npage.htm=.\n[AddBanner]\n*.htm=yes\n");
        plan = AhkDeployPlan.create(config, tool, env(), List.of());
        assertEquals(List.of("; v1.4.0"), plan.steps().get(0).banner());
        assertEquals(-1, plan.steps().get(0).dropLine());
        assertEquals(List.of("<!-- v2.0 -->"), plan.steps().get(1).banner());
        assertEquals(0, plan.steps().get(1).dropLine());
        config = AhkDeployConfig.parse("[Deploy]\nDestination=" + dest + "\nBanner=copy\n"
                + "[Install]\npage.htm=.\n[AddBanner]\n*.htm=yes\n");
        assertEquals(-1, AhkDeployPlan.create(config, tool, env(), List.of()).steps().get(0).dropLine());
        // the last matching line wins
        config = AhkDeployConfig.parse("[Deploy]\nDestination=" + dest + "\nBanner=x\n[Install]\n*.ahk=.\n"
                + "[AddBanner]\ntest.ahk=no\n*.ahk=yes\n");
        for (AhkDeployPlan.Step step : AhkDeployPlan.create(config, tool, env(), List.of()).steps()) {
            assertEquals(List.of("; x"), step.banner());
        }
        // yes for a type without known comment, invalid values and selectors
        config = AhkDeployConfig.parse("[Deploy]\nDestination=" + dest + "\nBanner=x\n"
                + "[Install]\nreadme.txt=.\n[AddBanner]\n*.txt=yes\n*.cmd=REM\n..\\x.ahk=yes\n*.x=block:/*\n");
        assertEquals(config.errors().toString(), 3, config.errors().size());
        assertTrue(config.errors().get(0), config.errors().get(0).contains("*.cmd"));
        assertTrue(config.errors().get(1), config.errors().get(1).contains("..\\x.ahk"));
        assertTrue(config.errors().get(2), config.errors().get(2).contains("*.x"));
        plan = AhkDeployPlan.create(config, tool, env(), List.of());
        assertEquals(plan.errors().toString(), 1, plan.errors().size());
        assertTrue(plan.errors().get(0), plan.errors().get(0).contains("readme.txt"));
        // [AddBanner] without Banner: a warning, no banner
        config = AhkDeployConfig.parse("[Deploy]\nDestination=" + dest + "\n[Install]\n*.ahk=.\n"
                + "[AddBanner]\n*.ahk=yes\n");
        assertEquals(List.of(), config.errors());
        plan = AhkDeployPlan.create(config, tool, env(), List.of());
        assertEquals(List.of(AhkBundle.message("deploy.ini.banner.missing", 6, "Json.ahk")), plan.warnings());
        for (AhkDeployPlan.Step step : plan.steps()) assertNull(step.banner());
        // the same target from two lines, also from both sections
        config = AhkDeployConfig.parse("[Deploy]\nDestination=" + dest + "\n[Install]\nJson.ahk=.\n"
                + "[InstallIfMissing]\nJSON.ahk=.\ntest.ahk=other\\\n");
        plan = AhkDeployPlan.create(config, tool, env(), List.of());
        assertEquals(plan.errors().toString(), 1, plan.errors().size());
        assertTrue(plan.errors().get(0), plan.errors().get(0).contains("target of line 4"));
        // Banner from a file of the library projects folder
        write(root.resolve("notice.txt"), "From {project-name}\n");
        config = AhkDeployConfig.parse("[Deploy]\nDestination=" + dest
                + "\nBanner=file:%LIBRARY_PROJECTS%\\notice.txt\n[Install]\nJson.ahk=.\n");
        plan = AhkDeployPlan.create(config, tool, env("LIBRARY_PROJECTS", root.toString()), List.of());
        assertEquals(List.of("; From Json"), plan.steps().get(0).banner());
        plan = AhkDeployPlan.create(config, tool, env(), List.of());
        assertTrue(plan.errors().toString(), plan.errors().get(0).contains("Library projects folder"));
    }

    /**
     * The warnings and hints about the banner as a whole: [InstallIfMissing] never gets one; with Banner but no
     * banner written a warning (files that could take one) or a gray hint (no such file), silent if all are excluded
     * by no; without Banner a warning for an including line and one for the AutoHotkey files.
     */
    public void testBannerWarnings() throws Exception {
        Path tool = root.resolve("Tool");
        String dest = "[Deploy]\nDestination=" + root.resolve("dest") + "\n";
        write(tool.resolve("tool.ahk"), "x := 1\n");
        write(tool.resolve("other.ahk"), "y := 1\n");
        write(tool.resolve("tool.exe"), "exe");
        write(tool.resolve("run.cmd"), "echo\r\n");
        write(tool.resolve("tool.ini"), "[a]\n");
        write(tool.resolve("readme.txt"), "text");
        // [InstallIfMissing] never gets a banner; an including line is a hint, with and without Banner
        for (String banner : List.of("Banner=x\n", "")) {
            AhkDeployPlan plan = AhkDeployPlan.create(AhkDeployConfig.parse(dest + banner
                    + "[Install]\ntool.ahk=.\n[InstallIfMissing]\ntool.ini=.\nother.ahk=.\n"
                    + "[AddBanner]\n*.ini=yes\n*.ahk=no\n"), tool, env(), List.of());
            assertEquals(List.of(), plan.errors());
            for (AhkDeployPlan.Step step : plan.steps()) assertNull(step.banner());
            assertEquals(List.of(AhkBundle.message("deploy.banner.if.missing", "tool.ini")), plan.hints());
            assertEquals(List.of(), plan.warnings());
        }
        // Banner, but only files that could take one and are not excluded: a warning
        AhkDeployPlan plan = AhkDeployPlan.create(AhkDeployConfig.parse(dest + "Banner=x\n"
                + "[Install]\nrun.cmd=.\ntool.ini=.\ntool.ahk=.\n[AddBanner]\n*.ahk=no\n"), tool, env(), List.of());
        assertEquals(List.of(AhkBundle.message("deploy.banner.none", "run.cmd", 1, "*.cmd")), plan.warnings());
        assertEquals(List.of(), plan.hints());
        // all excluded by no: silent
        plan = AhkDeployPlan.create(AhkDeployConfig.parse(dest + "Banner=x\n"
                + "[Install]\nrun.cmd=.\ntool.ahk=.\n[AddBanner]\n*.ahk=no\n*.cmd=no\n"), tool, env(), List.of());
        assertEquals(List.of(), plan.warnings());
        assertEquals(List.of(), plan.hints());
        // one file gets it: no summary
        plan = AhkDeployPlan.create(AhkDeployConfig.parse(dest + "Banner=x\n"
                + "[Install]\nrun.cmd=.\ntool.ahk=.\n"), tool, env(), List.of());
        assertEquals(List.of(), plan.warnings());
        // a banner whose lines are all left out counts as written
        plan = AhkDeployPlan.create(AhkDeployConfig.parse(dest + "Banner={version}\n"
                + "[Install]\nrun.cmd=.\ntool.ahk=.\n"), tool, env(), List.of());
        assertNull(plan.steps().get(1).banner());
        assertEquals(List.of(), plan.warnings());
        // no deployed file could take one (.exe, .txt): a gray hint, also with KeepExisting files
        plan = AhkDeployPlan.create(AhkDeployConfig.parse(dest + "Banner=x\n"
                + "[Install]\ntool.exe=.\nreadme.txt=.\n[InstallIfMissing]\ntool.ini=.\n"), tool, env(), List.of());
        assertEquals(List.of(), plan.errors());
        assertEquals(List.of(), plan.warnings());
        assertEquals(List.of(AhkBundle.message("deploy.banner.no.types")), plan.hints());
        // without Banner: an including line names its first file; AutoHotkey files deployed raw: one warning
        plan = AhkDeployPlan.create(AhkDeployConfig.parse(dest + "[Install]\nrun.cmd=.\n*.ahk=.\ntool.exe=.\n"
                + "[AddBanner]\n*.cmd=yes\n"), tool, env(), List.of());
        assertEquals(List.of(AhkBundle.message("deploy.ini.banner.missing", 8, "run.cmd"),
                AhkBundle.message("deploy.banner.unset", "other.ahk", 1, "*.ahk")), plan.warnings());
        assertTrue(plan.warnings().get(1), plan.warnings().get(1).contains("other.ahk (and 1 more)"));
        // no lines without Banner are silent; an AutoHotkey file included by a line is named only once
        plan = AhkDeployPlan.create(AhkDeployConfig.parse(dest + "[Install]\nrun.cmd=.\ntool.ahk=.\n"
                + "[AddBanner]\n*.cmd=no\n*.ahk=no\n"), tool, env(), List.of());
        assertEquals(List.of(), plan.warnings());
        plan = AhkDeployPlan.create(AhkDeployConfig.parse(dest + "[Install]\ntool.ahk=.\n"
                + "[AddBanner]\ntool.ahk=yes\n"), tool, env(), List.of());
        assertEquals(List.of(AhkBundle.message("deploy.ini.banner.missing", 6, "tool.ahk")), plan.warnings());
        assertTrue(AhkBundle.message("deploy.banner.unset", "a.ahk", 0, "*.ahk"),
                AhkBundle.message("deploy.banner.unset", "a.ahk", 0, "*.ahk").startsWith("Banner is not set, but a.ahk would"));
    }

    /** The folder variables of AutoHotkey are known; environment variables are not resolved. */
    public void testVariables() {
        java.util.function.Function<String, String> variables = AhkDeployPlan.variables();
        assertNotNull(variables.apply("A_MyDocuments"));
        assertNotNull(variables.apply("a_appdata"));
        assertNull(variables.apply("APPDATA"));
        assertNull(variables.apply("USERPROFILE"));
        List<String> errors = new ArrayList<>();
        assertNull(AhkDeployPlan.expand("%USERPROFILE%\\x", variables, null, errors, "Destination"));
        assertEquals(1, errors.size());
    }

    /** The smallest headers of a 64-bit or 32-bit executable with a certificate table of the given size. */
    private static byte[] exe(boolean wide, int certificates) {
        java.nio.ByteBuffer pe = java.nio.ByteBuffer.allocate(512).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        pe.putShort(0, (short) 0x5A4D).putInt(60, 128).putInt(128, 0x4550);
        pe.putShort(152, (short) (wide ? 0x20B : 0x10B));
        int directories = 152 + (wide ? 112 : 96);
        pe.putInt(directories - 4, 16).putInt(directories + 4 * 8 + 4, certificates);
        return pe.array();
    }

    /**
     * {@code EmbedOrigin}: the executable with a script next to it carries project and computer at its end, but not
     * the one without a script, the one of {@code [InstallIfMissing]}, a signed one, or with {@code no}; the built-in
     * banners name folder and computer only with it, an own text always.
     */
    public void testEmbedOrigin() throws Exception {
        Path tool = root.resolve("a, b 100%");
        for (String name : List.of("wide", "narrow", "signed", "foreign", "kept")) {
            Files.createDirectories(tool);
            Files.write(tool.resolve(name + ".exe"), exe(!name.equals("narrow"), name.equals("signed") ? 8 : 0));
            if (!name.equals("foreign")) write(tool.resolve(name + ".ahk"), "x := 1\n");
        }
        Path dest = root.resolve("dest");
        String lines = "\nDestination=" + dest + "\nBanner=@standard\n[Install]\nwide.exe=.\nnarrow.ex?=.\nsigned.exe=.\n"
                + "foreign.exe=.\nwide.ahk=.\n[InstallIfMissing]\nkept.exe=.\n";
        AhkDeployPlan plan = AhkDeployPlan.create(AhkDeployConfig.parse("[Deploy]" + lines), tool,
                env("A_ComputerName", "PC1"), List.of(), "T");
        assertEquals(List.of(), plan.errors());
        assertEquals(1, plan.hints().size());
        assertTrue(plan.hints().get(0), plan.hints().get(0).startsWith("signed.exe "));
        String block = "\r\n[ahk-v2-plugin]\r\nProjectPath=" + tool + "\r\nProjectComputer=PC1\r\n";
        String banner = String.join("|", plan.steps().get(4).banner());
        assertTrue(banner, banner.contains("[" + tool + "]") && banner.contains("; Deployed    T on PC1|"));
        for (AhkDeployCopier.StepResult result : AhkDeployCopier.copy(plan.steps())) {
            assertEquals(AhkDeployCopier.Outcome.COPIED, result.outcome());
        }
        for (String name : List.of("wide", "narrow", "signed", "foreign", "kept")) {
            byte[] source = Files.readAllBytes(tool.resolve(name + ".exe"));
            byte[] copy = Files.readAllBytes(dest.resolve(name + ".exe"));
            assertEquals(512, source.length);
            assertTrue(name, java.util.Arrays.equals(source, java.util.Arrays.copyOf(copy, 512)));
            assertEquals(name, name.equals("wide") || name.equals("narrow") ? block : "",
                    new String(copy, 512, copy.length - 512, StandardCharsets.UTF_8));
        }
        // without a computer name
        assertEquals("\r\n[ahk-v2-plugin]\r\nProjectPath=" + tool + "\r\n", AhkExeOrigin.block(tool, null));
        // no: neither banner nor executable
        plan = AhkDeployPlan.create(AhkDeployConfig.parse("[Deploy]\nEmbedOrigin=no" + lines), tool,
                env("A_ComputerName", "PC1"), List.of(), "T");
        assertEquals(List.of(), plan.errors());
        assertEquals(List.of(), plan.hints());
        for (AhkDeployPlan.Step step : plan.steps()) assertNull(step.origin());
        banner = String.join("|", plan.steps().get(4).banner());
        assertTrue(banner, banner.contains("; Project     a, b 100%|") && banner.contains("; Deployed    T|"));
        // an own text decides itself
        plan = AhkDeployPlan.create(AhkDeployConfig.parse("[Deploy]\nEmbedOrigin=no\nBanner={project-path} {computer}\n"
                + "[Install]\nwide.ahk=" + dest + "\n"), tool, env("A_ComputerName", "PC1"), List.of(), "T");
        assertEquals(List.of("; " + tool + " PC1"), plan.steps().get(0).banner());
        // no executable: unchanged
        byte[] text = "MZ text".getBytes(StandardCharsets.UTF_8);
        assertSame(text, AhkExeOrigin.append(text, block));
    }

    /** The copier writes the banner in front of the content and keeps the modification time of the source. */
    public void testCopyWithBanner() throws Exception {
        Path a = write(root.resolve("src/a.ahk"), "x := 1\r\n");
        Files.setLastModifiedTime(a, FileTime.fromMillis(1_600_000_000_000L));
        Path target = root.resolve("dest/a.ahk");
        List<AhkDeployCopier.StepResult> results = AhkDeployCopier.copy(List.of(
                new AhkDeployPlan.Step(a, target, false, List.of("; do not edit"))));
        assertEquals(AhkDeployCopier.Outcome.COPIED, results.get(0).outcome());
        assertEquals("; do not edit\r\nx := 1\r\n", Files.readString(target));
        assertEquals(1_600_000_000_000L, Files.getLastModifiedTime(target).toMillis());
        assertEquals("x := 1\r\n", Files.readString(a));
    }

    /**
     * Library projects: the deploy targets of the projects below the folder are known copies with their source and
     * banner lines; targets inside a project, other folders and files are not. In the IDE a copy is read-only and has
     * a banner; a library file without a project has the banner "Create Library Project".
     */
    public void testLibraryProjects() throws Exception {
        Path library = root.resolve("Library");
        Path lib = root.resolve("Lib");
        write(library.resolve("Json/Json.ahk"), "j := 1\n");
        write(library.resolve("Json/settings.ahk-v2-plugin.ini"), "[Deploy]\nDestination=" + lib
                + "\nBanner=Copy`nof {project-name}\n[Install]\nJson.ahk=.\nJson.ahk2=" + library.resolve("Json/out")
                + "\\\n[AddBanner]\nJson.ahk=yes\n");
        write(library.resolve("Json/Json.ahk2"), "x");
        write(library.resolve("Str/Str.ahk"), "s := 1\n");
        write(library.resolve("Str/settings.ahk-v2-plugin.ini"), "[Install]\nStr.ahk=" + lib + "\\\n");
        write(library.resolve("NoProject/x.ahk"), "x\n");
        Map<String, AhkLibraryProjects.Source> copies = AhkLibraryProjects.scan(library.toString(), env(), lib);
        assertEquals(2, copies.size());
        AhkLibraryProjects.Source json = copies.get(lib.resolve("JSON.ahk").toString().toLowerCase());
        assertNotNull(copies.keySet().toString(), json);
        assertEquals("Json", json.name());
        assertEquals(library.resolve("Json/Json.ahk"), json.file());
        assertTrue(AhkLibraryProjects.scan(root.resolve("missing").toString(), env(), lib).isEmpty());

        // the shift is the number of lines the copy has more than the source
        Path copy = write(lib.resolve("Json.ahk"), "; Copy\n; of Json\nj := 1\n");
        assertEquals(0, json.sourceLine(copy, 1));
        assertEquals(0, json.sourceLine(copy, 2));
        assertEquals(3, json.sourceLine(copy, 5));

        // in the IDE: read-only, banner, action
        Path other = write(lib.resolve("Other.ahk"), "o := 1\n");
        de.kumpewo.autohotkey.settings.AhkSettings settings = de.kumpewo.autohotkey.settings.AhkSettings.getInstance();
        try {
            settings.setLibraryProjectsFolder(library.toString());
            AhkLibraryProjects.invalidate();
            com.intellij.openapi.vfs.VirtualFile copyFile =
                    com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(copy);
            com.intellij.openapi.vfs.VirtualFile otherFile =
                    com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(other);
            assertNotNull(copyFile);
            assertNotNull(otherFile);
            AhkLibraryCopyAccess access = new AhkLibraryCopyAccess();
            assertFalse(access.isPotentiallyWritable(copyFile));
            assertTrue(access.isPotentiallyWritable(otherFile));
            assertEquals(List.of(copyFile), new ArrayList<>(access.requestWriting(List.of(copyFile, otherFile))));
            AhkLibraryCopyNotification notification = new AhkLibraryCopyNotification();
            assertNotNull(notification.collectNotificationData(getProject(), copyFile));
            assertNull(notification.collectNotificationData(getProject(), otherFile));
            // a file of the user library folder without a project: banner and action "Create Library Project"
            AhkLibraryLayout.setUserLibraryForTests(lib);
            assertNotNull(notification.collectNotificationData(getProject(), otherFile));
            assertEquals(List.of("Other"), AhkLibraryActions.FromFile.segments(otherFile));
            assertNull(AhkLibraryActions.FromFile.segments(copyFile));
            // no folder (Lib-direct): nothing is a copy, only the hint about library projects until it is dismissed
            settings.setLibraryProjectsFolder("");
            assertNull(AhkLibraryProjects.sourceOf(copyFile));
            assertNull(AhkLibraryActions.FromFile.segments(otherFile));
            assertTrue(AhkLibraryCopyNotification.needsProjectsHint(otherFile));
            assertNotNull(notification.collectNotificationData(getProject(), otherFile));
            com.intellij.ide.util.PropertiesComponent.getInstance()
                    .setValue(AhkLibraryCopyNotification.HINT_DISMISSED, true);
            assertFalse(AhkLibraryCopyNotification.needsProjectsHint(otherFile));
            assertNull(notification.collectNotificationData(getProject(), otherFile));
        } finally {
            com.intellij.ide.util.PropertiesComponent.getInstance().unsetValue(AhkLibraryCopyNotification.HINT_DISMISSED);
            AhkLibraryLayout.setUserLibraryForTests(null);
            settings.setLibraryProjectsFolder("");
            AhkLibraryProjects.invalidate();
        }
    }

    /** Library names and the paths of the naming rule: library file and project folder. */
    public void testLibraryLayout() {
        assertEquals(List.of("v2", "Gui"), AhkLibraryLayout.parseName(" v2\\Gui "));
        assertEquals(List.of("v2", "Gui"), AhkLibraryLayout.parseName("v2/Gui.AHK"));
        assertEquals(List.of("Json"), AhkLibraryLayout.parseName("Json"));
        for (String invalid : new String[]{"", ".ahk", "a\\..\\b", "a:b", "a\\", "x?", "a\\ b", "a.\\b"}) {
            assertNull(invalid, AhkLibraryLayout.parseName(invalid));
        }
        Path lib = root.resolve("Lib");
        Path projects = root.resolve("Library");
        List<String> gui = List.of("v2", "Gui");
        assertEquals("v2\\Gui", AhkLibraryLayout.name(gui));
        assertEquals(lib.resolve("v2").resolve("Gui.ahk"), AhkLibraryLayout.libraryFile(lib, gui));
        assertEquals(projects.resolve("Gui"), AhkLibraryLayout.projectFolder(projects, gui));
        assertEquals(gui, AhkLibraryLayout.nameOf(lib.resolve("v2/Gui.ahk"), lib));
        assertEquals(List.of("Json"), AhkLibraryLayout.nameOf(root.resolve("LIB/Json.ahk"), lib));
        assertNull(AhkLibraryLayout.nameOf(root.resolve("Other/Json.ahk"), lib));
        assertNull(AhkLibraryLayout.nameOf(root.resolve("Lib2/Json.ahk"), lib));
        assertEquals(projects.resolve("Gui"),
                AhkLibraryLayout.projectFolderFor(lib.resolve("v2/Gui.ahk"), lib, projects));
        assertNull(AhkLibraryLayout.projectFolderFor(lib.resolve("v2/Gui.ahk"), lib, null));
    }

    /**
     * A new library project: folder, source (new or copied), settings whose plan deploys the source into the user
     * library folder with the banner; an existing folder is an error and stays untouched.
     */
    public void testNewLibraryProject() throws Exception {
        Path projects = root.resolve("Library");
        Path documents = root.resolve("Docs");
        Path source = AhkNewLibraryProject.create(projects, List.of("v2", "Gui"), null);
        assertEquals(projects.resolve("Gui/Gui.ahk"), source);
        assertEquals(AhkNewLibraryProject.NEW_SOURCE, Files.readString(source));
        String ini = Files.readString(projects.resolve("Gui/settings.ahk-v2-plugin.ini"));
        assertFalse(ini, ini.contains("$"));
        // the placeholders of Banner in the comments stay as they are
        assertTrue(ini, ini.contains("{project-name} is the folder name of this project, {project-path}"));
        assertFalse(ini.replace("\r\n", "").contains("\n"));
        for (String line : ini.split("\r\n")) assertTrue(line, line.length() <= 120);
        assertCommentStyle(ini);
        assertTrue(ini, ini.contains("#Include <v2\\Gui>"));
        assertTrue(ini, ini.contains("for the library project Gui\r\n"));
        // Destination is the library folder itself, the subfolder is in the target
        assertTrue(ini, ini.contains("\r\nDestination=%A_MyDocuments%\\AutoHotkey\\Lib\\\r\n"));
        assertTrue(ini, ini.contains("\r\nGui.ahk=v2\\\r\n"));
        AhkDeployConfig config = AhkDeployConfig.parse(ini);
        assertEquals(List.of(), config.errors());
        assertEquals("@library", config.banner());
        assertTrue(ini, ini.contains("\r\n;Banner=find:banner.txt\r\n"));
        assertEquals(List.of(), config.versionErrors());
        assertEquals(2, config.versionRules().size());
        Function<String, String> variables = env("A_MyDocuments", documents.toString());
        AhkDeployPlan plan = AhkDeployPlan.create(config, projects.resolve("Gui"), variables, List.of(), "T",
                file -> "abc1234", AhkDeployPlan.libraries(variables));
        assertEquals(List.of(), plan.errors());
        assertEquals(documents.resolve("AutoHotkey\\Lib\\v2\\Gui.ahk"), plan.steps().get(0).target());
        // a file of another library folder added later gets a target relative to the library folder
        Path libFolder = documents.resolve("AutoHotkey\\Lib");
        assertEquals(".", AhkAddToLibraryProject.target(config, projects.resolve("Gui"),
                libFolder.resolve("Gui.ahk"), variables));
        assertEquals("v2\\", AhkAddToLibraryProject.target(config, projects.resolve("Gui"),
                libFolder.resolve("v2\\Gui.ahk"), variables));
        String dashes = "; " + AhkBanner.DASHES;
        assertEquals(List.of(dashes,
                "; INSTALLED LIBRARY  -  Do not modify here. Edit the source and deploy again.",
                ";",
                "; Project     Gui [" + projects.resolve("Gui") + "]",
                "; Source      Gui.ahk",
                "; Version     0.1.0",
                "; Commit      abc1234",
                "; Deployed    T",
                "; Use         #Include <v2\\Gui>",
                dashes), plan.steps().get(0).banner());
        // only the targets: without Git
        plan = AhkDeployPlan.targets(config, projects.resolve("Gui"), variables);
        assertFalse(plan.steps().get(0).banner().toString(), plan.steps().get(0).banner().toString().contains("Commit"));
        // existing folder
        Files.writeString(source, "mine");
        try {
            AhkNewLibraryProject.create(projects, List.of("v2", "Gui"), null);
            fail("existing folder");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("exists"));
        }
        assertEquals("mine", Files.readString(source));
        // from an existing library file: its bytes
        byte[] content = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'x', '\n'};
        Path json = AhkNewLibraryProject.create(projects, List.of("Json"), content);
        assertTrue(java.util.Arrays.equals(content, Files.readAllBytes(json)));
        assertTrue(Files.readString(projects.resolve("Json/settings.ahk-v2-plugin.ini"))
                .contains("Destination=%A_MyDocuments%\\AutoHotkey\\Lib\\\r\n"));
        assertTrue(Files.readString(projects.resolve("Json/settings.ahk-v2-plugin.ini")).contains("\r\nJson.ahk=.\r\n"));
        // a shared banner.txt above the projects folder: the new project finds it
        Files.writeString(projects.getParent().resolve(AhkNewLibraryProject.SHARED_BANNER), "Copy of {project-name}");
        AhkNewLibraryProject.create(projects, List.of("Str"), null);
        String shared = Files.readString(projects.resolve("Str/settings.ahk-v2-plugin.ini"));
        AhkDeployConfig sharedConfig = AhkDeployConfig.parse(shared);
        assertEquals("find:banner.txt", sharedConfig.banner());
        assertTrue(shared, shared.contains("\r\n;Banner=@library\r\n"));
        plan = AhkDeployPlan.targets(sharedConfig, projects.resolve("Str"), variables);
        assertEquals(List.of(), plan.errors());
        assertEquals(List.of("; Copy of Str"), plan.steps().get(0).banner());
    }

    /**
     * Library projects, also in subfolders; the project of the naming rule ({@code <projects>\Gui} for
     * {@code v2\Gui}) wins over another one with the same target.
     */
    public void testLibraryProjectsNested() throws Exception {
        Path library = root.resolve("Library");
        Path lib = root.resolve("Lib");
        write(library.resolve("Gui/Gui.ahk"), "g := 1\n");
        write(library.resolve("Gui/settings.ahk-v2-plugin.ini"), "[Install]\nGui.ahk=" + lib + "\\v2\\\n");
        write(library.resolve("Gui/nested/settings.ahk-v2-plugin.ini"), "[Install]\nx.ahk=" + lib + "\\\n");
        write(library.resolve("Gui/nested/x.ahk"), "x\n");
        write(library.resolve("A/Old/Gui.ahk"), "old\n");
        write(library.resolve("A/Old/settings.ahk-v2-plugin.ini"), "[Install]\nGui.ahk=" + lib + "\\v2\\\n");
        write(library.resolve(".hidden/settings.ahk-v2-plugin.ini"), "[Install]\nh.ahk=" + lib + "\\\n");
        write(library.resolve(".hidden/h.ahk"), "h\n");
        Map<String, AhkLibraryProjects.Source> copies = AhkLibraryProjects.scan(library.toString(), env(), lib);
        assertEquals(copies.keySet().toString(), 1, copies.size());
        assertEquals(library.resolve("Gui/Gui.ahk"),
                copies.get(lib.resolve("v2/Gui.ahk").toString().toLowerCase()).file());
        // without the naming rule the first project by path wins
        copies = AhkLibraryProjects.scan(library.toString(), env(), null);
        assertEquals(library.resolve("A/Old/Gui.ahk"), copies.get(lib.resolve("v2/Gui.ahk").toString().toLowerCase()).file());
    }

    /** The script of the elevated process quotes paths; its log is read back. */
    public void testElevatedScript() {
        List<AhkDeployPlan.Step> steps = List.of(
                new AhkDeployPlan.Step(Path.of("C:\\it's\\a.exe"), Path.of("C:\\Program Files\\x\\a.exe"), false),
                new AhkDeployPlan.Step(Path.of("C:\\t\\b.ini"), Path.of("C:\\cfg\\b.ini"), true));
        String script = AhkDeployCopier.elevatedScript(steps, Path.of("C:\\temp\\deploy.log"));
        assertTrue(script, script.contains("$log = 'C:\\temp\\deploy.log'"));
        assertTrue(script, script.contains(",@('C:\\it''s\\a.exe', 'C:\\Program Files\\x\\a.exe', $false)"));
        assertTrue(script, script.contains(",@('C:\\t\\b.ini', 'C:\\cfg\\b.ini', $true)"));
        assertFalse(script, script.replace("\r\n", "").contains("\n"));
        var results = AhkDeployCopier.readLog(steps, List.of("\uFEFFOK\t0", "ERROR\t1\tAccess denied", "junk"));
        assertEquals(2, results.size());
        assertEquals(AhkDeployCopier.Outcome.COPIED, results.get(0).outcome());
        assertEquals(AhkDeployCopier.Outcome.FAILED, results.get(1).outcome());
        assertEquals("Access denied", results.get(1).error());
    }

    /**
     * The script of the elevated process really copies, run here without elevation (Windows only): new file in a new
     * folder, overwrite, [InstallIfMissing], paths with spaces and quotes, stop at the first error.
     */
    public void testElevatedScriptRuns() throws Exception {
        if (!com.intellij.openapi.util.SystemInfo.isWindows) return;
        Path a = write(root.resolve("it's src/a.txt"), "new a");
        Files.setLastModifiedTime(a, FileTime.fromMillis(1_600_000_000_000L));
        Path existing = write(root.resolve("dest dir/a.txt"), "old content that is longer");
        Files.setAttribute(existing, "dos:readonly", true);
        Path kept = write(root.resolve("dest dir/k.txt"), "old k");
        List<AhkDeployPlan.Step> steps = List.of(
                new AhkDeployPlan.Step(a, existing, false),
                new AhkDeployPlan.Step(a, root.resolve("dest dir/new/a.txt"), false),
                new AhkDeployPlan.Step(a, kept, true),
                new AhkDeployPlan.Step(root.resolve("missing.txt"), root.resolve("dest dir/m.txt"), false),
                new AhkDeployPlan.Step(a, root.resolve("dest dir/never.txt"), false));
        Path log = root.resolve("deploy.log");
        Files.writeString(log, "");
        Path script = root.resolve("deploy.ps1");
        Files.write(script, ("﻿" + AhkDeployCopier.elevatedScript(steps, log)).getBytes(StandardCharsets.UTF_8));
        var line = new com.intellij.execution.configurations.GeneralCommandLine("powershell.exe", "-NoProfile",
                "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.toString());
        var output = new com.intellij.execution.process.CapturingProcessHandler(line).runProcess(SLOW_START_MS);
        assertEquals(output.getStderr(), 1, output.getExitCode());
        var results = AhkDeployCopier.readLog(steps, Files.readAllLines(log, StandardCharsets.UTF_8));
        List<AhkDeployCopier.Outcome> outcomes = new ArrayList<>();
        for (var result : results) outcomes.add(result.outcome());
        assertEquals(List.of(AhkDeployCopier.Outcome.COPIED, AhkDeployCopier.Outcome.COPIED,
                AhkDeployCopier.Outcome.SKIPPED, AhkDeployCopier.Outcome.FAILED), outcomes);
        assertEquals("new a", Files.readString(existing));
        assertEquals("new a", Files.readString(root.resolve("dest dir/new/a.txt")));
        assertEquals(1_600_000_000_000L, Files.getLastModifiedTime(existing).toMillis());
        assertEquals("old k", Files.readString(kept));
        assertFalse(Files.exists(root.resolve("dest dir/never.txt")));
        assertTrue(isReadOnly(existing));
        assertTrue(isReadOnly(root.resolve("dest dir/new/a.txt")));
        assertFalse(isReadOnly(kept));
    }

    /** The line ;@Ahk2Exe-SetVersion as Ahk2Exe reads it: case, comment, block comments, ignored sections. */
    public void testVersionDirective() {
        String text = "﻿;@Ahk2Exe-SetVersion 9.9 ; comment\n";
        List<AhkVersionDirective.Line> lines = AhkVersionDirective.find(text);
        assertEquals(1, lines.size());
        assertEquals("9.9", lines.get(0).value());
        assertEquals("9.9", text.substring(lines.get(0).valueStart(), lines.get(0).valueEnd()));

        text = "x := 1\r\n  ;@ahk2exe-setversion   1.2.3-beta\t; comment\r\n"
                + "; ;@Ahk2Exe-SetVersion 0.1\r\n"
                + "/*\r\n;@Ahk2Exe-SetVersion 0.2\r\n*/\r\n"
                + "/* one line */\r\n"
                + ";@Ahk2Exe-IgnoreBegin\r\n;@Ahk2Exe-SetVersion 0.3\r\n;@Ahk2Exe-IgnoreEnd\r\n"
                + ";@Ahk2Exe-SetVersionX 0.4\r\n"
                + ";@Ahk2Exe-SetProductVersion 0.5\r\n";
        lines = AhkVersionDirective.find(text);
        assertEquals(1, lines.size());
        AhkVersionDirective.Line line = lines.get(0);
        assertEquals(1, line.line());
        assertEquals("1.2.3-beta", line.value());
        assertEquals("1.2.3-beta", text.substring(line.valueStart(), line.valueEnd()));
        assertFalse(line.computed());

        lines = AhkVersionDirective.find("/*@Ahk2Exe-Keep\n;@Ahk2Exe-SetVersion %A_PriorLine%\n*/\n;@Ahk2Exe-SetVersion\n");
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).computed());
        assertEquals("", lines.get(1).value());
    }

    /** Next versions, valid versions and the tag name. */
    public void testVersionBump() {
        assertEquals("1.2.4", AhkVersionDirective.bump("1.2.3-dev", AhkVersionDirective.Part.PATCH));
        assertEquals("1.3.0", AhkVersionDirective.bump("1.2.3", AhkVersionDirective.Part.MINOR));
        assertEquals("2.0.0", AhkVersionDirective.bump("1.2.3", AhkVersionDirective.Part.MAJOR));
        // without any version yet: from 0.0.0
        assertEquals("0.0.1", AhkVersionDirective.next("", AhkVersionDirective.Part.PATCH));
        assertEquals("0.1.0", AhkVersionDirective.next("", AhkVersionDirective.Part.MINOR));
        assertEquals("1.0.0", AhkVersionDirective.next("", AhkVersionDirective.Part.MAJOR));
        assertEquals("1.2.4", AhkVersionDirective.next("1.2.3", AhkVersionDirective.Part.PATCH));
        assertNull(AhkVersionDirective.next("beta", AhkVersionDirective.Part.PATCH));
        assertEquals("1.0.1", AhkVersionDirective.bump("1.0", AhkVersionDirective.Part.PATCH));
        assertEquals("1.2.4.0", AhkVersionDirective.bump("1.2.3.4", AhkVersionDirective.Part.PATCH));
        assertEquals("1.1.0", AhkVersionDirective.bump("v1.0.5", AhkVersionDirective.Part.MINOR));
        assertFalse(AhkVersionDirective.isValidVersion("v1.0"));
        assertNull(AhkVersionDirective.bump("beta", AhkVersionDirective.Part.PATCH));
        assertTrue(AhkVersionDirective.isValidVersion("1.2.3-rc.1"));
        assertFalse(AhkVersionDirective.isValidVersion("1.2 3"));
        assertFalse(AhkVersionDirective.isValidVersion("1,2"));
        assertFalse(AhkVersionDirective.isValidVersion("%U_V%"));
        assertFalse(AhkVersionDirective.isValidVersion(""));
        assertEquals("v1.0.2", AhkVersionDirective.tagName("1.0.2"));
        assertEquals("v1.0", AhkVersionDirective.tagName("v1.0"));
        assertTrue(AhkVersionDirective.compare("1.10", "1.9.5") > 0);
        assertTrue(AhkVersionDirective.compare("1.2", "1.2.0.1") < 0);
        assertTrue(AhkVersionDirective.compare("beta", "0.1") < 0);
        assertEquals(0, AhkVersionDirective.compare("1.2.3", "1.2.3"));
    }

    /** The expected output file of Ahk2Exe. */
    public void testCompiledExe() {
        Path script = root.resolve("tool/tool.ahk");
        assertEquals(root.resolve("tool/tool.exe"), AhkDeployer.compiledExe(script, "MsgBox 1\n"));
        assertEquals(root.resolve("tool/bin/other.exe"),
                AhkDeployer.compiledExe(script, ";@Ahk2Exe-ExeName bin\\other\n"));
        assertNull(AhkDeployer.compiledExe(script, ";@Ahk2Exe-ExeName %A_ScriptName%\n"));
    }

    /** Without Git: no warning; the overview lists the copy sections without comments; copying works. */
    public void testPrepareAndCopyWithoutGit() throws Exception {
        write(root.resolve("tool.ahk"), "MsgBox 1\n");
        write(root.resolve("tool.ini"), "ini");
        write(root.resolve(AhkDeployConfig.FILE_NAME), "[Deploy]\nDestination=" + root.resolve("dest")
                + "\nRequiresAdmin=false\n[InstallIfMissing]\n; the configuration\ntool.ini=.\n"
                + "[Install]\n  ; the script\ntool.ahk=.\\\n");
        var prepared = AhkDeployer.prepare(root, env(), false);
        assertEquals(List.of(), prepared.errors());
        assertEquals(List.of(AhkBundle.message("deploy.banner.unset", "tool.ahk", 0, "*.ahk")), prepared.warnings());
        assertEquals(List.of(), prepared.hints());
        assertFalse(prepared.uncommitted());
        assertEquals(List.of("[Install]", "tool.ahk=.\\", "[InstallIfMissing]", "tool.ini=."), prepared.overview());
        List<AhkDeployer.Line> report = new ArrayList<>();
        AhkDeployer.execute(prepared, report, new EmptyProgressIndicator());
        assertTrue(report.toString(), report.get(report.size() - 1).text().startsWith("Deployed: 2 file(s) copied"));
        assertTrue(Files.isRegularFile(root.resolve("dest/tool.ahk")));

        // the old keys of Deploy are unknown now
        write(root.resolve(AhkDeployConfig.FILE_NAME), "[Deploy]\nCommitBeforeInstall=true\n"
                + "[Install]\ntool.ahk=" + root.resolve("dest") + "\\\n");
        prepared = AhkDeployer.prepare(root, env(), false);
        assertEquals(prepared.errors().toString(), 1, prepared.errors().size());
        assertTrue(prepared.errors().get(0), prepared.errors().get(0).contains("unknown key CommitBeforeInstall"));
    }

    /**
     * Compile: every executable of a copy line without wildcards that has a script of the same name next
     * to it is compiled from it (each script once, in the order of the lines) and counts as existing for the copies;
     * an executable without a script is just copied; mistakes (no ;@Ahk2Exe-Base, another ;@Ahk2Exe-ExeName) stop
     * everything. Without the switch only a warning about an older executable.
     */
    public void testPrepareCompile() throws Exception {
        String base = ";@Ahk2Exe-Base ..\\v2\\AutoHotkey64.exe\n";
        write(root.resolve("tool.ahk"), base + "MsgBox 1\n");
        write(root.resolve("console/tool-console.ahk"), base + "MsgBox 2\n");
        write(root.resolve("bin/prebuilt.exe"), "exe");
        write(root.resolve(AhkDeployConfig.FILE_NAME), "[Deploy]\nDestination=" + root.resolve("dest")
                + "\nCompile=true\n[Install]\nTOOL.EXE=.\nconsole\\tool-console.exe=.\n"
                + "bin\\prebuilt.exe=.\ntool.ex?=copy\\\n[InstallIfMissing]\ntool.exe=keep\\\n");
        var prepared = AhkDeployer.prepare(root, env(), false);
        assertEquals(List.of(root.resolve("tool.ahk"), root.resolve("console/tool-console.ahk")), prepared.compile());
        assertEquals(List.of(root.resolve("TOOL.EXE"), root.resolve("console/tool-console.exe"),
                root.resolve("tool.exe")), prepared.willExist());
        assertEquals(List.of("compile tool.ahk", "compile console\\tool-console.ahk", "[Install]", "TOOL.EXE=.",
                "console\\tool-console.exe=.", "bin\\prebuilt.exe=.", "tool.ex?=copy\\", "[InstallIfMissing]",
                "tool.exe=keep\\"), prepared.overview());
        // only "Ahk2Exe was not found" is possible (depends on the machine)
        assertTrue(prepared.errors().toString(), prepared.errors().stream().allMatch(e -> e.contains("Ahk2Exe was")));
        assertEquals(5, prepared.plan().steps().size());

        // mistakes: no ;@Ahk2Exe-Base, the script compiles to another file
        write(root.resolve("tool.ahk"), "MsgBox 1\n");
        write(root.resolve("console/tool-console.ahk"), base + ";@Ahk2Exe-ExeName other\nMsgBox 2\n");
        prepared = AhkDeployer.prepare(root, env(), false);
        List<String> errors = prepared.errors().stream().filter(e -> !e.contains("Ahk2Exe was")).toList();
        assertEquals(errors.toString(), 2, errors.size());
        assertTrue(errors.get(0), errors.get(0).contains("tool.ahk") && errors.get(0).contains(";@Ahk2Exe-Base"));
        assertTrue(errors.get(1), errors.get(1).contains("console\\other.exe"));

        // switched off: nothing compiled, the executable must exist, a warning if it is older
        write(root.resolve(AhkDeployConfig.FILE_NAME), "[Deploy]\nDestination=" + root.resolve("dest")
                + "\nCompile=false\n[Install]\ntool.exe=.\n");
        write(root.resolve("tool.exe"), "exe");
        Files.setLastModifiedTime(root.resolve("tool.exe"), FileTime.fromMillis(0));
        prepared = AhkDeployer.prepare(root, env(), false);
        assertEquals(List.of(), prepared.errors());
        assertEquals(List.of(), prepared.compile());
        assertEquals(prepared.warnings().toString(), 1, prepared.warnings().size());
        assertTrue(prepared.warnings().get(0), prepared.warnings().get(0).startsWith("tool.exe is older"));
    }

    /** Deploy really compiles the console variant in a folder with spaces and copies it (needs Ahk2Exe). */
    public void testDeployCompilesVariant() throws Exception {
        String ahk2Exe = de.kumpewo.autohotkey.settings.AhkSettings.getInstance().findAhk2Exe();
        if (ahk2Exe == null || Path.of(ahk2Exe).getParent().getParent() == null) return;
        Path base = Path.of(ahk2Exe).getParent().getParent().resolve("v2").resolve("AutoHotkey64.exe");
        if (!Files.isRegularFile(base)) return;
        write(root.resolve("tool.ahk"), "MsgBox 1\n");
        write(root.resolve("my console/my console.ahk"), ";@Ahk2Exe-Base " + base + "\nExitApp\n");
        write(root.resolve(AhkDeployConfig.FILE_NAME), "[Deploy]\nDestination=" + root.resolve("dest")
                + "\nCompile=true\n[Install]\ntool.ahk=.\nmy console\\my console.exe=.\n");
        var prepared = AhkDeployer.prepare(root, env(), false);
        assertEquals(List.of(), prepared.errors());
        List<AhkDeployer.Line> report = new ArrayList<>();
        AhkDeployer.execute(prepared, report, new EmptyProgressIndicator());
        assertTrue(report.toString(), report.stream().anyMatch(l -> l.text().equals("Compiled my console\\my console.ahk.")));
        assertTrue(report.toString(), report.get(report.size() - 1).text().startsWith("Deployed: 2 file(s) copied"));
        assertTrue(Files.isRegularFile(root.resolve("dest/my console.exe")));
    }

    /** The saved texts of the scripts, as the action reads them. */
    private static Map<Path, String> texts(Path... files) throws Exception {
        Map<Path, String> texts = new TreeMap<>();
        for (Path file : files) texts.put(file, Files.readString(file));
        return texts;
    }

    /**
     * Set Version without Git and without settings: the lines of all scripts are read (scripts without a line get
     * one), different versions are a warning, the highest is the current one; mistakes are reported.
     */
    public void testSetVersionPrepareWithoutGit() throws Exception {
        Path gui = write(root.resolve("tool.ahk"), ";@Ahk2Exe-SetVersion 1.0\nMsgBox 1\n");
        Path console = write(root.resolve("console/tool-console.ahk"), ";@Ahk2Exe-SetVersion 1.0\nMsgBox 2\n");
        Path lib = write(root.resolve("lib/helper.ahk"), "f() => 1\n");
        Path notes = write(root.resolve("notes.txt"), "text\n");
        var prepared = AhkSetVersion.prepare(root, "Tool", texts(gui, console, lib, notes), false);
        assertEquals(List.of(), prepared.errors());
        assertEquals(List.of(AhkBundle.message("version.dialog.added", "lib\\helper.ahk")), prepared.warnings());
        assertEquals(List.of("console\\tool-console.ahk", "lib\\helper.ahk", "tool.ahk"),
                prepared.scripts().stream().map(AhkSetVersion.Script::name).toList());
        assertTrue(prepared.scripts().get(1).added());
        assertEquals("1.0", prepared.current());
        assertFalse(prepared.repository());
        assertFalse(prepared.canCommit());
        assertEquals("Release Tool v1.0.1", prepared.message("1.0.1"));

        write(console, ";@Ahk2Exe-SetVersion 1.2\nMsgBox 2\n");
        prepared = AhkSetVersion.prepare(root, "Tool", texts(gui, console), false);
        assertEquals("1.2", prepared.current());
        assertEquals(prepared.warnings().toString(), 1, prepared.warnings().size());
        assertTrue(prepared.warnings().get(0), prepared.warnings().get(0).contains("tool.ahk 1.0"));

        prepared = AhkSetVersion.prepare(root, "Tool", Map.of(gui, ";@Ahk2Exe-SetVersion 1\n;@Ahk2Exe-SetVersion 2\n",
                console, ";@Ahk2Exe-SetVersion %U_V%\n"), false);
        assertEquals(prepared.errors().toString(), 2, prepared.errors().size());
        assertTrue(prepared.errors().get(0), prepared.errors().get(0).contains("computed"));
        assertTrue(prepared.errors().get(1), prepared.errors().get(1).contains("lines 1, 2"));
        assertNull(prepared.missing());
    }

    /**
     * Set Version without any file to change (no AutoHotkey file, or all excluded): no settings, no including line,
     * or including lines that match nothing; no error.
     */
    public void testSetVersionMissing() throws Exception {
        Path lib = write(root.resolve("lib/helper.ahk"), "f() => 1\n");
        Path notes = write(root.resolve("notes.txt"), "text\n");
        var prepared = AhkSetVersion.prepare(root, "Tool", texts(notes), false);
        assertEquals(List.of(), prepared.errors());
        assertEquals(AhkSetVersion.Missing.SETTINGS, prepared.missing());
        assertEquals(AhkBundle.message("version.missing.settings"), prepared.missingText());
        prepared = AhkSetVersion.prepare(root, "Tool", texts(lib), false);
        assertNull(prepared.missing());

        Path ini = write(root.resolve(AhkDeployConfig.FILE_NAME), "[Deploy]\n\n[SetVersion]\n; *.txt=yes\nlib/=no\n");
        prepared = AhkSetVersion.prepare(root, "Tool", texts(lib), false);
        assertEquals(AhkSetVersion.Missing.NO_INCLUDE, prepared.missing());
        assertEquals(AhkBundle.message("version.missing.include"), prepared.missingText());
        assertEquals(2, AhkSetVersion.sectionLine(Files.readString(ini), "setversion"));

        write(ini, "[SetVersion]\n*.md=yes\nlib/=no\n");
        prepared = AhkSetVersion.prepare(root, "Tool", texts(lib, notes), false);
        assertEquals(AhkSetVersion.Missing.NO_MATCH, prepared.missing());
        assertEquals(AhkBundle.message("version.missing.match"), prepared.missingText());

        write(ini, "[SetVersion]\n*.txt=yes\n");
        prepared = AhkSetVersion.prepare(root, "Tool", texts(lib, notes), false);
        assertNull(prepared.missing());
        assertEquals("", prepared.missingText());
    }

    /**
     * The section [SetVersion]: selectors like .gitignore with yes/no values; its mistakes only stop Set
     * Version, mistakes of [Deploy] only Deploy, mistakes of the file itself both.
     */
    public void testSetVersionRulesParsed() {
        AhkDeployConfig config = AhkDeployConfig.parse("""
                [SetVersion]
                scratch\\=no
                old/tool-v1.ahk=NO
                version.txt=line:#
                """);
        assertEquals(List.of(), config.versionErrors());
        assertEquals(List.of(), config.errors());
        assertEquals(3, config.versionRules().size());
        assertFalse(AhkFileRules.find(config.versionRules(), "lib\\scratch\\a.ahk").include());
        assertNotNull(AhkFileRules.find(config.versionRules(), "old\\TOOL-v1.ahk"));
        assertNull(AhkFileRules.find(config.versionRules(), "old\\tool-v2.ahk"));
        assertEquals("#", AhkFileRules.find(config.versionRules(), "version.txt").style().start());

        config = AhkDeployConfig.parse("""
                [Deploy]
                Unknown=1
                [SetVersion]
                a\\=maybe
                ..\\x\\=no
                \\=no
                """);
        assertEquals(config.versionErrors().toString(), 3, config.versionErrors().size());
        assertTrue(config.versionErrors().get(0), config.versionErrors().get(0).contains("must be yes"));
        assertEquals(config.errors().toString(), 1, config.errors().size());
        assertTrue(config.errors().get(0), config.errors().get(0).contains("unknown key"));

        config = AhkDeployConfig.parse("[Other]\nx=1\n[SetVersion]\nscratch\\=no\nscratch/=no\n");
        assertEquals(config.versionErrors().toString(), 2, config.versionErrors().size());
        assertEquals(config.errors().toString(), 2, config.errors().size());
    }

    /**
     * Set Version: excluded scripts are left out silently, included files without a line get one (other types with
     * their comment style), binary files only a warning, unknown styles stop.
     */
    public void testSetVersionPrepareWithRules() throws Exception {
        Path gui = write(root.resolve("tool.ahk"), ";@Ahk2Exe-SetVersion 1.0\nMsgBox 1\n");
        Path scratch = write(root.resolve("scratch/try.ahk"), ";@Ahk2Exe-SetVersion 0.1\nMsgBox 2\n");
        Path old = write(root.resolve("old/tool-v1.ahk"), ";@Ahk2Exe-SetVersion 1\n;@Ahk2Exe-SetVersion 2\n");
        Path helper = write(root.resolve("lib/helper.ahk"), "h := 1\n");
        Path readme = write(root.resolve("readme.md"), "# Tool\n");
        Path page = write(root.resolve("help/index.htm"), "<!-- @SetVersion 0.9 -->\n<p>x</p>\n");
        Path logo = write(root.resolve("logo.png"), "png");
        write(root.resolve(AhkDeployConfig.FILE_NAME), "[Deploy]\nDestination=%NO_SUCH_VARIABLE%\n[SetVersion]\n"
                + "scratch\\=no\nold\\*.ahk=no\nlib/helper.ahk=yes\nreadme.md=line:<!-- -->\n*.htm=yes\n"
                + "*.png=yes\n");
        AhkDeployConfig config = AhkSetVersion.readConfig(root, new ArrayList<>());
        assertEquals(List.of(gui, helper, readme, page, logo), AhkSetVersion.candidates(root, config,
                List.of(gui, scratch, old, helper, readme, page, logo, root.resolve("other.txt"))));
        Map<Path, String> texts = texts(gui, scratch, old, helper, readme, page);
        texts.put(logo, "");
        var prepared = AhkSetVersion.prepare(root, "Tool", texts, false);
        assertEquals(List.of(), prepared.errors());
        assertEquals(List.of("help\\index.htm", "lib\\helper.ahk", "readme.md", "tool.ahk"),
                prepared.scripts().stream().map(AhkSetVersion.Script::name).toList());
        assertEquals("1.0", prepared.current());
        assertTrue(prepared.warnings().toString(), prepared.warnings().stream().anyMatch(w -> w.contains("logo.png")));
        assertTrue(prepared.warnings().toString(), prepared.warnings().stream()
                .anyMatch(w -> w.contains("lib\\helper.ahk, readme.md")));
        AhkSetVersion.Script added = prepared.scripts().get(2);
        assertTrue(added.added());
        assertEquals("<!-- @SetVersion 1.1 -->", AhkSetVersion.versionLine(added.name(), added.style(), "1.1"));
        assertEquals(";@Ahk2Exe-SetVersion 1.1", AhkSetVersion.versionLine("lib\\helper.ahk",
                prepared.scripts().get(1).style(), "1.1"));
        assertEquals("0.9", prepared.scripts().get(0).line().value());
        // where a line is added
        boolean[] breakBefore = new boolean[1];
        assertEquals(0, AhkSetVersion.insertionOffset("a.txt", "a\nb", breakBefore));
        assertEquals(6, AhkSetVersion.insertionOffset("a.sh", "#!x y\nb", breakBefore));
        assertEquals(5, AhkSetVersion.insertionOffset("a.xml", "<?xml", breakBefore));
        assertTrue(breakBefore[0]);
        // AutoHotkey files: below the first #Requires line, otherwise at the start
        assertEquals(31, AhkSetVersion.insertionOffset("a.ahk", "; head\n#Requires AutoHotkey v2\nx := 1\n",
                breakBefore));
        assertFalse(breakBefore[0]);
        assertEquals(23, AhkSetVersion.insertionOffset("a.ahk", "#Requires AutoHotkey v2", breakBefore));
        assertTrue(breakBefore[0]);
        assertEquals(0, AhkSetVersion.insertionOffset("a.ah2", "x := 1\n", breakBefore));
        assertEquals(0, AhkSetVersion.insertionOffset("a.txt", "#Requires x\ny\n", breakBefore));
        assertEquals(11, AhkSetVersion.insertionOffset("a.cmd", "@Echo Off\r\necho\r\n", breakBefore));
        // batch files: :: is written, REM is read too and rewritten as ::
        AhkFileRules.Style batch = AhkFileRules.builtinStyle("a.cmd");
        assertEquals(":: @SetVersion 1.1", AhkSetVersion.versionLine("a.cmd", batch, "1.1"));
        String cmd = "@echo off\r\n  rem @SetVersion 1.0\r\necho\r\n";
        List<AhkVersionDirective.Line> found = AhkVersionDirective.findIn("a.cmd", cmd, batch);
        assertEquals(1, found.size());
        assertEquals("1.0", found.get(0).value());
        assertTrue(java.util.Arrays.equals(new int[]{13, 16}, AhkSetVersion.otherMarker(cmd, found.get(0), batch)));
        found = AhkVersionDirective.findIn("a.cmd", ":: @SetVersion 2.0\r\n", batch);
        assertEquals("2.0", found.get(0).value());
        assertNull(AhkSetVersion.otherMarker(":: @SetVersion 2.0\r\n", found.get(0), batch));
        // a version line right before @echo off moves behind it, written as ::
        String before = "REM @SetVersion 1.0\r\n@echo off\r\necho\r\n";
        found = AhkVersionDirective.findIn("a.cmd", before, batch);
        AhkSetVersion.Move move = AhkSetVersion.moveBehindHeader("a.cmd", before, found.get(0), batch, "1.1");
        assertNotNull(move);
        String moved = before.substring(0, move.insertAt()) + move.inserted() + before.substring(move.insertAt());
        moved = moved.substring(0, move.removeStart()) + moved.substring(move.removeEnd());
        assertEquals("@echo off\r\n:: @SetVersion 1.1\r\necho\r\n", moved);
        move = AhkSetVersion.moveBehindHeader("a.cmd", ":: @SetVersion 1.0\n@echo off", AhkVersionDirective.findIn(
                "a.cmd", ":: @SetVersion 1.0\n@echo off", batch).get(0), batch, "1.1");
        assertEquals("\n:: @SetVersion 1.1", move.inserted());
        assertEquals(28, move.insertAt());
        // not at the start, or no header line behind it: stays
        String other = "echo\r\n:: @SetVersion 1.0\r\n@echo off\r\n";
        assertNull(AhkSetVersion.moveBehindHeader("a.cmd", other,
                AhkVersionDirective.findIn("a.cmd", other, batch).get(0), batch, "1.1"));
        assertNull(AhkSetVersion.moveBehindHeader("a.cmd", cmd, AhkVersionDirective.findIn("a.cmd", cmd, batch).get(0),
                batch, "1.1"));
        // an own style line:REM reads only REM
        AhkFileRules.Style rem = new AhkFileRules.Style("REM", null, false);
        assertEquals(List.of(), AhkVersionDirective.findIn("a.cmd", ":: @SetVersion 2.0\r\n", rem));
        assertEquals(1, AhkVersionDirective.findIn("a.cmd", cmd, rem).size());

        write(root.resolve(AhkDeployConfig.FILE_NAME), "[SetVersion]\nscratch\\=maybe\n*.txt=yes\n");
        Path notes = write(root.resolve("notes.txt"), "n\n");
        prepared = AhkSetVersion.prepare(root, "Tool", texts(gui, notes), false);
        assertEquals(prepared.errors().toString(), 2, prepared.errors().size());
        assertTrue(prepared.errors().get(0), prepared.errors().get(0).contains("must be yes"));
        assertTrue(prepared.errors().get(1), prepared.errors().get(1).contains("notes.txt"));
    }

    /** The Ahk2Exe head takes over an existing version line (value as default, the line removed). */
    public void testAhk2ExeHeaderTakesVersion() {
        myFixture.configureByText("tool.ahk", "#Requires AutoHotkey v2.0\n;@Ahk2Exe-SetVersion 2.3.4\nMsgBox 1\n");
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(), () ->
                de.kumpewo.autohotkey.compile.AhkAhk2ExeHeader.insert(getProject(), myFixture.getEditor(),
                        myFixture.getFile()));
        com.intellij.codeInsight.template.impl.TemplateState state =
                com.intellij.codeInsight.template.impl.TemplateManagerImpl.getTemplateState(myFixture.getEditor());
        if (state != null) state.gotoEnd(false);
        String text = myFixture.getEditor().getDocument().getText();
        assertEquals(text, 1, AhkVersionDirective.find(text).size());
        assertEquals("2.3.4", AhkVersionDirective.find(text).get(0).value());
        assertTrue(text, text.contains(";@Ahk2Exe-Base"));
        assertTrue(text, text.endsWith("MsgBox 1\n"));
    }

    /**
     * With a temporary Git repository: Set Version needs every file versioned or ignored; then it commits all changes
     * of the project (staged changes outside stay staged) and tags v&lt;version&gt;; an existing tag is known; 
     * Deploy only warns about uncommitted changes and commits nothing.
     */
    public void testSetVersionCommitAndTag() throws Exception {
        if (!gitAvailable()) return;
        AhkDeployGit git = new AhkDeployGit(root);
        assertTrue(git.run("init", "-q").ok());
        git.run("config", "user.name", "Test");
        git.run("config", "user.email", "test@example.invalid");
        git.run("config", "commit.gpgsign", "false");
        git.run("config", "tag.gpgsign", "false");
        Path project = root.resolve("tool");
        Path gui = write(project.resolve("tool.ahk"), ";@Ahk2Exe-SetVersion 1.2\nMsgBox 1\n");
        Path console = write(project.resolve("console/tool-console.ahk"), ";@Ahk2Exe-SetVersion 1.2\nMsgBox 2\n");
        Path lib = write(project.resolve("lib.ahk"), "f() => 1\n");
        write(project.resolve(".gitignore"), "*.exe\n");
        write(project.resolve(AhkDeployConfig.FILE_NAME), "[Deploy]\nDestination=" + root.resolve("dest")
                + "\n[Install]\ntool.ahk=.\\\n");
        assertTrue(git.run("add", "-A").ok());
        assertTrue(git.run("commit", "-q", "-m", "initial").ok());
        assertTrue(git.run("tag", "-a", "v1.2", "-m", "old").ok());
        write(project.resolve("tool.exe"), "ignored");
        write(lib, "f() => 2\n");
        write(project.resolve("new.ahk"), "untracked");
        write(root.resolve("staged.txt"), "staged outside of the project");
        assertTrue(git.run("add", "staged.txt").ok());

        // Deploy: warning only, nothing committed
        var deploy = AhkDeployer.prepare(project, env(), true);
        assertEquals(List.of(), deploy.errors());
        assertTrue(deploy.uncommitted());

        // an untracked file: no commit possible
        var prepared = AhkSetVersion.prepare(project, "Tool", texts(gui, console, lib), true);
        assertEquals(List.of(), prepared.errors());
        assertTrue(prepared.repository());
        assertFalse(prepared.canCommit());
        assertEquals(1, prepared.untracked().size());
        assertTrue(prepared.untracked().get(0), prepared.untracked().get(0).endsWith("new.ahk"));
        Files.delete(project.resolve("new.ahk"));
        prepared = AhkSetVersion.prepare(project, "Tool", texts(gui, console, lib), true);
        assertTrue(prepared.canCommit());
        assertEquals(1, prepared.changed().size());
        assertTrue(prepared.changed().get(0), prepared.changed().get(0).endsWith("lib.ahk"));
        assertTrue(prepared.tagExists("v1.2"));
        assertTrue(prepared.tagExists("V1.2"));
        assertFalse(prepared.tagExists("v1.2.1"));

        // what the action does after the dialog: change the lines, save, commit and tag
        for (Path file : List.of(gui, console)) {
            String text = Files.readString(file);
            AhkVersionDirective.Line line = AhkSetVersion.currentLine(text, "1.2");
            assertNotNull(line);
            Files.writeString(file, text.substring(0, line.valueStart()) + "1.2.1" + text.substring(line.valueEnd()));
        }
        var choice = new AhkSetVersion.Choice("1.2.1", true, "v1.2.1", prepared.message("1.2.1"));
        assertNull(AhkSetVersion.commitAndTag(prepared, choice));
        assertEquals("Release Tool v1.2.1", git.run("log", "-1", "--format=%s").stdout().strip());
        assertEquals(List.of("tool/console/tool-console.ahk", "tool/lib.ahk", "tool/tool.ahk"),
                git.run("show", "--name-only", "--format=", "HEAD").stdout().strip().lines().sorted().toList());
        assertTrue(git.tagExists("v1.2.1"));
        assertEquals(git.run("rev-parse", "HEAD").stdout(), git.run("rev-parse", "v1.2.1^{commit}").stdout());
        assertEquals("A  staged.txt", git.run("status", "--porcelain", "--", "staged.txt").stdout().strip());
        assertEquals("", git.run("status", "--porcelain", "--", "tool").stdout().strip());

        // an invalid tag stops before the commit
        Files.writeString(gui, ";@Ahk2Exe-SetVersion 1.3\nMsgBox 1\n");
        String head = git.run("rev-parse", "HEAD").stdout();
        String error = AhkSetVersion.commitAndTag(prepared, new AhkSetVersion.Choice("1.3", true, "v1..3", "x"));
        assertNotNull(error);
        assertEquals(head, git.run("rev-parse", "HEAD").stdout());
        // without commit nothing happens in Git
        assertNull(AhkSetVersion.commitAndTag(prepared, new AhkSetVersion.Choice("1.3", false, "v1.3", "x")));
        assertEquals(head, git.run("rev-parse", "HEAD").stdout());
    }

    /** In a template of the settings file, explanations start with ";; " and commented-out options with one ";". */
    private static void assertCommentStyle(String ini) {
        for (String line : ini.split("\r\n")) {
            if (!line.startsWith(";")) continue;
            boolean explanation = line.equals(";;") || line.startsWith(";; ");
            assertTrue(line, explanation || line.matches(";[^; ][^=]*=.*"));
        }
    }

    /**
     * The default settings.ahk-v2-plugin.ini: valid as it is (nothing set), {tool} replaced, CR LF; with every option line
     * uncommented it is still valid and sets all keys (of a key shown twice, such as Destination, the first line).
     */
    public void testDefaultSettingsFile() {
        String text = AhkSettingsFile.text("mytool", false);
        assertFalse(text, text.contains("{tool}") || text.contains("$"));
        assertTrue(text, text.contains("\r\n;Banner=find:banner.txt\r\n"));
        // with a banner.txt in the project folder or above: the line is active
        assertEquals("find:banner.txt",
                AhkDeployConfig.parse(AhkSettingsFile.text("mytool", true)).banner());
        assertFalse(text.replace("\r\n", "").contains("\n"));
        assertCommentStyle(text);
        // hard wrap at 120 columns, and the switch EmbedOrigin is offered
        for (String line : text.split("\r\n")) assertTrue(line, line.length() <= 120);
        assertTrue(text, text.contains("\r\n;EmbedOrigin=no\r\n"));
        assertFalse(text, text.contains("[Compile]") || text.contains("[Version]") || text.contains("TagPrefix"));
        AhkDeployConfig config = AhkDeployConfig.parse(text);
        assertEquals(List.of(), config.errors());
        assertTrue(config.entries().isEmpty());
        assertNull(config.destination());
        assertEquals(2, config.versionRules().size());
        // remove the ";" of the option lines (";key=value", without a space after ";")
        StringBuilder uncommented = new StringBuilder();
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (String line : text.split("\r\n")) {
            boolean option = line.matches(";[^ ;].*=.*") && keys.add(line.substring(1, line.indexOf('=')));
            uncommented.append(option ? line.substring(1) : line).append("\r\n");
        }
        // the alternative Destination for a library
        assertTrue(text, text.contains("\r\n;Destination=%A_MyDocuments%\\AutoHotkey\\Lib\r\n"));
        config = AhkDeployConfig.parse(uncommented.toString());
        assertEquals(List.of(), config.errors());
        assertEquals(List.of(), config.versionErrors());
        assertTrue(config.banner(), config.banner().startsWith("Installed copy of {source-relative}"));
        assertEquals(4, config.bannerRules().size());
        assertEquals(3, config.versionRules().size());
        assertEquals(new AhkDeployConfig.CleanupEntry("mytool-old.exe", ".", config.cleanup().get(0).line()),
                config.cleanup().get(0));
        assertEquals(2, config.cleanup().size());
        assertEquals("C:\\bin", config.destination());
        assertTrue(config.message(), config.message().contains("mytool"));
        // the commented-out switches show the value that changes the default
        assertFalse(config.generateOverview());
        assertTrue(config.requiresAdmin());
        assertFalse(config.compile());
        assertEquals(4, config.entries().size());
        assertEquals(new AhkDeployConfig.Entry("mytool.exe", ".", false, config.entries().get(1).line()),
                config.entries().get(1));
        assertEquals(new AhkDeployConfig.Entry("mytool.ini", "%A_AppData%\\mytool\\", true,
                config.entries().get(3).line()), config.entries().get(3));
    }

    /**
     * The default file of a project without AutoHotkey files: the examples come from the files of a known type in the
     * project root (configuration files to [InstallIfMissing], no AutoHotkey lines); uncommented, the file is valid.
     */
    public void testDefaultSettingsFileExamplesFromRootFiles() {
        String text = AhkSettingsFile.text("tool", false, List.of("build.cmd", "README.md", "app.exe", "config.yaml",
                AhkDeployConfig.FILE_NAME, ".gitignore", "notes.txt", "logo.png", "deploy.ps1"), false);
        assertFalse(text, text.contains("$") || text.contains("{tool}") || text.contains(";tool.ahk=")
                || text.contains(";*.ahk=yes") || text.contains("notes.txt=") || text.contains("logo.png="));
        assertTrue(text, text.contains("\r\n;app.exe=.\r\n;build.cmd=.\r\n;deploy.ps1=.\r\n;README.md=.\r\n"
                + ";pictures\\*.png=pictures\r\n"));
        assertTrue(text, text.contains("\r\n;config.yaml=%A_AppData%\\tool\\\r\n"));
        assertTrue(text, text.contains("\r\n;test\\=no\r\n;*.cmd=yes\r\n;*.md=yes\r\n;*.ps1=yes\r\n;*.yaml=yes\r\n"
                + ";readme.txt=line:#\r\n"));
        assertTrue(text, text.contains("\r\n;*.cmd=yes\r\n;*.md=yes\r\n;*.ps1=yes\r\n;*.yaml=yes\r\nscratch\\=no\r\n"));
        assertTrue(text, text.contains("\r\n;app-old.exe=.\r\n"));
        // remove the ";" of the option lines (of a key shown twice, the first line)
        StringBuilder uncommented = new StringBuilder();
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (String line : text.split("\r\n")) {
            boolean option = line.matches(";[^ ;].*=.*") && keys.add(line.substring(1, line.indexOf('=')));
            uncommented.append(option ? line.substring(1) : line).append("\r\n");
        }
        AhkDeployConfig config = AhkDeployConfig.parse(uncommented.toString());
        assertEquals(List.of(), config.errors());
        assertEquals(List.of(), config.versionErrors());
        assertEquals(6, config.entries().size());
    }

    /** Without files of a known type: generic examples with the project name; an AutoHotkey file in the root counts. */
    public void testDefaultSettingsFileGenericExamples() {
        String text = AhkSettingsFile.text("tool", false, List.of("notes.txt"), false);
        assertTrue(text, text.contains("\r\n;tool.cmd=.\r\n;pictures"));
        assertTrue(text, text.contains("\r\n;tool.ini=%A_AppData%\\tool\\\r\n"));
        assertTrue(text, text.contains("\r\n;*.cmd=yes\r\nscratch\\=no\r\n"));
        assertTrue(text, text.contains("\r\n;tool-old.cmd=.\r\n"));
        text = AhkSettingsFile.text("tool", false, List.of("Tool.ahk", "clip.ahk", "help.htm"), false);
        assertTrue(text, text.contains("\r\n;tool.ahk=.\r\n;tool.exe=.\r\n;clip.ahk=.\r\n;help.htm=.\r\n;pictures"));
        assertTrue(text, text.contains("\r\n;*.htm=yes\r\nscratch\\=no\r\n") && !text.contains(";*.ahk=yes"));
        assertTrue(text, text.contains("\r\n;tool-old.exe=.\r\n"));
        // at most MAX_EXAMPLES lines per section
        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < 15; i++) many.add("f" + (char) ('a' + i) + ".cmd");
        text = AhkSettingsFile.text("tool", false, many, false);
        assertTrue(text, text.contains(";fj.cmd=.") && !text.contains(";fk.cmd=."));
    }

    /** Built-in comment styles of more file types; .txt, .php and .json have none. */
    public void testBuiltinCommentStyles() {
        assertEquals(new AhkFileRules.Style("#", null, false), AhkFileRules.builtinStyle("x.py"));
        assertEquals(new AhkFileRules.Style("//", null, false), AhkFileRules.builtinStyle("x.JS"));
        assertEquals(new AhkFileRules.Style("<!--", "-->", true), AhkFileRules.builtinStyle("README.md"));
        assertEquals(new AhkFileRules.Style("/*", "*/", true), AhkFileRules.builtinStyle("a.css"));
        assertEquals(new AhkFileRules.Style("--", null, false), AhkFileRules.builtinStyle("q.sql"));
        assertEquals(new AhkFileRules.Style("'", null, false), AhkFileRules.builtinStyle("m.vbs"));
        assertEquals(new AhkFileRules.Style(";", null, false), AhkFileRules.builtinStyle("keys.reg"));
        assertNull(AhkFileRules.builtinStyle("notes.txt"));
        assertNull(AhkFileRules.builtinStyle("index.php"));
        assertNull(AhkFileRules.builtinStyle("data.json"));
    }

    /** The header of a registry file stays the first line, like <?xml and #!. */
    public void testRegistryHeaderStaysFirst() {
        assertTrue(AhkBanner.hasHeaderLine("Windows Registry Editor Version 5.00\r\n\r\n[HKEY_CURRENT_USER\\x]"));
        assertTrue(AhkBanner.hasHeaderLine("REGEDIT4\r\n"));
        assertFalse(AhkBanner.hasHeaderLine("[HKEY_CURRENT_USER\\x]\r\n"));
        // the first line @echo off of a batch file stays first
        assertTrue(AhkBanner.hasHeaderLine("@ECHO OFF & setlocal\r\n"));
        assertFalse(AhkBanner.hasHeaderLine("echo off\r\n"));
        assertEquals("@echo off\r\n:: x\r\necho\r\n", new String(AhkBanner.apply(
                "@echo off\r\necho\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII), List.of(":: x")),
                java.nio.charset.StandardCharsets.US_ASCII));
        boolean[] breakBefore = new boolean[1];
        String reg = "Windows Registry Editor Version 5.00\r\n\r\n[HKEY_CURRENT_USER\\x]\r\n";
        assertEquals(reg.indexOf("\r\n") + 2, AhkSetVersion.insertionOffset("keys.reg", reg, breakBefore));
    }

    /** Does the project contain an AutoHotkey file (also when none is open)? Chooses the examples of a new settings file. */
    public void testProjectContainsAhkFiles() {
        assertFalse(AhkProjectRoot.containsAhkFiles(getProject()));
        myFixture.addFileToProject("notes.txt", "x");
        assertFalse(AhkProjectRoot.containsAhkFiles(getProject()));
        myFixture.addFileToProject("sub/tool.ahk", "MsgBox 1\n");
        assertTrue(AhkProjectRoot.containsAhkFiles(getProject()));
    }

    /** Creating the default file in the project root with the project name; an existing file is never overwritten. */
    public void testCreateSettingsFile() throws Exception {
        var dir = myFixture.addFileToProject("tool/clip.ahk", "MsgBox 1\n").getVirtualFile().getParent();
        AhkSettingsFile.create(getProject(), dir);
        var ini = dir.findChild(AhkDeployConfig.FILE_NAME);
        assertNotNull(ini);
        String text = com.intellij.openapi.vfs.VfsUtilCore.loadText(ini);
        assertTrue(text, text.contains(";Message=Deploy " + getProject().getName() + " to %DESTINATION%"));
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            try {
                com.intellij.openapi.vfs.VfsUtil.saveText(ini, "[Deploy]\nDestination=C:\\mine\n");
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        });
        AhkSettingsFile.create(getProject(), dir);
        assertEquals("[Deploy]\nDestination=C:\\mine\n", com.intellij.openapi.vfs.VfsUtilCore.loadText(ini));
    }

    /** "Create Settings" in a folder below a banner.txt: Banner=find:banner.txt is active. */
    public void testCreateSettingsFileFindsSharedBanner() throws Exception {
        write(root.resolve(AhkNewLibraryProject.SHARED_BANNER), "Copy of {project-name}");
        write(root.resolve("tools/clip/clip.ahk"), "MsgBox 1\n");
        var dir = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .refreshAndFindFileByNioFile(root.resolve("tools/clip"));
        assertNotNull(dir);
        AhkSettingsFile.create(getProject(), dir);
        assertEquals("find:banner.txt",
                AhkDeployConfig.parse(Files.readString(root.resolve("tools/clip/" + AhkDeployConfig.FILE_NAME)))
                        .banner());
    }

    /**
     * Deploy reads the settings file from disk: the default file created by "Create Settings", options uncommented
     * in the editor and not saved, counts once the documents are saved before the action.
     */
    public void testDeployAfterUnsavedSettings() throws Exception {
        write(root.resolve("pictures/a.png"), "png");
        var dir = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root);
        assertNotNull(dir);
        AhkSettingsFile.create(getProject(), dir);
        var ini = dir.findChild(AhkDeployConfig.FILE_NAME);
        assertNotNull(ini);
        var documents = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance();
        var document = documents.getDocument(ini);
        assertNotNull(document);
        String dest = root.resolve("dest").toString();
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText(
                document.getText().replace(";Destination=C:\\bin", "Destination=" + dest)
                        .replace(";pictures\\*.png=pictures", "pictures\\*.png=pictures")));
        assertTrue(documents.isDocumentUnsaved(document));
        assertTrue(de.kumpewo.autohotkey.validate.AhkValidator.saveAllDocuments(getProject(), "Deploy"));
        assertFalse(documents.isDocumentUnsaved(document));
        assertEquals(List.of(), AhkDeployer.prepare(root, env(), false).errors());
    }

    /**
     * A modified document with the same modification stamp as its file is dropped by the platform when saving,
     * without being written (seen after commenting and uncommenting a line with a save in between): the saving before
     * the action banners that the file differs and writes the document itself, in the encoding and with the line
     * separators of the file.
     */
    public void testDeployAfterSkippedSave() throws Exception {
        Path path = root.resolve(AhkDeployConfig.FILE_NAME);
        Files.write(path, ("﻿[Deploy]\r\nDestination=" + root.resolve("dest") + "\r\n[Install]\r\n;a.png=.\r\n")
                .getBytes(StandardCharsets.UTF_8));
        write(root.resolve("a.png"), "png");
        var ini = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
        assertNotNull(ini);
        var documents = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance();
        var document = documents.getDocument(ini);
        assertNotNull(document);
        int offset = document.getText().indexOf(";a.png");
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(),
                () -> document.deleteString(offset, offset + 1));
        ((com.intellij.openapi.editor.ex.DocumentEx) document).setModificationStamp(ini.getModificationStamp());
        assertTrue(documents.isDocumentUnsaved(document));
        assertTrue(de.kumpewo.autohotkey.validate.AhkValidator.saveAllDocuments(getProject(), "Deploy"));
        assertFalse(documents.isDocumentUnsaved(document));
        byte[] saved = Files.readAllBytes(path);
        assertEquals("﻿[Deploy]\r\nDestination=" + root.resolve("dest") + "\r\n[Install]\r\na.png=.\r\n",
                new String(saved, StandardCharsets.UTF_8));
        assertEquals(List.of(), AhkDeployer.prepare(root, env(), false).errors());
    }

    /**
     * A settings.ahk-plugin.ini of version 2.1 is read with the old names only; the new file rejects the old names
     * with the new one in the message; "Update Settings" renames sections, keys and the names in comments.
     */
    public void testLegacySettings() {
        String old = """
                ; settings.ahk-plugin.ini: [Install] holds the options, [Overwrite] the copies; CopyNotice is the notice
                [Install]
                Destination=C:\\Tools
                "CopyNotice" = @standard
                CompileBeforeInstall=no
                [Overwrite]
                tool.ahk=.
                [KeepExisting]
                tool.ini=.
                [ApplyCopyNotice]
                *.cmd=yes
                [SetVersion]
                test\\=no
                """.replace("\n", "\r\n");
        AhkDeployConfig config = AhkDeployConfig.parse(old, true);
        assertTrue(config.legacy());
        assertEquals(List.of(), config.errors());
        assertEquals("C:\\Tools", config.destination());
        assertEquals("@standard", config.banner());
        assertFalse(config.compile());
        assertEquals(2, config.entries().size());
        assertTrue(config.entries().get(1).ifMissing());
        assertEquals(1, config.bannerRules().size());
        assertEquals(1, config.versionRules().size());

        String updated = AhkSettingsFormat.updated(old);
        assertEquals("""
                ; settings.ahk-v2-plugin.ini: [Deploy] holds the options, [Install] the copies; Banner is the notice
                [Deploy]
                Destination=C:\\Tools
                "Banner" = @standard
                Compile=no
                [Install]
                tool.ahk=.
                [InstallIfMissing]
                tool.ini=.
                [AddBanner]
                *.cmd=yes
                [SetVersion]
                test\\=no
                """.replace("\n", "\r\n"), updated);
        config = AhkDeployConfig.parse(updated);
        assertFalse(config.legacy());
        assertEquals(List.of(), config.errors());
        assertEquals(2, config.entries().size());

        // the old file knows only the old names; its messages name it
        config = AhkDeployConfig.parse("[Deploy]\nx=1\n[Install]\nBanner=x\n", true);
        assertEquals(List.of(
                AhkBundle.message("deploy.ini.section.unknown.legacy", 1, "[Deploy]"),
                AhkBundle.message("deploy.ini.key.unknown", 4, "Banner")
                        .replace(AhkDeployConfig.FILE_NAME, AhkDeployConfig.LEGACY_FILE_NAME)), config.errors());

        // the new file names the new name of an old section or key, and an option in [Install]
        config = AhkDeployConfig.parse("[Deploy]\nCopyNotice=x\nCompileBeforeInstall=no\n[Overwrite]\na=.\n"
                + "[Install]\nDestination=C:\\x\n");
        assertEquals(List.of(
                AhkBundle.message("deploy.ini.key.old", 2, "CopyNotice", "Banner"),
                AhkBundle.message("deploy.ini.key.old", 3, "CompileBeforeInstall", "Compile"),
                AhkBundle.message("deploy.ini.section.old", 4, "[Overwrite]", "[Install]"),
                AhkBundle.message("deploy.ini.install.option", 7, "Destination")), config.errors());

        // renaming a source in an old file still finds its copy line
        AhkSettingsEdit.Rename rename = AhkSettingsEdit.rename(old, "tool.ahk", "main.ahk", false,
                AhkSettingsEdit.TargetMode.SOURCE_ONLY, target -> true, true);
        assertTrue(rename.text(), rename.text().contains("\r\nmain.ahk=.\r\n"));
        assertEquals("Overwrite", rename.edits().get(0).section());
    }

    /**
     * The old file is read if there is no new one, with the hint first; "Update Settings" turns it into the new file
     * (same encoding); with both files the new one wins and the old one is a warning.
     */
    public void testLegacySettingsDeploy() throws Exception {
        Path root = Files.createTempDirectory("ahk-legacy").toRealPath();
        Path dest = Files.createTempDirectory("ahk-legacy-dest").toRealPath();
        write(root.resolve("tool.ahk"), "MsgBox 1\r\n");
        Path old = root.resolve(AhkDeployConfig.LEGACY_FILE_NAME);
        Path ini = root.resolve(AhkDeployConfig.FILE_NAME);
        Files.write(old, ("\uFEFF[Install]\r\nDestination=" + dest + "\r\n[Overwrite]\r\ntool.ahk=.\r\n"
                + "[ApplyCopyNotice]\r\n*.ahk=no\r\n").getBytes(StandardCharsets.UTF_8));
        assertTrue(AhkProjectRoot.hasSettings(root));
        assertEquals(old, AhkDeployConfig.file(root));
        AhkDeployer.Prepared prepared = AhkDeployer.prepare(root, env(), false);
        assertEquals(List.of(), prepared.errors());
        assertEquals(AhkBundle.message("deploy.hint.legacy"), prepared.hints().get(0));
        assertEquals(1, prepared.plan().steps().size());

        assertNull(AhkDeployer.updateSettings(root));
        assertFalse(Files.exists(old));
        assertEquals("\uFEFF[Deploy]\r\nDestination=" + dest + "\r\n[Install]\r\ntool.ahk=.\r\n"
                + "[AddBanner]\r\n*.ahk=no\r\n", Files.readString(ini, StandardCharsets.UTF_8));
        prepared = AhkDeployer.prepare(root, env(), false);
        assertEquals(List.of(), prepared.errors());
        assertEquals(List.of(), prepared.hints());
        assertEquals(List.of(), prepared.warnings());

        // both files: the new one wins, the old one is ignored with a warning
        Files.writeString(old, "[Install]\nDestination=C:\\nowhere\n[Overwrite]\nnothing.ahk=.\n");
        prepared = AhkDeployer.prepare(root, env(), false);
        assertEquals(List.of(), prepared.errors());
        assertEquals(List.of(AhkBundle.message("deploy.warning.legacy.ignored")), prepared.warnings());
        assertEquals(List.of(), prepared.hints());
    }

    /** A new line at the end of a section (after its last key line), or a new section at the end. */
    public void testSettingsEditWithLine() {
        assertEquals("[Deploy]\r\nx=1\r\n[Install]\r\na.ahk=.\r\nb.ahk=v2\\\r\n\r\n; end\r\n",
                AhkSettingsEdit.withLine("[Deploy]\r\nx=1\r\n[Install]\r\na.ahk=.\r\n\r\n; end\r\n",
                        "Install", "b.ahk=v2\\"));
        assertEquals("[install]\nb.ahk=.\n[InstallIfMissing]\nc=.\n",
                AhkSettingsEdit.withLine("[install]\n[InstallIfMissing]\nc=.\n", "Install", "b.ahk=."));
        // a file with the names of version 2.1 gets the line in the section of the old name
        assertEquals("[Install]\nDestination=C:\\x\n[Overwrite]\na=.\nb=.\n",
                AhkSettingsEdit.withLine("[Install]\nDestination=C:\\x\n[Overwrite]\na=.\n", "Install", "b=.", true));
        assertEquals("[Install]\na=.\nb=.\n", AhkSettingsEdit.withLine("[Install]\na=.", "Install", "b=."));
        assertEquals("[Deploy]\r\nx=1\r\n\r\n[Install]\r\nb=.\r\n",
                AhkSettingsEdit.withLine("[Deploy]\r\nx=1", "Install", "b=."));
        assertEquals("[Install]\r\nb=.\r\n", AhkSettingsEdit.withLine("", "Install", "b=."));
    }

    /** Folder targets as the tests write them (no file system): a trailing separator or a lone ".". */
    private static boolean folderTarget(String target) {
        return target.endsWith("\\") || target.endsWith("/") || target.equals(".");
    }

    /** The new text of a rename in the settings text. */
    private static AhkSettingsEdit.Rename rename(String text, String oldPath, String newPath, boolean folder,
                                                 AhkSettingsEdit.TargetMode mode) {
        return AhkSettingsEdit.rename(text, oldPath, newPath, folder, mode, AhkDeployTest::folderTarget);
    }

    /**
     * Renaming a file or folder of the project, source only: the sources of the copy lines and the exact selectors
     * that name it change, only before the "="; wildcards, other sections and the values stay.
     */
    public void testSettingsEditRenames() {
        String text = """
                [Deploy]
                Destination=gui.ahk
                [Install]
                gui.ahk=gui.ahk
                 "Gui.ahk2" = x
                *.ahk=.
                sub\\gui.ahk=.
                [InstallIfMissing]
                pics/a.png=.
                pics\\*.png=pics\\
                [SetVersion]
                /gui.ahk=yes
                gui.ahk=no
                sub/gui.ahk=yes
                pics/=no
                [Cleanup]
                old.ahk=.
                """;
        AhkSettingsEdit.TargetMode mode = AhkSettingsEdit.TargetMode.SOURCE_ONLY;
        AhkSettingsEdit.Rename result = rename(text, "gui.ahk", "main.ahk", false, mode);
        assertEquals(result.edits().toString(), List.of(4, 12, 13),
                result.edits().stream().map(AhkSettingsEdit.LineEdit::line).toList());
        String renamed = result.text();
        assertTrue(renamed, renamed.contains("\nmain.ahk=gui.ahk\n"));
        assertTrue(renamed, renamed.contains("\n/main.ahk=yes\nmain.ahk=no\n"));
        assertTrue(renamed, renamed.contains("\n[Cleanup]\nold.ahk=.\n"));
        assertEquals("gui.ahk=gui.ahk", result.edits().get(0).oldText());
        assertEquals("main.ahk=gui.ahk", result.edits().get(0).newText());
        assertEquals("Install", result.edits().get(0).section());
        // the target has the old file name: the choice is asked; the caret is on the target
        assertTrue(result.choice());
        assertEquals(renamed.indexOf("main.ahk=gui.ahk") + "main.ahk=".length(), result.caret());
        // a file in a subfolder: only anchored selectors
        result = rename(text, "sub\\gui.ahk", "sub\\main.ahk", false, mode);
        assertEquals(List.of(7, 14), result.edits().stream().map(AhkSettingsEdit.LineEdit::line).toList());
        assertTrue(result.text().contains("\nsub/main.ahk=yes\n"));
        // quotes and spaces are kept
        result = rename(text, "gui.ahk2", "x.ahk2", false, mode);
        assertTrue(result.text().contains("\n \"x.ahk2\" = x\n"));
        assertFalse(result.choice());
        // a folder: every path below it, also with wildcards in the file name; the folder selector itself
        result = rename(text, "pics", "images", true, mode);
        String moved = result.text();
        assertTrue(moved, moved.contains("\nimages/a.png=.\nimages\\*.png=pics\\\n"));
        assertTrue(moved, moved.contains("\nimages/=no\n"));
        assertFalse(result.choice());
        assertTrue(rename(text, "other.ahk", "x.ahk", false, mode).edits().isEmpty());
    }

    /**
     * A renamed file whose copy line deploys it to a folder or under the old name: the target keeps the name, takes
     * the new one (with [Cleanup] for the old copy of [Install]) or stays as written; the executable of a renamed
     * script follows; a [Cleanup] line that would remove a new target goes. A move keeps the name: no choice.
     */
    public void testSettingsEditRenameTargets() {
        String text = """
                [Deploy]
                Destination=C:\\bin
                [Install]
                Gui.ahk=v2\\
                Gui.exe=.
                other.ahk=v2\\other.ahk
                "Gui.ahk2" = "v2\\"
                [InstallIfMissing]
                Gui.ahk=cfg/Gui.ahk
                [Cleanup]
                Gui-v2.ahk=v2\\
                """;
        AhkSettingsEdit.Rename source = rename(text, "Gui.ahk", "Gui-v2.ahk", false,
                AhkSettingsEdit.TargetMode.SOURCE_ONLY);
        assertTrue(source.choice());
        assertTrue(source.executable());
        assertEquals("""
                [Deploy]
                Destination=C:\\bin
                [Install]
                Gui-v2.ahk=v2\\
                Gui-v2.exe=.
                other.ahk=v2\\other.ahk
                "Gui.ahk2" = "v2\\"
                [InstallIfMissing]
                Gui-v2.ahk=cfg/Gui.ahk
                [Cleanup]
                """, source.text());
        assertEquals(source.text().indexOf("v2\\\n"), source.caret());
        assertNull(source.edits().get(source.edits().size() - 1).newText());

        AhkSettingsEdit.Rename keep = rename(text, "Gui.ahk", "Gui-v2.ahk", false,
                AhkSettingsEdit.TargetMode.KEEP_NAME);
        assertEquals("""
                [Deploy]
                Destination=C:\\bin
                [Install]
                Gui-v2.ahk=v2\\Gui.ahk
                Gui-v2.exe=Gui.exe
                other.ahk=v2\\other.ahk
                "Gui.ahk2" = "v2\\"
                [InstallIfMissing]
                Gui-v2.ahk=cfg/Gui.ahk
                [Cleanup]
                Gui-v2.ahk=v2\\
                """, keep.text());

        AhkSettingsEdit.Rename renamed = rename(text, "Gui.ahk", "Gui-v2.ahk", false,
                AhkSettingsEdit.TargetMode.RENAME);
        assertEquals("""
                [Deploy]
                Destination=C:\\bin
                [Install]
                Gui-v2.ahk=v2\\
                Gui-v2.exe=.
                other.ahk=v2\\other.ahk
                "Gui.ahk2" = "v2\\"
                [InstallIfMissing]
                Gui-v2.ahk=cfg/Gui-v2.ahk
                [Cleanup]
                Gui.ahk=v2\\
                Gui.exe=.
                """, renamed.text());
        assertEquals(-1, renamed.caret());
        List<AhkSettingsEdit.LineEdit> added = renamed.edits().stream().filter(e -> e.oldText() == null).toList();
        assertEquals(List.of("Gui.ahk=v2\\", "Gui.exe=."), added.stream().map(AhkSettingsEdit.LineEdit::newText).toList());

        // quotes are kept; a folder target that is "/"-only gets "/"
        keep = rename(text, "Gui.ahk2", "Main.ahk2", false, AhkSettingsEdit.TargetMode.KEEP_NAME);
        assertTrue(keep.text(), keep.text().contains("\n\"Main.ahk2\" = \"v2\\Gui.ahk2\"\n"));
        assertFalse(keep.executable());
        // a move into another folder keeps the file name: no choice, the target stays
        AhkSettingsEdit.Rename move = rename(text, "Gui.ahk", "src\\Gui.ahk", false,
                AhkSettingsEdit.TargetMode.KEEP_NAME);
        assertFalse(move.choice());
        assertTrue(move.text(), move.text().contains("\nsrc\\Gui.ahk=v2\\\nsrc\\Gui.exe=.\n"));
        // renaming back: the [Cleanup] line for the name is removed
        AhkSettingsEdit.Rename back = rename(renamed.text(), "Gui-v2.ahk", "Gui.ahk", false,
                AhkSettingsEdit.TargetMode.RENAME);
        assertTrue(back.text(), back.text().endsWith("[Cleanup]\nGui-v2.ahk=v2\\\nGui-v2.exe=.\n"));
    }

    /**
     * After renaming, the settings file of the project root changes as chosen in the dialog (Cancel keeps it); without
     * a choice it changes at once and a dialog shows it.
     */
    public void testSettingsRenameUpdate() throws Exception {
        Path ini = write(root.resolve(AhkDeployConfig.FILE_NAME), "[Install]\r\ngui.ahk=.\r\n");
        try {
            com.intellij.openapi.ui.TestDialogManager.setTestDialog(message -> 3);
            AhkSettingsRenameListener.update(getProject(), root, "gui.ahk", "main.ahk", false);
            assertEquals("[Install]\r\ngui.ahk=.\r\n", Files.readString(ini));
            com.intellij.openapi.ui.TestDialogManager.setTestDialog(message -> AhkSettingsRenameListener.KEEP);
            AhkSettingsRenameListener.update(getProject(), root, "gui.ahk", "main.ahk", false);
            assertEquals("[Install]\r\nmain.ahk=gui.ahk\r\n", Files.readString(ini));
            rewrite(ini, "[Install]\r\ngui.ahk=.\r\n");
            com.intellij.openapi.ui.TestDialogManager.setTestDialog(message -> AhkSettingsRenameListener.RENAME);
            AhkSettingsRenameListener.update(getProject(), root, "gui.ahk", "main.ahk", false);
            assertEquals("[Install]\r\nmain.ahk=.\r\n\r\n[Cleanup]\r\ngui.ahk=.\r\n", Files.readString(ini));
            // a folder: no choice, the change is made and shown
            rewrite(ini, "[Install]\r\nsub\\gui.ahk=v2\\\r\n");
            List<String> shown = new ArrayList<>();
            com.intellij.openapi.ui.TestDialogManager.setTestDialog(message -> {
                shown.add(message);
                return 0;
            });
            AhkSettingsRenameListener.update(getProject(), root, "sub", "lib", true);
            assertEquals("[Install]\r\nlib\\gui.ahk=v2\\\r\n", Files.readString(ini));
            assertEquals(1, shown.size());
        } finally {
            com.intellij.openapi.ui.TestDialogManager.setTestDialog(com.intellij.openapi.ui.TestDialog.DEFAULT);
        }
    }

    /** Writes the file and refreshes it in the IDE, so that its document has the new text. */
    private static void rewrite(Path file, String text) throws Exception {
        write(file, text);
        com.intellij.openapi.vfs.VfsUtil.markDirtyAndRefresh(false, false, false, file.toFile());
    }

    /**
     * "Add to Library Project...": the file is copied into the project root and gets a line in [Install] whose
     * target gives the library file again; a name that is taken gives name_added.ahk with the placeholder, which is an
     * error until it is replaced. The projects of the subfolder of the file come first.
     */
    public void testAddToLibraryProject() throws Exception {
        Path library = root.resolve("Library");
        Path lib = root.resolve("Lib");
        Path gui = library.resolve("v2/Gui");
        write(gui.resolve("Gui.ahk"), "g := 1\n");
        write(gui.resolve(AhkDeployConfig.FILE_NAME), "[Deploy]\r\nDestination=" + lib + "\\v2\\\r\n"
                + "[Install]\r\nGui.ahk=.\r\n\r\n; more\r\n");
        Path json = library.resolve("Json");
        write(json.resolve(AhkDeployConfig.FILE_NAME), "[Deploy]\r\n");
        Path extra = write(lib.resolve("v2/Extra.ahk"), "e := 1\n");
        if (com.intellij.openapi.util.SystemInfo.isWindows) Files.setAttribute(extra, "dos:readonly", true);
        AhkAddToLibraryProject.Result result = AhkAddToLibraryProject.add(gui, extra, env());
        assertEquals(gui.resolve("Extra.ahk"), result.copy());
        if (com.intellij.openapi.util.SystemInfo.isWindows) assertFalse(isReadOnly(result.copy()));
        assertEquals("Extra.ahk=.", result.line());
        assertEquals("e := 1\n", Files.readString(result.copy()));
        assertEquals("[Deploy]\r\nDestination=" + lib + "\\v2\\\r\n[Install]\r\nGui.ahk=.\r\nExtra.ahk=.\r\n"
                + "\r\n; more\r\n", Files.readString(gui.resolve(AhkDeployConfig.FILE_NAME)));
        // the library file is a copy of the project now
        Map<String, AhkLibraryProjects.Source> copies = AhkLibraryProjects.scan(library.toString(), env(), lib);
        assertEquals(gui.resolve("Extra.ahk"), copies.get(extra.toString().toLowerCase()).file());
        // a file in another folder: relative to Destination
        Path top = write(lib.resolve("Top.ahk"), "t\n");
        assertEquals("Top.ahk=..\\", AhkAddToLibraryProject.add(gui, top, env()).line());
        // the name is taken: name_added.ahk with the placeholder, an error of the settings
        result = AhkAddToLibraryProject.add(gui, extra, env());
        assertEquals("Extra_added.ahk={set target}", result.line());
        List<String> errors = AhkDeployConfig.parse(Files.readString(gui.resolve(AhkDeployConfig.FILE_NAME))).errors();
        assertEquals(errors.toString(), 1, errors.size());
        assertTrue(errors.get(0), errors.get(0).contains("{set target}"));
        assertEquals(gui.resolve("Extra_added [1].ahk"), AhkAddToLibraryProject.freeName(gui, "Extra.ahk"));
        // without Destination: the absolute folder; a missing [Install] is added
        assertEquals("Top.ahk=" + lib + "\\", AhkAddToLibraryProject.add(json, top, env()).line());
        assertEquals("[Deploy]\r\n\r\n[Install]\r\nTop.ahk=" + lib + "\\\r\n",
                Files.readString(json.resolve(AhkDeployConfig.FILE_NAME)));
        // the list: projects of the subfolder first
        List<Path> projects = AhkLibraryProjects.projects(library);
        assertEquals(List.of(json, gui), projects);
        assertEquals(List.of(gui, json), AhkAddToLibraryProject.sorted(projects, library, List.of("v2")));
        assertEquals(List.of(json, gui), AhkAddToLibraryProject.sorted(projects, library, List.of()));
        assertEquals("v2\\Gui", AhkAddToLibraryProject.name(library, gui));
    }

    /**
     * [Cleanup]: selector=folder, the folder absolute or relative to Destination; the files found (each once, not
     * recursive) are listed; mistakes: a file as folder, an invalid selector, a file this deployment writes; a
     * missing folder removes nothing. The same selector may appear in several lines.
     */
    public void testCleanupPlan() throws Exception {
        Path dest = root.resolve("dest");
        Path other = root.resolve("other");
        write(root.resolve("tool.ahk"), "x");
        Path oldExe = write(dest.resolve("old.exe"), "x");
        Path png = write(dest.resolve("pictures/a.png"), "x");
        write(dest.resolve("pictures/sub/b.png"), "x");
        write(dest.resolve("pictures/c.txt"), "x");
        Path test = write(other.resolve("test.exe"), "x");
        AhkDeployConfig config = AhkDeployConfig.parse("[Deploy]\nDestination=" + dest + "\n[Install]\n"
                + "tool.ahk=.\n[Cleanup]\npictures\\*.PNG=.\nold.exe=.\nold.exe=%DESTINATION%\ntest.exe=" + other
                + "\nmissing.exe=" + root.resolve("nowhere") + "\n*.png=pictures\\sub\\\n");
        assertEquals(List.of(), config.errors());
        assertEquals(6, config.cleanup().size());
        AhkDeployPlan plan = AhkDeployPlan.create(config, root, env(), List.of());
        assertEquals(List.of(), plan.errors());
        assertEquals(List.of(png, oldExe, test, dest.resolve("pictures/sub/b.png")),
                plan.removals().stream().map(AhkDeployPlan.Removal::file).toList());
        assertEquals(6, plan.removals().get(0).line());
        assertEquals("pictures\\*.PNG=.", plan.removals().get(0).entry());
        // the overview lists the lines
        List<String> overview = AhkDeployer.overview(config, root, List.of());
        assertEquals("[Cleanup]", overview.get(2));
        assertEquals("pictures\\*.PNG=.", overview.get(3));

        config = AhkDeployConfig.parse("[Deploy]\nDestination=" + dest + "\n[Install]\ntool.ahk=.\n[Cleanup]\n"
                + "x.exe=" + test + "\n..\\x.exe=.\n*.ahk=.\ny.exe=\n");
        assertEquals(config.errors().toString(), 1, config.errors().size());
        assertTrue(config.errors().get(0), config.errors().get(0).contains("no folder"));
        plan = AhkDeployPlan.create(config, root, env(), List.of());
        assertEquals(plan.errors().toString(), 3, plan.errors().size());
        assertTrue(plan.errors().get(0), plan.errors().get(0).contains("is a file"));
        assertTrue(plan.errors().get(1), plan.errors().get(1).contains("relative to the folder"));
        // tool.ahk is written by this deployment (it does not exist yet in dest)
        assertTrue(plan.errors().get(2), plan.errors().get(2).contains("which this deployment writes (line 4)"));
        // relative folder without Destination
        plan = AhkDeployPlan.create(AhkDeployConfig.parse("[Cleanup]\nx.exe=.\n"), root, env(), List.of());
        assertTrue(plan.errors().toString(), plan.errors().get(0).contains("no Destination"));
    }

    /**
     * Deploying with [Cleanup]: without "remove" nothing is touched; with it the confirmed files are removed before
     * copying (a file gone meanwhile counts as removed, so that the test does not fill the Recycle Bin). The script of
     * the elevated process removes first and reports each file.
     */
    public void testCleanupExecute() throws Exception {
        Path dest = root.resolve("dest");
        write(root.resolve("tool.ahk"), "x");
        Path old = write(dest.resolve("old.ahk"), "x");
        write(root.resolve(AhkDeployConfig.FILE_NAME), "[Deploy]\nDestination=" + dest
                + "\n[Install]\ntool.ahk=.\n[Cleanup]\nold.ahk=.\n");
        var prepared = AhkDeployer.prepare(root, env(), false);
        assertEquals(List.of(), prepared.errors());
        assertEquals(List.of(old), prepared.plan().removals().stream().map(AhkDeployPlan.Removal::file).toList());
        List<AhkDeployer.Line> report = new ArrayList<>();
        AhkDeployer.execute(prepared, false, report, new EmptyProgressIndicator());
        assertTrue(Files.exists(old));
        assertTrue(report.toString(), report.get(report.size() - 1).text().startsWith("Deployed: 1 file(s) copied"));
        Files.delete(old);
        report.clear();
        AhkDeployer.execute(prepared, true, report, new EmptyProgressIndicator());
        assertEquals("Moved to the Recycle Bin: " + old, report.get(0).text());
        assertTrue(report.toString(), report.get(report.size() - 1).text()
                .startsWith("Deployed: 1 file(s) moved to the Recycle Bin, 1 copied"));

        List<AhkDeployPlan.Removal> removals = List.of(new AhkDeployPlan.Removal(Path.of("C:\\it's\\old.exe"), 3,
                "old.exe=."));
        String script = AhkDeployCopier.elevatedScript(removals, List.of(), Path.of("C:\\temp\\deploy.log"));
        assertTrue(script, script.contains("  ,'C:\\it''s\\old.exe'\r\n"));
        assertTrue(script, script.contains("SendToRecycleBin"));
        assertTrue(script, script.indexOf("$removals") < script.indexOf("$steps"));
        assertFalse(script, script.replace("\r\n", "").contains("\n"));
        var results = AhkDeployCopier.readRemovals(removals, List.of("REMOVED\t0", "OK\t0"));
        assertEquals(1, results.size());
        assertNull(results.get(0).error());
        results = AhkDeployCopier.readRemovals(removals, List.of("NOTREMOVED\t0\t" + AhkDeployCopier.NO_RECYCLE_BIN));
        assertTrue(results.get(0).error(), results.get(0).error().contains("no Recycle Bin"));
    }
}
