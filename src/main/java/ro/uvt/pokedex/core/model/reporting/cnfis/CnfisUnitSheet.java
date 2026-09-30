package ro.uvt.pokedex.core.model.reporting.cnfis;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * H129 — an Anexa 6 (the institutional table of articles and patents) built by the head of a department or
 * faculty for one edition, from the frozen sheets of the unit's members. Building it LOCKS those sheets
 * ({@link CnfisSheetSnapshot#getLockedByUnitSheetId}); deleting the table releases them.
 */
@Data
@Document(collection = "cnfisUnitSheets")
@CompoundIndex(name = "idx_cnfis_unit_sheet_unit_edition", def = "{'unitKind': 1, 'unitId': 1, 'reportingYear': 1, 'createdAt': -1}")
public class CnfisUnitSheet {

    public enum UnitKind { DEPARTMENT, DIVISION }

    @Id
    private String id;
    private UnitKind unitKind;
    private String unitId;
    private String unitName;
    private int reportingYear;
    private Instant createdAt;
    private String createdBy;

    /** The member sheets the table was built from, in the order they were taken. */
    private List<Member> members = new ArrayList<>();
    /** Members with no sheet at all when the table was built — they are not in it. */
    private List<String> missingMembers = new ArrayList<>();

    private int rowCount;
    private int patentCount;

    @Data
    public static class Member {
        private String userEmail;
        private String displayName;
        private String snapshotId;
        private boolean provisional;
        private String departmentName;
    }
}
