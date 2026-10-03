package ro.uvt.pokedex.core.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import ro.uvt.pokedex.core.model.registry.RegistryChange;
import ro.uvt.pokedex.core.model.registry.RegistryItem;
import ro.uvt.pokedex.core.model.registry.RegistryKind;
import ro.uvt.pokedex.core.model.registry.RegistryStatus;

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
 * <p>A document without a status is one of the CNFIS list: confirmed, basis {@link Basis#CNFIS_LIST}. Since H144 the
 * event is one kind of the registries experts rank ({@link RegistryItem}): its rank is the level, its kind the
 * category.</p>
 */
@Data
@Document(collection = "scholardex.artisticEvent")
public class ArtisticEvent implements RegistryItem {
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
    private RegistryStatus status;
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
    private List<RegistryChange> history = new ArrayList<>();

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

    /** Confirmed: ranked, and counted. The CNFIS list carries no status. */
    @Override
    public boolean isConfirmed() {
        return status == null || status == RegistryStatus.CONFIRMED;
    }

    @Override
    public RegistryKind registryKind() {
        return RegistryKind.ARTISTIC_EVENT;
    }

    /** The rank, as the registries name levels. */
    @Override
    public String getLevel() {
        return rank == null ? null : rank.name();
    }

    @Override
    public void setLevel(String level) {
        this.rank = level == null || level.isBlank() ? null : Rank.valueOf(level);
    }

    /** The kind of event (festival, competition, season, tour), as the registries name categories. */
    @Override
    public String getCategory() {
        return kind == null ? null : kind.name();
    }

    @Override
    public void setCategory(String category) {
        this.kind = category == null || category.isBlank() ? null : Kind.valueOf(category);
    }

    /** Artistic events are ranked on a basis, without criteria to tick. */
    @Override
    public List<String> getCriteria() {
        return List.of();
    }

    @Override
    public void setCriteria(List<String> criteria) {
        // no criteria for artistic events
    }

    @Override
    public String effectiveBasis() {
        if (basis != null) return basis;
        return status == null ? Basis.CNFIS_LIST.name() : null;
    }
}
