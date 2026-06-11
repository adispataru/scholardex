package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PipelineRebuildServiceTest {

    @Mock
    private ScopusBigBangMigrationService scopusRebuild;
    @Mock
    private WosBigBangMigrationService wosRebuild;
    @Mock
    private OwnedCollectionRegistry ownedCollectionRegistry;

    @Test
    void rebuildAssertsOwnershipThenRunsWosThenScopus() {
        PipelineRebuildService service =
                new PipelineRebuildService(scopusRebuild, wosRebuild, ownedCollectionRegistry);

        PipelineRebuildService.PipelineRebuildResult result = service.rebuildAllDerivedFromSource();

        InOrder order = inOrder(ownedCollectionRegistry, wosRebuild, scopusRebuild);
        order.verify(ownedCollectionRegistry).assertAllWipeable(any());
        order.verify(wosRebuild).run(false, null);
        order.verify(scopusRebuild).runFull();
        assertThat(result).isNotNull();
    }

    @Test
    void rebuildAbortsBeforeWipingWhenGuardRejects() {
        doThrow(new IllegalArgumentException("foreign collection"))
                .when(ownedCollectionRegistry).assertAllWipeable(any());
        PipelineRebuildService service =
                new PipelineRebuildService(scopusRebuild, wosRebuild, ownedCollectionRegistry);

        assertThatThrownBy(service::rebuildAllDerivedFromSource)
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(wosRebuild, scopusRebuild);
    }

    @Test
    void startupValidationPassesWhenAllManagedCollectionsAreOwned() {
        OwnedCollectionRegistry allOwned =
                new OwnedCollectionRegistry(PipelineRebuildService.MANAGED_DERIVED_COLLECTIONS);
        PipelineRebuildService service =
                new PipelineRebuildService(scopusRebuild, wosRebuild, allOwned);

        assertThatCode(service::validateManagedCollectionsAreOwned).doesNotThrowAnyException();
    }

    @Test
    void startupValidationFailsIfAManagedCollectionIsNotOwned() {
        Set<String> missingOne = new HashSet<>(PipelineRebuildService.MANAGED_DERIVED_COLLECTIONS);
        missingOne.remove("scholardex.source_links");
        PipelineRebuildService service =
                new PipelineRebuildService(scopusRebuild, wosRebuild, new OwnedCollectionRegistry(missingOne));

        assertThatThrownBy(service::validateManagedCollectionsAreOwned)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scholardex.source_links");
    }
}
