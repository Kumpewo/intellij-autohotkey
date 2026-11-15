package de.kumpewo.autohotkey;

import com.intellij.DynamicBundle;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.PropertyKey;

/**
 * Access to all texts the user sees; they are stored in English in {@code messages/AhkBundle.properties}.
 * New visible texts always go there, never directly into the code.
 * Note: texts with parameters ({0}) are passed through MessageFormat, so an apostrophe must be written as
 * {@code ''} there.
 */
public final class AhkBundle {

    private static final String BUNDLE = "messages.AhkBundle";

    private AhkBundle() {
    }

    /** Holder idiom: extension classes must not use static initialization. */
    private static final class Holder {
        static final DynamicBundle INSTANCE = new DynamicBundle(AhkBundle.class, BUNDLE);
    }

    /** Returns the text for {@code key} with the parameters filled in. */
    public static @Nls @NotNull String message(@PropertyKey(resourceBundle = BUNDLE) @NotNull String key,
                                               Object @NotNull ... params) {
        return Holder.INSTANCE.getMessage(key, params);
    }
}
