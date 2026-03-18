package ro.uvt.pokedex.core.model.activities;

import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.DBRef;
import org.springframework.data.mongodb.core.mapping.Document;
import ro.uvt.pokedex.core.service.application.PersistenceYearSupport;

import java.util.Map;
import java.util.Optional;

@Data
@Document(collection = "activityInstances")
public class ActivityInstance {
    private static final Logger log = LoggerFactory.getLogger(ActivityInstance.class);
    @Id
    private String id;
    private String name;
    private String researcherId;
    private String date;
    @DBRef
    private Activity activity;
    private Map<String, String> fields;
    private Map<Activity.ReferenceField, String> referenceFields;

    public Optional<Integer> getYearOptional() {
        return PersistenceYearSupport.extractYear(date, id, log);
    }

    public int getYear(){
        return Integer.parseInt(date.substring(0, 4));
    }
}
