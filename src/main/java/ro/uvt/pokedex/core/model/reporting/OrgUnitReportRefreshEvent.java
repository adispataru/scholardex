package ro.uvt.pokedex.core.model.reporting;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Audit record of an admin "Refresh all" batch action on an org-unit report roll-up. Carries no
 * scores — the runs themselves are the data; these events give the delta compare picker nameable
 * points in time and answer "who refreshed what, when, and how much of the roster it covered".
 */
@Data
@Document(collection = "orgUnitReportRefreshEvents")
@CompoundIndex(name = "idx_orgunit_refresh_event",
        def = "{'unitType': 1, 'unitId': 1, 'reportDefinitionId': 1, 'createdAt': -1}")
public class OrgUnitReportRefreshEvent {

    @Id
    private String id;

    private UnitType unitType;
    private String unitId;
    private String reportDefinitionId;
    /** How the runs were produced; null on legacy documents ⇒ treat as {@link Mode#CONFIRMED}. */
    private Mode mode;

    private Instant createdAt;
    private String triggeredByEmail;
    /** Optional admin-supplied name for this refresh, shown in the compare picker. */
    private String label;

    /** Runs written by the batch (confirmed refreshes or provisional scorings, per {@code mode}). */
    private int refreshed;
    private int failed;
    private int skippedProvisional;
    private int skippedFresh;
    /** PROVISIONAL mode: members skipped because they have confirmed publications (authoritative runs). */
    private int skippedConfirmed;
    /** PROVISIONAL mode: members with no linked identifiers resolving to a canonical author. */
    private int unresolved;
    private int rosterSize;
    private long durationMs;

    public enum UnitType {
        DIVISION,
        DEPARTMENT,
        GROUP
    }

    public enum Mode {
        /** Normal refresh through the researcher's CONFIRMED-publication path. */
        CONFIRMED,
        /** Provisional scoring from profile-linked identifiers (unvalidated authorship). */
        PROVISIONAL
    }
}
