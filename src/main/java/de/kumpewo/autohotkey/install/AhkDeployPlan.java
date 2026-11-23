package de.kumpewo.autohotkey.install;

import de.kumpewo.autohotkey.AhkBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The resolved copy steps of an {@link AhkDeployConfig}, checked before anything happens:
 * <ul>
 *   <li>Variables ({@link #variables()}): the built-in variables of AutoHotkey that need no script
 *       ({@code %A_MyDocuments%}, {@code %A_AppData%} ..., {@link de.kumpewo.autohotkey.include.AhkFolderVariables}),
 *       {@code %LIBRARY_PROJECTS%} (the setting "Library projects folder"), in targets and {@code Message} also
 *       {@code %DESTINATION%}; {@code %%} is a {@code %}. Environment variables are not resolved: an unknown name is
 *       an error.</li>
 *   <li>{@code Destination} must be absolute.</li>
 *   <li>Targets: a relative target lies under {@code Destination} (without one: error). A target is a folder if it
 *       ends with {@code \} or {@code /}, if it exists as a folder, or if the source has a wildcard (then an existing
 *       file of that name is an error); the file keeps its name. Two lines must not give the same target.</li>
 *   <li>Sources: relative to the project root, never outside ({@code ..}, absolute paths and drive letters are
 *       errors); wildcards {@code *} and {@code ?} only in the file name, not recursive, only files. A missing file or
 *       a wildcard without match is an error, unless the file will be created by the compilation
 *       ({@code willExist}).</li>
 *   <li>{@code Message}: variables like the targets, {@code `n} is a line break.</li>
 *   <li>Banner ({@code Banner}, {@link AhkBanner}), only for {@code [Install]}: AutoHotkey files get it
 *       unless the last matching line of {@code [AddBanner]} excludes them, other files only if it includes
 *       them. The comment style comes from the line or the file type (otherwise an error); binary files never get it
 *       (warning). A file that is no AutoHotkey file and whose banner uses {@code {version}} loses its line
 *       {@code @SetVersion} in the copy. {@code {commit}} asks Git only if the text uses it; {@code {include}} is the
 *       target relative to a Lib folder ({@link #libraries}). Files of {@code [InstallIfMissing]} never get a banner; a
 *       line that includes one is a hint. The summary of the banner ({@link #summarizeBanner}): with
 *       {@code Banner} but no banner written, a warning if a file could take one and is not excluded by
 *       {@code no}, a hint ({@link #hints()}) if no deployed file could take one; without {@code Banner}, a
 *       warning for the first file an including line names and one for the AutoHotkey files that would get it.</li>
 *   <li>Origin ({@code EmbedOrigin}, {@link AhkExeOrigin}): an executable of {@code [Install]} with a script of the
 *       same name next to it gets the project folder and the computer at its end; a signed one does not (hint).
 *       Without {@code EmbedOrigin} the built-in banners name neither of them, too.</li>
 *   <li>{@code [Cleanup]} ({@link #removals()}): the folder is resolved like a target (variables, relative to
 *       {@code Destination}); if it exists as a file, that is an error. The selector is checked like a source
 *       (relative, no {@code ..}, wildcards only in the file name) and names files relative to the folder, not
 *       recursive, only files. A folder that does not exist or a selector without match removes nothing. A file that
 *       this deployment writes (a target of {@code [Install]} or {@code [InstallIfMissing]}, also one that does not
 *       exist yet) is an error.</li>
 * </ul>
 */
public final class AhkDeployPlan {

    /**
     * One copy.
     *
     * @param source       absolute source file
     * @param target       absolute target file
     * @param ifMissing only copy if the target does not exist ({@code [InstallIfMissing]})
     * @param banner       comment lines written at the start of the copy ({@link AhkBanner}), {@code null} = none
     * @param dropLine     0-based line of the source left out of the copy (its {@code @SetVersion}), -1 = none
     * @param origin       text appended to the copy of an executable ({@link AhkExeOrigin}), {@code null} = none
     */
    public record Step(@NotNull Path source, @NotNull Path target, boolean ifMissing,
                       @Nullable List<String> banner, int dropLine, @Nullable String origin) {
        /** A copy without banner. */
        public Step(@NotNull Path source, @NotNull Path target, boolean ifMissing) {
            this(source, target, ifMissing, null, -1, null);
        }

        /** A copy with banner, no line left out. */
        public Step(@NotNull Path source, @NotNull Path target, boolean ifMissing, @Nullable List<String> banner) {
            this(source, target, ifMissing, banner, -1, null);
        }
    }

    /**
     * A file that {@code [Cleanup]} moves to the Recycle Bin before copying.
     *
     * @param file  absolute path of the existing file
     * @param line  1-based line of {@code [Cleanup]} that names it (the first one)
     * @param entry that line as {@code selector=folder}
     */
    public record Removal(@NotNull Path file, int line, @NotNull String entry) {
    }

    /** The banner of a source and the line it replaces ({@link Step#dropLine}). */
    private record Banner(@Nullable List<String> lines, int dropLine) {
        static final Banner NONE = new Banner(null, -1);
    }

    private final @Nullable Path destination;
    private final @Nullable String message;
    private final List<Step> steps = new ArrayList<>();
    private final List<Removal> removals = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final List<String> hints = new ArrayList<>();
    /** A banner was written into at least one copy (also one whose lines all were left out). */
    private boolean bannerApplied;
    /** A source of {@code [Install]} could take a banner (known comment style, no binary file). */
    private boolean bannerCapable;
    /** Sources of {@code [Install]} that could take a banner, get none and are not excluded by {@code no}. */
    private final List<String> bannerMissed = new ArrayList<>();
    /** A banner could not be made (mistake of a line or of {@code Banner}): no summary. */
    private boolean bannerFailed;
    /** Without {@code Banner}: the first source an including line names, and that line. */
    private @Nullable String unusedRuleFile;
    private int unusedRuleLine;
    /** Without {@code Banner}: AutoHotkey sources of {@code [Install]} that would get a banner. */
    private final List<String> rawAutoHotkey = new ArrayList<>();
    /** Target (lower case) → line that gives it, to find a target given twice. */
    private final Map<String, Integer> targets = new java.util.HashMap<>();
    /** Lines of the text of {@code Banner}, {@code null} without one (or after a mistake). */
    private @Nullable List<String> bannerText;
    private @NotNull String timestamp = "";
    /** Name of this computer (variable {@code A_ComputerName}), {@code null} if unknown. */
    private @Nullable String computer;
    /** Source → value of {@code {commit}} ({@code null} = empty), asked only if the banner uses it. */
    private @NotNull Function<Path, String> commit = file -> null;
    /** The Lib folders for {@code {include}}. */
    private @NotNull List<Path> libraries = List.of();
    private @NotNull AhkDeployConfig config = AhkDeployConfig.parse("");

    private AhkDeployPlan(@Nullable Path destination, @Nullable String message) {
        this.destination = destination;
        this.message = message;
    }

    /** The resolved {@code Destination}, {@code null} without one. */
    public @Nullable Path destination() {
        return destination;
    }

    /** The resolved {@code Message}, {@code null} without one. */
    public @Nullable String message() {
        return message;
    }

    /** The copies in the order of the file. */
    public @NotNull List<Step> steps() {
        return steps;
    }

    /** The files {@code [Cleanup]} removes, in the order of its lines (each file once). */
    public @NotNull List<Removal> removals() {
        return removals;
    }

    /** Mistakes found, empty if everything can be copied. */
    public @NotNull List<String> errors() {
        return errors;
    }

    /**
     * Warnings for the confirmation, shown in red (e.g. binary files with a banner rule, a banner that no file gets).
     */
    public @NotNull List<String> warnings() {
        return warnings;
    }

    /** Hints for the confirmation, shown in gray: only noticed, nothing wrong ({@code Banner} without effect). */
    public @NotNull List<String> hints() {
        return hints;
    }

    /**
     * The variables of the settings file with case-insensitive names, as lookup for {@link #create}: the built-in
     * folder variables of AutoHotkey and {@code LIBRARY_PROJECTS} (if the setting is not empty).
     */
    public static @NotNull Function<String, String> variables() {
        Map<String, String> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        values.putAll(de.kumpewo.autohotkey.include.AhkFolderVariables.values());
        String library = de.kumpewo.autohotkey.settings.AhkSettings.getInstance().getLibraryProjectsFolder();
        if (!library.isEmpty()) values.put(LIBRARY_PROJECTS, library);
        return values::get;
    }

    /** Name of the variable of the library projects folder. */
    static final String LIBRARY_PROJECTS = "LIBRARY_PROJECTS";

    /** Time of the deployment for {@code {timestamp}}: local time, {@code yyyy-MM-dd HH:mm}. */
    static @NotNull String timestamp() {
        return java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    /**
     * Resolves and checks the configuration.
     *
     * @param config    the read {@code settings.ahk-v2-plugin.ini}
     * @param root      the project root, folder of the {@code settings.ahk-v2-plugin.ini}
     * @param env       the variables ({@link #variables()}; case-insensitive lookup, {@code null} = undefined)
     * @param willExist files that do not exist yet but will before copying (the compiled executables)
     */
    public static @NotNull AhkDeployPlan create(@NotNull AhkDeployConfig config, @NotNull Path root,
                                                 @NotNull Function<String, String> env,
                                                 @NotNull Collection<Path> willExist) {
        return create(config, root, env, willExist, timestamp(), gitCommit(root), libraries(env));
    }

    /**
     * As {@link #create(AhkDeployConfig, Path, Function, Collection)} without Git (the banners have no
     * {@code {commit}}), for callers that only need the targets or the destination.
     */
    public static @NotNull AhkDeployPlan targets(@NotNull AhkDeployConfig config, @NotNull Path root,
                                                  @NotNull Function<String, String> env) {
        return create(config, root, env, List.of(), timestamp(), file -> null, libraries(env));
    }

    /** As {@link #create(AhkDeployConfig, Path, Function, Collection)} with the time for {@code {timestamp}}. */
    static @NotNull AhkDeployPlan create(@NotNull AhkDeployConfig config, @NotNull Path root,
                                          @NotNull Function<String, String> env,
                                          @NotNull Collection<Path> willExist, @NotNull String timestamp) {
        return create(config, root, env, willExist, timestamp, file -> null, libraries(env));
    }

    /**
     * Resolves and checks the configuration (see {@link #create(AhkDeployConfig, Path, Function, Collection)}).
     *
     * @param timestamp the time for {@code {timestamp}}
     * @param commit    source → value of {@code {commit}} ({@code null} = empty)
     * @param libraries the Lib folders for {@code {include}}
     */
    static @NotNull AhkDeployPlan create(@NotNull AhkDeployConfig config, @NotNull Path root,
                                          @NotNull Function<String, String> env,
                                          @NotNull Collection<Path> willExist, @NotNull String timestamp,
                                          @NotNull Function<Path, String> commit, @NotNull List<Path> libraries) {
        List<String> errors = new ArrayList<>();
        Path destination = null;
        if (config.destination() != null) {
            String text = expand(config.destination(), env, null, errors, "Destination");
            if (text != null) {
                Path path = path(text);
                if (path == null || !path.isAbsolute()) {
                    errors.add(AhkBundle.message("deploy.destination.relative", config.destination()));
                } else {
                    destination = path.normalize();
                }
            }
        }
        String message = null;
        if (config.message() != null) {
            message = expand(config.message(), env, destination, errors, "Message");
            if (message != null) message = message.replace("`n", "\n");
        }
        AhkDeployPlan plan = new AhkDeployPlan(destination, message);
        plan.errors.addAll(errors);
        plan.timestamp = timestamp;
        String computer = env.apply("A_ComputerName");
        plan.computer = computer == null || computer.isEmpty() ? null : computer;
        plan.commit = commit;
        plan.libraries = libraries;
        plan.config = config;
        Path dir = root.toAbsolutePath().normalize();
        if (config.banner() != null) {
            plan.bannerText = AhkBanner.text(config.banner(), dir, env, plan.errors);
            if (plan.bannerText == null) {
                plan.bannerFailed = true;
            } else {
                plan.bannerText = AhkBanner.builtin(plan.bannerText, config.embedOrigin(),
                        config.embedOrigin() && plan.computer != null);
            }
        }
        for (AhkDeployConfig.Entry entry : config.entries()) {
            plan.addEntry(entry, dir, env, config.destination() != null, willExist);
        }
        for (AhkDeployConfig.CleanupEntry entry : config.cleanup()) {
            plan.addCleanup(entry, env, config.destination() != null);
        }
        plan.summarizeBanner();
        return plan;
    }

    /**
     * The warnings and hints about the banner as a whole, after all copies (see class comment): nothing if a banner
     * is written or if making one failed (the error says enough).
     */
    private void summarizeBanner() {
        if (bannerFailed) return;
        if (config.banner() != null) {
            if (bannerApplied) return;
            if (!bannerMissed.isEmpty()) {
                warnings.add(AhkBundle.message("deploy.banner.none", bannerMissed.get(0), bannerMissed.size() - 1,
                        selector(bannerMissed.get(0))));
            } else if (!bannerCapable && !steps.isEmpty()) {
                hints.add(AhkBundle.message("deploy.banner.no.types"));
            }
            return;
        }
        if (unusedRuleFile != null) {
            warnings.add(AhkBundle.message("deploy.ini.banner.missing", unusedRuleLine, unusedRuleFile));
        }
        if (!rawAutoHotkey.isEmpty()) {
            warnings.add(AhkBundle.message("deploy.banner.unset", rawAutoHotkey.get(0), rawAutoHotkey.size() - 1,
                    selector(rawAutoHotkey.get(0))));
        }
    }

    /** The selector proposed for a file in a message: {@code *.ext}, or the path itself without an extension. */
    private static @NotNull String selector(@NotNull String relative) {
        String extension = AhkFileRules.extension(relative);
        return extension.isEmpty() ? relative : "*." + extension;
    }

    /**
     * The Lib folders for {@code {include}}: the user library of the variable {@code A_MyDocuments} and the global
     * Lib folders of the plugin ({@link de.kumpewo.autohotkey.include.AhkInclude#globalLibraryDirectories()}).
     */
    static @NotNull List<Path> libraries(@NotNull Function<String, String> env) {
        List<Path> result = new ArrayList<>();
        String documents = env.apply("A_MyDocuments");
        Path user = documents == null ? null : path(documents);
        if (user != null && user.isAbsolute()) result.add(user.resolve("AutoHotkey").resolve("Lib").normalize());
        try {
            for (Path dir : de.kumpewo.autohotkey.include.AhkInclude.globalLibraryDirectories()) {
                Path normalized = dir.toAbsolutePath().normalize();
                if (!result.contains(normalized)) result.add(normalized);
            }
        } catch (RuntimeException e) {
            // no settings (e.g. outside the IDE): only the user library
        }
        return result;
    }

    /**
     * The value of {@code {include}} for a target: {@code #Include <name>} with the path relative to the first Lib
     * folder that contains it, without {@code .ahk}; {@code null} for other targets.
     */
    static @Nullable String include(@NotNull Path target, @NotNull List<Path> libraries) {
        String name = target.getFileName() == null ? "" : target.getFileName().toString();
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".ahk")) return null;
        String text = target.toString();
        for (Path library : libraries) {
            String prefix = library.toString();
            if (!prefix.endsWith("\\")) prefix += "\\";
            if (text.length() > prefix.length() && text.regionMatches(true, 0, prefix, 0, prefix.length())) {
                String relative = text.substring(prefix.length());
                return "#Include <" + relative.substring(0, relative.length() - 4) + ">";
            }
        }
        return null;
    }

    /**
     * The value of {@code {commit}} by Git in the project root: the short hash of HEAD, with {@code +} if the source
     * has uncommitted changes; {@code null} without Git, without a commit, or for a file that is untracked or ignored.
     * HEAD is asked once.
     */
    static @NotNull Function<Path, String> gitCommit(@NotNull Path root) {
        AhkDeployGit git = new AhkDeployGit(root);
        String[] head = {null};
        return file -> {
            if (head[0] == null) {
                AhkDeployGit.Result result = git.run("rev-parse", "--short", "HEAD");
                head[0] = result.ok() ? result.stdout().strip() : "";
            }
            if (head[0].isEmpty()) return null;
            AhkDeployGit.Result status = git.run("status", "--porcelain", "--ignored", "--", file.toString());
            if (!status.ok()) return null;
            String out = status.stdout().strip();
            if (out.startsWith("??") || out.startsWith("!!")) return null;
            return out.isEmpty() ? head[0] : head[0] + "+";
        };
    }

    /**
     * Resolves a line of {@code [Cleanup]} (see class comment): the folder like a target, the selector like a source
     * relative to it. Mistakes: an invalid selector, a folder that is a file, and a file that this deployment writes.
     */
    private void addCleanup(AhkDeployConfig.CleanupEntry entry, Function<String, String> env,
                            boolean hasDestination) {
        String where = AhkBundle.message("deploy.where", entry.line(), entry.selector());
        String selector = entry.selector().replace('/', '\\');
        if (!validSource(selector)) {
            errors.add(AhkBundle.message("deploy.cleanup.selector.invalid", where));
            return;
        }
        String text = expand(entry.folder(), env, destination, errors, where);
        if (text == null) return;
        Path folder = path(text);
        if (folder == null || (!folder.isAbsolute() && folder.getRoot() != null)) {
            errors.add(AhkBundle.message("deploy.target.invalid", where, text));
            return;
        }
        if (!folder.isAbsolute()) {
            if (destination == null) {
                errors.add(hasDestination ? AhkBundle.message("deploy.target.invalid", where, text)
                        : AhkBundle.message("deploy.cleanup.relative", where, text));
                return;
            }
            folder = destination.resolve(folder);
        }
        folder = folder.normalize();
        if (Files.isRegularFile(folder)) {
            errors.add(AhkBundle.message("deploy.cleanup.folder.is.file", where, folder));
            return;
        }
        int slash = selector.lastIndexOf('\\');
        Path dir = slash < 0 ? folder : folder.resolve(selector.substring(0, slash)).normalize();
        Pattern name = wildcard(selector.substring(slash + 1));
        // a file this deployment writes must not be removed (also one that does not exist yet)
        for (Step step : steps) {
            Path target = step.target();
            if (target.getParent() != null && target.getParent().toString().equalsIgnoreCase(dir.toString())
                    && name.matcher(target.getFileName().toString()).matches()) {
                Integer line = targets.get(target.toString().toLowerCase(java.util.Locale.ROOT));
                errors.add(AhkBundle.message("deploy.cleanup.conflict", where, target, line == null ? 0 : line));
                return;
            }
        }
        if (!Files.isDirectory(dir)) return;
        for (Path file : match(dir, selector.substring(slash + 1), List.of())) {
            String key = file.toString().toLowerCase(java.util.Locale.ROOT);
            if (removals.stream().noneMatch(r -> r.file().toString().toLowerCase(java.util.Locale.ROOT).equals(key))) {
                removals.add(new Removal(file, entry.line(), entry.selector() + "=" + entry.folder()));
            }
        }
    }

    /**
     * The banner lines for one source, {@code null} = none (see class comment); mistakes go to the errors, binary
     * files to the warnings, including lines for {@code [InstallIfMissing]} to the hints. Records what
     * {@link #summarizeBanner} needs.
     */
    private @NotNull Banner banner(Path file, Path root, Path target, boolean ifMissing) {
        String relative = root.relativize(file).toString();
        String name = file.getFileName().toString();
        AhkFileRules.Rule rule = AhkFileRules.find(config.bannerRules(), relative);
        boolean ruleIncludes = rule != null && rule.include();
        if (ifMissing) {
            // the user is meant to change these files: never a banner, with or without Banner
            if (ruleIncludes) addOnce(hints, AhkBundle.message("deploy.banner.if.missing", relative));
            return Banner.NONE;
        }
        boolean include = rule != null ? rule.include() : AhkFileRules.isAutoHotkey(name);
        if (config.banner() == null) {
            if (ruleIncludes) {
                if (unusedRuleFile == null) {
                    unusedRuleFile = relative;
                    unusedRuleLine = rule.line();
                }
            } else if (include) {
                rawAutoHotkey.add(relative);
            }
            return Banner.NONE;
        }
        if (bannerText == null) return Banner.NONE;
        boolean capable = !AhkFileRules.isBinary(name) && AhkFileRules.style(rule, name) != null;
        if (capable) {
            bannerCapable = true;
            if (!include && rule == null) bannerMissed.add(relative);
        }
        if (!include) return Banner.NONE;
        if (AhkFileRules.isBinary(name)) {
            addOnce(warnings, AhkBundle.message("deploy.banner.binary", relative));
            return Banner.NONE;
        }
        AhkFileRules.Style style = AhkFileRules.style(rule, name);
        if (style == null) {
            bannerFailed = true;
            errors.add(AhkBundle.message("deploy.banner.prefix",
                    AhkBundle.message("deploy.where", rule == null ? 0 : rule.line(), rule == null ? relative
                            : rule.pattern().text()), relative));
            return Banner.NONE;
        }
        // from here on the banner counts as written, also if all its lines are left out
        bannerApplied = true;
        Path project = root.getFileName();
        AhkVersionDirective.Line version = versionLine(file, style);
        String value = version == null || version.computed() ? null : version.value();
        String hash = AhkBanner.uses(bannerText, "{commit}") ? commit.apply(file) : null;
        List<String> lines = AhkBanner.lines(bannerText, style, new AhkBanner.Values(
                project == null ? root.toString() : project.toString(), root.toString(), relative, file.toString(),
                value, hash, timestamp, include(target, libraries), computer));
        // every line left out (all placeholders empty): no banner
        if (lines.isEmpty()) return Banner.NONE;
        // the version is in the banner: the own line @SetVersion of other files is left out
        boolean drop = version != null && !AhkFileRules.isAutoHotkey(name)
                && AhkBanner.uses(bannerText, "{version}");
        return new Banner(lines, drop ? version.line() : -1);
    }

    /**
     * The text appended to the copy of an executable ({@link AhkExeOrigin}), {@code null} = none (see class comment).
     */
    private @Nullable String origin(Path file, Path root, boolean ifMissing, Collection<Path> willExist) {
        if (ifMissing || !config.embedOrigin()
                || !file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".exe")
                || !Files.isRegularFile(AhkDeployer.scriptOf(file))) {
            return null;
        }
        // an executable about to be compiled is replaced: its old signature says nothing
        if (!contains(willExist, file) && AhkExeOrigin.isSigned(file)) {
            addOnce(hints, AhkBundle.message("deploy.origin.signed", root.relativize(file).toString()));
        }
        return AhkExeOrigin.block(root, computer);
    }

    /** Adds the text to the list unless it is already there. */
    private static void addOnce(@NotNull List<String> list, @NotNull String text) {
        if (!list.contains(text)) list.add(text);
    }

    /** The only version line of the file ({@link AhkVersionDirective#findIn}), {@code null} without exactly one. */
    private static @Nullable AhkVersionDirective.Line versionLine(Path file, AhkFileRules.Style style) {
        try {
            List<AhkVersionDirective.Line> lines = AhkVersionDirective.findIn(file.getFileName().toString(),
                    AhkDeployer.readText(file), style);
            return lines.size() == 1 ? lines.get(0) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Is the source as written allowed: relative, no {@code ..}, wildcards only in the file name? */
    private static boolean validSource(String source) {
        int slash = source.lastIndexOf('\\');
        String folderPart = slash < 0 ? "" : source.substring(0, slash);
        String namePart = source.substring(slash + 1);
        return !(source.startsWith("\\") || source.contains(":") || folderPart.contains("*") || folderPart.contains("?")
                || namePart.isEmpty() || hasDotDot(source));
    }

    private void addEntry(AhkDeployConfig.Entry entry, Path root, Function<String, String> env,
                          boolean hasDestination, Collection<Path> willExist) {
        String where = AhkBundle.message("deploy.where", entry.line(), entry.source());
        // source
        String source = entry.source().replace('/', '\\');
        int slash = source.lastIndexOf('\\');
        String folderPart = slash < 0 ? "" : source.substring(0, slash);
        String namePart = source.substring(slash + 1);
        if (!validSource(source)) {
            errors.add(AhkBundle.message("deploy.source.invalid", where));
            return;
        }
        Path folder = path(folderPart.isEmpty() ? root.toString() : root.resolve(folderPart).toString());
        if (folder == null) {
            errors.add(AhkBundle.message("deploy.source.invalid", where));
            return;
        }
        boolean wildcard = namePart.contains("*") || namePart.contains("?");
        List<Path> sources = new ArrayList<>();
        if (wildcard) {
            sources.addAll(match(folder.normalize(), namePart, willExist));
            if (sources.isEmpty()) {
                errors.add(AhkBundle.message("deploy.source.no.match", where));
                return;
            }
        } else {
            Path file = folder.resolve(namePart).normalize();
            if (Files.isDirectory(file)) {
                errors.add(AhkBundle.message("deploy.source.folder", where));
                return;
            }
            if (!Files.isRegularFile(file) && !contains(willExist, file)) {
                errors.add(AhkBundle.message("deploy.source.missing", where));
                return;
            }
            sources.add(file);
        }
        // target
        String text = expand(entry.target(), env, destination, errors, where);
        if (text == null) return;
        boolean folderTarget = text.endsWith("\\") || text.endsWith("/") || wildcard || endsWithDots(text);
        Path target = path(text);
        if (target == null || (!target.isAbsolute() && target.getRoot() != null)) {
            errors.add(AhkBundle.message("deploy.target.invalid", where, text));
            return;
        }
        if (!target.isAbsolute()) {
            if (destination == null) {
                errors.add(hasDestination ? AhkBundle.message("deploy.target.invalid", where, text)
                        : AhkBundle.message("deploy.target.relative", where, text));
                return;
            }
            target = destination.resolve(target);
        }
        target = target.normalize();
        // Destination is always a folder, also before it exists (target "." or "%DESTINATION%")
        if (Files.isDirectory(target) || (destination != null && target.equals(destination.normalize()))) {
            folderTarget = true;
        }
        if (wildcard && Files.isRegularFile(target)) {
            errors.add(AhkBundle.message("deploy.target.is.file", where, target));
            return;
        }
        for (Path file : sources) {
            Path to = folderTarget ? target.resolve(file.getFileName().toString()) : target;
            String key = to.toString().toLowerCase(java.util.Locale.ROOT);
            Integer other = targets.putIfAbsent(key, entry.line());
            if (other != null) {
                errors.add(AhkBundle.message("deploy.target.twice", where, to, other));
                continue;
            }
            Banner banner = banner(file, root, to, entry.ifMissing());
            steps.add(new Step(file, to, entry.ifMissing(), banner.lines(), banner.dropLine(),
                    origin(file, root, entry.ifMissing(), willExist)));
        }
    }

    /**
     * The source of a copy line resolved against the project root if it names an executable ({@code .exe}, any case)
     * without wildcards; {@code null} for other sources and for paths that are not allowed (like a source: relative
     * and never outside the root). Whether the file exists is not checked here.
     */
    static @Nullable Path executable(@NotNull String written, @NotNull Path root) {
        String source = written.replace('/', '\\');
        if (source.isEmpty() || source.startsWith("\\") || source.endsWith("\\") || source.contains(":")
                || source.contains("*") || source.contains("?") || hasDotDot(source)
                || !source.toLowerCase(java.util.Locale.ROOT).endsWith(".exe")) {
            return null;
        }
        Path relative = path(source);
        return relative == null ? null : root.toAbsolutePath().normalize().resolve(relative).normalize();
    }

    /**
     * Is the target of a copy line without wildcards in the source a folder, as the deployment decides it: ending with
     * a separator, {@code .} or {@code ..}, {@code Destination} itself or an existing folder? {@code false} if it
     * cannot be resolved (unknown variable, invalid path, relative without destination).
     *
     * @param written     the target as written in the settings file
     * @param destination the resolved {@code Destination}, {@code null} if there is none
     * @param env         the variables ({@link #variables()})
     */
    public static boolean isFolderTarget(@NotNull String written, @Nullable Path destination,
                                         @NotNull Function<String, String> env) {
        String text = expand(written, env, destination, new ArrayList<>(), "");
        if (text == null || text.isEmpty()) return false;
        if (text.endsWith("\\") || text.endsWith("/") || endsWithDots(text)) return true;
        Path target = path(text);
        if (target == null || (!target.isAbsolute() && target.getRoot() != null)) return false;
        if (!target.isAbsolute()) {
            if (destination == null) return false;
            target = destination.resolve(target);
        }
        target = target.normalize();
        return Files.isDirectory(target) || (destination != null && target.equals(destination.normalize()));
    }

    /** True if the last segment of the path is {@code .} or {@code ..}, which always names a folder. */
    private static boolean endsWithDots(String text) {
        String last = text.substring(Math.max(text.lastIndexOf('\\'), text.lastIndexOf('/')) + 1);
        return last.equals(".") || last.equals("..");
    }

    /** True if a segment of the path is {@code ..}. */
    private static boolean hasDotDot(String source) {
        for (String segment : source.split("\\\\")) {
            if (segment.equals("..")) return true;
        }
        return false;
    }

    /** Files of the folder whose names match the wildcard pattern (case-insensitive), sorted by name. */
    private static List<Path> match(Path folder, String pattern, Collection<Path> willExist) {
        Pattern regex = wildcard(pattern);
        TreeSet<Path> result = new TreeSet<>();
        if (Files.isDirectory(folder)) {
            try (Stream<Path> files = Files.list(folder)) {
                files.filter(Files::isRegularFile)
                        .filter(p -> regex.matcher(p.getFileName().toString()).matches())
                        .forEach(result::add);
            } catch (IOException | RuntimeException ignored) {
                // unreadable folder: no match
            }
        }
        for (Path file : willExist) {
            Path normalized = file.toAbsolutePath().normalize();
            if (folder.equals(normalized.getParent()) && regex.matcher(normalized.getFileName().toString()).matches()) {
                result.add(normalized);
            }
        }
        return new ArrayList<>(result);
    }

    /** Regular expression for a file name with {@code *} and {@code ?}. */
    static @NotNull Pattern wildcard(@NotNull String pattern) {
        StringBuilder regex = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
    }

    /** Is the file (case-insensitive, as on Windows) among the paths? */
    private static boolean contains(Collection<Path> paths, Path file) {
        for (Path path : paths) {
            if (path.toAbsolutePath().normalize().toString().equalsIgnoreCase(file.toString())) return true;
        }
        return false;
    }

    /** The path, or {@code null} if the text is no valid path. */
    private static @Nullable Path path(String text) {
        try {
            return Path.of(text);
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /**
     * Resolves {@code %DESTINATION%} (only if {@code destination} is given), other {@code %NAME%} from {@code env} and
     * {@code %%}; an unknown variable or a single {@code %} is added to {@code errors} and gives {@code null}.
     */
    static @Nullable String expand(@NotNull String text, @NotNull Function<String, String> env,
                                   @Nullable Path destination, @NotNull List<String> errors, @NotNull String where) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c != '%') {
                result.append(c);
                i++;
                continue;
            }
            int end = text.indexOf('%', i + 1);
            if (end < 0) {
                errors.add(AhkBundle.message("deploy.percent.single", where, text));
                return null;
            }
            String name = text.substring(i + 1, end);
            if (name.isEmpty()) {
                result.append('%');
            } else if (name.equalsIgnoreCase("DESTINATION") && destination != null) {
                result.append(destination);
            } else if (name.equalsIgnoreCase("DESTINATION") && "Destination".equals(where)) {
                errors.add(AhkBundle.message("deploy.variable.unknown", where, name));
                return null;
            } else {
                String value = name.equalsIgnoreCase("DESTINATION") ? null : env.apply(name);
                if (value == null) {
                    errors.add(name.equalsIgnoreCase("DESTINATION")
                            ? AhkBundle.message("deploy.destination.missing", where)
                            : name.equalsIgnoreCase(LIBRARY_PROJECTS)
                            ? AhkBundle.message("deploy.library.folder.missing", where)
                            : AhkBundle.message("deploy.variable.unknown", where, name));
                    return null;
                }
                result.append(value);
            }
            i = end + 1;
        }
        return result.toString();
    }
}
