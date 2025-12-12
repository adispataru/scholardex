package ro.uvt.pokedex.core.model.activities;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.DBRef;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;
import java.util.Map;

@Data
@Document(collection = "activityInstances")
public class ActivityInstance {
    @Id
    private String id;
    private String name;
    private String researcherId;
    private String date;
    @DBRef
    private Activity activity;
    private Map<String, String> fields;
    private Map<Activity.ReferenceField, String> referenceFields;

    public int getYear(){
        return Integer.parseInt(date.substring(0, 4));
    }
}
