package ro.uvt.pokedex.core.service.importing.scopus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.service.application.ScholardexEdgeWriterService;
import ro.uvt.pokedex.core.service.application.ScholardexPublicationWriter;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;
import ro.uvt.pokedex.core.service.openalex.OpenAlexAuthorResolver;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenAlexCanonicalizationServiceTest {

    @Mock private OpenAlexPublicationFactRepository openAlexPublicationFactRepository;
    @Mock private ScholardexPublicationFactRepository scholardexPublicationFactRepository;
    @Mock private ScholardexPublicationWriter publicationWriter;
    @Mock private ScholardexSourceLinkService sourceLinkService;
    @Mock private ScholardexEdgeWriterService edgeWriterService;
    @Mock private ScholardexPublicationCanonicalizationService publicationCanonicalizationService;
    @Mock private OpenAlexAuthorResolver authorResolver;

    @InjectMocks private OpenAlexCanonicalizationService service;

    @Test
    void doiMatchingExistingScopusPublicationLinksWithoutMintingOrClobbering() {
        OpenAlexPublicationFact source = source("W1", "10.1/known", "A paper", "sauth_self");
        ScholardexPublicationFact existing = new ScholardexPublicationFact();
        existing.setId("spub_existing");
        existing.setSource("SCOPUS"); // foreign pub — must not be mutated
        when(openAlexPublicationFactRepository.findAll()).thenReturn(List.of(source));
        when(scholardexPublicationFactRepository.findAllByDoiNormalized("10.1/known")).thenReturn(List.of(existing));

        service.rebuildCanonicalFacts();

        verify(sourceLinkService).link(
                eq(ScholardexEntityType.PUBLICATION), eq("OPENALEX"), eq("W1"), eq("spub_existing"),
                eq("openalex-fact-bridge"), any(), any(), any(), eq(false));
        verify(publicationWriter, never()).upsertAndLinkSource(any(), any(), any());
        // Self-authorship edge to the syncing researcher (not flagged corresponding here).
        verify(edgeWriterService).upsertAuthorshipEdge(
                argThat(cmd -> "spub_existing".equals(cmd.leftId()) && "sauth_self".equals(cmd.rightId())
                        && "OPENALEX".equals(cmd.source())),
                eq(Boolean.FALSE));
    }

    @Test
    void doiMatchingAnOpenAlexOwnedPublicationRefreshesItInPlace() {
        OpenAlexPublicationFact source = source("W9", "10.1/owned", "Updated title", "sauth_self");
        source.setCitedByCount(42);
        ScholardexPublicationFact owned = new ScholardexPublicationFact();
        owned.setId("spub_owned");
        owned.setSource("OPENALEX"); // we minted it
        when(openAlexPublicationFactRepository.findAll()).thenReturn(List.of(source));
        when(scholardexPublicationFactRepository.findAllByDoiNormalized("10.1/owned")).thenReturn(List.of(owned));

        service.rebuildCanonicalFacts();

        verify(publicationWriter).upsertAndLinkSource(
                argThat(fact -> "spub_owned".equals(fact.getId())
                        && Integer.valueOf(42).equals(fact.getCitedByCount())
                        && "Updated title".equals(fact.getTitle())),
                argThat(prov -> "OPENALEX".equals(prov.source()) && "W9".equals(prov.sourceRecordId())),
                eq("openalex-fact-bridge"));
        verify(sourceLinkService, never()).link(any(), anyString(), anyString(), anyString(), anyString(), any(), any(), any(), eq(false));
    }

    @Test
    void doiWithNoExistingPublicationMints() {
        OpenAlexPublicationFact source = source("W2", "10.1/new", "Fresh paper", "sauth_self");
        when(openAlexPublicationFactRepository.findAll()).thenReturn(List.of(source));
        when(scholardexPublicationFactRepository.findAllByDoiNormalized("10.1/new")).thenReturn(List.of());
        when(publicationCanonicalizationService.buildCanonicalPublicationId(
                any(), any(), any(), any(), eq("10.1/new"), any(), any(), any(), any()))
                .thenReturn("spub_minted");

        service.rebuildCanonicalFacts();

        verify(publicationWriter).upsertAndLinkSource(
                argThat(fact -> "spub_minted".equals(fact.getId())
                        && "10.1/new".equals(fact.getDoiNormalized())
                        && "Fresh paper".equals(fact.getTitle())),
                argThat(prov -> "OPENALEX".equals(prov.source()) && "W2".equals(prov.sourceRecordId())),
                eq("openalex-fact-bridge"));
        verify(edgeWriterService).upsertAuthorshipEdge(
                argThat(cmd -> "spub_minted".equals(cmd.leftId()) && "sauth_self".equals(cmd.rightId())),
                eq(Boolean.FALSE));
    }

    @Test
    void correspondingAuthorIsResolvedToCanonicalAuthorAndEdgeFlagged() {
        // A corresponding co-author (distinct from the syncing researcher) resolves to a canonical author id and
        // gets an edge flagged corresponding=true; the syncing researcher gets a normal (corresponding=false) edge.
        OpenAlexPublicationFact source = source("W3", "10.1/coauthored", "Co-authored paper", "sauth_self");
        OpenAlexPublicationFact.CorrespondingAuthorRef ref = new OpenAlexPublicationFact.CorrespondingAuthorRef();
        ref.setDisplayName("Corr Coauthor");
        ref.setOrcid("0000-0002-1825-0097");
        source.setCorrespondingAuthors(List.of(ref));
        ScholardexPublicationFact owned = new ScholardexPublicationFact();
        owned.setId("spub_owned");
        owned.setSource("OPENALEX");
        when(openAlexPublicationFactRepository.findAll()).thenReturn(List.of(source));
        when(scholardexPublicationFactRepository.findAllByDoiNormalized("10.1/coauthored")).thenReturn(List.of(owned));
        when(authorResolver.resolveOrMint(eq(ref), any(), any())).thenReturn("sauth_coauthor");

        service.rebuildCanonicalFacts();

        verify(edgeWriterService).upsertAuthorshipEdge(
                argThat(cmd -> "sauth_coauthor".equals(cmd.rightId())), eq(Boolean.TRUE));
        verify(edgeWriterService).upsertAuthorshipEdge(
                argThat(cmd -> "sauth_self".equals(cmd.rightId())), eq(Boolean.FALSE));
    }

    @Test
    void researcherWhoIsTheCorrespondingAuthorGetsASingleCorrespondingEdge() {
        // ORCID seeding makes the corresponding author resolve to the syncing researcher's own canonical author,
        // so there is ONE edge, flagged corresponding=true — not a duplicate.
        OpenAlexPublicationFact source = source("W4", "10.1/selfcorr", "My own paper", "sauth_self");
        OpenAlexPublicationFact.CorrespondingAuthorRef ref = new OpenAlexPublicationFact.CorrespondingAuthorRef();
        ref.setDisplayName("Me");
        ref.setOrcid("0000-0002-0702-6276");
        source.setCorrespondingAuthors(List.of(ref));
        source.getSyncedResearchers().getFirst().setOrcid("0000-0002-0702-6276");
        ScholardexPublicationFact owned = new ScholardexPublicationFact();
        owned.setId("spub_owned");
        owned.setSource("OPENALEX");
        when(openAlexPublicationFactRepository.findAll()).thenReturn(List.of(source));
        when(scholardexPublicationFactRepository.findAllByDoiNormalized("10.1/selfcorr")).thenReturn(List.of(owned));
        when(authorResolver.resolveOrMint(eq(ref), any(), any())).thenReturn("sauth_self"); // resolves to the researcher

        service.rebuildCanonicalFacts();

        // ORCID seeded onto the researcher's author, and exactly one edge — flagged corresponding.
        verify(authorResolver).attachOrcid("sauth_self", "0000-0002-0702-6276");
        verify(edgeWriterService).upsertAuthorshipEdge(
                argThat(cmd -> "sauth_self".equals(cmd.rightId())), eq(Boolean.TRUE));
        verify(edgeWriterService, never()).upsertAuthorshipEdge(any(), eq(Boolean.FALSE));
    }

    @Test
    void sharedDoiMatchingMultiplePublicationsIsQuarantinedNotGuessed() {
        OpenAlexPublicationFact source = source("W5", "10.1/container", "A chapter", "sauth_self");
        ScholardexPublicationFact a = new ScholardexPublicationFact();
        a.setId("spub_a");
        ScholardexPublicationFact b = new ScholardexPublicationFact();
        b.setId("spub_b");
        when(openAlexPublicationFactRepository.findAll()).thenReturn(List.of(source));
        when(scholardexPublicationFactRepository.findAllByDoiNormalized("10.1/container")).thenReturn(List.of(a, b));

        service.rebuildCanonicalFacts();

        verify(sourceLinkService).markConflict(
                eq(ScholardexEntityType.PUBLICATION), eq("OPENALEX"), eq("W5"),
                eq("OPENALEX_PUBLICATION_DOI_AMBIGUOUS"), any(), any(), any(), eq(false));
        verify(publicationWriter, never()).upsertAndLinkSource(any(), any(), any());
        verify(edgeWriterService, never()).upsertAuthorshipEdge(any(), any());
    }

    private OpenAlexPublicationFact source(String workId, String doiNormalized, String title, String researcherAuthorId) {
        OpenAlexPublicationFact fact = new OpenAlexPublicationFact();
        fact.setSourceRecordId(workId);
        fact.setOpenalexWorkId(workId);
        fact.setDoi(doiNormalized);
        fact.setTitle(title);
        fact.setCoverDate("2020-01-01");
        fact.setCreator(title);
        OpenAlexPublicationFact.SyncedResearcher researcher = new OpenAlexPublicationFact.SyncedResearcher();
        researcher.setCanonicalAuthorId(researcherAuthorId);
        fact.setSyncedResearchers(new java.util.ArrayList<>(List.of(researcher)));
        return fact;
    }
}
