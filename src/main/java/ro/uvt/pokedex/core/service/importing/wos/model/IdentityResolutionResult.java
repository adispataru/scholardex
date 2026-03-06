package ro.uvt.pokedex.core.service.importing.wos.model;

public record IdentityResolutionResult(
        String journalId,
        String identityKey,
        WosIdentityResolutionStatus status,
        String conflictId
) {
}
