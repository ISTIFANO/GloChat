package com.marketchat.commerce.catalog.application;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Lower-case, accent-free search terms; drops filler words in FR / EN / AR. */
public final class TextNormalizer {

    private static final Set<String> STOP_WORDS = Set.of(
            "je", "veux", "voudrais", "un", "une", "des", "du", "de", "la", "le", "les", "pour", "avec", "et", "moi",
            "quelque", "chose", "bon", "bonne", "envie", "svp", "stp", "s'il", "plait",
            "i", "want", "a", "an", "the", "some", "for", "with", "and", "me", "please", "something", "good",
            "بغيت", "اريد", "من", "في", "و");

    private TextNormalizer() {
    }

    public static String normalize(String text) {
        String n = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{Nd}\\s]", " ").replaceAll("\\s+", " ").trim();
    }

    public static List<String> terms(String text) {
        return Arrays.stream(normalize(text).split(" "))
                .filter(t -> t.length() > 1 && !STOP_WORDS.contains(t))
                .distinct()
                .toList();
    }
}
