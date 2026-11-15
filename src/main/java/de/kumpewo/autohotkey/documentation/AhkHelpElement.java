package de.kumpewo.autohotkey.documentation;

import com.intellij.psi.PsiElement;
import com.intellij.psi.impl.FakePsiElement;
import org.jetbrains.annotations.Nullable;

/**
 * Target of a link inside a help page in the documentation popup ({@code psi_element://page#anchor}); the
 * documentation provider shows the page from the anchor on.
 */
public class AhkHelpElement extends FakePsiElement {
    private final String page;
    private final String anchor; // without '#', may be null
    private final PsiElement parent;

    public AhkHelpElement(String page, @Nullable String anchor, PsiElement parent) {
        this.page = page;
        this.anchor = anchor;
        this.parent = parent;
    }

    /** Path of the page under {@code /help/}. */
    public String getPage() {
        return page;
    }

    /** Anchor without {@code #}, or {@code null}. */
    public String getAnchor() {
        return anchor;
    }

    /** The element the documentation was opened for. */
    @Override
    public PsiElement getParent() {
        return parent;
    }

    /** The page path. */
    @Override
    public String getName() {
        return page;
    }
}
