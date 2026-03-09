package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Document(collection = "scopus.publication_touch_queue")
@CompoundIndex(name = "uniq_scopus_publication_touch", def = "{'source': 1, 'eid': 1}", unique = true)
public class ScopusPublicationTouch {
    @Id
    private String id;
    private String source;
    private String eid;
    private Instant touchedAt;
}
