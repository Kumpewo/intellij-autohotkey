package de.kumpewo.autohotkey.formatter;

import com.intellij.lang.Commenter;
import org.jetbrains.annotations.Nullable;

/** Comment characters for "Comment with Line/Block Comment" (Ctrl+/, Ctrl+Shift+/). */
public class AhkCommenter implements Commenter {

    /** Line comment. */
    @Nullable
    @Override
    public String getLineCommentPrefix() {
        return ";"; // the usual comment character of AutoHotkey v2
    }

    /** Start of a block comment. */
    @Nullable
    @Override
    public String getBlockCommentPrefix() {
        return "/*"; // block comments
    }

    /** End of a block comment. */
    @Nullable
    @Override
    public String getBlockCommentSuffix() {
        return "*/"; // block comments
    }

    /** Not supported (block comments cannot be nested). */
    @Nullable
    @Override
    public String getCommentedBlockCommentPrefix() {
        return null;
    }

    /** Not supported (block comments cannot be nested). */
    @Nullable
    @Override
    public String getCommentedBlockCommentSuffix() {
        return null;
    }
}
