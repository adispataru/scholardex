package ro.uvt.pokedex.core.service.application.model;

import java.util.List;

public record SuspiciousAuthorshipState(
        List<Flag> flags
) {
    public enum Code {
        NAME_MISMATCH,
        NO_AFFILIATION_OVERLAP,
        AFFILIATION_SCOPE_MISMATCH,
        SECONDARY_ID_ONLY
    }

    public record Flag(
            Code code,
            String message
    ) {
    }
}
