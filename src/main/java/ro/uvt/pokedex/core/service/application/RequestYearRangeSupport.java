package ro.uvt.pokedex.core.service.application;

import java.time.Year;

public final class RequestYearRangeSupport {
    private static final int MIN_YEAR = 1900;

    private RequestYearRangeSupport() {
    }

    public record YearRange(int start, int end) {
    }

    public static YearRange parseAndValidate(String startRaw, String endRaw) {
        int currentYear = Year.now().getValue();
        int start;
        int end;
        try {
            start = Integer.parseInt(startRaw);
            end = Integer.parseInt(endRaw);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Year parameters must be integers.", ex);
        }

        if (start < MIN_YEAR || start > currentYear || end < MIN_YEAR || end > currentYear) {
            throw new IllegalArgumentException("Year parameters must be between 1900 and current year.");
        }
        if (start > end) {
            throw new IllegalArgumentException("Start year must be less than or equal to end year.");
        }
        return new YearRange(start, end);
    }
}
