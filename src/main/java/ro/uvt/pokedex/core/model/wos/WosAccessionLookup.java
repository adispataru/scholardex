package ro.uvt.pokedex.core.model.wos;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * What the WoS OpenURL gateway answered for a DOI — a spared side table (never rebuilt), so a DOI is asked
 * about once, "no record" is re-asked only after a while (WoS indexes with delay), and the answer survives a
 * canonical rebuild even though the publication's {@code wosId} is re-derived from it by the linker.
 */
@Data
@Document(collection = "scholardex.wos_accession_lookups")
public class WosAccessionLookup {

    public enum Status { FOUND, NOT_FOUND }

    /** Lower-cased, trimmed DOI. */
    @Id
    private String doi;
    private Status status;
    /** {@code WOS:…} accession number when FOUND. */
    private String wosId;
    private Instant checkedAt;
    private int attempts;
}
