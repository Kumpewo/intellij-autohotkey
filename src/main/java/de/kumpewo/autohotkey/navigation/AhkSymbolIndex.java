package de.kumpewo.autohotkey.navigation;

import com.intellij.util.indexing.DataIndexer;
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter;
import com.intellij.util.indexing.FileBasedIndex;
import com.intellij.util.indexing.FileContent;
import com.intellij.util.indexing.ID;
import com.intellij.util.indexing.ScalarIndexExtension;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.KeyDescriptor;
import com.intellij.openapi.vfs.VirtualFile;
import de.kumpewo.autohotkey.AhkFileType;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/**
 * Index of the symbol names of all AutoHotkey files ({@link AhkSymbols#collect}): name (as written) to the files
 * that define it. Used by Go to Symbol and Go to Class; the elements themselves are collected from the files found.
 * Validation copies ({@code *.~validate.ahk}) are not indexed.
 */
public class AhkSymbolIndex extends ScalarIndexExtension<String> {

    /** Id of the index. */
    public static final ID<String, Void> NAME = ID.create("de.kumpewo.autohotkey.symbols");

    /** Id of the index. */
    @Override
    public @NotNull ID<String, Void> getName() {
        return NAME;
    }

    /** Names of the symbols in the file. */
    @Override
    public @NotNull DataIndexer<String, Void, FileContent> getIndexer() {
        return content -> {
            Map<String, Void> result = new HashMap<>();
            for (AhkSymbols.Symbol symbol : AhkSymbols.collect(content.getPsiFile())) {
                result.put(symbol.name(), null);
            }
            return result;
        };
    }

    /** Names are stored as strings. */
    @Override
    public @NotNull KeyDescriptor<String> getKeyDescriptor() {
        return EnumeratorStringDescriptor.INSTANCE;
    }

    /** Version of the index; increase when {@link AhkSymbols#collect} changes. */
    @Override
    public int getVersion() {
        return 1;
    }

    /** AutoHotkey files except validation copies. */
    @Override
    public FileBasedIndex.@NotNull InputFilter getInputFilter() {
        return new DefaultFileTypeSpecificInputFilter(AhkFileType.INSTANCE) {
            @Override
            public boolean acceptInput(@NotNull VirtualFile file) {
                return !isValidationCopy(file.getName());
            }
        };
    }

    /** The index reads the content of the files. */
    @Override
    public boolean dependsOnFileContent() {
        return true;
    }

    /** Is it a copy written by Validate ({@code name.~validate.ahk})? */
    static boolean isValidationCopy(@NotNull String fileName) {
        return fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".~validate.ahk");
    }
}
