package ro.uvt.pokedex.core.service.application;

import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;

import java.time.LocalDate;
import java.time.Year;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class PublicationOrderingSupport {

    private PublicationOrderingSupport() {
    }

    public static Comparator<ScholardexPublicationView> publicationComparator() {
        return Comparator
                .comparingLong((ScholardexPublicationView publication) -> dateSortKey(publication)).reversed()
                .thenComparing(PublicationOrderingSupport::titleSortKey)
                .thenComparing(PublicationOrderingSupport::idSortKey);
    }

    public static void sortPublicationsInPlace(List<ScholardexPublicationView> publications) {
        publications.sort(publicationComparator());
    }

    public static void sortScoringPublicationsInPlace(List<? extends ScoringPublicationReadModel> publications) {
        publications.sort(scoringPublicationComparator());
    }

    public static Comparator<ScoringPublicationReadModel> scoringPublicationComparator() {
        return Comparator
                .comparingLong((ScoringPublicationReadModel publication) -> dateSortKey(publication)).reversed()
                .thenComparing(PublicationOrderingSupport::titleSortKey)
                .thenComparing(PublicationOrderingSupport::idSortKey);
    }

    private static long dateSortKey(ScholardexPublicationView publication) {
        if (publication == null) {
            return Long.MIN_VALUE;
        }

        String rawCoverDate = publication.getCoverDate();
        if (rawCoverDate == null) {
            return Long.MIN_VALUE;
        }

        String normalized = rawCoverDate.trim();
        if (normalized.isEmpty()) {
            return Long.MIN_VALUE;
        }

        try {
            if (normalized.length() == 4) {
                int year = Integer.parseInt(normalized);
                return Year.of(year).atDay(1).toEpochDay();
            }
            return LocalDate.parse(normalized).toEpochDay();
        } catch (DateTimeParseException | NumberFormatException ex) {
            return Long.MIN_VALUE;
        }
    }

    private static long dateSortKey(ScoringPublicationReadModel publication) {
        if (publication == null) {
            return Long.MIN_VALUE;
        }
        return dateSortKey(publication.getCoverDate());
    }

    private static long dateSortKey(String rawCoverDate) {
        if (rawCoverDate == null) {
            return Long.MIN_VALUE;
        }

        String normalized = rawCoverDate.trim();
        if (normalized.isEmpty()) {
            return Long.MIN_VALUE;
        }

        try {
            if (normalized.length() == 4) {
                int year = Integer.parseInt(normalized);
                return Year.of(year).atDay(1).toEpochDay();
            }
            return LocalDate.parse(normalized).toEpochDay();
        } catch (DateTimeParseException | NumberFormatException ex) {
            return Long.MIN_VALUE;
        }
    }

    private static String titleSortKey(ScholardexPublicationView publication) {
        return Objects.toString(publication != null ? publication.getTitle() : null, "")
                .toLowerCase(Locale.ROOT);
    }

    private static String titleSortKey(ScoringPublicationReadModel publication) {
        return Objects.toString(publication != null ? publication.getTitle() : null, "")
                .toLowerCase(Locale.ROOT);
    }

    private static String idSortKey(ScholardexPublicationView publication) {
        return Objects.toString(publication != null ? publication.getId() : null, "");
    }

    private static String idSortKey(ScoringPublicationReadModel publication) {
        return Objects.toString(publication != null ? publication.getId() : null, "");
    }
}
