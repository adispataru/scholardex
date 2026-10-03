package ro.uvt.pokedex.core.model.registry;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * H144 — an entry of the registries experts rank (conferences, organisations, awards): the name researchers give, its
 * spellings, its level once an expert of its domain ranked it, why (the basis, the criteria ticked, a note) and every
 * decision. A name researchers give that no entry knows waits as a proposal, shared by every record naming it.
 */
@Data
@Document(collection = "scholardex.registry_entries")
public class RegistryEntry implements RegistryItem {
    @Id
    private String id;
    private RegistryKind kind;
    private String name;
    private List<String> aliases = new ArrayList<>();
    private String domainId;
    private RegistryStatus status;
    /** One of the kind's levels; null while waiting or once rejected. */
    private String level;
    private String category;
    /** The criteria an expert ticked (Comisia 28's, for a conference). */
    private List<String> criteria = new ArrayList<>();
    private String country;
    private String basis;
    private String note;
    private String source;
    private String proposedBy;
    private Instant proposedAt;
    private String decidedBy;
    private Instant decidedAt;
    private String mergedInto;
    private List<RegistryChange> history = new ArrayList<>();

    public static RegistryEntry of(RegistryKind kind) {
        RegistryEntry entry = new RegistryEntry();
        entry.setKind(kind);
        return entry;
    }

    @Override
    public RegistryKind registryKind() {
        return kind;
    }

    /** An entry is confirmed only when ranked: unlike the CNFIS list, a missing status here means nothing decided. */
    @Override
    public boolean isConfirmed() {
        return status == RegistryStatus.CONFIRMED;
    }
}
