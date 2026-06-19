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

    @InjectMocks private OpenAlexCanonicalizationService service;

    @Test
    void doiMatchingExistingPublicationLinksWithoutMintingOrClobbering() {
        OpenAlexPublicationFact source = source("W1", "10.1/known", "A paper", "author-1");
        ScholardexPublicationFact existing = new ScholardexPublicationFact();
        existing.setId("spub_existing");
        existing.setSource("SCOPUS"); // foreign pub — must not be mutated
        when(openAlexPublicationFactRepository.findAll()).thenReturn(List.of(source));
        when(scholardexPublicationFactRepository.findAllByDoiNormalized("10.1/known")).thenReturn(List.of(existing));

        service.rebuildCanonicalFacts();

        // LINK: source-link to the existing pub, no canonical-fact mutation (no mint).
        verify(sourceLinkService).link(
                eq(ScholardexEntityType.PUBLICATION), eq("OPENALEX"), eq("W1"), eq("spub_existing"),
                eq("openalex-fact-bridge"), any(), any(), any(), eq(false));
        verify(publicationWriter, never()).upsertAndLinkSource(any(), any(), any());
        // Self-authorship edge to the syncing researcher, source-scoped (coexists with any Scopus edge).
        verify(edgeWriterService).upsertAuthorshipEdge(argThat(cmd ->
                "spub_existing".equals(cmd.leftId())
                        && "author-1".equals(cmd.rightId())
                        && "OPENALEX".equals(cmd.source())));
    }

    @Test
    void doiMatchingAnOpenAlexOwnedPublicationRefreshesItInPlace() {
        // A re-sync of a DOI'd work OpenAlex previously minted must UPDATE that pub (refresh citedByCount,
        // corresponding authors, title) rather than link-to-self and freeze the data.
        OpenAlexPublicationFact source = source("W9", "10.1/owned", "Updated title", "author-1");
        source.setCitedByCount(42);
        source.setCorrespondingAuthorNames(List.of("Corr Author"));
        ScholardexPublicationFact owned = new ScholardexPublicationFact();
        owned.setId("spub_owned");
        owned.setSource("OPENALEX"); // we minted it
        when(openAlexPublicationFactRepository.findAll()).thenReturn(List.of(source));
        when(scholardexPublicationFactRepository.findAllByDoiNormalized("10.1/owned")).thenReturn(List.of(owned));

        service.rebuildCanonicalFacts();

        verify(publicationWriter).upsertAndLinkSource(
                argThat(fact -> "spub_owned".equals(fact.getId())
                        && Integer.valueOf(42).equals(fact.getCitedByCount())
                        && "Updated title".equals(fact.getTitle())
                        && fact.getCorrespondingAuthors().equals(List.of("Corr Author"))),
                argThat(prov -> "OPENALEX".equals(prov.source()) && "W9".equals(prov.sourceRecordId())),
                eq("openalex-fact-bridge"));
        // Not a link-to-self.
        verify(sourceLinkService, never()).link(any(), anyString(), anyString(), anyString(), anyString(), any(), any(), any(), eq(false));
    }

    @Test
    void doiWithNoExistingPublicationMints() {
        OpenAlexPublicationFact source = source("W2", "10.1/new", "Fresh paper", "author-1");
        source.setCorrespondingAuthorNames(List.of("Jane Corresponding"));
        when(openAlexPublicationFactRepository.findAll()).thenReturn(List.of(source));
        when(scholardexPublicationFactRepository.findAllByDoiNormalized("10.1/new")).thenReturn(List.of());
        when(publicationCanonicalizationService.buildCanonicalPublicationId(
                any(), any(), any(), any(), eq("10.1/new"), any(), any(), any(), any()))
                .thenReturn("spub_minted");

        service.rebuildCanonicalFacts();

        verify(publicationWriter).upsertAndLinkSource(
                argThat(fact -> "spub_minted".equals(fact.getId())
                        && "10.1/new".equals(fact.getDoiNormalized())
                        && "Fresh paper".equals(fact.getTitle())
                        && fact.getCorrespondingAuthors().equals(List.of("Jane Corresponding"))),
                argThat(prov -> "OPENALEX".equals(prov.source()) && "W2".equals(prov.sourceRecordId())),
                eq("openalex-fact-bridge"));
        verify(edgeWriterService).upsertAuthorshipEdge(argThat(cmd ->
                "spub_minted".equals(cmd.leftId()) && "author-1".equals(cmd.rightId())));
    }

    @Test
    void sharedDoiMatchingMultiplePublicationsIsQuarantinedNotGuessed() {
        OpenAlexPublicationFact source = source("W3", "10.1/container", "A chapter", "author-1");
        ScholardexPublicationFact a = new ScholardexPublicationFact();
        a.setId("spub_a");
        ScholardexPublicationFact b = new ScholardexPublicationFact();
        b.setId("spub_b");
        when(openAlexPublicationFactRepository.findAll()).thenReturn(List.of(source));
        when(scholardexPublicationFactRepository.findAllByDoiNormalized("10.1/container")).thenReturn(List.of(a, b));

        service.rebuildCanonicalFacts();

        verify(sourceLinkService).markConflict(
                eq(ScholardexEntityType.PUBLICATION), eq("OPENALEX"), eq("W3"),
                eq("OPENALEX_PUBLICATION_DOI_AMBIGUOUS"), any(), any(), any(), eq(false));
        verify(publicationWriter, never()).upsertAndLinkSource(any(), any(), any());
        verify(sourceLinkService, never()).link(any(), anyString(), anyString(), anyString(), anyString(), any(), any(), any(), eq(false));
        verify(edgeWriterService, never()).upsertAuthorshipEdge(any());
    }

    private OpenAlexPublicationFact source(String workId, String doiNormalized, String title, String researcherAuthorId) {
        OpenAlexPublicationFact fact = new OpenAlexPublicationFact();
        fact.setSourceRecordId(workId);
        fact.setOpenalexWorkId(workId);
        fact.setDoi(doiNormalized);
        fact.setTitle(title);
        fact.setCoverDate("2020-01-01");
        fact.setCreator(title);
        fact.setSyncedResearcherAuthorIds(List.of(researcherAuthorId));
        return fact;
    }
}
