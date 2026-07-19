package ro.uvt.pokedex.core.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A classified publisher from the FSP (Psihologie, Anexa 28) standard. Unlike the flat
 * {@link FeaaAnexa1Publisher} allowlist, this carries a {@code tier} (A1/A2/B) that maps to the
 * fișă multiplier {@code m} (A1→3, A2→1, B→0.5) used by the {@code PSYCH_BOOK} scorer.
 *
 * <p>The fișă enumerates A2 and B publishers by name; A1 ("edituri de prestigiu internaţional") is
 * defined by the KVK ≥25-libraries / complementary route rather than a fixed name list, so A1 rows
 * are optional here and can be curated later.
 */
@Data
@Document(collection = "scholardex.psihologiePublishers")
public class PsihologiePublisher {
    @Id
    private String id;
    private Integer nr;
    private String name;
    private String tier;
}
