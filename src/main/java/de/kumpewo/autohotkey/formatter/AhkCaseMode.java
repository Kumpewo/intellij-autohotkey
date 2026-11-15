package de.kumpewo.autohotkey.formatter;

import de.kumpewo.autohotkey.AhkBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Switch of a keyword group (keyword-case.txt) or of the built-in names. The English text from AhkBundle is
 * shown; keyword-case.txt may contain the English values ("as in list", "lowercase", "keep"), the German values
 * ("wie Liste", "klein", "nicht aendern") or the name of the constant. The file values are fixed here and not taken
 * from the bundle: while the IDE builds the searchable options index, bundle texts come back with markers.
 */
public enum AhkCaseMode {
    /** Spelling of the list. */
    AS_LIST("case.mode.as.list", "as in list", "wie Liste"),
    /** Everything lower case. */
    LOWER("case.mode.lower", "lowercase", "klein"),
    /** The spelling in the source stays. */
    KEEP("case.mode.keep", "keep", "nicht ändern", "nicht aendern");

    private final String key;
    private final List<String> fileValues;

    AhkCaseMode(String key, String... fileValues) {
        this.key = key;
        this.fileValues = List.of(fileValues);
    }

    /** Text in the dialog (English). */
    public @NotNull String title() {
        return AhkBundle.message(key);
    }

    /** Value from keyword-case.txt or the stored settings, otherwise null. Case-insensitive. */
    public static @Nullable AhkCaseMode parse(@NotNull String text) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        for (AhkCaseMode mode : values()) {
            if (mode.name().equalsIgnoreCase(t) || mode.title().equalsIgnoreCase(t)) return mode;
            for (String value : mode.fileValues) {
                if (value.equalsIgnoreCase(t)) return mode;
            }
        }
        return null;
    }

    /** Text shown in the combo box. */
    @Override
    public String toString() {
        return title();   // shown in the combo box
    }
}
