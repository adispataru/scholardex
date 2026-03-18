package ro.uvt.pokedex.core.model.activities;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

@Data
@Document(collection = "activities")
public class Activity {
    @Id
    private String id;
    protected String name;
    private List<Field> fields;
    private List<ReferenceField> referenceFields;

    @Data
    public static class Field {
        public String name;
        public List<String> allowedValues;
        public boolean number = false;
    }

    public static enum ReferenceField {
        FORUM_NAME,
        FORUM_ISSN,
        FORUM_EISSN,
        FORUM_ISBN,
        FORUM_PUBLISHER,
        PROJECT_GRANT_ID,
        UNIVERSITY_NAME,
        EVENT_NAME
    }
}
