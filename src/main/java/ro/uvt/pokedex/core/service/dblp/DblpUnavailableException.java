package ro.uvt.pokedex.core.service.dblp;

import java.time.Instant;

/**
 * DBLP is rate-limiting or blocking this caller (an HTML page on the JSON search API, a 429 that persists after
 * {@code Retry-After}, or a run of consecutive failures). The client has entered a backoff window and refuses further
 * queries until {@link #getBackoffUntil()}; callers should stop the current batch rather than hammer every next query.
 */
public class DblpUnavailableException extends DblpLookupException {

    private final Instant backoffUntil;

    public DblpUnavailableException(String message, Instant backoffUntil) {
        super(message);
        this.backoffUntil = backoffUntil;
    }

    public Instant getBackoffUntil() {
        return backoffUntil;
    }
}
