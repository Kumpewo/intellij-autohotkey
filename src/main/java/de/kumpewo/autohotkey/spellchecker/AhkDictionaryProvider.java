package de.kumpewo.autohotkey.spellchecker;

import com.intellij.spellchecker.dictionary.Dictionary;
import com.intellij.spellchecker.dictionary.RuntimeDictionaryProvider;
import de.kumpewo.autohotkey.highlighting.AhkNames;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Dictionary "AutoHotkey" for the spell checker (spellchecker.dictionary.runtimeDictionaryProvider): all built-in
 * names from the name lists (variables, functions, classes, nested classes, methods, properties, directives, key
 * names, Loop subcommands) and their parts ({@code A_LoopField} = loopfield, loop, field; {@code MsgBox} = msgbox,
 * msg, box), plus a few AutoHotkey terms. Comparison ignores case.
 */
public class AhkDictionaryProvider implements RuntimeDictionaryProvider {

    /** The dictionary. */
    @Override
    public Dictionary[] getDictionaries() {
        return new Dictionary[]{Words.DICTIONARY};
    }

    /** The words, built once (holder idiom: no static initialization in the extension class). */
    private static final class Words {
        /** Name lists whose names are words of the dictionary. */
        static final List<String> LISTS = List.of("variables", "functions", "classes", "nested-classes", "methods",
                "properties", "directives", "keys");
        /** AutoHotkey terms that are in no name list. */
        static final List<String> TERMS = List.of("AutoHotkey", "AHK", "hotkey", "hotkeys", "hotstring", "hotstrings",
                "remap", "remapping", "Ahk2Exe", "DBGp", "AutoHotkeyUX", "AutoHotkey64", "AutoHotkey32");
        static final Set<String> WORDS = build();
        static final Dictionary DICTIONARY = new AhkDictionary(WORDS);

        /** All names and their parts in lower case. */
        private static Set<String> build() {
            Set<String> words = new HashSet<>();
            for (String list : LISTS) {
                for (String name : AhkNames.names(list)) add(words, name);
            }
            for (String name : AhkNames.loopSubcommands()) add(words, name);
            for (String term : TERMS) add(words, term);
            return Collections.unmodifiableSet(words);
        }

        /**
         * The name, its parts between non-letters ({@code A_LoopField} = loopfield) and their camel-case parts
         * (loop, field).
         */
        private static void add(Set<String> words, String name) {
            words.add(name.toLowerCase(Locale.ROOT));
            for (String segment : name.split("[^A-Za-z]+")) {
                if (segment.length() > 1) words.add(segment.toLowerCase(Locale.ROOT));
                for (String part : segment.split("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")) {
                    if (part.length() > 1) words.add(part.toLowerCase(Locale.ROOT));
                }
            }
        }
    }

    /** Dictionary over a fixed set of lower-case words. */
    private static final class AhkDictionary implements Dictionary {
        private final Set<String> words;

        AhkDictionary(Set<String> words) {
            this.words = words;
        }

        /** Name shown in the spelling settings. */
        @Override
        public @NotNull String getName() {
            return "AutoHotkey";
        }

        /** Required by the interface (deprecated there); see {@link #lookup}. */
        @SuppressWarnings("removal")
        @Override
        public Boolean contains(@NotNull String word) {
            return words.contains(word.toLowerCase(Locale.ROOT));
        }

        /** Present if the word is a built-in name or part of one, ignoring case. */
        @Override
        public @NotNull LookupStatus lookup(@NotNull String word) {
            return words.contains(word.toLowerCase(Locale.ROOT)) ? LookupStatus.Present : LookupStatus.Absent;
        }

        /** All words. */
        @Override
        public @NotNull Set<String> getWords() {
            return words;
        }
    }
}
