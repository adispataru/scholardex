package ro.uvt.pokedex.core.service.application;

import java.util.Locale;

/**
 * H87 S3a — CLDR plural categories for the two supported UI languages.
 *
 * <p>Romanian needs THREE forms and the third takes a particle, which a naive "add an s" port gets wrong in a way
 * every Romanian reader notices:</p>
 * <pre>
 *   1  → o publicație        (one)
 *   5  → 5 publicații        (few)
 *   20 → 20 <b>de</b> publicații  (other)
 * </pre>
 *
 * <p>Rules per CLDR:</p>
 * <ul>
 *   <li>ro — one: n = 1; few: n = 0 or (n ≠ 1 and n mod 100 in 1..19); other: everything else.
 *       Note 0 and 101 are <i>few</i> ("0 publicații", "101 publicații"), while 20 and 100 are <i>other</i>.</li>
 *   <li>en — one: n = 1; other: everything else. ({@code few} is never selected, so English bundles may
 *       repeat the {@code other} text in the {@code few} key.)</li>
 * </ul>
 *
 * <p>Kept deliberately small and explicit rather than pulling in ICU4J: two languages, rules that are stable,
 * and a matching implementation on the JS side ({@code frontend/src/modules/shared/i18n.js}) that must agree.</p>
 */
public final class PluralRules {

    private PluralRules() {
    }

    public enum Category {
        ONE("one"),
        FEW("few"),
        OTHER("other");

        private final String suffix;

        Category(String suffix) {
            this.suffix = suffix;
        }

        /** Message-key suffix, e.g. {@code workspace.publications.count} + "." + {@code few}. */
        public String suffix() {
            return suffix;
        }
    }

    public static Category select(Locale locale, long count) {
        String language = locale == null ? "ro" : locale.getLanguage();
        long n = Math.abs(count);
        if ("ro".equals(language)) {
            if (n == 1) {
                return Category.ONE;
            }
            long mod100 = n % 100;
            return (n == 0 || (mod100 >= 1 && mod100 <= 19)) ? Category.FEW : Category.OTHER;
        }
        return n == 1 ? Category.ONE : Category.OTHER;
    }

    /** {@code base} + the category suffix, e.g. {@code landing.welcome.updates.few}. */
    public static String key(String base, Locale locale, long count) {
        return base + "." + select(locale, count).suffix();
    }
}
