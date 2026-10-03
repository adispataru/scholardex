package ro.uvt.pokedex.core.model.registry;

import lombok.Data;

import java.time.Instant;

/** One decision on a registry entry: who, when, what, the level before and after, why. */
@Data
public class RegistryChange {
    private Instant at;
    private String by;
    /** PROPOSED, RANKED, MERGED, REJECTED, REOPENED, EDITED. */
    private String action;
    private String fromLevel;
    private String toLevel;
    private String note;
}
