package ro.uvt.pokedex.core.model.user;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StaffRecordTest {

    private static final LocalDate REFERENCE = LocalDate.of(2025, 1, 1);

    @Test
    void whoCountsAsStaffAtTheReferenceDate() {
        assertEquals(Boolean.TRUE, record(StaffRecord.EmploymentType.TITULAR_FUNCTIA_DE_BAZA, null, null).isStaffOn(REFERENCE));
        assertEquals(Boolean.TRUE, record(StaffRecord.EmploymentType.TITULAR_FARA_FUNCTIA_DE_BAZA, null, null).isStaffOn(REFERENCE));
        assertEquals(Boolean.FALSE, record(StaffRecord.EmploymentType.ASOCIAT, null, null).isStaffOn(REFERENCE));
        assertEquals(Boolean.FALSE, record(StaffRecord.EmploymentType.DOCTORAND, null, null).isStaffOn(REFERENCE));
        assertEquals(Boolean.FALSE, record(StaffRecord.EmploymentType.PENSIONAT, null, null).isStaffOn(REFERENCE));
        assertNull(record(null, null, null).isStaffOn(REFERENCE), "no record says nothing");
    }

    @Test
    void aFixedTermContractCountsOnlyWhileItRuns() {
        StaffRecord.EmploymentType fixed = StaffRecord.EmploymentType.PERIOADA_DETERMINATA_NORMA_INTREAGA;
        assertEquals(Boolean.TRUE, record(fixed, LocalDate.of(2024, 10, 1), LocalDate.of(2025, 9, 30)).isStaffOn(REFERENCE));
        assertEquals(Boolean.TRUE, record(fixed, LocalDate.of(2024, 10, 1), REFERENCE).isStaffOn(REFERENCE), "the last day counts");
        assertEquals(Boolean.FALSE, record(fixed, LocalDate.of(2021, 10, 1), LocalDate.of(2024, 9, 30)).isStaffOn(REFERENCE));
        assertEquals(Boolean.FALSE, record(fixed, LocalDate.of(2025, 2, 1), null).isStaffOn(REFERENCE), "not yet employed");
    }

    private static StaffRecord record(StaffRecord.EmploymentType type, LocalDate from, LocalDate to) {
        StaffRecord record = new StaffRecord();
        record.setEmploymentType(type);
        record.setEmployedFrom(from);
        record.setEmployedTo(to);
        return record;
    }
}
