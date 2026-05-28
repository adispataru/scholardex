package ro.uvt.pokedex.core.service.reporting.transfer;

public record CommitResult(boolean success, String committedEntityId, String errorMessage) {

    public static CommitResult ok(String committedEntityId) {
        return new CommitResult(true, committedEntityId, null);
    }

    public static CommitResult failure(String errorMessage) {
        return new CommitResult(false, null, errorMessage);
    }
}
