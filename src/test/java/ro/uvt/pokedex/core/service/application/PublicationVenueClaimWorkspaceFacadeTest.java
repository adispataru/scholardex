package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationVenueClaim;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.repository.scopus.canonical.PublicationVenueClaimRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicationVenueClaimWorkspaceFacadeTest {

    @Mock private EffectiveAuthorshipReadService effectiveAuthorshipReadService;
    @Mock private PublicationVenueClaimService claimService;
    @Mock private PublicationVenueClaimRepository claimRepository;
    @Mock private ScholardexForumFactRepository forumFactRepository;
    @InjectMocks private PublicationVenueClaimWorkspaceFacade facade;

    private static ScholardexPublicationView pub(String id) {
        ScholardexPublicationView view = new ScholardexPublicationView();
        view.setId(id);
        return view;
    }

    private static ScholardexForumFact forum(String id, String name, String aggregationType, List<String> dblpIds) {
        ScholardexForumFact forum = new ScholardexForumFact();
        forum.setId(id);
        forum.setName(name);
        forum.setAggregationType(aggregationType);
        if (dblpIds != null) {
            forum.setDblpIds(dblpIds);
        }
        return forum;
    }

    @Test
    void aClaimOnSomeoneElsesPublicationIsRefusedBeforeItReachesTheService() {
        // The whole point of the researcher path is ownership: a claim moves every co-author's score,
        // and the ONLY thing standing between an arbitrary publication and the admin queue is this check.
        when(effectiveAuthorshipReadService.findEffectivePublicationsForUser("florin@test"))
                .thenReturn(List.of(pub("spub_mine")));

        assertThatThrownBy(() -> facade.requestClaim("florin@test", null,
                "spub_someone_elses", "sforum_x", false, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("your own publication list");
        verify(claimService, never()).requestClaim(any(), any(), anyBoolean(), any(), any(), any(), any());
    }

    @Test
    void anOwnPublicationClaimIsDelegatedWithTheRequesterStamped() {
        when(effectiveAuthorshipReadService.findEffectivePublicationsForUser("florin@test"))
                .thenReturn(List.of(pub("spub_mine")));
        PublicationVenueClaim claim = new PublicationVenueClaim();
        claim.setStatus(PublicationVenueClaim.Status.PENDING);
        claim.setClaimedForumId("sforum_eurosys");
        when(claimService.requestClaim("spub_mine", "sforum_eurosys", true, "EuroMLSys",
                "florin@test", null, "note")).thenReturn(claim);

        PublicationVenueClaim result = facade.requestClaim("florin@test", null,
                "spub_mine", "sforum_eurosys", true, "EuroMLSys", "note");

        assertThat(result.getStatus()).isEqualTo(PublicationVenueClaim.Status.PENDING);
    }

    @Test
    void forumSearchRanksConferenceStreamsFirstAndBookSeriesLast() {
        // The design caution made executable: a Book-Series forum ("Lecture Notes on …") is the very state
        // the claim flow exists to fix, so whatever the repository returns, it must sort to the bottom.
        when(forumFactRepository.findTop100ByNameContainingIgnoreCase("net")).thenReturn(List.of(
                forum("f-series", "Lecture Notes on Data Engineering and Communications Technologies",
                        "Book Series", null),
                forum("f-journal", "Journal of Networking", "Journal", null),
                forum("f-proc", "Proceedings of the Networking Conference", "Conference Proceeding", null),
                forum("f-stream", "NETWORKING", "Conference Proceeding", List.of("conf/networking"))
        ));

        List<PublicationVenueClaimWorkspaceFacade.ForumOption> options = facade.searchForums("net");

        assertThat(options).extracting(PublicationVenueClaimWorkspaceFacade.ForumOption::id)
                .containsExactly("f-stream", "f-proc", "f-journal", "f-series");
        assertThat(options.getFirst().conferenceStream()).isTrue();
    }

    @Test
    void aShortQueryReturnsNothingRatherThanScanningEverything() {
        assertThat(facade.searchForums("a")).isEmpty();
        assertThat(facade.searchForums(null)).isEmpty();
        verify(forumFactRepository, never()).findTop100ByNameContainingIgnoreCase(any());
    }
}
