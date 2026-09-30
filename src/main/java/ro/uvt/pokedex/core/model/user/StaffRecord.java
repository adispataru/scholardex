package ro.uvt.pokedex.core.model.user;

import lombok.Data;

import java.time.LocalDate;

/**
 * H129 — the employment facts CNFIS counts by ("Anexa 1" staff): who is a university author of a paper at
 * the reference date of an edition. Filled by the head of the department on the roster page; the platform
 * does not learn them from the staff import. Absent on most accounts: an author whose record is missing is
 * counted as staff, and the sheet says whose record is missing.
 */
@Data
public class StaffRecord {

    /** The employment forms of the CNFIS "Personal didactic" sheet, plus the ones CNFIS does not count. */
    public enum EmploymentType {
        /** titular cu funcția de bază în universitate */
        TITULAR_FUNCTIA_DE_BAZA(true),
        /** titular fără funcția de bază (the base post is at another institution) */
        TITULAR_FARA_FUNCTIA_DE_BAZA(true),
        /** angajat pe perioadă determinată, cu normă întreagă (art. 202 alin. 3–5, Legea 199/2023) */
        PERIOADA_DETERMINATA_NORMA_INTREAGA(true),
        /** cadru didactic asociat — not counted */
        ASOCIAT(false),
        /** student doctorand — not counted */
        DOCTORAND(false),
        /** pensionat — not counted */
        PENSIONAT(false);

        private final boolean countedByCnfis;

        EmploymentType(boolean countedByCnfis) {
            this.countedByCnfis = countedByCnfis;
        }

        public boolean isCountedByCnfis() {
            return countedByCnfis;
        }
    }

    private EmploymentType employmentType;
    private LocalDate employedFrom;
    private LocalDate employedTo;

    /** Whether the person counts as university staff on the given day; null when the record says nothing. */
    public Boolean isStaffOn(LocalDate day) {
        if (employmentType == null) {
            return null;
        }
        if (!employmentType.isCountedByCnfis()) {
            return false;
        }
        if (employedFrom != null && day.isBefore(employedFrom)) {
            return false;
        }
        return employedTo == null || !day.isAfter(employedTo);
    }
}
