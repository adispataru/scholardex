package ro.uvt.pokedex.core.model.workspace;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Document(collection = "scholardex.workspacePreferences")
public class WorkspacePreferences {
    @Id
    private String userEmail;
    private List<String> overviewCardOrder = new ArrayList<>();
    private List<String> dismissedNotificationIds = new ArrayList<>();
    private Instant lastVisitAt;
    private Instant updatedAt;
    /**
     * The individual report the evaluation page opens by default when no report is asked for explicitly.
     * Null = "never chose" → the first visible report, as before. Ignored when it is no longer visible to the user.
     */
    private String preferredReportId;

    /**
     * H138: the competition domain (1–13 of PN-IV PD/TE 2026, Anexa 1) the researcher chose for each
     * domain-selectable UEFISCDI report, by report id. Absent = not chosen yet (the page asks first).
     */
    private java.util.Map<String, Integer> competitionDomainByReportId = new java.util.HashMap<>();
}
