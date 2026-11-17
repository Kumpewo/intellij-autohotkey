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
 * The resolved copy steps of an {@link AhkInstallConfig}, checked before anything happens:
 * <ul>
 *   <li>{@code Destination} must be absolute; environment variables ({@code %NAME%}) are resolved in it.</li>
 *   <li>Targets: {@code %DESTINATION%} (before all environment variables of the same name) and environment variables
 *       of the IDE process are resolved, {@code %%} is a {@code %}; a relative target lies under {@code Destination}
 *       (without one: error). A target is a folder if it ends with {@code \} or {@code /}, if it exists as a folder,
 *       or if the source has a wildcard (then an existing file of that name is an error); the file keeps its
 *       name.</li>
 *   <li>Sources: relative to the tool folder, never outside ({@code ..}, absolute paths and drive letters are
 *       errors); wildcards {@code *} and {@code ?} only in the file name, not recursive, only files. A missing file or
 *       a wildcard without match is an error, unless the file will be created by the compilation
 *       ({@code willExist}).</li>
 *   <li>{@code Message}: variables like the targets, {@code `n} is a line break.</li>
 * </ul>
 */
public final class AhkInstallPlan {

    /**
     * One copy.
     *
     * @param source       absolute source file
     * @param target       absolute target file
     * @param keepExisting only copy if the target does not exist ({@code [KeepExisting]})
     */
    public record Step(@NotNull Path source, @NotNull Path target, boolean keepExisting) {
    }

    private final @Nullable Path destination;
    private final @Nullable String message;
    private final List<Step> steps = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();

    private AhkInstallPlan(@Nullable Path destination, @Nullable String message) {
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

    /** Mistakes found, empty if everything can be copied. */
    public @NotNull List<String> errors() {
        return errors;
    }

    /** The environment of the IDE process with case-insensitive names, as lookup for {@link #create}. */
    public static @NotNull Function<String, String> processEnvironment() {
        Map<String, String> env = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        env.putAll(System.getenv());
        return env::get;
    }

    /**
     * Resolves and checks the configuration.
     *
     * @param config    the read {@code settings.ahk-plugin.ini}
     * @param toolDir   folder of the script and of the {@code settings.ahk-plugin.ini}
     * @param env       environment variables (case-insensitive lookup, {@code null} = undefined)
     * @param willExist files that do not exist yet but will before copying (the compiled executable)
     */
    public static @NotNull AhkInstallPlan create(@NotNull AhkInstallConfig config, @NotNull Path toolDir,
                                                 @NotNull Function<String, String> env,
                                                 @NotNull Collection<Path> willExist) {
        List<String> errors = new ArrayList<>();
        Path destination = null;
        if (config.destination() != null) {
            String text = expand(config.destination(), env, null, errors, "Destination");
            if (text != null) {
                Path path = path(text);
                if (path == null || !path.isAbsolute()) {
                    errors.add(AhkBundle.message("install.destination.relative", config.destination()));
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
        AhkInstallPlan plan = new AhkInstallPlan(destination, message);
        plan.errors.addAll(errors);
        Path dir = toolDir.toAbsolutePath().normalize();
        for (AhkInstallConfig.Entry entry : config.entries()) {
            plan.addEntry(entry, dir, env, config.destination() != null, willExist);
        }
        return plan;
    }

    private void addEntry(AhkInstallConfig.Entry entry, Path toolDir, Function<String, String> env,
                          boolean hasDestination, Collection<Path> willExist) {
        String where = AhkBundle.message("install.where", entry.line(), entry.source());
        // source
        String source = entry.source().replace('/', '\\');
        int slash = source.lastIndexOf('\\');
        String folderPart = slash < 0 ? "" : source.substring(0, slash);
        String namePart = source.substring(slash + 1);
        if (source.startsWith("\\") || source.contains(":") || folderPart.contains("*") || folderPart.contains("?")
                || namePart.isEmpty() || hasDotDot(source)) {
            errors.add(AhkBundle.message("install.source.invalid", where));
            return;
        }
        Path folder = path(folderPart.isEmpty() ? toolDir.toString() : toolDir.resolve(folderPart).toString());
        if (folder == null) {
            errors.add(AhkBundle.message("install.source.invalid", where));
            return;
        }
        boolean wildcard = namePart.contains("*") || namePart.contains("?");
        List<Path> sources = new ArrayList<>();
        if (wildcard) {
            sources.addAll(match(folder.normalize(), namePart, willExist));
            if (sources.isEmpty()) {
                errors.add(AhkBundle.message("install.source.no.match", where));
                return;
            }
        } else {
            Path file = folder.resolve(namePart).normalize();
            if (Files.isDirectory(file)) {
                errors.add(AhkBundle.message("install.source.folder", where));
                return;
            }
            if (!Files.isRegularFile(file) && !contains(willExist, file)) {
                errors.add(AhkBundle.message("install.source.missing", where));
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
            errors.add(AhkBundle.message("install.target.invalid", where, text));
            return;
        }
        if (!target.isAbsolute()) {
            if (destination == null) {
                errors.add(hasDestination ? AhkBundle.message("install.target.invalid", where, text)
                        : AhkBundle.message("install.target.relative", where, text));
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
            errors.add(AhkBundle.message("install.target.is.file", where, target));
            return;
        }
        for (Path file : sources) {
            steps.add(new Step(file, folderTarget ? target.resolve(file.getFileName().toString()) : target,
                    entry.keepExisting()));
        }
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
                errors.add(AhkBundle.message("install.percent.single", where, text));
                return null;
            }
            String name = text.substring(i + 1, end);
            if (name.isEmpty()) {
                result.append('%');
            } else if (name.equalsIgnoreCase("DESTINATION") && destination != null) {
                result.append(destination);
            } else if (name.equalsIgnoreCase("DESTINATION") && "Destination".equals(where)) {
                errors.add(AhkBundle.message("install.variable.unknown", where, name));
                return null;
            } else {
                String value = name.equalsIgnoreCase("DESTINATION") ? null : env.apply(name);
                if (value == null) {
                    errors.add(name.equalsIgnoreCase("DESTINATION")
                            ? AhkBundle.message("install.destination.missing", where)
                            : AhkBundle.message("install.variable.unknown", where, name));
                    return null;
                }
                result.append(value);
            }
            i = end + 1;
        }
        return result.toString();
    }
}
