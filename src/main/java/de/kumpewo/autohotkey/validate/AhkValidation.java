package de.kumpewo.autohotkey.validate;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Results of the last validation with {@code AutoHotkey /validate} (AhkValidator), per file. A result is valid
 * only as long as the document has not changed since ({@link Result#stamp}); after any edit it is ignored.
 * Read by AhkValidationAnnotator (error from AutoHotkey) and AhkSyntaxErrorAnnotator (hint "AutoHotkey accepts this
 * code"). Light service ({@code @Service}), therefore not registered in {@code plugin.xml}.
 */
@Service(Service.Level.PROJECT)
public final class AhkValidation {

    /**
     * Result for one file.
     *
     * @param source   the validated script (the error may lie in a file it includes, the result may belong to an
     *                 included file)
     * @param stamp    modification stamp of the file's document at the time of the validation
     * @param accepted true if AutoHotkey found no error (for the validated script and for the file the validation
     *                 was started from, if the script includes it)
     * @param line     line of the error (1-based), 0 = no error in this file
     * @param message  error message of AutoHotkey, empty without error
     */
    public record Result(@NotNull VirtualFile source, long stamp, boolean accepted, int line, @NotNull String message) {
    }

    private final Map<VirtualFile, Result> results = new ConcurrentHashMap<>();
    private final Set<VirtualFile> running = ConcurrentHashMap.newKeySet();

    /** The instance of the project. */
    public static @NotNull AhkValidation getInstance(@NotNull Project project) {
        return project.getService(AhkValidation.class);
    }

    /**
     * Replaces the results of an earlier validation of {@code source} with the new ones. Returns the files whose
     * result changed (old and new ones), whose highlighting must be refreshed.
     */
    public @NotNull Set<VirtualFile> store(@NotNull VirtualFile source, @NotNull Map<VirtualFile, Result> newResults) {
        Set<VirtualFile> changed = new HashSet<>(newResults.keySet());
        results.entrySet().removeIf(entry -> {
            if (!entry.getValue().source().equals(source)) return false;
            changed.add(entry.getKey());
            return true;
        });
        results.putAll(newResults);
        return changed;
    }

    /** The result for the file, if there is one and the file has not changed since. */
    public @Nullable Result current(@NotNull PsiFile file) {
        VirtualFile virtualFile = file.getOriginalFile().getVirtualFile();
        if (virtualFile == null) return null;
        Result result = results.get(virtualFile);
        if (result == null) return null;
        Document document = PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
        return document != null && document.getModificationStamp() == result.stamp() ? result : null;
    }

    /** True if AutoHotkey accepted the unchanged file in its last validation. */
    public static boolean isAccepted(@NotNull PsiFile file) {
        Result result = getInstance(file.getProject()).current(file);
        return result != null && result.accepted();
    }

    /** Marks a validation of the file as running; false if one is already running. */
    boolean start(@NotNull VirtualFile file) {
        return running.add(file);
    }

    /** Marks the validation of the file as finished. */
    void finish(@NotNull VirtualFile file) {
        running.remove(file);
    }
}
