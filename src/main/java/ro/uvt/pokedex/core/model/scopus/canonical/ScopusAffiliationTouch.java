package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Document(collection = "scopus.affiliation_touch_queue")
@CompoundIndex(name = "uniq_scopus_affiliation_touch", def = "{'source': 1, 'afid': 1}", unique = true)
public class ScopusAffiliationTouch {
    @Id
    private String id;
    private String source;
    private String afid;
    private Instant touchedAt;
}
