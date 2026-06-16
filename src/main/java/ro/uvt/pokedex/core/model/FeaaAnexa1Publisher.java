package ro.uvt.pokedex.core.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A prestige international publisher from Anexa 1 of the FEAA (Ordin 6129/2016, Anexa 27) standard.
 * A flat name-matched allowlist — like {@link CNCSISPublisher}, not a ranked list — used to classify
 * a book/chapter as "international" (∈ this list) vs "national/other" (∉) for the FEAA Pi coefficient.
 */
@Data
@Document(collection = "scholardex.feaaAnexa1")
public class FeaaAnexa1Publisher {
    @Id
    private String id;
    private Integer nr;
    private String name;
}
