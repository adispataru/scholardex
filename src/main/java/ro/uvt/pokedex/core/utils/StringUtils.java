package ro.uvt.pokedex.core.utils;

import java.text.Normalizer;

public class StringUtils {

    public static String normalize(String input) {
        return Normalizer.normalize(input, Normalizer.Form.NFD)
                .replaceAll("[^\\p{ASCII}]", "") // Remove diacritic marks
                .toLowerCase(); // Convert to lower case for case-insensitive matching
    }
}

