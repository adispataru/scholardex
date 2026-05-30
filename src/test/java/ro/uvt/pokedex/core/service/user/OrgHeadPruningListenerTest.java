package ro.uvt.pokedex.core.service.user;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrgHeadPruningListenerTest {

    @Mock private OrgDivisionRepository orgDivisionRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private GroupRepository groupRepository;

    @InjectMocks
    private OrgHeadPruningListener listener;

    @Test
    void blankUserIdIsNoOp() {
        listener.onUserDeactivated(new UserDeactivatedEvent(null, "deleted"));
        listener.onUserDeactivated(new UserDeactivatedEvent("", "deleted"));
        listener.onUserDeactivated(new UserDeactivatedEvent("   ", "deleted"));

        verify(orgDivisionRepository, never()).findByHeadUserIdsContaining(any());
    }

    @Test
    void pruneRemovesUserFromDivisionDepartmentAndGroupListsAndPersistsOnce() {
        OrgDivision div = division("div-1", List.of("ana@uvt.ro", "other@uvt.ro"));
        Department dept = department("dept-1", List.of("ana@uvt.ro"));
        Group group = group("g-1", List.of("ana@uvt.ro", "other@uvt.ro"));

        when(orgDivisionRepository.findByHeadUserIdsContaining("ana@uvt.ro")).thenReturn(List.of(div));
        when(departmentRepository.findByHeadUserIdsContaining("ana@uvt.ro")).thenReturn(List.of(dept));
        when(groupRepository.findBySupervisorUserIdsContaining("ana@uvt.ro")).thenReturn(List.of(group));

        listener.onUserDeactivated(new UserDeactivatedEvent("ana@uvt.ro", "locked"));

        assertEquals(List.of("other@uvt.ro"), div.getHeadUserIds());
        assertTrue(dept.getHeadUserIds().isEmpty());
        assertEquals(List.of("other@uvt.ro"), group.getSupervisorUserIds());

        verify(orgDivisionRepository).saveAll(List.of(div));
        verify(departmentRepository).saveAll(List.of(dept));
        verify(groupRepository).saveAll(List.of(group));
    }

    @Test
    void pruneSkipsRepositoriesWhenNoHits() {
        when(orgDivisionRepository.findByHeadUserIdsContaining("nobody@uvt.ro")).thenReturn(List.of());
        when(departmentRepository.findByHeadUserIdsContaining("nobody@uvt.ro")).thenReturn(List.of());
        when(groupRepository.findBySupervisorUserIdsContaining("nobody@uvt.ro")).thenReturn(List.of());

        listener.onUserDeactivated(new UserDeactivatedEvent("nobody@uvt.ro", "deleted"));

        verify(orgDivisionRepository, never()).saveAll(any());
        verify(departmentRepository, never()).saveAll(any());
        verify(groupRepository, never()).saveAll(any());
    }

    private static OrgDivision division(String id, List<String> heads) {
        OrgDivision d = new OrgDivision();
        d.setId(id);
        d.setHeadUserIds(new ArrayList<>(heads));
        return d;
    }

    private static Department department(String id, List<String> heads) {
        Department d = new Department();
        d.setId(id);
        d.setHeadUserIds(new ArrayList<>(heads));
        return d;
    }

    private static Group group(String id, List<String> supervisors) {
        Group g = new Group();
        g.setId(id);
        g.setSupervisorUserIds(new ArrayList<>(supervisors));
        return g;
    }
}
