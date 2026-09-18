package ro.uvt.pokedex.core.model.issn;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * What the international ISSN register said about an ISSN a researcher typed for a journal that is NOT in our corpus
 * (category D journals: real, but outside WoS and Scopus). A spared side table — not derived data, never rebuilt.
 */
@Data
@Document(collection = "scholardex.issn_verifications")
public class IssnVerification {

    public enum Status {
        /** The register knows it; {@code keyTitle} holds the official title. */
        VERIFIED,
        /** The register answered "no such ISSN". */
        NOT_FOUND,
        /** Check digit fine, but the register could not be asked yet; retried nightly. Scores as D meanwhile. */
        UNVERIFIED
    }

    /** Normalized {@code NNNN-NNNC}. */
    @Id
    private String issn;
    private Status status;
    private String keyTitle;
    private Instant firstAskedAt;
    private Instant lastCheckedAt;
    private int attempts;
}
