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
    /**
     * H142 — where an imported record came from (e.g. "Fișa de verificare: fisa.xlsx"); null for a record typed in.
     * An import never overwrites what the person typed: it only adds records.
     */
    private String importSource;
    /** H142 — identifies an imported item (person + type + text), so importing the same file again adds nothing. */
    private String importKey;
    /** H142 — true until the person has looked at an imported record ("de verificat"); null for typed records. */
    private Boolean needsReview;
    /**
     * H142 slice 7 — what its owner changed after the record was made, oldest first (null when nothing was): the
     * faculty's submitted records are corrected by their owners at once, so each change is kept.
     */
    private java.util.List<ActivityChange> changes;
    /**
     * H143 — the request to classify this book's publisher where no list decides, and the head's decision on it;
     * null when the record never asked.
     */
    private PublisherClaim publisherClaim;
    /**
     * H142 slice 3 — for an imported artistic performance, the level the file gave it (the row of the grid, the CNFIS
     * level): shown to the experts who rank the event, never scored.
     */
    private String eventLevelSuggestion;

    public Optional<Integer> getYearOptional() {
        return PersistenceYearSupport.extractYear(date, id, log);
    }

    public int getYear(){
        if (date == null || date.length() < 4) return 0;
        return Integer.parseInt(date.substring(0, 4));
    }
}
