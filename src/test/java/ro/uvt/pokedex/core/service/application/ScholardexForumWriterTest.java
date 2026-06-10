package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScholardexForumWriterTest {

    @Mock
    private ScholardexForumFactRepository repository;
    @Mock
    private ScholardexSourceLinkService sourceLinkService;

    private ScholardexForumWriter writer() {
        return new ScholardexForumWriter(repository, sourceLinkService);
    }

    @Test
    void stampsProvenanceSavesAndUpsertsSourceLink() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ScholardexForumFact fact = new ScholardexForumFact();
        fact.setId("forum-1");

        var provenance = new CanonicalWriteProvenance("USER_DEFINED", "rec-1", "batch-1", "corr-1", "evt-1");
        ScholardexForumFact saved = writer().upsertAndLinkSource(fact, provenance, "reason-x");

        assertThat(saved.getSource()).isEqualTo("USER_DEFINED");
        assertThat(saved.getSourceRecordId()).isEqualTo("rec-1");
        assertThat(saved.getSourceBatchId()).isEqualTo("batch-1");
        assertThat(saved.getSourceCorrelationId()).isEqualTo("corr-1");
        assertThat(saved.getUpdatedAt()).isNotNull();

        verify(repository).save(fact);
        verify(sourceLinkService).link(
                eq(ScholardexEntityType.FORUM), eq("USER_DEFINED"), eq("rec-1"), eq("forum-1"),
                eq("reason-x"), eq("evt-1"), eq("batch-1"), eq("corr-1"), eq(false));
    }

    @Test
    void doesNotStampSourceEventIdOntoTheFact() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ScholardexForumFact fact = new ScholardexForumFact();
        fact.setId("forum-2");
        fact.setSourceEventId("preexisting-event");

        writer().upsertAndLinkSource(fact,
                new CanonicalWriteProvenance("USER_DEFINED", "rec-2", "b", "c", "link-event"), "reason");

        assertThat(fact.getSourceEventId()).isEqualTo("preexisting-event");
    }

    @Test
    void skipsSourceLinkWhenSourceOrRecordIdBlank() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ScholardexForumFact fact = new ScholardexForumFact();
        fact.setId("forum-3");

        writer().upsertAndLinkSource(fact,
                new CanonicalWriteProvenance("SOURCE", "  ", "b", "c", null), "reason");

        verify(repository).save(fact);
        verify(sourceLinkService, never()).link(any(), any(), any(), any(), any(), any(), any(), any(), eq(false));
    }
}
