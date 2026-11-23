package de.kumpewo.autohotkey.install;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.WritingAccessProvider;
import de.kumpewo.autohotkey.AhkBundle;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Installed copies of library projects ({@link AhkLibraryProjects}) are read-only in the IDE: the editor shows the
 * lock, and typing asks nothing but shows the message. The file on disk is not changed; the copy is replaced by the
 * next deployment of its project.
 */
public class AhkLibraryCopyAccess extends WritingAccessProvider {

    /** The copies among the files cannot be written. */
    @Override
    public @NotNull Collection<VirtualFile> requestWriting(@NotNull Collection<? extends VirtualFile> files) {
        List<VirtualFile> denied = new ArrayList<>();
        for (VirtualFile file : files) {
            if (AhkLibraryProjects.sourceOf(file) != null) denied.add(file);
        }
        return denied;
    }

    /** A copy is never writable. */
    @Override
    public boolean isPotentiallyWritable(@NotNull VirtualFile file) {
        return AhkLibraryProjects.sourceOf(file) == null;
    }

    /** Shown when someone tries to change a copy. */
    @Override
    public String getReadOnlyMessage() {
        return AhkBundle.message("library.copy.read.only");
    }
}
