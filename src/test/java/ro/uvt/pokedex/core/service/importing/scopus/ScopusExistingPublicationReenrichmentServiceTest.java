package ro.uvt.pokedex.core.service.importing.scopus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusPublicationFactRepository;
import ro.uvt.pokedex.core.service.application.ScholardexProjectionDirtyService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H82 — the narrow re-enrichment pass that makes a FULL Scopus sync heal precedence-governed fields
 * (coverDate/coverDisplayDate) on already-imported publications, which payload-hash dedupe otherwise
 * keeps out of the canonicalization pipeline entirely.
 */
@ExtendWith(MockitoExtension.class)
class ScopusExistingPublicationReenrichmentServiceTest {

    @Mock
    private ScholardexPublicationFactRepository canonicalRepo;
    @Mock
    private ScopusPublicationFactRepository scopusRepo;
    @Mock
    private ScholardexProjectionDirtyService dirtyService;

    private ScopusExistingPublicationReenrichmentService service() {
        return new ScopusExistingPublicationReenrichmentService(canonicalRepo, scopusRepo, dirtyService);
    }

    private static ScopusPublicationFact scopusFact(String eid, String coverDate) {
        ScopusPublicationFact f = new ScopusPublicationFact();
        f.setEid(eid);
        f.setCoverDate(coverDate);
        f.setCoverDisplayDate(coverDate == null ? null : "April " + coverDate.substring(0, 4));
        return f;
    }

    private static ScholardexPublicationFact canonicalFact(String eid, String coverDate) {
        ScholardexPublicationFact f = new ScholardexPublicationFact();
        f.setId("spub_" + eid);
        f.setEid(eid);
        f.setCoverDate(coverDate);
        f.setSourceBatchId("batch-orig");
        return f;
    }

    @Test
    void claimsScopusCoverDateOnDriftedExistingPublication() {
        // The FGCS shape: OpenAlex first-online 2008 stuck on the canonical fact, Scopus says 2009.
        when(scopusRepo.findByEidIn(anyCollection())).thenReturn(List.of(scopusFact("e1", "2009-01-01")));
        ScholardexPublicationFact canonical = canonicalFact("e1", "2008-01-01");
        when(canonicalRepo.findAllByEidIn(anyCollection())).thenReturn(List.of(canonical));

        int changed = service().reclaimPrecedenceFields(List.of("e1"), "test");

        assertEquals(1, changed);
        assertEquals("2009-01-01", canonical.getCoverDate());
        assertEquals("April 2009", canonical.getCoverDisplayDate());
        verify(canonicalRepo).saveAll(List.of(canonical));
        verify(dirtyService).markDirty(
                eq(ScholardexEntityType.PUBLICATION), eq("spub_e1"), eq("batch-orig"),
                eq(null), eq(null), eq("test"));
    }

    @Test
    void leavesMatchingAndScopusDatelessPublicationsAlone() {
        when(scopusRepo.findByEidIn(anyCollection())).thenReturn(List.of(
                scopusFact("same", "2020-01-01"),
                scopusFact("dateless", null)));
        when(canonicalRepo.findAllByEidIn(anyCollection())).thenReturn(List.of(
                canonicalFact("same", "2020-01-01"),
                canonicalFact("dateless", "2019-01-01"),
                canonicalFact("openalex-only", "2018-01-01"))); // no scopus fact at all

        int changed = service().reclaimPrecedenceFields(List.of("same", "dateless", "openalex-only"), "test");

        assertEquals(0, changed);
        verify(canonicalRepo, never()).saveAll(any());
        verify(dirtyService, never()).markDirty(any(), any(), any(), any(), any(), any());
    }

    @Test
    void emptyEidSetIsANoOp() {
        assertEquals(0, service().reclaimPrecedenceFields(List.of(), "test"));
        verify(scopusRepo, never()).findByEidIn(anyCollection());
    }
}
