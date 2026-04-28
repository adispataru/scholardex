package ro.uvt.pokedex.core.service.scopus;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.tasks.ScopusPublicationUpdate;
import ro.uvt.pokedex.core.service.scopus.dto.AuthorWorksRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ScopusPublicationSyncPlannerTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-04-28T10:15:30Z"),
            ZoneId.of("UTC")
    );

    private final ScopusPublicationSyncPlanner planner = new ScopusPublicationSyncPlanner(FIXED_CLOCK);

    @Test
    void resolveFromDateReturnsNullForFullMode() {
        ScopusPublicationUpdate task = publicationTask("FULL", null);

        String fromDate = planner.resolveFromDate(task, List.of(publication("2024-06-15")));

        assertNull(fromDate);
    }

    @Test
    void resolveFromDateUsesPeriodStartYearWhenPresent() {
        ScopusPublicationUpdate task = publicationTask("PERIOD", 2021);

        String fromDate = planner.resolveFromDate(task, List.of(publication("2024-06-15")));

        assertEquals("2021-01-01", fromDate);
    }

    @Test
    void resolveFromDateFallsBackToLatestKnownPublicationMinusOneYear() {
        ScopusPublicationUpdate task = publicationTask("SINCE_LAST_UPDATE", null);

        String fromDate = planner.resolveFromDate(task, List.of(
                publication("2023"),
                publication("2024-06"),
                publication("2024-07-15"),
                publication("not-a-date"),
                publication(" ")
        ));

        assertEquals("2023-07-15", fromDate);
    }

    @Test
    void resolveFromDateFallsBackToCurrentDateMinusSixYearsWhenNoPublicationDatesExist() {
        ScopusPublicationUpdate task = publicationTask(null, null);

        String fromDate = planner.resolveFromDate(task, List.of(
                publication(null),
                publication(""),
                publication("2024-13-40")
        ));

        assertEquals("2020-04-28", fromDate);
    }

    @Test
    void parseCoverDateAcceptsDayMonthAndYearPrecision() {
        assertEquals(Optional.of(LocalDate.parse("2024-06-15")), planner.parseCoverDate("2024-06-15"));
        assertEquals(Optional.of(LocalDate.parse("2024-06-01")), planner.parseCoverDate("2024-06"));
        assertEquals(Optional.of(LocalDate.parse("2024-01-01")), planner.parseCoverDate("2024"));
    }

    @Test
    void parseCoverDateRejectsMissingMalformedAndUnsupportedPrecision() {
        assertEquals(Optional.empty(), planner.parseCoverDate(null));
        assertEquals(Optional.empty(), planner.parseCoverDate(" "));
        assertEquals(Optional.empty(), planner.parseCoverDate("2024-06-15T12:00"));
        assertEquals(Optional.empty(), planner.parseCoverDate("2024-13-40"));
    }

    @Test
    void buildRequestPopulatesAuthorWorksDefaultsAndPaging() {
        AuthorWorksRequest request = planner.buildRequest("author-1", "2023-07-15", "cursor-2", 250);

        assertNotNull(request.getRequest_id());
        assertFalse(request.getRequest_id().isBlank());
        assertEquals("author-1", request.getAuthor_id());
        assertEquals("2023-07-15", request.getFrom_date());
        assertTrue(request.isInclude_enrichment());
        assertEquals("legacy", request.getFormat());
        assertEquals(250, request.getPaging().getPage_size());
        assertEquals("cursor-2", request.getPaging().getCursor());
    }

    private ScopusPublicationUpdate publicationTask(String syncMode, Integer startYear) {
        ScopusPublicationUpdate task = new ScopusPublicationUpdate();
        task.setSyncMode(syncMode);
        task.setStartYear(startYear);
        return task;
    }

    private ScholardexPublicationView publication(String coverDate) {
        ScholardexPublicationView publication = new ScholardexPublicationView();
        publication.setCoverDate(coverDate);
        return publication;
    }
}
