package ro.uvt.pokedex.core.service.importing.scopus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexSourceLinkRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusPublicationFactRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CitationCountSourceBackfillServiceTest {

    @Mock
    private MongoTemplate mongoTemplate;
    @Mock
    private ScholardexSourceLinkRepository sourceLinkRepository;
    @Mock
    private ScopusPublicationFactRepository scopusPublicationFactRepository;
    @Mock
    private OpenAlexPublicationFactRepository openAlexPublicationFactRepository;
    @Mock
    private BulkOperations bulk;

    @Test
    void stampsEachSourceCountFromItsLinkedFactAndSkipsAlreadyStampedPublications() {
        ScholardexPublicationFact both = fact("spub_both", null, null);
        ScholardexPublicationFact scopusOnly = fact("spub_scopus", null, null);
        ScholardexPublicationFact unchanged = fact("spub_done", 7, 9);
        ScholardexPublicationFact unlinked = fact("spub_none", null, null);
        when(mongoTemplate.find(any(Query.class), eq(ScholardexPublicationFact.class)))
                .thenReturn(List.of(both, scopusOnly, unchanged, unlinked))
                .thenReturn(List.of());
        when(sourceLinkRepository.findByEntityTypeAndSourceAndCanonicalEntityIdIn(
                eq(ScholardexEntityType.PUBLICATION), eq("SCOPUS"), anyCollection()))
                .thenReturn(List.of(link("2-s2.0-1", "spub_both"), link("2-s2.0-2", "spub_scopus"),
                        link("2-s2.0-3", "spub_done")));
        when(sourceLinkRepository.findByEntityTypeAndSourceAndCanonicalEntityIdIn(
                eq(ScholardexEntityType.PUBLICATION), eq("OPENALEX"), anyCollection()))
                .thenReturn(List.of(link("W1", "spub_both"), link("W1b", "spub_both"), link("W3", "spub_done")));
        when(scopusPublicationFactRepository.findByEidIn(anyCollection()))
                .thenReturn(List.of(scopus("2-s2.0-1", 12), scopus("2-s2.0-2", 3), scopus("2-s2.0-3", 7)));
        // two OpenAlex records on the same canonical pub → the higher count wins
        when(openAlexPublicationFactRepository.findBySourceRecordIdIn(anyCollection()))
                .thenReturn(List.of(openAlex("W1", 10), openAlex("W1b", 15), openAlex("W3", 9)));
        when(mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, ScholardexPublicationFact.class)).thenReturn(bulk);

        CitationCountSourceBackfillService.Result result = service().run();

        assertThat(result).isEqualTo(new CitationCountSourceBackfillService.Result(4, 3, 2, 2));
        ArgumentCaptor<Query> queries = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> updates = ArgumentCaptor.forClass(Update.class);
        verify(bulk, org.mockito.Mockito.times(2)).updateOne(queries.capture(), updates.capture());
        verify(bulk).execute();
        assertThat(queries.getAllValues().get(0).getQueryObject().get("_id")).isEqualTo("spub_both");
        assertThat(updates.getAllValues().get(0).getUpdateObject().toJson())
                .contains("\"citedByCountScopus\": 12").contains("\"citedByCountOpenAlex\": 15");
        assertThat(queries.getAllValues().get(1).getQueryObject().get("_id")).isEqualTo("spub_scopus");
        assertThat(updates.getAllValues().get(1).getUpdateObject().toJson())
                .contains("\"citedByCountScopus\": 3").contains("$unset").contains("citedByCountOpenAlex");
    }

    @Test
    void anEmptyCorpusWritesNothing() {
        when(mongoTemplate.find(any(Query.class), eq(ScholardexPublicationFact.class))).thenReturn(List.of());

        CitationCountSourceBackfillService.Result result = service().run();

        assertThat(result).isEqualTo(new CitationCountSourceBackfillService.Result(0, 0, 0, 0));
        verify(mongoTemplate, never()).bulkOps(any(), eq(ScholardexPublicationFact.class));
    }

    private CitationCountSourceBackfillService service() {
        return new CitationCountSourceBackfillService(mongoTemplate, sourceLinkRepository,
                scopusPublicationFactRepository, openAlexPublicationFactRepository);
    }

    private static ScholardexPublicationFact fact(String id, Integer scopus, Integer openAlex) {
        ScholardexPublicationFact fact = new ScholardexPublicationFact();
        fact.setId(id);
        fact.setCitedByCountScopus(scopus);
        fact.setCitedByCountOpenAlex(openAlex);
        return fact;
    }

    private static ScholardexSourceLink link(String sourceRecordId, String canonicalId) {
        ScholardexSourceLink link = new ScholardexSourceLink();
        link.setSourceRecordId(sourceRecordId);
        link.setCanonicalEntityId(canonicalId);
        return link;
    }

    private static ScopusPublicationFact scopus(String eid, int count) {
        ScopusPublicationFact fact = new ScopusPublicationFact();
        fact.setEid(eid);
        fact.setCitedByCount(count);
        return fact;
    }

    private static OpenAlexPublicationFact openAlex(String workId, int count) {
        OpenAlexPublicationFact fact = new OpenAlexPublicationFact();
        fact.setSourceRecordId(workId);
        fact.setCitedByCount(count);
        return fact;
    }
}
