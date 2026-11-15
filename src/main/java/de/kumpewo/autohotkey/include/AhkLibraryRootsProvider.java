package de.kumpewo.autohotkey.include;

import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.roots.AdditionalLibraryRootsListener;
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider;
import com.intellij.openapi.roots.SyntheticLibrary;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Registers the global AutoHotkey Lib folders ({@link AhkInclude#globalLibraryDirectories}) as the library
 * "AutoHotkey Lib" (project view: "External Libraries"). Reason: for files outside the project the platform only
 * applies the lexer highlighting, no annotator runs. As a library source all annotators run, and the files are in
 * the search scope of the project.
 * Only existing folders; {@code A_ScriptDir\Lib} depends on the script and is usually inside the project anyway.
 */
public final class AhkLibraryRootsProvider extends AdditionalLibraryRootsProvider {

    static final String LIBRARY_NAME = "AutoHotkey Lib";

    /** One synthetic library with all existing global Lib folders (none if there are none). */
    @Override
    public @NotNull Collection<SyntheticLibrary> getAdditionalProjectLibraries(@NotNull Project project) {
        List<VirtualFile> roots = roots();
        return roots.isEmpty() ? List.of() : List.of(new Library(roots));
    }

    /** The platform watches the Lib folders for changes. */
    @Override
    public @NotNull Collection<VirtualFile> getRootsToWatch(@NotNull Project project) {
        return roots();
    }

    /** Existing global Lib folders as VirtualFile (without refresh, may run in a read action). */
    static @NotNull List<VirtualFile> roots() {
        List<VirtualFile> roots = new ArrayList<>();
        for (Path dir : AhkInclude.globalLibraryDirectories()) {
            VirtualFile file = LocalFileSystem.getInstance().findFileByNioFile(dir);
            if (file != null && file.isDirectory() && !roots.contains(file)) roots.add(file);
        }
        return roots;
    }

    /**
     * Runs a change that can move the Lib folders (path to AutoHotkey in the settings) and reports changed roots
     * to all open projects.
     */
    public static void changeRoots(@NotNull Runnable change) {
        List<VirtualFile> before = roots();
        change.run();
        // first bring new folders into the VFS, then compare
        for (Path dir : AhkInclude.globalLibraryDirectories()) LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir);
        List<VirtualFile> after = roots();
        if (before.equals(after)) return;
        ApplicationManager.getApplication().runWriteAction(() -> {
            for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                if (project.isDisposed()) continue;
                AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(project, LIBRARY_NAME, before, after,
                        LIBRARY_NAME);
            }
        });
    }

    /** Library with a name for the project view; equal if the roots are equal. */
    private static final class Library extends SyntheticLibrary implements ItemPresentation {
        private final List<VirtualFile> roots;

        Library(List<VirtualFile> roots) {
            super(LIBRARY_NAME, null);
            this.roots = List.copyOf(roots);
        }

        /** The Lib folders. */
        @Override
        public @NotNull Collection<VirtualFile> getSourceRoots() {
            return roots;
        }

        /** Equal if the roots are equal. */
        @Override
        public boolean equals(Object o) {
            return o instanceof Library other && roots.equals(other.roots);
        }

        /** Hash of the roots. */
        @Override
        public int hashCode() {
            return roots.hashCode();
        }

        /** Name in the project view. */
        @Override
        public @Nullable String getPresentableText() {
            return LIBRARY_NAME;
        }

        /** Default icon. */
        @Override
        public @Nullable Icon getIcon(boolean unused) {
            return null;
        }
    }
}
