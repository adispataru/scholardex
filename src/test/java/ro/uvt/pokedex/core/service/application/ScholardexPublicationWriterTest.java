package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScholardexPublicationWriterTest {

    @Mock
    private ScholardexPublicationFactRepository repository;
    @Mock
    private ScholardexSourceLinkService sourceLinkService;

    private ScholardexPublicationWriter writer() {
        return new ScholardexPublicationWriter(repository, sourceLinkService);
    }

    @Test
    void stampsProvenanceSavesAndUpsertsSourceLink() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ScholardexPublicationFact fact = new ScholardexPublicationFact();
        fact.setId("pub-1");

        var provenance = new CanonicalWriteProvenance("SCOPUS", "rec-1", "batch-1", "corr-1", "evt-1");
        ScholardexPublicationFact saved = writer().upsertAndLinkSource(fact, provenance, "reason-x");

        // Uniform provenance + updatedAt stamped on the fact.
        assertThat(saved.getSource()).isEqualTo("SCOPUS");
        assertThat(saved.getSourceRecordId()).isEqualTo("rec-1");
        assertThat(saved.getSourceBatchId()).isEqualTo("batch-1");
        assertThat(saved.getSourceCorrelationId()).isEqualTo("corr-1");
        assertThat(saved.getUpdatedAt()).isNotNull();

        verify(repository).save(fact);
        // Source link recorded with provenance, including sourceEventId, and explicitReplayAttempt=false.
        verify(sourceLinkService).link(
                eq(ScholardexEntityType.PUBLICATION), eq("SCOPUS"), eq("rec-1"), eq("pub-1"),
                eq("reason-x"), eq("evt-1"), eq("batch-1"), eq("corr-1"), eq(false));
    }

    @Test
    void doesNotStampSourceEventIdOntoTheFact() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ScholardexPublicationFact fact = new ScholardexPublicationFact();
        fact.setId("pub-2");
        fact.setSourceEventId("preexisting-event");

        // Provenance carries a sourceEventId (for the link) but the writer must not overwrite the
        // fact's own sourceEventId — that stays caller-managed.
        writer().upsertAndLinkSource(fact,
                new CanonicalWriteProvenance("WOS_LINKER", "wos-9", "run-1", "v1", "link-event"), "wos-link");

        assertThat(fact.getSourceEventId()).isEqualTo("preexisting-event");
    }

    @Test
    void skipsSourceLinkWhenSourceOrRecordIdBlank() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ScholardexPublicationFact fact = new ScholardexPublicationFact();
        fact.setId("pub-3");

        writer().upsertAndLinkSource(fact,
                new CanonicalWriteProvenance("SOURCE", "  ", "b", "c", null), "reason");

        verify(repository).save(fact);
        verify(sourceLinkService, never()).link(any(), any(), any(), any(), any(), any(), any(), any(), eq(false));
    }
}
