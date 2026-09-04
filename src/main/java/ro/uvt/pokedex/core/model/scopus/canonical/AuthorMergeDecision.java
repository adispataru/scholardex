package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * H103 — a human-confirmed author identity, durable across rebuilds. An explicit
 * {@code /admin/initialization/author/merge} mutates canonical author docs and repoints references,
 * but {@code scholardex.author_facts} and {@code scholardex.source_links} are managed derived
 * collections: a from-scratch rebuild re-derives authors from source facts with exactly the bridging
 * that failed to union these identities in the first place, resurrecting the ghosts (and, for the H99
 * case, the nondeterministic score flap they caused). This row records the decision the same way H84
 * records publication merges, and {@code AuthorReconcileService.reapplyPersistedMerges()} re-executes
 * it after every canonical author build.
 *
 * <p><b>Anchored on identity-KEY sets, not canonical ids.</b> Canonical author ids are rebuild-unstable;
 * the Scopus AU-IDs / ORCIDs / OpenAlex ids / WoS ids that the merged cluster carried are the durable
 * identity. Re-apply resolves every current author carrying ANY anchored key and merges them when more
 * than one document answers. Intentionally OUTSIDE {@code PipelineRebuildService.MANAGED_DERIVED_COLLECTIONS}
 * — this table is the thing that must outlive the wipe.</p>
 */
@Data
@Document(collection = "scholardex.author_merge_decisions")
public class AuthorMergeDecision {

    @Id
    private String id;

    private List<String> anchorScopusIds = new ArrayList<>();
    private List<String> anchorOrcidIds = new ArrayList<>();
    private List<String> anchorOpenAlexIds = new ArrayList<>();
    private List<String> anchorWosIds = new ArrayList<>();
    private List<String> anchorUserSourceIds = new ArrayList<>();

    /** Display names of the merged docs at decision time — audit legibility only, never matched on. */
    private List<String> displayNamesSnapshot = new ArrayList<>();

    private String decidedBy;
    private Instant createdAt;
    private Instant updatedAt;
    /** Rebuild-re-apply observability, mirroring H84's {@code lastAppliedAt}. */
    private Instant lastAppliedAt;
}
