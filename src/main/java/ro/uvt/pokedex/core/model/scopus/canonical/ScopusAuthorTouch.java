package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Document(collection = "scopus.author_touch_queue")
@CompoundIndex(name = "uniq_scopus_author_touch", def = "{'source': 1, 'authorId': 1}", unique = true)
public class ScopusAuthorTouch {
    @Id
    private String id;
    private String source;
    private String authorId;
    private Instant touchedAt;
}
