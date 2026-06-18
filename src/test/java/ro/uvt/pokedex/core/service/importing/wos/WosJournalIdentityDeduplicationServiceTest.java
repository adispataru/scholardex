package ro.uvt.pokedex.core.service.importing.wos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosCategoryFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosCoverageFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosJournalIdentity;
import ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact;
import ro.uvt.pokedex.core.repository.reporting.WosCategoryFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosCoverageFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosJournalIdentityRepository;
import ro.uvt.pokedex.core.repository.reporting.WosMetricFactRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WosJournalIdentityDeduplicationServiceTest {

    @Mock private WosJournalIdentityRepository journalIdentityRepository;
    @Mock private WosMetricFactRepository metricFactRepository;
    @Mock private WosCategoryFactRepository categoryFactRepository;
    @Mock private WosCoverageFactRepository coverageFactRepository;

    private WosJournalIdentityDeduplicationService service() {
        return new WosJournalIdentityDeduplicationService(
                journalIdentityRepository, metricFactRepository, categoryFactRepository, coverageFactRepository);
    }

    private WosJournalIdentity identity(String id, String primary, String eIssn, String normTitle,
                                        List<String> aliases, Instant createdAt) {
        WosJournalIdentity i = new WosJournalIdentity();
        i.setId(id);
        i.setPrimaryIssn(primary);
        i.setEIssn(eIssn);
        i.setNormalizedTitle(normTitle);
        i.setTitle(normTitle);
        i.setAliasIssns(new ArrayList<>(aliases));
        i.setCreatedAt(createdAt);
        return i;
    }

    private WosMetricFact metric(String journalId, Integer year, MetricType type) {
        WosMetricFact f = new WosMetricFact();
        f.setId(journalId + "_" + year + "_" + type);
        f.setJournalId(journalId);
        f.setYear(year);
        f.setMetricType(type);
        return f;
    }

    @Test
    void mergesPrintAndEIssnSwappedDuplicatesOfSameJournal() {
        // The H66B M9 pattern: two identities for one journal with the print/eISSN roles swapped, same
        // normalized title. They share token 18756883 and the title, so they collapse into one canonical
        // identity carrying the union of ISSN tokens; the loser's facts repoint to the winner, and a fact
        // colliding on the winner's unique key (same year+metric — the duplicates describe one journal) is
        // dropped rather than duplicated.
        WosJournalIdentity full = identity("jid_full", "18756891", "18756883", "ijcis", List.of(), Instant.parse("2026-01-01T00:00:00Z"));
        WosJournalIdentity print = identity("jid_print", "18756883", null, "ijcis", List.of("20000004"), Instant.parse("2026-02-01T00:00:00Z"));
        when(journalIdentityRepository.findAll()).thenReturn(List.of(full, print));

        when(metricFactRepository.findAllByJournalId("jid_full")).thenReturn(List.of(metric("jid_full", 2022, MetricType.IF)));
        when(metricFactRepository.findAllByJournalId("jid_print")).thenReturn(List.of(
                metric("jid_print", 2022, MetricType.IF),   // collides on winner key -> dropped
                metric("jid_print", 2023, MetricType.AIS)   // unique -> repointed
        ));

        WosJournalIdentityDeduplicationService.WosIdentityDedupResult result = service().deduplicate();

        assertEquals(1, result.groupsMerged());
        assertEquals(1, result.identitiesMerged());
        assertEquals(1, result.metricFactsRepointed());
        assertEquals(1, result.metricFactsDropped());

        // winner = jid_full (more tokens), saved with the loser's extra alias folded in.
        ArgumentCaptor<WosJournalIdentity> savedWinner = ArgumentCaptor.forClass(WosJournalIdentity.class);
        verify(journalIdentityRepository).save(savedWinner.capture());
        assertEquals("jid_full", savedWinner.getValue().getId());
        assertTrue(savedWinner.getValue().getAliasIssns().contains("20000004"));

        // loser deleted
        ArgumentCaptor<Iterable<WosJournalIdentity>> deleted = ArgumentCaptor.forClass(Iterable.class);
        verify(journalIdentityRepository).deleteAll(deleted.capture());
        List<String> deletedIds = new ArrayList<>();
        deleted.getValue().forEach(i -> deletedIds.add(i.getId()));
        assertEquals(List.of("jid_print"), deletedIds);

        // unique metric repointed to the winner
        ArgumentCaptor<Iterable<WosMetricFact>> savedMetrics = ArgumentCaptor.forClass(Iterable.class);
        verify(metricFactRepository).saveAll(savedMetrics.capture());
        List<WosMetricFact> moved = new ArrayList<>();
        savedMetrics.getValue().forEach(moved::add);
        assertEquals(1, moved.size());
        assertEquals(2023, moved.getFirst().getYear());
        assertEquals("jid_full", moved.getFirst().getJournalId());

        // colliding metric dropped
        ArgumentCaptor<Iterable<WosMetricFact>> deletedMetrics = ArgumentCaptor.forClass(Iterable.class);
        verify(metricFactRepository).deleteAll(deletedMetrics.capture());
        List<WosMetricFact> dropped = new ArrayList<>();
        deletedMetrics.getValue().forEach(dropped::add);
        assertEquals(1, dropped.size());
        assertEquals(2022, dropped.getFirst().getYear());
    }

    @Test
    void doesNotMergeIssnOverlapWhenTitlesDiffer() {
        // The title guard: two journals that merely share an eISSN (sibling/continuation) but carry different
        // normalized titles must NOT collapse — that would corrupt distinct journals.
        WosJournalIdentity a = identity("jid_a", "18756891", "18756883", "european physical journal c", List.of(), Instant.parse("2026-01-01T00:00:00Z"));
        WosJournalIdentity b = identity("jid_b", "01709739", "18756883", "zeitschrift fuer physik c", List.of(), Instant.parse("2026-01-01T00:00:00Z"));
        when(journalIdentityRepository.findAll()).thenReturn(List.of(a, b));

        WosJournalIdentityDeduplicationService.WosIdentityDedupResult result = service().deduplicate();

        assertEquals(0, result.groupsMerged());
        assertEquals(0, result.identitiesMerged());
        verify(journalIdentityRepository, never()).save(any(WosJournalIdentity.class));
        verify(journalIdentityRepository, never()).deleteAll(any());
    }

    @Test
    void leavesUniqueIdentitiesUntouched() {
        WosJournalIdentity a = identity("jid_a", "18756891", null, "journal a", List.of(), Instant.parse("2026-01-01T00:00:00Z"));
        WosJournalIdentity b = identity("jid_b", "01709739", null, "journal b", List.of(), Instant.parse("2026-01-01T00:00:00Z"));
        when(journalIdentityRepository.findAll()).thenReturn(List.of(a, b));

        WosJournalIdentityDeduplicationService.WosIdentityDedupResult result = service().deduplicate();

        assertEquals(2, result.identitiesScanned());
        assertEquals(0, result.groupsMerged());
        verify(journalIdentityRepository, never()).deleteAll(any());
    }
}
