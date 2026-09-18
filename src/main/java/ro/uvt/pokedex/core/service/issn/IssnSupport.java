package ro.uvt.pokedex.core.service.issn;

/** ISSN shape and check digit (ISO 3297): eight characters, the last a mod-11 check digit where 10 is written X. */
public final class IssnSupport {

    private IssnSupport() {
    }

    /** {@code NNNN-NNNC} in upper case, or null when the input is not eight ISSN characters. */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String compact = raw.replaceAll("[^0-9Xx]", "").toUpperCase();
        if (compact.length() != 8 || compact.substring(0, 7).contains("X")) {
            return null;
        }
        return compact.substring(0, 4) + "-" + compact.substring(4);
    }

    /** True when the value has the ISSN shape AND its check digit is right — catches typos, not unassigned numbers. */
    public static boolean isValid(String raw) {
        String issn = normalize(raw);
        if (issn == null) {
            return false;
        }
        String digits = issn.replace("-", "");
        int sum = 0;
        for (int i = 0; i < 7; i++) {
            sum += (digits.charAt(i) - '0') * (8 - i);
        }
        int check = (11 - (sum % 11)) % 11;
        char expected = check == 10 ? 'X' : (char) ('0' + check);
        return digits.charAt(7) == expected;
    }
}
