package ro.uvt.pokedex.core.service.scopus;

import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.tasks.ScopusPublicationUpdate;
import ro.uvt.pokedex.core.service.scopus.dto.AuthorWorksRequest;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class ScopusPublicationSyncPlanner {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Clock clock;

    ScopusPublicationSyncPlanner() {
        this(Clock.systemDefaultZone());
    }

    ScopusPublicationSyncPlanner(Clock clock) {
        this.clock = clock;
    }

    String resolveFromDate(ScopusPublicationUpdate task, List<ScholardexPublicationView> authorPublications) {
        String mode = task.getSyncMode();
        if ("FULL".equals(mode)) {
            return null;
        }
        if ("PERIOD".equals(mode) && task.getStartYear() != null) {
            return task.getStartYear() + "-01-01";
        }
        return computeFromDate(authorPublications);
    }

    String computeFromDate(List<ScholardexPublicationView> publications) {
        LocalDate base = publications.stream()
                .map(ScholardexPublicationView::getCoverDate)
                .map(this::parseCoverDate)
                .flatMap(Optional::stream)
                .max(LocalDate::compareTo)
                .orElse(LocalDate.now(clock).minusYears(5));
        return base.minusYears(1).format(ISO);
    }

    Optional<LocalDate> parseCoverDate(String s) {
        try {
            if (s == null || s.isBlank()) return Optional.empty();
            if (s.length() == 10) return Optional.of(LocalDate.parse(s));
            if (s.length() == 7) return Optional.of(LocalDate.parse(s + "-01"));
            if (s.length() == 4) return Optional.of(LocalDate.parse(s + "-01-01"));
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    AuthorWorksRequest buildRequest(String authorId, String fromDate, String cursor, int pageSize) {
        AuthorWorksRequest req = new AuthorWorksRequest();
        req.setRequest_id(UUID.randomUUID().toString());
        req.setAuthor_id(authorId);
        req.setFrom_date(fromDate);
        AuthorWorksRequest.Paging paging = new AuthorWorksRequest.Paging();
        paging.setPage_size(pageSize);
        paging.setCursor(cursor);
        req.setPaging(paging);
        return req;
    }
}
