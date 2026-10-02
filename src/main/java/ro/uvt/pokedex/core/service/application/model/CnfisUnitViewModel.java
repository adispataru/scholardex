package ro.uvt.pokedex.core.service.application.model;

import java.util.List;

/** H129 slice 3 — a head's view of the CNFIS reporting of their unit for one edition. */
public record CnfisUnitViewModel(
        String unitKind,
        String unitId,
        String unitName,
        CnfisEditionViewModel edition,
        List<Member> members,
        List<Table> tables
) {
    /**
     * One member and the sheet that would represent them: their own frozen one, the head's provisional one, or none;
     * {@code citations} counts the sheet's Anexa 4.1 rows, whose total the institution's Anexa 1 asks per person.
     */
    public record Member(String email, String displayName, String departmentName, String domain,
                         String snapshotId, String frozenAt, boolean provisional, boolean locked, int rows, int patents,
                         int citations) {
        public boolean hasSheet() {
            return snapshotId != null;
        }
    }

    /** Whether any member's sheet holds citations of artistic works (the column shows only then). */
    public boolean anyCitations() {
        return members != null && members.stream().anyMatch(m -> m.citations() > 0);
    }

    public record Table(String id, String createdAt, String createdBy, int members, int provisionalMembers,
                        List<String> missingMembers, int rows, int patents) {
    }
}
