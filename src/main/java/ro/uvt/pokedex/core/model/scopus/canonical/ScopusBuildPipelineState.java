package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * H56: fingerprint of the last successful Scopus {@code buildFacts} run, used by the stage-skip gate.
 * A rebuild whose inputs (import-event ledger, canonical forums touched by WoS onboarding) and builder
 * versions are identical to this fingerprint is a deterministic no-op and may be skipped wholesale.
 * Sound only because unchanged replays no longer churn {@code updatedAt} on derived facts (H56).
 */
@Data
@Document(collection = "scopus.pipeline_state")
public class ScopusBuildPipelineState {

    public static final String BUILD_FACTS_STATE_ID = "scopus-build-facts";

    @Id
    private String id;
    private long importEventsCount;
    private Instant importEventsMaxUpdatedAt;
    private long canonicalForumsCount;
    private Instant canonicalForumsMaxUpdatedAt;
    /** Concatenation of every {@link ro.uvt.pokedex.core.service.importing.BuilderVersion} constant. */
    private String builderVersions;
    private Instant lastSuccessAt;
}
