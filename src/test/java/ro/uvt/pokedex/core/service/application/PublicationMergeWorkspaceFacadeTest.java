package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationMergeDecision;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.repository.scopus.canonical.PublicationMergeDecisionRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicationMergeWorkspaceFacadeTest {

    @Mock private EffectiveAuthorshipReadService effectiveAuthorshipReadService;
    @Mock private PublicationMergeService publicationMergeService;
    @Mock private PublicationMergeDecisionRepository decisionRepository;

    @InjectMocks private PublicationMergeWorkspaceFacade facade;

    private static ScholardexPublicationView pub(String id, String title, String coverDate,
                                                 String eid, String doi, int cites) {
        ScholardexPublicationView view = new ScholardexPublicationView();
        view.setId(id);
        view.setTitle(title);
        view.setCoverDate(coverDate);
        view.setEid(eid);
        view.setDoi(doi);
        view.setCitedbyCount(cites);
        return view;
    }

    /** The mOSAIC shape: case-variant titles, coverDates a year apart, Scopus side richer. */
    @Test
    void suggestsTheSameTitlePairWithTheScopusSideAsSurvivor() {
        when(effectiveAuthorshipReadService.findEffectivePublicationsForUser("u@uvt.ro")).thenReturn(List.of(
                pub("spub_oa", "An analysis of mOSAIC ontology for Cloud resources annotation", "2011-01-01", null, null, 161),
                pub("spub_sc", "An analysis of mOSAIC ontology for cloud resources annotation", "2011-12-14", "2-s2.0-1", null, 163),
                pub("spub_other", "A completely different paper", "2015-01-01", "2-s2.0-2", null, 5)
        ));
        when(decisionRepository.findBySurvivorCanonicalIdInOrDuplicateCanonicalIdIn(any(), any()))
                .thenReturn(List.of());

        PublicationMergeWorkspaceFacade.MergeWorkspaceView view = facade.mergeState("u@uvt.ro");

        assertThat(view.suggestions()).hasSize(1);
        assertThat(view.suggestions().getFirst().survivor().id()).isEqualTo("spub_sc");
        assertThat(view.suggestions().getFirst().duplicate().id()).isEqualTo("spub_oa");
        assertThat(view.mergeStateByPublicationId()).isEmpty();
    }

    @Test
    void sameTitleButDistantYearsIsNotSuggested() {
        when(effectiveAuthorshipReadService.findEffectivePublicationsForUser("u@uvt.ro")).thenReturn(List.of(
                pub("spub_a", "Recurring workshop report", "2011-01-01", "2-s2.0-1", null, 3),
                pub("spub_b", "Recurring workshop report", "2015-01-01", "2-s2.0-2", null, 1)
        ));
        when(decisionRepository.findBySurvivorCanonicalIdInOrDuplicateCanonicalIdIn(any(), any()))
                .thenReturn(List.of());

        assertThat(facade.mergeState("u@uvt.ro").suggestions()).isEmpty();
    }

    @Test
    void standingDecisionsSuppressSuggestionsAndPendingOnesMarkBothRows() {
        when(effectiveAuthorshipReadService.findEffectivePublicationsForUser("u@uvt.ro")).thenReturn(List.of(
                pub("spub_a", "Same paper", "2011-01-01", "2-s2.0-1", null, 3),
                pub("spub_b", "Same paper", "2011-06-01", null, null, 1)
        ));
        PublicationMergeDecision pending = new PublicationMergeDecision();
        pending.setStatus(PublicationMergeDecision.Status.PENDING);
        pending.setPairKey(PublicationMergeDecision.pairKeyOf("spub_a", "spub_b"));
        pending.getSurvivor().setCanonicalId("spub_a");
        pending.getDuplicate().setCanonicalId("spub_b");
        when(decisionRepository.findBySurvivorCanonicalIdInOrDuplicateCanonicalIdIn(any(), any()))
                .thenReturn(List.of(pending));

        PublicationMergeWorkspaceFacade.MergeWorkspaceView view = facade.mergeState("u@uvt.ro");

        assertThat(view.suggestions()).isEmpty(); // already requested — never re-suggested
        assertThat(view.mergeStateByPublicationId())
                .containsEntry("spub_a", "PENDING")
                .containsEntry("spub_b", "PENDING");
    }

    @Test
    void rejectedDecisionSuppressesTheSuggestionWithoutBadges() {
        when(effectiveAuthorshipReadService.findEffectivePublicationsForUser("u@uvt.ro")).thenReturn(List.of(
                pub("spub_a", "Same paper", "2011-01-01", "2-s2.0-1", null, 3),
                pub("spub_b", "Same paper", "2011-06-01", null, null, 1)
        ));
        PublicationMergeDecision rejected = new PublicationMergeDecision();
        rejected.setStatus(PublicationMergeDecision.Status.REJECTED);
        rejected.setPairKey(PublicationMergeDecision.pairKeyOf("spub_a", "spub_b"));
        rejected.getSurvivor().setCanonicalId("spub_a");
        rejected.getDuplicate().setCanonicalId("spub_b");
        when(decisionRepository.findBySurvivorCanonicalIdInOrDuplicateCanonicalIdIn(any(), any()))
                .thenReturn(List.of(rejected));

        PublicationMergeWorkspaceFacade.MergeWorkspaceView view = facade.mergeState("u@uvt.ro");

        assertThat(view.suggestions()).isEmpty();
        assertThat(view.mergeStateByPublicationId()).isEmpty();
    }

    @Test
    void flagReordersSidesByRichnessAndDelegates() {
        when(effectiveAuthorshipReadService.findEffectivePublicationsForUser("u@uvt.ro")).thenReturn(List.of(
                pub("spub_poor", "Same paper", "2011-01-01", null, null, 161),
                pub("spub_rich", "Same paper", "2011-12-14", "2-s2.0-1", null, 3)
        ));

        // The researcher flags with the poorer record first — the eid-bearing side must still survive.
        facade.flag("u@uvt.ro", null, "spub_poor", "spub_rich", "same paper twice");

        verify(publicationMergeService).requestMerge("spub_rich", "spub_poor", "u@uvt.ro", null, "same paper twice");
    }

    @Test
    void flagRejectsPublicationsOutsideTheResearchersOwnList() {
        when(effectiveAuthorshipReadService.findEffectivePublicationsForUser("u@uvt.ro")).thenReturn(List.of(
                pub("spub_mine", "My paper", "2011-01-01", "2-s2.0-1", null, 3)
        ));

        assertThatThrownBy(() -> facade.flag("u@uvt.ro", null, "spub_mine", "spub_foreign", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("your own publication list");
        assertThatThrownBy(() -> facade.flag("u@uvt.ro", null, "spub_mine", "spub_mine", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
