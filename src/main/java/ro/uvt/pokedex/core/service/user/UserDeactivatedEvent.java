package ro.uvt.pokedex.core.service.user;

/**
 * Published after a {@code User} is deleted or locked. Listeners can react with cleanup
 * tasks (e.g. removing the user's email from {@code headUserIds} / {@code supervisorUserIds}
 * lists across the org tree).
 *
 * @param userId immutable identifier of the affected user (currently their email)
 * @param reason free-form context: {@code "deleted"} or {@code "locked"}
 */
public record UserDeactivatedEvent(String userId, String reason) {
}
