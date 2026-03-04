package ro.uvt.pokedex.core.service.application;

import ro.uvt.pokedex.core.model.scopus.Publication;

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

    public static Comparator<Publication> publicationComparator() {
        return Comparator
                .comparingLong(PublicationOrderingSupport::dateSortKey).reversed()
                .thenComparing(PublicationOrderingSupport::titleSortKey)
                .thenComparing(PublicationOrderingSupport::idSortKey);
    }

    public static void sortPublicationsInPlace(List<Publication> publications) {
        publications.sort(publicationComparator());
    }

    private static long dateSortKey(Publication publication) {
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

    private static String titleSortKey(Publication publication) {
        return Objects.toString(publication != null ? publication.getTitle() : null, "")
                .toLowerCase(Locale.ROOT);
    }

    private static String idSortKey(Publication publication) {
        return Objects.toString(publication != null ? publication.getId() : null, "");
    }
}
