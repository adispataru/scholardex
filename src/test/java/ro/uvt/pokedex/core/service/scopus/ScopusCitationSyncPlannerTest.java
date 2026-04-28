package ro.uvt.pokedex.core.service.scopus;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.tasks.ScopusCitationsUpdate;
import ro.uvt.pokedex.core.service.scopus.dto.CitationsByEidRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScopusCitationSyncPlannerTest {

    private final ScopusCitationSyncPlanner planner = new ScopusCitationSyncPlanner();

    @Test
    void citedPublicationIdsKeepOnlyAuthorPublicationIds() {
        assertEquals(
                List.of("p1", "p2"),
                planner.citedPublicationIds(List.of(
                        publication("p1", "eid-1", "2024-01-01"),
                        publication(null, "eid-missing-id", "2024-01-01"),
                        publication("p2", null, "2024-01-01")
                ))
        );
    }

    @Test
    void citingPublicationIdsKeepOnlyDistinctCitationCitingIdsInEncounterOrder() {
        assertEquals(
                List.of("c1", "c2"),
                planner.citingPublicationIds(List.of(
                        citation("p1", "c1"),
                        citation("p1", null),
                        citation("p2", "c2"),
                        citation("p3", "c1")
                ))
        );
    }

    @Test
    void computeEidLastCitationDatesReturnsEmptyWhenAuthorHasNoPublications() {
        Map<String, String> result = planner.computeEidLastCitationDates(
                List.of(),
                List.of(citation("p1", "c1")),
                List.of(publication("c1", "citing-1", "2025-01-01"))
        );

        assertTrue(result.isEmpty());
    }

    @Test
    void computeEidLastCitationDatesInitializesKnownEidsAndUsesLatestValidCitingDate() {
        Map<String, String> result = planner.computeEidLastCitationDates(
                List.of(
                        publication("p1", "eid-1", "2020"),
                        publication("p2", "eid-2", "2021"),
                        publication("p3", null, "2022")
                ),
                List.of(
                        citation("p1", "c1"),
                        citation("p1", "c2"),
                        citation("p2", "c3"),
                        citation("p3", "c1")
                ),
                List.of(
                        publication("c1", "citing-1", "2024-06"),
                        publication("c2", "citing-2", "2025-03-10"),
                        publication("c3", "citing-3", "not-a-date")
                )
        );

        assertEquals("2025-03-10", result.get("eid-1"));
        assertNull(result.get("eid-2"));
        assertFalse(result.containsKey(null));
    }

    @Test
    void computeEidLastCitationDatesSkipsMissingRowsAndBlankDates() {
        Map<String, String> result = planner.computeEidLastCitationDates(
                List.of(publication("p1", "eid-1", "2020")),
                List.of(
                        citation("missing-cited", "c1"),
                        citation("p1", "missing-citing"),
                        citation("p1", "c2"),
                        citation("p1", "c3")
                ),
                List.of(
                        publication("c1", "citing-1", "2025-01-01"),
                        publication("c2", "citing-2", " "),
                        publication("c3", "citing-3", "2024")
                )
        );

        assertEquals("2024-01-01", result.get("eid-1"));
    }

    @Test
    void resolveEidLastDatesForFullModeClearsDateFilters() {
        ScopusCitationsUpdate task = citationTask("FULL", null);

        Map<String, String> result = planner.resolveEidLastDates(task, Map.of(
                "eid-1", "2025-03-10",
                "eid-2", "2024-01-01"
        ));

        assertEquals(Map.of("eid-1", "", "eid-2", ""), result);
    }

    @Test
    void resolveEidLastDatesForPeriodModeCapsMissingAndOlderDatesToPeriodStart() {
        ScopusCitationsUpdate task = citationTask("PERIOD", 2022);

        Map<String, String> result = planner.resolveEidLastDates(task, Map.of(
                "eid-1", "2025-03-10",
                "eid-2", "2020-01-01",
                "eid-3", ""
        ));

        assertEquals("2025-03-10", result.get("eid-1"));
        assertEquals("2022-01-01", result.get("eid-2"));
        assertEquals("2022-01-01", result.get("eid-3"));
    }

    @Test
    void resolveEidLastDatesForDefaultModeKeepsComputedDates() {
        ScopusCitationsUpdate task = citationTask(null, null);
        Map<String, String> computed = Map.of("eid-1", "2025-03-10");

        Map<String, String> result = planner.resolveEidLastDates(task, computed);

        assertEquals(computed, result);
        assertNotSame(computed, result);
    }

    @Test
    void buildRequestPopulatesDefaultsAndEidDates() {
        CitationsByEidRequest request = planner.buildRequest(Map.of("eid-1", "2025-03-10"), 125);

        assertNotNull(request.getRequestId());
        assertFalse(request.getRequestId().isBlank());
        assertEquals(Map.of("eid-1", "2025-03-10"), request.getEidLastDate());
        assertEquals(125, request.getPageSizePerEid());
        assertTrue(request.isIncludeEnrichment());
    }

    @Test
    void buildRequestUsesSchedulerDefaultPageSize() {
        CitationsByEidRequest request = planner.buildRequest(Map.of("eid-1", ""));

        assertNotNull(request);
        assertEquals(100, request.getPageSizePerEid());
        assertEquals(Map.of("eid-1", ""), request.getEidLastDate());
        assertTrue(request.isIncludeEnrichment());
    }

    private ScopusCitationsUpdate citationTask(String syncMode, Integer startYear) {
        ScopusCitationsUpdate task = new ScopusCitationsUpdate();
        task.setSyncMode(syncMode);
        task.setStartYear(startYear);
        return task;
    }

    private ScholardexPublicationView publication(String id, String eid, String coverDate) {
        ScholardexPublicationView publication = new ScholardexPublicationView();
        publication.setId(id);
        publication.setEid(eid);
        publication.setCoverDate(coverDate);
        return publication;
    }

    private ScholardexCitationView citation(String citedId, String citingId) {
        ScholardexCitationView citation = new ScholardexCitationView();
        citation.setCitedId(citedId);
        citation.setCitingId(citingId);
        return citation;
    }
}
