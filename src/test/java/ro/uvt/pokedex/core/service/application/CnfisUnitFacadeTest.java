package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.DepartmentAffiliation;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.model.reporting.CNFISReport2025;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.reporting.cnfis.CnfisSheetSnapshot;
import ro.uvt.pokedex.core.model.reporting.cnfis.CnfisUnitSheet;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.repository.cnfis.CnfisSheetSnapshotRepository;
import ro.uvt.pokedex.core.repository.cnfis.CnfisUnitSheetRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;
import ro.uvt.pokedex.core.service.application.model.CnfisUnitViewModel;
import ro.uvt.pokedex.core.service.reporting.CNFISReportExportService;
import ro.uvt.pokedex.core.service.reporting.CnfisEdition;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CnfisUnitFacadeTest {

    @Mock private DepartmentRepository departmentRepository;
    @Mock private OrgDivisionRepository orgDivisionRepository;
    @Mock private DepartmentAffiliationService affiliationService;
    @Mock private UserRepository userRepository;
    @Mock private CnfisSheetSnapshotRepository snapshotRepository;
    @Mock private CnfisUnitSheetRepository unitSheetRepository;
    @Mock private CnfisReportingFacade cnfisReportingFacade;
    @Mock private ScholardexProjectionReadService projectionReadService;
    @Mock private CNFISReportExportService exportService;

    private CnfisUnitFacade facade;

    @BeforeEach
    void aDepartmentOfThree() {
        facade = new CnfisUnitFacade(departmentRepository, orgDivisionRepository, affiliationService, userRepository,
                snapshotRepository, unitSheetRepository, cnfisReportingFacade, projectionReadService, exportService);
        Department department = new Department();
        department.setId("dept");
        department.setName("Psihologie");
        lenient().when(departmentRepository.findById("dept")).thenReturn(Optional.of(department));
        lenient().when(affiliationService.listCurrentAffiliations("dept"))
                .thenReturn(List.of(affiliation("ana@uvt.ro"), affiliation("ion@uvt.ro"), affiliation("dan@uvt.ro")));
        lenient().when(userRepository.findAllById(any())).thenReturn(List.of(user("ana@uvt.ro", "Ana"), user("ion@uvt.ro", "Ion"), user("dan@uvt.ro", "Dan")));
        lenient().when(unitSheetRepository.findByUnitKindAndUnitIdAndReportingYearOrderByCreatedAtDesc(any(), any(), eq(2025)))
                .thenReturn(List.of());
    }

    @Test
    void aMembersOwnFrozenSheetBeatsAProvisionalOneAndTheNewestOfAKindWins() {
        CnfisSheetSnapshot anaOld = snapshot("a1", "ana@uvt.ro", false, "2026-09-01T00:00:00Z");
        CnfisSheetSnapshot anaNew = snapshot("a2", "ana@uvt.ro", false, "2026-09-20T00:00:00Z");
        CnfisSheetSnapshot anaProvisional = snapshot("a3", "ana@uvt.ro", true, "2026-09-29T00:00:00Z");
        CnfisSheetSnapshot ionProvisional = snapshot("i1", "ion@uvt.ro", true, "2026-09-29T00:00:00Z");
        when(snapshotRepository.findByUserEmailInAndReportingYear(any(), eq(2025)))
                .thenReturn(List.of(anaOld, anaNew, anaProvisional, ionProvisional));

        CnfisUnitViewModel unit = facade.buildUnit(CnfisUnitSheet.UnitKind.DEPARTMENT, "dept", 2025).orElseThrow();

        assertEquals(List.of("Ana Pop", "Dan Pop", "Ion Pop"), unit.members().stream().map(CnfisUnitViewModel.Member::displayName).toList());
        CnfisUnitViewModel.Member ana = unit.members().get(0);
        assertEquals("a2", ana.snapshotId(), "her own newest frozen copy, not the head's provisional one");
        assertFalse(ana.provisional());
        CnfisUnitViewModel.Member dan = unit.members().get(1);
        assertFalse(dan.hasSheet());
        CnfisUnitViewModel.Member ion = unit.members().get(2);
        assertEquals("i1", ion.snapshotId());
        assertTrue(ion.provisional());
    }

    @Test
    void provisionalSheetsAreGeneratedForWhoeverHasNoneOrForTheOneAskedFor() {
        when(snapshotRepository.findByUserEmailInAndReportingYear(any(), eq(2025)))
                .thenReturn(List.of(snapshot("a1", "ana@uvt.ro", false, "2026-09-20T00:00:00Z")));
        when(cnfisReportingFacade.freezeProvisional(any(), eq(CnfisEdition.EDITION_2025), eq("head@uvt.ro")))
                .thenReturn(Optional.of(new CnfisSheetSnapshot()));

        int all = facade.generateProvisional(CnfisUnitSheet.UnitKind.DEPARTMENT, "dept", 2025, null, "head@uvt.ro");
        assertEquals(2, all, "Ion and Dan; Ana has her own");
        verify(cnfisReportingFacade, never()).freezeProvisional(eq("ana@uvt.ro"), any(), any());

        int one = facade.generateProvisional(CnfisUnitSheet.UnitKind.DEPARTMENT, "dept", 2025, "ana@uvt.ro", "head@uvt.ro");
        assertEquals(1, one, "asked for by name, even though she has her own");
    }

    @Test
    void buildingTheTableLocksTheSheetsAndNamesWhoIsMissingAndDeletingReleasesThem() {
        CnfisSheetSnapshot ana = snapshot("a1", "ana@uvt.ro", false, "2026-09-20T00:00:00Z");
        ana.getRows().add(row("spub_shared"));
        ana.getRows().add(row("spub_ana"));
        CnfisSheetSnapshot ion = snapshot("i1", "ion@uvt.ro", true, "2026-09-29T00:00:00Z");
        ion.getRows().add(row("spub_shared"));
        when(snapshotRepository.findByUserEmailInAndReportingYear(any(), eq(2025))).thenReturn(List.of(ana, ion));
        when(unitSheetRepository.save(any())).thenAnswer(inv -> {
            CnfisUnitSheet t = inv.getArgument(0);
            t.setId("table-1");
            return t;
        });

        CnfisUnitSheet table = facade.buildTable(CnfisUnitSheet.UnitKind.DEPARTMENT, "dept", 2025, "head@uvt.ro").orElseThrow();

        assertEquals(2, table.getMembers().size());
        assertEquals(List.of("Dan Pop"), table.getMissingMembers());
        assertEquals(2, table.getRowCount(), "the shared paper is counted once");
        assertTrue(table.getMembers().stream().anyMatch(m -> m.getUserEmail().equals("ion@uvt.ro") && m.isProvisional()));
        assertEquals("table-1", ana.getLockedByUnitSheetId());
        assertEquals("table-1", ion.getLockedByUnitSheetId());

        when(unitSheetRepository.findById("table-1")).thenReturn(Optional.of(table));
        when(snapshotRepository.findByLockedByUnitSheetId("table-1")).thenReturn(List.of(ana, ion));
        assertTrue(facade.deleteTable(CnfisUnitSheet.UnitKind.DEPARTMENT, "dept", "table-1"));
        assertNull(ana.getLockedByUnitSheetId());
        assertNull(ion.getLockedByUnitSheetId());
        verify(unitSheetRepository).deleteById("table-1");
    }

    @Test
    void aTableOfAnotherUnitIsNeitherDeletedNorExported() throws Exception {
        CnfisUnitSheet table = new CnfisUnitSheet();
        table.setId("t9");
        table.setUnitKind(CnfisUnitSheet.UnitKind.DEPARTMENT);
        table.setUnitId("other-dept");
        when(unitSheetRepository.findById("t9")).thenReturn(Optional.of(table));

        assertFalse(facade.deleteTable(CnfisUnitSheet.UnitKind.DEPARTMENT, "dept", "t9"));
        assertTrue(facade.exportTable(CnfisUnitSheet.UnitKind.DEPARTMENT, "dept", "t9").isEmpty());
        verify(unitSheetRepository, never()).deleteById(any());
    }

    @Test
    void theTableIsExportedOncePerPaperFromTheSheetsItHolds() throws Exception {
        CnfisUnitSheet table = new CnfisUnitSheet();
        table.setId("t1");
        table.setUnitKind(CnfisUnitSheet.UnitKind.DEPARTMENT);
        table.setUnitId("dept");
        CnfisUnitSheet.Member m1 = new CnfisUnitSheet.Member();
        m1.setSnapshotId("a1");
        CnfisUnitSheet.Member m2 = new CnfisUnitSheet.Member();
        m2.setSnapshotId("i1");
        table.getMembers().addAll(List.of(m1, m2));
        CnfisSheetSnapshot ana = snapshot("a1", "ana@uvt.ro", false, "2026-09-20T00:00:00Z");
        ana.getRows().add(row("spub_shared"));
        CnfisSheetSnapshot ion = snapshot("i1", "ion@uvt.ro", true, "2026-09-29T00:00:00Z");
        ion.getRows().add(row("spub_shared"));
        ion.getRows().add(row("spub_ion"));
        when(unitSheetRepository.findById("t1")).thenReturn(Optional.of(table));
        when(snapshotRepository.findById("a1")).thenReturn(Optional.of(ana));
        when(snapshotRepository.findById("i1")).thenReturn(Optional.of(ion));
        when(projectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of());
        when(exportService.generateAnexa6(any(), any(), any(), any())).thenReturn(new byte[]{1});

        facade.exportTable(CnfisUnitSheet.UnitKind.DEPARTMENT, "dept", "t1");

        org.mockito.ArgumentCaptor<List<? extends ScoringPublicationReadModel>> pubs = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(exportService).generateAnexa6(pubs.capture(), any(), any(), any());
        assertEquals(List.of("spub_shared", "spub_ion"), pubs.getValue().stream().map(ScoringPublicationReadModel::getId).toList());
    }

    @Test
    void aPatentOrAConcertTwoColleaguesDeclaredAppearsOnce() throws Exception {
        CnfisUnitSheet table = new CnfisUnitSheet();
        table.setId("t2");
        table.setUnitKind(CnfisUnitSheet.UnitKind.DEPARTMENT);
        table.setUnitId("dept");
        CnfisUnitSheet.Member m1 = new CnfisUnitSheet.Member();
        m1.setSnapshotId("a1");
        m1.setUserEmail("ana@uvt.ro");
        CnfisUnitSheet.Member m2 = new CnfisUnitSheet.Member();
        m2.setSnapshotId("i1");
        m2.setUserEmail("ion@uvt.ro");
        table.getMembers().addAll(List.of(m1, m2));
        CnfisSheetSnapshot ana = snapshot("a1", "ana@uvt.ro", false, "2026-09-20T00:00:00Z");
        ana.getPatents().add(patent("act-a", "Sistem de răcire", "RO 128500 B1", 3, 2));
        ana.getArtsRows().add(arts("act-a2", "Concert de deschidere", "Festivalul Enescu", 1));
        CnfisSheetSnapshot ion = snapshot("i1", "ion@uvt.ro", false, "2026-09-29T00:00:00Z");
        ion.getPatents().add(patent("act-i", "Sistem de racire (brevet)", "RO128500B1", 3, 1));
        ion.getPatents().add(patent("act-i2", "Alt dispozitiv", "RO 130001 B1", 1, 1));
        ion.getArtsRows().add(arts("act-i3", "Concert de deschidere", "Festivalul ENESCU", 1));
        when(unitSheetRepository.findById("t2")).thenReturn(Optional.of(table));
        when(snapshotRepository.findById("a1")).thenReturn(Optional.of(ana));
        when(snapshotRepository.findById("i1")).thenReturn(Optional.of(ion));
        when(projectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of());
        when(exportService.generateAnexa6(any(), any(), any(), any())).thenReturn(new byte[]{1});
        when(exportService.generateAnexa61(any())).thenReturn(new byte[]{1});

        facade.exportTable(CnfisUnitSheet.UnitKind.DEPARTMENT, "dept", "t2");
        facade.exportArtsTable(CnfisUnitSheet.UnitKind.DEPARTMENT, "dept", "t2");

        org.mockito.ArgumentCaptor<List<ro.uvt.pokedex.core.model.reporting.CNFISReport2025>> patents =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(exportService).generateAnexa6(any(), any(), any(), patents.capture());
        assertEquals(2, patents.getValue().size(), "the shared patent once, by its granted code");
        assertEquals(2, patents.getValue().getFirst().getNumarAutoriUniversitate(), "the larger count of university authors");
        org.mockito.ArgumentCaptor<List<ro.uvt.pokedex.core.service.reporting.CNFISReportExportService.ArtsExportRow>> arts =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(exportService).generateAnexa61(arts.capture());
        assertEquals(1, arts.getValue().size(), "the same concert once");
    }

    private static CnfisSheetSnapshot.Patent patent(String id, String title, String code, int authors, int university) {
        CnfisSheetSnapshot.Patent p = new CnfisSheetSnapshot.Patent();
        p.setActivityInstanceId(id);
        p.setTitle(title);
        p.setCode(code);
        p.setOffice("OSIM");
        p.setYear("2023");
        p.setType("National");
        p.setAuthorCount(authors);
        p.setUniversityAuthorCount(university);
        return p;
    }

    private static CnfisSheetSnapshot.ArtsRow arts(String id, String work, String event, int participants) {
        CnfisSheetSnapshot.ArtsRow r = new CnfisSheetSnapshot.ArtsRow();
        r.setActivityInstanceId(id);
        r.setWork(work);
        r.setEvent(event);
        r.setYear("2023");
        r.setLevel("INTERNATIONAL_TOP");
        r.setKind("INDIVIDUAL");
        r.setUniversityParticipants(participants);
        return r;
    }

    @Test
    void aFacultyGathersTheMembersOfAllItsDepartments() {
        OrgDivision faculty = new OrgDivision();
        faculty.setId("fac");
        faculty.setName("FPSE");
        Department other = new Department();
        other.setId("dept-2");
        other.setName("Științe ale Educației");
        Department psychology = departmentRepository.findById("dept").orElseThrow();
        when(orgDivisionRepository.findById("fac")).thenReturn(Optional.of(faculty));
        when(departmentRepository.findByDivisionId("fac")).thenReturn(List.of(psychology, other));
        when(affiliationService.listCurrentAffiliations("dept-2")).thenReturn(List.of(affiliation("eva@uvt.ro")));
        when(userRepository.findAllById(any())).thenReturn(List.of(user("ana@uvt.ro", "Ana"), user("eva@uvt.ro", "Eva")));
        when(snapshotRepository.findByUserEmailInAndReportingYear(any(), eq(2025))).thenReturn(List.of());

        CnfisUnitViewModel unit = facade.buildUnit(CnfisUnitSheet.UnitKind.DIVISION, "fac", 2025).orElseThrow();

        assertEquals("FPSE", unit.unitName());
        assertEquals("Psihologie", unit.members().get(0).departmentName());
        assertEquals("Științe ale Educației", unit.members().get(1).departmentName());
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private static DepartmentAffiliation affiliation(String email) {
        DepartmentAffiliation a = new DepartmentAffiliation();
        a.setUserId(email);
        return a;
    }

    private static User user(String email, String firstName) {
        User u = new User();
        u.setEmail(email);
        User.ResearcherProfile p = new User.ResearcherProfile();
        p.setFirstName(firstName);
        p.setLastName("Pop");
        u.setResearcherProfile(p);
        return u;
    }

    private static CnfisSheetSnapshot snapshot(String id, String email, boolean provisional, String createdAt) {
        CnfisSheetSnapshot s = new CnfisSheetSnapshot();
        s.setId(id);
        s.setUserEmail(email);
        s.setReportingYear(2025);
        s.setProvisional(provisional);
        s.setCreatedAt(Instant.parse(createdAt));
        return s;
    }

    private static CnfisSheetSnapshot.Row row(String publicationId) {
        CnfisSheetSnapshot.Row r = new CnfisSheetSnapshot.Row();
        r.setPublicationId(publicationId);
        r.setTitle("Title " + publicationId);
        r.setYear("2023");
        r.setDoi("10.1/" + publicationId);
        CNFISReport2025 c = new CNFISReport2025();
        c.setIsiQ1(true);
        r.setClassification(c);
        return r;
    }
}
