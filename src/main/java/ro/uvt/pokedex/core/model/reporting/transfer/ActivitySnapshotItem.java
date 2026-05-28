package ro.uvt.pokedex.core.model.reporting.transfer;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@NoArgsConstructor
public class ActivitySnapshotItem implements SnapshotItem {
    public static final String ENTITY_TYPE = "ACTIVITY";

    private String itemKey;
    private String roleKey;
    private String activityName;
    private String activityId;
    private String description;
    private String category;
    private Double score;

    @Override
    public String entityType() {
        return ENTITY_TYPE;
    }

    @Override
    public String itemKey() {
        return itemKey;
    }

    public Map<String, Object> toRowMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("activity.activityName", activityName);
        m.put("activity.description", description);
        m.put("activity.category", category);
        m.put("activity.score", score);
        return m;
    }
}
