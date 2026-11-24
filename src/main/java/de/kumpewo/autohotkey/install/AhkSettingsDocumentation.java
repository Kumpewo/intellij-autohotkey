package de.kumpewo.autohotkey.install;

import com.intellij.model.Pointer;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.backend.documentation.DocumentationLinkHandler;
import com.intellij.platform.backend.documentation.DocumentationResult;
import com.intellij.platform.backend.documentation.DocumentationTarget;
import com.intellij.platform.backend.documentation.DocumentationTargetProvider;
import com.intellij.platform.backend.documentation.LinkResolveResult;
import com.intellij.platform.backend.presentation.TargetPresentation;
import com.intellij.psi.PsiFile;
import de.kumpewo.autohotkey.AhkBundle;
import de.kumpewo.autohotkey.documentation.AhkExternalHelp;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Quick documentation in {@code settings.ahk-v2-plugin.ini}, whatever plugin handles INI files: the explanation
 * ({@link AhkSettingsHelp}) of the option, section or file header at the position ({@link AhkSettingsExplain#topicAt})
 * with a link to the manual. Only for Ctrl+Q, not for the mouse: the platform asks the same way for both, so only the
 * position of a caret counts.
 */
public final class AhkSettingsDocumentation implements DocumentationTargetProvider {

    /** Start of the links to a page of the manual. */
    static final String LINK = "autohotkey-manual:";

    /** The page of the manual. */
    private static final String PAGE = "/extra/installer.htm#";

    /** The topic at a caret of a settings file, nothing at other positions (the mouse) and in other files. */
    @Override
    public @NotNull List<? extends DocumentationTarget> documentationTargets(@NotNull PsiFile file, int offset) {
        VirtualFile virtual = file.getOriginalFile().getVirtualFile();
        if (virtual == null || !AhkSettingsNotification.isSettings(virtual)) return List.of();
        if (!isCaret(file, offset)) return List.of();
        String id = AhkSettingsExplain.topicAt(file.getViewProvider().getContents(), offset,
                AhkSettingsNotification.isLibrary(virtual));
        return id == null ? List.of() : List.of(new Target(id));
    }

    /** Is there a caret at the offset in an editor of the file? */
    private static boolean isCaret(@NotNull PsiFile file, int offset) {
        Document document = file.getViewProvider().getDocument();
        return document != null && EditorFactory.getInstance().editors(document, file.getProject())
                .anyMatch(editor -> editor.getCaretModel().getOffset() == offset);
    }

    /** The HTML text of a topic; {@code Deploy}: all options one after the other. */
    static @NotNull String html(@NotNull String id) {
        StringBuilder result = new StringBuilder();
        String anchor;
        if (id.equals("Deploy")) {
            anchor = "Install";
            result.append("<h2>[Deploy]</h2>\n");
            for (String option : AhkSettingsHelp.ids()) {
                if (!option.startsWith(AhkSettingsHelp.OPTION)) continue;
                result.append("<h3>").append(title(option)).append("</h3>\n")
                        .append(AhkSettingsHelp.html(option)).append('\n');
            }
        } else {
            AhkSettingsHelp.Topic topic = AhkSettingsHelp.topic(id);
            anchor = topic == null ? "Ini" : topic.anchor();
            result.append("<h2>").append(title(id)).append("</h2>\n").append(AhkSettingsHelp.html(id)).append('\n');
        }
        return result.append("<p><a href=\"").append(LINK).append(PAGE).append(anchor).append("\">")
                .append(AhkBundle.message("settings.explain.manual")).append("</a></p>").toString();
    }

    /** The heading of a topic: the option, {@code [Section]} or the file name. */
    private static @NotNull String title(@NotNull String id) {
        if (id.startsWith(AhkSettingsHelp.OPTION)) return id.substring(AhkSettingsHelp.OPTION.length());
        return id.startsWith(AhkSettingsHelp.FILE) ? AhkDeployConfig.FILE_NAME : "[" + id + "]";
    }

    /**
     * A topic as target of the quick documentation.
     *
     * @param id id of the topic
     */
    record Target(@NotNull String id) implements DocumentationTarget {

        /** The target holds no data that could become invalid. */
        @Override
        public @NotNull Pointer<? extends DocumentationTarget> createPointer() {
            return Pointer.hardPointer(this);
        }

        /** The heading. */
        @Override
        public @NotNull TargetPresentation computePresentation() {
            return TargetPresentation.builder(title(id)).presentation();
        }

        /** The text. */
        @Override
        public @NotNull DocumentationResult computeDocumentation() {
            return DocumentationResult.documentation(html(id));
        }
    }

    /** Opens the links to the manual in the plugin's help file; the popup keeps its content. */
    public static final class Links implements DocumentationLinkHandler {

        /** Opens the page if the link is one of {@link Target}. */
        @Override
        public @Nullable LinkResolveResult resolveLink(@NotNull DocumentationTarget target, @NotNull String url) {
            if (!(target instanceof Target) || !url.startsWith(LINK)) return null;
            ApplicationManager.getApplication().executeOnPooledThread(
                    () -> AhkExternalHelp.openExtra(null, url.substring(LINK.length())));
            return LinkResolveResult.resolvedTarget(target);
        }
    }
}
