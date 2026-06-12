package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusBuildPipelineState;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusBuildPipelineStateRepository;
import ro.uvt.pokedex.core.service.importing.BuilderVersion;

import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.Optional;

/**
 * H56 stage-skip gate for the Scopus {@code buildFacts} pipeline.
 *
 * <p>The pipeline is a deterministic function of the import-event ledger plus the canonical forums
 * that WoS onboarding may have touched between runs. When both are byte-stable since the last
 * successful run (count + max {@code updatedAt}) and no builder version changed, re-running produces
 * an identical result — the whole pipeline can be skipped. This is only sound because H56 made
 * unchanged replays write-free (derived facts no longer churn {@code updatedAt} on a no-op replay).
 *
 * <p>Deliberately opt-in per request ({@code skipUnchanged=true}): operator-triggered runs after e.g.
 * a conflict resolution use the default and always execute.
 */
@Service
@RequiredArgsConstructor
public class ScopusBuildSkipGateService {

    private static final Logger log = LoggerFactory.getLogger(ScopusBuildSkipGateService.class);

    private static final String IMPORT_EVENTS_COLLECTION = "scopus.import_events";
    private static final String CANONICAL_FORUMS_COLLECTION = "scholardex.forum_facts";

    private final MongoTemplate mongoTemplate;
    private final ScopusBuildPipelineStateRepository stateRepository;

    /** True when the inputs and builder versions are identical to the last recorded successful run. */
    public boolean canSkipBuildFacts() {
        Optional<ScopusBuildPipelineState> stored = stateRepository.findById(ScopusBuildPipelineState.BUILD_FACTS_STATE_ID);
        if (stored.isEmpty()) {
            return false;
        }
        ScopusBuildPipelineState current = currentFingerprint();
        ScopusBuildPipelineState previous = stored.get();
        boolean unchanged = previous.getImportEventsCount() == current.getImportEventsCount()
                && Objects.equals(previous.getImportEventsMaxUpdatedAt(), current.getImportEventsMaxUpdatedAt())
                && previous.getCanonicalForumsCount() == current.getCanonicalForumsCount()
                && Objects.equals(previous.getCanonicalForumsMaxUpdatedAt(), current.getCanonicalForumsMaxUpdatedAt())
                && Objects.equals(previous.getBuilderVersions(), current.getBuilderVersions());
        log.info("Scopus build-facts skip gate: unchanged={} events[count={} maxUpdatedAt={}] forums[count={} maxUpdatedAt={}] lastSuccessAt={}",
                unchanged,
                current.getImportEventsCount(), current.getImportEventsMaxUpdatedAt(),
                current.getCanonicalForumsCount(), current.getCanonicalForumsMaxUpdatedAt(),
                previous.getLastSuccessAt());
        return unchanged;
    }

    /** Record the current input fingerprint after a fully successful (errors=0) buildFacts run. */
    public void recordBuildFactsSuccess() {
        ScopusBuildPipelineState state = currentFingerprint();
        state.setLastSuccessAt(Instant.now());
        stateRepository.save(state);
    }

    private ScopusBuildPipelineState currentFingerprint() {
        ScopusBuildPipelineState state = new ScopusBuildPipelineState();
        state.setId(ScopusBuildPipelineState.BUILD_FACTS_STATE_ID);
        state.setImportEventsCount(mongoTemplate.count(new Query(), IMPORT_EVENTS_COLLECTION));
        state.setImportEventsMaxUpdatedAt(maxUpdatedAt(IMPORT_EVENTS_COLLECTION));
        state.setCanonicalForumsCount(mongoTemplate.count(new Query(), CANONICAL_FORUMS_COLLECTION));
        state.setCanonicalForumsMaxUpdatedAt(maxUpdatedAt(CANONICAL_FORUMS_COLLECTION));
        state.setBuilderVersions(builderVersions());
        return state;
    }

    private Instant maxUpdatedAt(String collection) {
        Query query = new Query().with(Sort.by(Sort.Direction.DESC, "updatedAt")).limit(1);
        query.fields().include("updatedAt");
        org.bson.Document doc = mongoTemplate.findOne(query, org.bson.Document.class, collection);
        if (doc == null) {
            return null;
        }
        Date updatedAt = doc.getDate("updatedAt");
        return updatedAt == null ? null : updatedAt.toInstant();
    }

    private String builderVersions() {
        return String.join("|",
                BuilderVersion.SCOPUS_FACT,
                BuilderVersion.WOS_FACT,
                BuilderVersion.USER_DEFINED_FACT,
                BuilderVersion.SCHOLARDEX_PUBLICATION,
                BuilderVersion.SCHOLARDEX_AUTHOR,
                BuilderVersion.SCHOLARDEX_AFFILIATION,
                BuilderVersion.SCHOLARDEX_CITATION,
                BuilderVersion.SCHOLARDEX_FORUM,
                BuilderVersion.SCHOLARDEX_EDGE);
    }
}
