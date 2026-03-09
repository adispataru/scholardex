package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Document(collection = "scopus.forum_touch_queue")
@CompoundIndex(name = "uniq_scopus_forum_touch", def = "{'source': 1, 'sourceId': 1}", unique = true)
public class ScopusForumTouch {
    @Id
    private String id;
    private String source;
    private String sourceId;
    private Instant touchedAt;
}
