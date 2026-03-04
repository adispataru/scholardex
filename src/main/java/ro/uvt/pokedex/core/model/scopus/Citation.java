package ro.uvt.pokedex.core.model.scopus;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.DBRef;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Document(collection = "scopus.citations2025")
@CompoundIndex(name = "uniq_cited_citing", def = "{'citedId': 1, 'citingId': 1}", unique = true)
public class Citation {
    @Id
    private String id;
//    @DBRef
//    private Publication cited;
//    @DBRef
//    private Publication citing;
    private String citedId;
    private String citingId;

}
