package ro.uvt.pokedex.core.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * An artistic event or host — a festival, a competition, the season or the tour of an institution — and its rank.
 * The CNFIS list of festivals and competitions per domain seeded the registry (H129); since H142 slice 3 the registry
 * grows by the decisions of experts: a name researchers give that the registry does not know is a PROPOSED event,
 * which an expert of its domain ranks (CONFIRMED), merges into another as a spelling variant (MERGED) or rejects
 * (REJECTED). A researcher names the event; nobody picks its level for a record.
 *
 * <p>A document without a status is one of the CNFIS list: confirmed, basis {@link Basis#CNFIS_LIST}.</p>
 */
@Data
@Document(collection = "scholardex.artisticEvent")
public class ArtisticEvent {
    @Id
    private String id;
    private String name;
    private String domainId;
    private Rank rank;

    /** Other spellings of the same event, matched like its name. */
    private List<String> aliases = new ArrayList<>();
    private Kind kind;
    private String country;
    private String organiser;
    private Status status;
    /** Why the rank: one of {@link Basis}, by name. */
    private String basis;
    private String note;
    /** Where the name came from: a record, an institutional table, the CNFIS list (null). */
    private String source;
    private String proposedBy;
    private Instant proposedAt;
    private String decidedBy;
    private Instant decidedAt;
    /** The confirmed event a MERGED proposal now spells. */
    private String mergedInto;
    private List<Change> history = new ArrayList<>();

    /**
     * Best first. CNFIS Anexa 5.1 knows three levels (top international, international, national); the Music standard
     * asks whether a performance had "vizibilitate internațională sau națională de vârf" — so a Romanian festival or
     * institution with international visibility is {@link #NATIONAL_TOP}: national for CNFIS, top for CNATDCU; a
     * {@link #LOCAL} host is known, but no CNFIS level.
     */
    public static enum Rank {
        INTERNATIONAL_TOP("1"),
        INTERNATIONAL("2"),
        NATIONAL_TOP("3"),
        NATIONAL("3"),
        LOCAL("4");
        private String value;
        Rank(String value) {
            this.value = value;
        }
    }

    public enum Kind { FESTIVAL, COMPETITION, SEASON, TOUR, OTHER }

    public enum Status { CONFIRMED, PROPOSED, REJECTED, MERGED }

    /** The grounds an expert ranks on: the CNFIS list and its rules, and the footnotes of the Music standard. */
    public enum Basis {
        CNFIS_LIST,
        CNFIS_CAPITAL_INSTITUTION,
        CNFIS_UNION_PARTNERSHIP,
        CNFIS_MINISTRY_FUNDING,
        TOP_FESTIVAL_ABROAD,
        TOP_INSTITUTION_ABROAD,
        TOP_FESTIVAL_ROMANIA,
        TOP_INSTITUTION_ROMANIA,
        REGIONAL_OR_LOCAL,
        OTHER
    }

    /** One decision on the event: who, when, what, the rank before and after, why. */
    @Data
    public static class Change {
        private Instant at;
        private String by;
        private String action;
        private Rank fromRank;
        private Rank toRank;
        private String note;
    }

    /** Confirmed: ranked, and counted. The CNFIS list carries no status. */
    public boolean isConfirmed() {
        return status == null || status == Status.CONFIRMED;
    }
}
