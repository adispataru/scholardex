package ro.uvt.pokedex.core.model;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;


@Data
@Document(collection = "scholardex.artisticEvent")
public class ArtisticEvent {
    @Id
    private String id;
    private String name;
    private String domainId;
    private Rank rank;

    public static enum Rank {
        INTERNATIONAL_TOP("1"),
        INTERNATIONAL("2"),
        NATIONAL("3");
        private String value;
        Rank(String value) {
            this.value = value;
        }
    }

}
