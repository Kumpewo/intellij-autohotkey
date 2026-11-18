package de.kumpewo.autohotkey.install;

import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

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
 * Tests of the installer and of "Set Version" (one tool = one project, {@code root} plays the project root): reading
 * {@code settings.ahk-plugin.ini}, resolving and checking the copies, the executables compiled before copying,
 * copying, the script of the elevated process, the overview, the lines {@code ;@Ahk2Exe-SetVersion} of all scripts,
 * the next version and the Git steps (with a temporary repository; skipped without Git).
 */
public class AhkInstallTest extends BasePlatformTestCase {

    private Path root;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        root = Files.createTempDirectory("ahk-install-test");
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            FileUtil.delete(root);
        } finally {
            super.tearDown();
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
        AhkInstallConfig config = AhkInstallConfig.parse("""
                ; comment
                [install]
                  ; indented comment
                Destination = "C:\\Tools"
                message=Text; not a comment
                RequiresAdmin=TRUE
                [Overwrite]
                tool.exe=tool_new.exe
                pictures\\*.png=pictures
                [KeepExisting]
                tool.ini=%APPDATA%\\tool\\
                """);
        assertEquals(List.of(), config.errors());
        assertEquals("C:\\Tools", config.destination());
        assertEquals("Text; not a comment", config.message());
        assertTrue(config.requiresAdmin());
        assertTrue(config.generateOverview());
        assertTrue(config.compile());
        assertEquals(3, config.entries().size());
        assertEquals(new AhkInstallConfig.Entry("tool.exe", "tool_new.exe", false, 8), config.entries().get(0));
        assertTrue(config.entries().get(2).keepExisting());

        config = AhkInstallConfig.parse("""
                x=1
                [Install]
                Foo=1
                RequiresAdmin=yes
                GenerateOverview=false
                [Overwrite]
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
        assertTrue(errors.get(2), errors.get(2).contains("yes"));
        assertTrue(errors.get(3), errors.get(3).contains("twice"));
        assertTrue(errors.get(4), errors.get(4).contains("b.exe"));
        assertTrue(errors.get(5), errors.get(5).contains("no target"));
        assertTrue(errors.get(6), errors.get(6).contains("[Other]"));
        // the sections of the former tool folders are unknown now
        assertTrue(errors.get(7), errors.get(7).contains("[Compile]"));
        assertTrue(errors.get(8), errors.get(8).contains("[Version]"));
        assertFalse(config.generateOverview());
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
        AhkInstallConfig config = AhkInstallConfig.parse("[Install]\nDestination=" + dest
                + "\nMessage=Copy to %DESTINATION%`nUse 100%% of %USER%\n"
                + "[Overwrite]\ntool.exe=tool_new.exe\npictures\\*.png=pictures\ntool.ini=existing\nnew.exe=bin\\\n"
                + "[KeepExisting]\ntool.ex?=%APPDATA%\\cfg\\\n");
        AhkInstallPlan plan = AhkInstallPlan.create(config, tool, env("APPDATA", root.resolve("appdata").toString(),
                "user", "me"), List.of(tool.resolve("new.exe")));
        assertEquals(List.of(), plan.errors());
        assertEquals("Copy to " + dest + "\nUse 100% of me", plan.message());
        List<String> steps = new ArrayList<>();
        for (AhkInstallPlan.Step step : plan.steps()) {
            steps.add(tool.relativize(step.source()) + " > " + root.relativize(step.target())
                    + (step.keepExisting() ? " keep" : ""));
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
        AhkInstallConfig config = AhkInstallConfig.parse("[Install]\nDestination=" + dest
                + "\n[Overwrite]\na.exe=.\nb.exe=%DESTINATION%\nc.exe=sub\\..\nd.exe=sub\\.\n");
        AhkInstallPlan plan = AhkInstallPlan.create(config, tool, env(), List.of());
        assertEquals(List.of(), plan.errors());
        List<String> steps = new ArrayList<>();
        for (AhkInstallPlan.Step step : plan.steps()) steps.add(root.relativize(step.target()).toString());
        assertEquals(List.of("new dest\\a.exe", "new dest\\b.exe", "new dest\\c.exe", "new dest\\sub\\d.exe"), steps);
        assertFalse(Files.exists(dest));
    }

    /** Every mistake is found before the start. */
    public void testPlanErrors() throws Exception {
        Path tool = root.resolve("tool");
        write(tool.resolve("tool.exe"), "exe");
        write(tool.resolve("sub/x.txt"), "x");
        write(root.resolve("dest/file.txt"), "f");
        AhkInstallConfig config = AhkInstallConfig.parse("[Overwrite]\n"
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
        AhkInstallPlan plan = AhkInstallPlan.create(config, tool, env(), List.of());
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
        plan = AhkInstallPlan.create(AhkInstallConfig.parse("[Install]\nDestination=relative\n"), tool, env(), List.of());
        assertTrue(plan.errors().toString(), plan.errors().get(0).contains("absolute"));
    }

    /** Copying: new files and folders, overwriting, [KeepExisting], modification time. */
    public void testCopy() throws Exception {
        Path a = write(root.resolve("src/a.txt"), "new a");
        Path b = write(root.resolve("src/b.txt"), "new b");
        Files.setLastModifiedTime(a, FileTime.fromMillis(1_600_000_000_000L));
        Path existing = write(root.resolve("dest/a.txt"), "old content that is longer");
        Path kept = write(root.resolve("dest/b.txt"), "old b");
        List<AhkInstallCopier.StepResult> results = AhkInstallCopier.copy(List.of(
                new AhkInstallPlan.Step(a, existing, false),
                new AhkInstallPlan.Step(a, root.resolve("dest/new/deep/a.txt"), false),
                new AhkInstallPlan.Step(b, kept, true),
                new AhkInstallPlan.Step(root.resolve("src/missing.txt"), root.resolve("dest/m.txt"), false),
                new AhkInstallPlan.Step(b, root.resolve("dest/never.txt"), false)));
        assertEquals(4, results.size());
        assertEquals(AhkInstallCopier.Outcome.COPIED, results.get(0).outcome());
        assertEquals(AhkInstallCopier.Outcome.COPIED, results.get(1).outcome());
        assertEquals(AhkInstallCopier.Outcome.SKIPPED, results.get(2).outcome());
        assertEquals(AhkInstallCopier.Outcome.FAILED, results.get(3).outcome());
        assertEquals("new a", Files.readString(existing));
        assertEquals("new a", Files.readString(root.resolve("dest/new/deep/a.txt")));
        assertEquals(1_600_000_000_000L, Files.getLastModifiedTime(existing).toMillis());
        assertEquals("old b", Files.readString(kept));
        assertFalse(Files.exists(root.resolve("dest/never.txt")));
    }

    /** The script of the elevated process quotes paths; its log is read back. */
    public void testElevatedScript() {
        List<AhkInstallPlan.Step> steps = List.of(
                new AhkInstallPlan.Step(Path.of("C:\\it's\\a.exe"), Path.of("C:\\Program Files\\x\\a.exe"), false),
                new AhkInstallPlan.Step(Path.of("C:\\t\\b.ini"), Path.of("C:\\cfg\\b.ini"), true));
        String script = AhkInstallCopier.elevatedScript(steps, Path.of("C:\\temp\\install.log"));
        assertTrue(script, script.contains("$log = 'C:\\temp\\install.log'"));
        assertTrue(script, script.contains(",@('C:\\it''s\\a.exe', 'C:\\Program Files\\x\\a.exe', $false)"));
        assertTrue(script, script.contains(",@('C:\\t\\b.ini', 'C:\\cfg\\b.ini', $true)"));
        assertFalse(script, script.replace("\r\n", "").contains("\n"));
        var results = AhkInstallCopier.readLog(steps, List.of("\uFEFFOK\t0", "ERROR\t1\tAccess denied", "junk"));
        assertEquals(2, results.size());
        assertEquals(AhkInstallCopier.Outcome.COPIED, results.get(0).outcome());
        assertEquals(AhkInstallCopier.Outcome.FAILED, results.get(1).outcome());
        assertEquals("Access denied", results.get(1).error());
    }

    /**
     * The script of the elevated process really copies, run here without elevation (Windows only): new file in a new
     * folder, overwrite, [KeepExisting], paths with spaces and quotes, stop at the first error.
     */
    public void testElevatedScriptRuns() throws Exception {
        if (!com.intellij.openapi.util.SystemInfo.isWindows) return;
        Path a = write(root.resolve("it's src/a.txt"), "new a");
        Files.setLastModifiedTime(a, FileTime.fromMillis(1_600_000_000_000L));
        Path existing = write(root.resolve("dest dir/a.txt"), "old content that is longer");
        Path kept = write(root.resolve("dest dir/k.txt"), "old k");
        List<AhkInstallPlan.Step> steps = List.of(
                new AhkInstallPlan.Step(a, existing, false),
                new AhkInstallPlan.Step(a, root.resolve("dest dir/new/a.txt"), false),
                new AhkInstallPlan.Step(a, kept, true),
                new AhkInstallPlan.Step(root.resolve("missing.txt"), root.resolve("dest dir/m.txt"), false),
                new AhkInstallPlan.Step(a, root.resolve("dest dir/never.txt"), false));
        Path log = root.resolve("install.log");
        Files.writeString(log, "");
        Path script = root.resolve("install.ps1");
        Files.write(script, ("﻿" + AhkInstallCopier.elevatedScript(steps, log)).getBytes(StandardCharsets.UTF_8));
        var line = new com.intellij.execution.configurations.GeneralCommandLine("powershell.exe", "-NoProfile",
                "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.toString());
        var output = new com.intellij.execution.process.CapturingProcessHandler(line).runProcess(60_000);
        assertEquals(output.getStderr(), 1, output.getExitCode());
        var results = AhkInstallCopier.readLog(steps, Files.readAllLines(log, StandardCharsets.UTF_8));
        List<AhkInstallCopier.Outcome> outcomes = new ArrayList<>();
        for (var result : results) outcomes.add(result.outcome());
        assertEquals(List.of(AhkInstallCopier.Outcome.COPIED, AhkInstallCopier.Outcome.COPIED,
                AhkInstallCopier.Outcome.SKIPPED, AhkInstallCopier.Outcome.FAILED), outcomes);
        assertEquals("new a", Files.readString(existing));
        assertEquals("new a", Files.readString(root.resolve("dest dir/new/a.txt")));
        assertEquals(1_600_000_000_000L, Files.getLastModifiedTime(existing).toMillis());
        assertEquals("old k", Files.readString(kept));
        assertFalse(Files.exists(root.resolve("dest dir/never.txt")));
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
        assertEquals(root.resolve("tool/tool.exe"), AhkInstaller.compiledExe(script, "MsgBox 1\n"));
        assertEquals(root.resolve("tool/bin/other.exe"),
                AhkInstaller.compiledExe(script, ";@Ahk2Exe-ExeName bin\\other\n"));
        assertNull(AhkInstaller.compiledExe(script, ";@Ahk2Exe-ExeName %A_ScriptName%\n"));
    }

    /** Without Git: no warning; the overview lists the copy sections without comments; copying works. */
    public void testPrepareAndCopyWithoutGit() throws Exception {
        write(root.resolve("tool.ahk"), "MsgBox 1\n");
        write(root.resolve("tool.ini"), "ini");
        write(root.resolve(AhkInstallConfig.FILE_NAME), "[Install]\nDestination=" + root.resolve("dest")
                + "\nRequiresAdmin=false\n[KeepExisting]\n; the configuration\ntool.ini=.\n"
                + "[Overwrite]\n  ; the script\ntool.ahk=.\\\n");
        var prepared = AhkInstaller.prepare(root, env(), false);
        assertEquals(List.of(), prepared.errors());
        assertEquals(List.of(), prepared.warnings());
        assertFalse(prepared.uncommitted());
        assertEquals(List.of("[Overwrite]", "tool.ahk=.\\", "[KeepExisting]", "tool.ini=."), prepared.overview());
        List<AhkInstaller.Line> report = new ArrayList<>();
        AhkInstaller.execute(prepared, report, new EmptyProgressIndicator());
        assertTrue(report.toString(), report.get(report.size() - 1).text().startsWith("Deployed: 2 file(s) copied"));
        assertTrue(Files.isRegularFile(root.resolve("dest/tool.ahk")));

        // the old keys of the installer are unknown now
        write(root.resolve(AhkInstallConfig.FILE_NAME), "[Install]\nCommitBeforeInstall=true\n"
                + "[Overwrite]\ntool.ahk=" + root.resolve("dest") + "\\\n");
        prepared = AhkInstaller.prepare(root, env(), false);
        assertEquals(prepared.errors().toString(), 1, prepared.errors().size());
        assertTrue(prepared.errors().get(0), prepared.errors().get(0).contains("unknown key CommitBeforeInstall"));
    }

    /**
     * CompileBeforeInstall: every executable of a copy line without wildcards that has a script of the same name next
     * to it is compiled from it (each script once, in the order of the lines) and counts as existing for the copies;
     * an executable without a script is just copied; mistakes (no ;@Ahk2Exe-Base, another ;@Ahk2Exe-ExeName) stop
     * everything. Without the switch only a warning about an older executable.
     */
    public void testPrepareCompile() throws Exception {
        String base = ";@Ahk2Exe-Base ..\\v2\\AutoHotkey64.exe\n";
        write(root.resolve("tool.ahk"), base + "MsgBox 1\n");
        write(root.resolve("console/tool-console.ahk"), base + "MsgBox 2\n");
        write(root.resolve("bin/prebuilt.exe"), "exe");
        write(root.resolve(AhkInstallConfig.FILE_NAME), "[Install]\nDestination=" + root.resolve("dest")
                + "\nCompileBeforeInstall=true\n[Overwrite]\nTOOL.EXE=.\nconsole\\tool-console.exe=.\n"
                + "bin\\prebuilt.exe=.\ntool.ex?=copy\\\n[KeepExisting]\ntool.exe=keep\\\n");
        var prepared = AhkInstaller.prepare(root, env(), false);
        assertEquals(List.of(root.resolve("tool.ahk"), root.resolve("console/tool-console.ahk")), prepared.compile());
        assertEquals(List.of(root.resolve("TOOL.EXE"), root.resolve("console/tool-console.exe"),
                root.resolve("tool.exe")), prepared.willExist());
        assertEquals(List.of("compile tool.ahk", "compile console\\tool-console.ahk", "[Overwrite]", "TOOL.EXE=.",
                "console\\tool-console.exe=.", "bin\\prebuilt.exe=.", "tool.ex?=copy\\", "[KeepExisting]",
                "tool.exe=keep\\"), prepared.overview());
        // only "Ahk2Exe was not found" is possible (depends on the machine)
        assertTrue(prepared.errors().toString(), prepared.errors().stream().allMatch(e -> e.contains("Ahk2Exe was")));
        assertEquals(5, prepared.plan().steps().size());

        // mistakes: no ;@Ahk2Exe-Base, the script compiles to another file
        write(root.resolve("tool.ahk"), "MsgBox 1\n");
        write(root.resolve("console/tool-console.ahk"), base + ";@Ahk2Exe-ExeName other\nMsgBox 2\n");
        prepared = AhkInstaller.prepare(root, env(), false);
        List<String> errors = prepared.errors().stream().filter(e -> !e.contains("Ahk2Exe was")).toList();
        assertEquals(errors.toString(), 2, errors.size());
        assertTrue(errors.get(0), errors.get(0).contains("tool.ahk") && errors.get(0).contains(";@Ahk2Exe-Base"));
        assertTrue(errors.get(1), errors.get(1).contains("console\\other.exe"));

        // switched off: nothing compiled, the executable must exist, a warning if it is older
        write(root.resolve(AhkInstallConfig.FILE_NAME), "[Install]\nDestination=" + root.resolve("dest")
                + "\nCompileBeforeInstall=false\n[Overwrite]\ntool.exe=.\n");
        write(root.resolve("tool.exe"), "exe");
        Files.setLastModifiedTime(root.resolve("tool.exe"), FileTime.fromMillis(0));
        prepared = AhkInstaller.prepare(root, env(), false);
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
        write(root.resolve(AhkInstallConfig.FILE_NAME), "[Install]\nDestination=" + root.resolve("dest")
                + "\nCompileBeforeInstall=true\n[Overwrite]\ntool.ahk=.\nmy console\\my console.exe=.\n");
        var prepared = AhkInstaller.prepare(root, env(), false);
        assertEquals(List.of(), prepared.errors());
        List<AhkInstaller.Line> report = new ArrayList<>();
        AhkInstaller.execute(prepared, report, new EmptyProgressIndicator());
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
     * Set Version without Git and without settings: the lines of all scripts are read (scripts without a line are
     * left out), different versions are a warning, the highest is the current one; mistakes are reported.
     */
    public void testSetVersionPrepareWithoutGit() throws Exception {
        Path gui = write(root.resolve("tool.ahk"), ";@Ahk2Exe-SetVersion 1.0\nMsgBox 1\n");
        Path console = write(root.resolve("console/tool-console.ahk"), ";@Ahk2Exe-SetVersion 1.0\nMsgBox 2\n");
        Path lib = write(root.resolve("lib/helper.ahk"), "f() => 1\n");
        var prepared = AhkSetVersion.prepare(root, "Tool", texts(gui, console, lib), false);
        assertEquals(List.of(), prepared.errors());
        assertEquals(List.of(), prepared.warnings());
        assertEquals(List.of("console\\tool-console.ahk", "tool.ahk"),
                prepared.scripts().stream().map(AhkSetVersion.Script::name).toList());
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
        prepared = AhkSetVersion.prepare(root, "Tool", texts(lib), false);
        assertTrue(prepared.errors().toString(), prepared.errors().get(0).contains("No AutoHotkey file"));
    }

    /**
     * The section [SetVersion]: files and folders (ending with a backslash, recursive) with the value exclude, wildcards
     * only in the last name; its mistakes only stop Set Version, mistakes of [Install] only the installer, mistakes of
     * the file itself both.
     */
    public void testSetVersionExclusionsParsed() {
        AhkInstallConfig config = AhkInstallConfig.parse("""
                [SetVersion]
                scratch\\=exclude
                old/tool-v1.ahk=EXCLUDE
                test*\\=exclude
                misc\\*.ahk=exclude
                """);
        assertEquals(List.of(), config.versionErrors());
        assertEquals(List.of(), config.errors());
        assertTrue(config.excludes("scratch\\a.ahk"));
        assertTrue(config.excludes("Scratch\\deep\\b.ahk"));
        assertFalse(config.excludes("scratch.ahk"));
        assertFalse(config.excludes("lib\\scratch\\a.ahk"));
        assertTrue(config.excludes("old\\TOOL-v1.ahk"));
        assertFalse(config.excludes("old\\tool-v2.ahk"));
        assertTrue(config.excludes("tests\\x\\y.ahk"));
        assertFalse(config.excludes("tests.ahk"));
        assertTrue(config.excludes("misc\\a.ahk"));
        assertFalse(config.excludes("misc\\sub\\a.ahk"));
        assertFalse(config.excludes("tool.ahk"));

        config = AhkInstallConfig.parse("""
                [Install]
                Unknown=1
                [SetVersion]
                a\\=include
                ..\\x\\=exclude
                a*\\b.ahk=exclude
                \\=exclude
                """);
        assertEquals(config.versionErrors().toString(), 4, config.versionErrors().size());
        assertTrue(config.versionErrors().get(0), config.versionErrors().get(0).contains("must be exclude"));
        assertEquals(config.errors().toString(), 1, config.errors().size());
        assertTrue(config.errors().get(0), config.errors().get(0).contains("unknown key"));
        assertEquals(List.of(), config.exclusions());

        config = AhkInstallConfig.parse("[Other]\nx=1\n[SetVersion]\nscratch\\=exclude\nscratch/=exclude\n");
        assertEquals(config.versionErrors().toString(), 2, config.versionErrors().size());
        assertEquals(config.errors().toString(), 2, config.errors().size());
    }

    /** Set Version leaves out the scripts excluded in the settings silently and stops on mistakes of [SetVersion]. */
    public void testSetVersionPrepareWithExclusions() throws Exception {
        Path gui = write(root.resolve("tool.ahk"), ";@Ahk2Exe-SetVersion 1.0\nMsgBox 1\n");
        Path scratch = write(root.resolve("scratch/try.ahk"), ";@Ahk2Exe-SetVersion 0.1\nMsgBox 2\n");
        Path old = write(root.resolve("old/tool-v1.ahk"), ";@Ahk2Exe-SetVersion 1\n;@Ahk2Exe-SetVersion 2\n");
        write(root.resolve(AhkInstallConfig.FILE_NAME),
                "[Install]\nDestination=%NO_SUCH_VARIABLE%\n[SetVersion]\nscratch\\=exclude\nold\\*.ahk=exclude\n");
        var prepared = AhkSetVersion.prepare(root, "Tool", texts(gui, scratch, old), false);
        assertEquals(List.of(), prepared.errors());
        assertEquals(List.of(), prepared.warnings());
        assertEquals(List.of("tool.ahk"), prepared.scripts().stream().map(AhkSetVersion.Script::name).toList());

        write(root.resolve(AhkInstallConfig.FILE_NAME), "[SetVersion]\nscratch\\=yes\n");
        prepared = AhkSetVersion.prepare(root, "Tool", texts(gui, scratch), false);
        assertEquals(prepared.errors().toString(), 1, prepared.errors().size());
        assertTrue(prepared.errors().get(0), prepared.errors().get(0).contains("must be exclude"));
    }

    /**
     * With a temporary Git repository: Set Version needs every file versioned or ignored; then it commits all changes
     * of the project (staged changes outside stay staged) and tags v&lt;version&gt;; an existing tag is known; the
     * installer only warns about uncommitted changes and commits nothing.
     */
    public void testSetVersionCommitAndTag() throws Exception {
        AhkInstallGit git = new AhkInstallGit(root);
        if (!git.run("--version").ok()) return;
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
        write(project.resolve(AhkInstallConfig.FILE_NAME), "[Install]\nDestination=" + root.resolve("dest")
                + "\n[Overwrite]\ntool.ahk=.\\\n");
        assertTrue(git.run("add", "-A").ok());
        assertTrue(git.run("commit", "-q", "-m", "initial").ok());
        assertTrue(git.run("tag", "-a", "v1.2", "-m", "old").ok());
        write(project.resolve("tool.exe"), "ignored");
        write(lib, "f() => 2\n");
        write(project.resolve("new.ahk"), "untracked");
        write(root.resolve("staged.txt"), "staged outside of the project");
        assertTrue(git.run("add", "staged.txt").ok());

        // the installer: warning only, nothing committed
        var install = AhkInstaller.prepare(project, env(), true);
        assertEquals(List.of(), install.errors());
        assertTrue(install.uncommitted());

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

    /**
     * The default settings.ahk-plugin.ini: valid as it is (nothing set), {tool} replaced, CR LF; with every option line
     * uncommented it is still valid and sets all keys.
     */
    public void testDefaultSettingsFile() {
        String text = AhkSettingsFile.text("mytool");
        assertFalse(text, text.contains("{tool}"));
        assertFalse(text.replace("\r\n", "").contains("\n"));
        assertFalse(text, text.contains("[Compile]") || text.contains("[Version]") || text.contains("TagPrefix"));
        AhkInstallConfig config = AhkInstallConfig.parse(text);
        assertEquals(List.of(), config.errors());
        assertTrue(config.entries().isEmpty());
        assertNull(config.destination());
        // remove the ";" of the option lines (";key=value", without a space after ";")
        StringBuilder uncommented = new StringBuilder();
        for (String line : text.split("\r\n")) {
            uncommented.append(line.matches(";[^ ;].*=.*") ? line.substring(1) : line).append("\r\n");
        }
        config = AhkInstallConfig.parse(uncommented.toString());
        assertEquals(List.of(), config.errors());
        assertEquals("C:\\bin", config.destination());
        assertTrue(config.message(), config.message().contains("mytool"));
        // the commented-out switches show the value that changes the default
        assertFalse(config.generateOverview());
        assertTrue(config.requiresAdmin());
        assertFalse(config.compile());
        assertEquals(4, config.entries().size());
        assertEquals(new AhkInstallConfig.Entry("mytool-console.exe", ".", false, config.entries().get(1).line()),
                config.entries().get(1));
        assertEquals(new AhkInstallConfig.Entry("mytool.ini", "%APPDATA%\\mytool\\", true,
                config.entries().get(3).line()), config.entries().get(3));
    }

    /** The project actions are offered as soon as the project contains an AutoHotkey file, also when none is open. */
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
        var ini = dir.findChild(AhkInstallConfig.FILE_NAME);
        assertNotNull(ini);
        String text = com.intellij.openapi.vfs.VfsUtilCore.loadText(ini);
        assertTrue(text, text.contains(";Message=Close the running " + getProject().getName() + " first."));
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            try {
                com.intellij.openapi.vfs.VfsUtil.saveText(ini, "[Install]\nDestination=C:\\mine\n");
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        });
        AhkSettingsFile.create(getProject(), dir);
        assertEquals("[Install]\nDestination=C:\\mine\n", com.intellij.openapi.vfs.VfsUtilCore.loadText(ini));
    }
}
