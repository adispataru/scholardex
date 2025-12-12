package ro.uvt.pokedex.core.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Map;

@Data
@Document(collection = "urap.rankings")
public class URAPUniversityRanking {
    @Id
    private String name;
    private String country;
    private Map<Integer, Score> scores;

    @Data
    public static class Score {
        private int rank;
        private Double article;
        private Double citation;
        private Double totalDocument;
        private Double AIT;
        private Double CIT;
        private Double collaboration;
        private Double total;

    }


}
