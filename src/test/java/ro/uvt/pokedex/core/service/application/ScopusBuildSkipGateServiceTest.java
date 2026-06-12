package ro.uvt.pokedex.core.service.application;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusBuildPipelineState;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusBuildPipelineStateRepository;

import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScopusBuildSkipGateServiceTest {

    private static final Instant EVENTS_TS = Instant.parse("2026-06-12T10:00:00Z");
    private static final Instant FORUMS_TS = Instant.parse("2026-06-12T11:00:00Z");

    @Mock private MongoTemplate mongoTemplate;
    @Mock private ScopusBuildPipelineStateRepository stateRepository;

    private ScopusBuildSkipGateService service() {
        return new ScopusBuildSkipGateService(mongoTemplate, stateRepository);
    }

    private void stubLiveFingerprint(long eventsCount, Instant eventsTs, long forumsCount, Instant forumsTs) {
        lenient().when(mongoTemplate.count(any(Query.class), eq("scopus.import_events"))).thenReturn(eventsCount);
        lenient().when(mongoTemplate.count(any(Query.class), eq("scholardex.forum_facts"))).thenReturn(forumsCount);
        lenient().when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("scopus.import_events")))
                .thenReturn(eventsTs == null ? null : new Document("updatedAt", Date.from(eventsTs)));
        lenient().when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("scholardex.forum_facts")))
                .thenReturn(forumsTs == null ? null : new Document("updatedAt", Date.from(forumsTs)));
    }

    private ScopusBuildPipelineState storedState(long eventsCount, Instant eventsTs, long forumsCount, Instant forumsTs) {
        ScopusBuildPipelineState state = new ScopusBuildPipelineState();
        state.setId(ScopusBuildPipelineState.BUILD_FACTS_STATE_ID);
        state.setImportEventsCount(eventsCount);
        state.setImportEventsMaxUpdatedAt(eventsTs);
        state.setCanonicalForumsCount(forumsCount);
        state.setCanonicalForumsMaxUpdatedAt(forumsTs);
        // must mirror the service's builderVersions() concatenation
        state.setBuilderVersions(String.join("|",
                ro.uvt.pokedex.core.service.importing.BuilderVersion.SCOPUS_FACT,
                ro.uvt.pokedex.core.service.importing.BuilderVersion.WOS_FACT,
                ro.uvt.pokedex.core.service.importing.BuilderVersion.USER_DEFINED_FACT,
                ro.uvt.pokedex.core.service.importing.BuilderVersion.SCHOLARDEX_PUBLICATION,
                ro.uvt.pokedex.core.service.importing.BuilderVersion.SCHOLARDEX_AUTHOR,
                ro.uvt.pokedex.core.service.importing.BuilderVersion.SCHOLARDEX_AFFILIATION,
                ro.uvt.pokedex.core.service.importing.BuilderVersion.SCHOLARDEX_CITATION,
                ro.uvt.pokedex.core.service.importing.BuilderVersion.SCHOLARDEX_FORUM,
                ro.uvt.pokedex.core.service.importing.BuilderVersion.SCHOLARDEX_EDGE));
        state.setLastSuccessAt(Instant.parse("2026-06-12T12:00:00Z"));
        return state;
    }

    @Test
    void canSkipWhenFingerprintIdenticalToLastSuccess() {
        stubLiveFingerprint(246_039L, EVENTS_TS, 32_454L, FORUMS_TS);
        when(stateRepository.findById(ScopusBuildPipelineState.BUILD_FACTS_STATE_ID))
                .thenReturn(Optional.of(storedState(246_039L, EVENTS_TS, 32_454L, FORUMS_TS)));

        assertTrue(service().canSkipBuildFacts());
    }

    @Test
    void cannotSkipWhenNoRecordedSuccess() {
        when(stateRepository.findById(ScopusBuildPipelineState.BUILD_FACTS_STATE_ID)).thenReturn(Optional.empty());
        assertFalse(service().canSkipBuildFacts());
    }

    @Test
    void cannotSkipWhenEventLedgerChanged() {
        stubLiveFingerprint(246_040L, EVENTS_TS.plusSeconds(60), 32_454L, FORUMS_TS);
        when(stateRepository.findById(ScopusBuildPipelineState.BUILD_FACTS_STATE_ID))
                .thenReturn(Optional.of(storedState(246_039L, EVENTS_TS, 32_454L, FORUMS_TS)));

        assertFalse(service().canSkipBuildFacts());
    }

    @Test
    void cannotSkipWhenCanonicalForumsTouchedBetweenRuns() {
        // e.g. WoS onboarding merged/changed canonical forums — forum dedup/canon must re-run.
        stubLiveFingerprint(246_039L, EVENTS_TS, 32_454L, FORUMS_TS.plusSeconds(120));
        when(stateRepository.findById(ScopusBuildPipelineState.BUILD_FACTS_STATE_ID))
                .thenReturn(Optional.of(storedState(246_039L, EVENTS_TS, 32_454L, FORUMS_TS)));

        assertFalse(service().canSkipBuildFacts());
    }

    @Test
    void recordSuccessPersistsCurrentFingerprint() {
        stubLiveFingerprint(100L, EVENTS_TS, 50L, FORUMS_TS);

        service().recordBuildFactsSuccess();

        verify(stateRepository).save(argThat(state ->
                ScopusBuildPipelineState.BUILD_FACTS_STATE_ID.equals(state.getId())
                        && state.getImportEventsCount() == 100L
                        && EVENTS_TS.equals(state.getImportEventsMaxUpdatedAt())
                        && state.getCanonicalForumsCount() == 50L
                        && FORUMS_TS.equals(state.getCanonicalForumsMaxUpdatedAt())
                        && state.getBuilderVersions() != null
                        && state.getLastSuccessAt() != null));
    }
}
