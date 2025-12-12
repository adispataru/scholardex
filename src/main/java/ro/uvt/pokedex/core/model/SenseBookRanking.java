package ro.uvt.pokedex.core.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Document(collection = "senseRankings")
public class SenseBookRanking {
    @Id
    private String id;
    private String name;
    private Rank ranking;

    public enum Rank {
        A,
        B,
        C,
        D,
        E
    }
}

