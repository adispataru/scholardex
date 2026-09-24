package ro.uvt.pokedex.core.service.wos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.service.CacheService;
import ro.uvt.pokedex.core.service.application.PublicationEnrichmentLinkerService;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WosAccessionSweepSchedulerTest {

    @Mock private ScholardexPublicationFactRepository publicationFactRepository;
    @Mock private CacheService cacheService;
    @Mock private WosAccessionService wosAccessionService;
    @Mock private PublicationEnrichmentLinkerService linkerService;

    private static ScholardexPublicationFact pub(String id, String doi, String wosId) {
        ScholardexPublicationFact p = new ScholardexPublicationFact();
        p.setId(id);
        p.setEid("eid-" + id);
        p.setDoi(doi);
        p.setWosId(wosId);
        return p;
    }

    @Test
    void asksOnlyForUnresolvedDoisOfUniversityAuthorsDedupedAndBoundedAndLinksWhatItFinds() {
        when(cacheService.getUniversityAuthorIds()).thenReturn(Set.of("a1", "a2"));
        ScholardexPublicationFact shared = pub("p-shared", "10.1/shared", null);
        when(publicationFactRepository.findByAuthorIdsContains("a1"))
                .thenReturn(List.of(shared, pub("p-done", "10.1/done", "WOS:9"), pub("p-nodoi", null, null)));
        when(publicationFactRepository.findByAuthorIdsContains("a2"))
                .thenReturn(List.of(shared, pub("p-two", "10.1/two", ""), pub("p-three", "10.1/three", null)));
        when(wosAccessionService.resolve("10.1/shared")).thenReturn(Optional.of("WOS:1"));
        when(wosAccessionService.resolve("10.1/two")).thenReturn(Optional.empty());
        when(linkerService.linkWosEnrichment(eq("p-shared"), eq("eid-p-shared"), eq("10.1/shared"), eq("WOS:1"),
                eq(WosAccessionSweepScheduler.SOURCE), eq(WosAccessionSweepScheduler.LINKER_VERSION), any()))
                .thenReturn(new PublicationEnrichmentLinkerService.LinkResult(
                        PublicationEnrichmentLinkerService.LinkState.LINKED, "resolved", "p-shared", null));

        WosAccessionSweepScheduler scheduler = new WosAccessionSweepScheduler(
                publicationFactRepository, cacheService, wosAccessionService, linkerService);
        WosAccessionSweepScheduler.Result r = scheduler.sweep(2);

        assertEquals(3, r.candidates(), "shared once, two, three — not the resolved one, not the DOI-less one");
        assertEquals(2, r.asked(), "bounded by the limit");
        assertEquals(1, r.found());
        assertEquals(1, r.linked());
        verify(wosAccessionService, never()).resolve("10.1/three");
        verify(wosAccessionService, never()).resolve("10.1/done");
    }
}
