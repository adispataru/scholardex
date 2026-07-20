package ro.uvt.pokedex.core.service.application.reporting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.service.application.OrgUnitRosterService;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrgUnitPromotionBoardServiceTest {

    @Mock private OrgDivisionRepository orgDivisionRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private GroupRepository groupRepository;
    @Mock private IndividualReportRepository individualReportRepository;
    @Mock private OrgUnitRosterService orgUnitRosterService;
    @Mock private OrgUnitRunRollupService orgUnitRunRollupService;
    @Mock private PromotionReadinessService promotionReadinessService;

    @InjectMocks
    private OrgUnitPromotionBoardService service;

    private final IndividualReport report = new IndividualReport();
    private final OrgUnitRunRollupService.OrgUnitRunRollup rollup =
            new OrgUnitRunRollupService.OrgUnitRunRollup(List.of(), Map.of(), 0, 0, 0, null, null, null, null);
    private final PromotionReadinessService.PromotionBoard board =
            new PromotionReadinessService.PromotionBoard(List.of(), List.of(), List.of(), List.of(), 0);

    private void stubCommon() {
        when(individualReportRepository.findById("rep-1")).thenReturn(Optional.of(report));
        when(orgUnitRunRollupService.rollup(any(), eq(report), isNull())).thenReturn(rollup);
        when(promotionReadinessService.build(eq(report), eq(rollup), eq(Set.of(1)))).thenReturn(board);
    }

    @Test
    void divisionFlavorResolvesDivisionNameAndRoster() {
        stubCommon();
        OrgDivision division = new OrgDivision();
        division.setName("FMI");
        when(orgDivisionRepository.findById("u-1")).thenReturn(Optional.of(division));
        when(orgUnitRosterService.divisionRoster("u-1")).thenReturn(List.of());

        var view = service.build(OrgUnitPromotionBoardService.OrgUnitType.DIVISION, "u-1", "rep-1", Set.of(1));

        assertEquals("FMI", view.orElseThrow().unitName());
        verify(orgUnitRosterService).divisionRoster("u-1");
        verify(orgUnitRosterService, never()).departmentRoster(any());
    }

    @Test
    void departmentFlavorResolvesDepartmentNameAndRoster() {
        stubCommon();
        Department department = new Department();
        department.setName("Computer Science");
        when(departmentRepository.findById("u-2")).thenReturn(Optional.of(department));
        when(orgUnitRosterService.departmentRoster("u-2")).thenReturn(List.of());

        var view = service.build(OrgUnitPromotionBoardService.OrgUnitType.DEPARTMENT, "u-2", "rep-1", Set.of(1));

        assertEquals("Computer Science", view.orElseThrow().unitName());
        verify(orgUnitRosterService).departmentRoster("u-2");
    }

    @Test
    void groupFlavorResolvesGroupNameAndRoster() {
        stubCommon();
        Group group = new Group();
        group.setName("HPC Group");
        when(groupRepository.findById("u-3")).thenReturn(Optional.of(group));
        when(orgUnitRosterService.groupRoster("u-3")).thenReturn(List.of());

        var view = service.build(OrgUnitPromotionBoardService.OrgUnitType.GROUP, "u-3", "rep-1", Set.of(1));

        assertEquals("HPC Group", view.orElseThrow().unitName());
        verify(orgUnitRosterService).groupRoster("u-3");
    }

    @Test
    void missingReportOrUnitYieldsEmpty() {
        when(individualReportRepository.findById("rep-x")).thenReturn(Optional.empty());
        assertTrue(service.build(OrgUnitPromotionBoardService.OrgUnitType.DIVISION, "u-1", "rep-x", Set.of()).isEmpty());

        when(individualReportRepository.findById("rep-1")).thenReturn(Optional.of(report));
        when(orgDivisionRepository.findById("missing")).thenReturn(Optional.empty());
        assertTrue(service.build(OrgUnitPromotionBoardService.OrgUnitType.DIVISION, "missing", "rep-1", Set.of()).isEmpty());
    }
}
