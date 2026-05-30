package ro.uvt.pokedex.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import ro.uvt.pokedex.core.repository.InstitutionRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentAffiliationRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.org.MembershipRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;
import ro.uvt.pokedex.core.service.importing.GroupService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focuses on the safety guard added to {@link OrgSeedRunner}: a reset with existing data
 * above the bundled footprint refuses to wipe unless explicitly confirmed.
 */
@ExtendWith(MockitoExtension.class)
class OrgSeedRunnerTest {

    @Mock private InstitutionRepository institutionRepository;
    @Mock private OrgDivisionRepository orgDivisionRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private DepartmentAffiliationRepository departmentAffiliationRepository;
    @Mock private MembershipRepository membershipRepository;
    @Mock private GroupRepository groupRepository;
    @Mock private GroupService groupService;

    @InjectMocks
    private OrgSeedRunner runner;

    @Test
    void resetWithLargerThanBundledFootprintRefusesAndDoesNotWipeOrSeed() throws Exception {
        ReflectionTestUtils.setField(runner, "reset", true);
        ReflectionTestUtils.setField(runner, "confirmDestroy", false);
        // Many real groups — clearly user data, not the bundled seed.
        stubCounts(1, 3, 4, /* groups */ 42, 50, 50);

        runner.run();

        verifyNothingWiped();
        verify(institutionRepository, never()).save(any());
        verify(orgDivisionRepository, never()).saveAll(any());
        verify(departmentRepository, never()).saveAll(any());
    }

    @Test
    void resetWithLargerFootprintButConfirmDestroyTrueWipesAndReseeds() throws Exception {
        ReflectionTestUtils.setField(runner, "reset", true);
        ReflectionTestUtils.setField(runner, "confirmDestroy", true);
        ReflectionTestUtils.setField(runner, "importSampleCsv", false);
        stubCounts(1, 3, 4, /* groups */ 42, 0, 0);
        // After wipe, the second institutionRepository.count() check sees 0 → proceed to seed.
        // The stub returns the same value for all calls; switch to a counter-aware stub.
        when(institutionRepository.count()).thenReturn(1L, 0L);

        runner.run();

        verifyAllCollectionsWiped();
        verify(institutionRepository).save(any());
        verify(orgDivisionRepository).saveAll(any());
        verify(departmentRepository).saveAll(any());
    }

    @Test
    void resetWithFootprintInsideBundledLimitsWipesWithoutConfirmation() throws Exception {
        ReflectionTestUtils.setField(runner, "reset", true);
        ReflectionTestUtils.setField(runner, "confirmDestroy", false);
        ReflectionTestUtils.setField(runner, "importSampleCsv", false);
        // Exactly the bundled footprint — assumed to be a prior seed run.
        stubCounts(1, 3, 4, 3, 7, 7);
        when(institutionRepository.count()).thenReturn(1L, 0L);

        runner.run();

        verifyAllCollectionsWiped();
        verify(institutionRepository).save(any());
    }

    @Test
    void noResetSkipsWipeAndOnlySeedsWhenInstitutionsEmpty() throws Exception {
        ReflectionTestUtils.setField(runner, "reset", false);
        ReflectionTestUtils.setField(runner, "importSampleCsv", false);
        when(institutionRepository.count()).thenReturn(0L);

        runner.run();

        verifyNothingWiped();
        verify(institutionRepository).save(any()); // seed path
    }

    @Test
    void noResetWithExistingInstitutionsLeavesEverythingAlone() throws Exception {
        ReflectionTestUtils.setField(runner, "reset", false);
        when(institutionRepository.count()).thenReturn(1L);

        runner.run();

        verifyNothingWiped();
        verify(institutionRepository, never()).save(any());
    }

    // --- helpers ---

    private void stubCounts(long inst, long div, long dept, long grp, long mem, long aff) {
        lenient().when(institutionRepository.count()).thenReturn(inst);
        lenient().when(orgDivisionRepository.count()).thenReturn(div);
        lenient().when(departmentRepository.count()).thenReturn(dept);
        lenient().when(groupRepository.count()).thenReturn(grp);
        lenient().when(membershipRepository.count()).thenReturn(mem);
        lenient().when(departmentAffiliationRepository.count()).thenReturn(aff);
    }

    private void verifyAllCollectionsWiped() {
        verify(membershipRepository).deleteAll();
        verify(departmentAffiliationRepository).deleteAll();
        verify(groupRepository).deleteAll();
        verify(departmentRepository).deleteAll();
        verify(orgDivisionRepository).deleteAll();
        verify(institutionRepository).deleteAll();
    }

    private void verifyNothingWiped() {
        verify(membershipRepository, never()).deleteAll();
        verify(departmentAffiliationRepository, never()).deleteAll();
        verify(groupRepository, never()).deleteAll();
        verify(departmentRepository, never()).deleteAll();
        verify(orgDivisionRepository, never()).deleteAll();
        verify(institutionRepository, never()).deleteAll();
    }
}
