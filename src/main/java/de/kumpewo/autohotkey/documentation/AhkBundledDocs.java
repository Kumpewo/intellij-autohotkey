package de.kumpewo.autohotkey.documentation;

import de.kumpewo.autohotkey.parser.AhkLanguageLevel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * The copy of the AutoHotkey help that ships with the plugin ({@code help/docs/} for v2.0, {@code help/v2.1/docs/}
 * for v2.1), as a fallback for the menu entries "AutoHotkey v2.0 Help" / "AutoHotkey v2.1 Help" when no
 * {@code AutoHotkey.chm} is installed: the files are copied to {@code <temp>/intellij-autohotkey/docs-<version>/}
 * and the start page is opened in the default browser. An existing copy is reused as long as the plugin JAR is
 * unchanged (stamp file {@link #STAMP}).
 */
public final class AhkBundledDocs {

    /** Start page of the help, relative to its root folder. */
    public static final String START = "index.htm";
    /** File in the target folder that records which plugin JAR the copy came from. */
    static final String STAMP = ".stamp";

    private AhkBundledDocs() {}

    /**
     * Files of the help of a level and where they come from.
     *
     * @param files paths relative to the root folder with {@code /} as separator ({@code index.htm},
     *              {@code lib/MsgBox.htm})
     * @param stamp identifies the plugin JAR (path, size, date); {@code null} if the resources are not in a JAR
     *              (development, tests), then a copy is never reused
     */
    public record Listing(@NotNull List<String> files, @Nullable String stamp) {}

    /** Resource folder of the help of the level, without leading and with trailing {@code /} ({@code help/docs/}). */
    public static @NotNull String root(@NotNull AhkLanguageLevel level) {
        String folder = level.dataLevel().folder;
        return folder == null ? "help/docs/" : "help/" + folder + "/docs/";
    }

    /** The files of the help of the level; empty if the plugin has no copy of it. */
    public static @NotNull Listing list(@NotNull AhkLanguageLevel level) throws IOException {
        String root = root(level);
        URL start = AhkBundledDocs.class.getResource("/" + root + START);
        List<String> files = new ArrayList<>();
        if (start == null) return new Listing(files, null);
        String url = start.toString();
        int separator = url.indexOf("!/");
        if (url.startsWith("jar:") && separator > 0) {
            Path jarPath;
            try {
                jarPath = Path.of(new URI(url.substring("jar:".length(), separator)));
            } catch (URISyntaxException | RuntimeException e) {
                throw new IOException(e);
            }
            try (JarFile file = new JarFile(jarPath.toFile())) {
                for (Enumeration<JarEntry> entries = file.entries(); entries.hasMoreElements(); ) {
                    JarEntry entry = entries.nextElement();
                    if (!entry.isDirectory() && entry.getName().startsWith(root)) {
                        files.add(entry.getName().substring(root.length()));
                    }
                }
            }
            String stamp = jarPath + "|" + Files.size(jarPath) + "|" + Files.getLastModifiedTime(jarPath).toMillis();
            return new Listing(files, stamp);
        }
        if ("file".equals(start.getProtocol())) {
            Path base;
            try {
                base = Path.of(start.toURI()).getParent();
            } catch (URISyntaxException | RuntimeException e) {
                throw new IOException(e);
            }
            try (Stream<Path> walk = Files.walk(base)) {
                walk.filter(Files::isRegularFile)
                        .forEach(p -> files.add(base.relativize(p).toString().replace('\\', '/')));
            }
        }
        return new Listing(files, null);
    }

    /** Copies the help of the level to {@code <temp>/intellij-autohotkey/docs-<version>/}, see {@link #extract(AhkLanguageLevel, Path)}. */
    public static @Nullable Path extract(@NotNull AhkLanguageLevel level) throws IOException {
        return extract(level, Path.of(System.getProperty("java.io.tmpdir"), AhkExternalHelp.TEMP_FOLDER,
                "docs-" + level.dataLevel().version));
    }

    /**
     * Copies the help of the level into {@code dir} (existing files are replaced) unless the copy there came from the
     * same plugin JAR, and returns the start page; {@code null} if the plugin has no copy of this help.
     */
    public static @Nullable Path extract(@NotNull AhkLanguageLevel level, @NotNull Path dir) throws IOException {
        Listing listing = list(level);
        if (listing.files().isEmpty()) return null;
        Path start = dir.resolve(START);
        Path stampFile = dir.resolve(STAMP);
        if (listing.stamp() != null && Files.isRegularFile(start) && Files.isRegularFile(stampFile)
                && listing.stamp().equals(Files.readString(stampFile, StandardCharsets.UTF_8))) {
            return start;
        }
        Files.deleteIfExists(stampFile);
        String root = root(level);
        for (String file : listing.files()) {
            try (InputStream in = AhkBundledDocs.class.getResourceAsStream("/" + root + file)) {
                if (in == null) continue;
                Path target = dir.resolve(file);
                Files.createDirectories(target.getParent());
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (listing.stamp() != null) Files.writeString(stampFile, listing.stamp(), StandardCharsets.UTF_8);
        return Files.isRegularFile(start) ? start : null;
    }
}
