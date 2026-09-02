package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationMergeDecision;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.UserService;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H84 S4 — the UVT-authored duplicate sweep: grouping by normalized title (±1 year), the three
 * exclusions from the S4 measurement (generic ≤3-word titles, different-DOI pairs, preprints),
 * richness-picked survivor, idempotent PENDING writes, and dry-run report-only behavior.
 */
@ExtendWith(MockitoExtension.class)
class PublicationMergeSweepServiceTest {

    @Mock private UserService userService;
    @Mock private ResearcherAuthorLookupService researcherAuthorLookupService;
    @Mock private PostgresScholardexProjectionReadPort postgresProjectionReadPort;
    @Mock private PublicationMergeService publicationMergeService;

    @InjectMocks private PublicationMergeSweepService service;

    private final List<ScholardexPublicationView> pubs = new java.util.ArrayList<>();

    @BeforeEach
    void wireResearcherUniverse() {
        User user = new User();
        User.ResearcherProfile profile = new User.ResearcherProfile();
        user.setResearcherProfile(profile);
        lenient().when(userService.findUsersWithResearcherProfile()).thenReturn(List.of(user));
        lenient().when(researcherAuthorLookupService.resolveAuthorLookupKeys(profile)).thenReturn(List.of("sauth_uvt"));
        lenient().when(postgresProjectionReadPort.findPublicationIdsByAuthorIdIn(anyCollection()))
                .thenReturn(Set.of("any"));
        lenient().when(postgresProjectionReadPort.findPublicationsByIdIn(anyCollection()))
                .thenAnswer(inv -> List.copyOf(pubs));
        lenient().when(publicationMergeService.findDecision(any(), any())).thenReturn(Optional.empty());
        lenient().when(publicationMergeService.requestMerge(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new PublicationMergeDecision());
    }

    private ScholardexPublicationView pub(String id, String title, String coverDate, String doi, String eid, int cited) {
        ScholardexPublicationView pub = new ScholardexPublicationView();
        pub.setId(id);
        pub.setTitle(title);
        pub.setCoverDate(coverDate);
        pub.setDoi(doi);
        pub.setEid(eid);
        pub.setCitedByCount(cited);
        pubs.add(pub);
        return pub;
    }

    @Test
    void sameTitlePairIsRequestedWithTheRicherRecordAsSurvivor() {
        pub("spub_scopus", "Datastores in Cloud Governance Frameworks", "2012-12-01", "10.1/x", "2-s2.0-1", 5);
        pub("spub_oa", "Datastores in Cloud Governance Frameworks", "2012-01-01", null, null, 2);

        PublicationMergeSweepService.SweepResult result = service.sweep(false);

        assertEquals(1, result.pairs().size());
        assertTrue(result.pairs().get(0).requested());
        verify(publicationMergeService).requestMerge(
                eq("spub_scopus"), eq("spub_oa"),
                eq(PublicationMergeSweepService.SWEEP_PRINCIPAL), any(), eq(PublicationMergeSweepService.SWEEP_NOTE));
    }

    @Test
    void genericShortTitlesAndDifferentDoisAndPreprintsAreExcluded() {
        // ≤3 words after normalization: initials-level noise.
        pub("g1", "Cloud Computing Projects", "2020-01-01", null, "e1", 0);
        pub("g2", "Cloud Computing Projects", "2020-01-01", null, null, 0);
        // Different DOIs on both sides: genuinely distinct records.
        pub("d1", "A Longer Distinct Record Title Here", "2021-01-01", "10.1/aaa", "e2", 0);
        pub("d2", "A Longer Distinct Record Title Here", "2021-01-01", "10.1/bbb", null, 0);
        // Preprint-vs-published: excluded by decision.
        ScholardexPublicationView preprint = pub("p1", "An Interesting Published Result About Things", "2022-01-01", null, null, 0);
        preprint.setSubtype("preprint");
        pub("p2", "An Interesting Published Result About Things", "2022-01-01", "10.1/ccc", "e3", 1);

        PublicationMergeSweepService.SweepResult result = service.sweep(false);

        assertEquals(0, result.pairs().size());
        verify(publicationMergeService, never()).requestMerge(any(), any(), any(), any(), any());
    }

    @Test
    void yearsFurtherThanOneApartDoNotPair() {
        pub("y1", "Same Title Years Apart Considerably", "2015-01-01", null, "e1", 0);
        pub("y2", "Same Title Years Apart Considerably", "2019-01-01", null, null, 0);

        assertEquals(0, service.sweep(false).pairs().size());
    }

    @Test
    void alreadyDecidedPairsAreReportedButNeverRewritten() {
        pub("a1", "A Previously Reviewed Duplicate Pair Title", "2018-01-01", null, "e1", 0);
        pub("a2", "A Previously Reviewed Duplicate Pair Title", "2018-01-01", null, null, 0);
        when(publicationMergeService.findDecision("a1", "a2")).thenReturn(Optional.of(new PublicationMergeDecision()));

        PublicationMergeSweepService.SweepResult result = service.sweep(false);

        assertEquals(1, result.pairs().size());
        assertTrue(result.pairs().get(0).alreadyDecided());
        verify(publicationMergeService, never()).requestMerge(any(), any(), any(), any(), any());
    }

    @Test
    void dryRunReportsPairsWithoutWriting() {
        pub("r1", "A Dry Run Candidate Pair With Words", "2019-01-01", null, "e1", 0);
        pub("r2", "A Dry Run Candidate Pair With Words", "2019-01-01", null, null, 0);

        PublicationMergeSweepService.SweepResult result = service.sweep(true);

        assertEquals(1, result.pairs().size());
        assertTrue(result.dryRun());
        verify(publicationMergeService, never()).requestMerge(any(), any(), any(), any(), any());
    }
}
