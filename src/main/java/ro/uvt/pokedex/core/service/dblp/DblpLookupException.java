package ro.uvt.pokedex.core.service.dblp;

/**
 * A single DBLP API lookup failed (timeout, I/O error, 5xx, unparseable body). Distinct from "no hits" so a sweep
 * can count it instead of reporting a silent 0% resolve; the candidate stays unresolved and is retried next sync.
 */
public class DblpLookupException extends RuntimeException {

    public DblpLookupException(String message) {
        super(message);
    }

    public DblpLookupException(String message, Throwable cause) {
        super(message, cause);
    }
}
