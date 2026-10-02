package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.activities.PublisherClaim;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.repository.reporting.IndicatorRepository;
import ro.uvt.pokedex.core.service.security.PrincipalAuthorDeclarationAccessService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** H143 — a head decides a researcher's request for a publisher category; nobody decides their own. */
class PublisherClaimReviewServiceTest {

    private final ActivityInstanceRepository repository = mock(ActivityInstanceRepository.class);
    private final PrincipalAuthorDeclarationAccessService access = mock(PrincipalAuthorDeclarationAccessService.class);
    private final PublisherClaimReviewService service = new PublisherClaimReviewService(repository, access,
            new PublisherCategoryFacade(mock(IndicatorRepository.class), repository));
    private final Authentication head = new TestingAuthenticationToken("director@e-uvt.ro", null, "SUPERVISOR");

    private ActivityInstance book(String id, PublisherClaim.Status status) {
        ActivityInstance instance = new ActivityInstance();
        instance.setId(id);
        instance.setResearcherId("ion@e-uvt.ro");
        Activity type = new Activity();
        type.setId("t1");
        type.setName("Carte coordonată (Comisia 28, I17)");
        instance.setActivity(type);
        instance.setFields(new HashMap<>(Map.of("Titlu", "O carte", "Incadrare_solicitata", "B — un criteriu din ruta complementară")));
        PublisherClaim claim = new PublisherClaim();
        claim.setStatus(status);
        claim.setRequested("B — un criteriu din ruta complementară");
        instance.setPublisherClaim(claim);
        when(repository.findById(id)).thenReturn(Optional.of(instance));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        return instance;
    }

    @Test
    void aHeadApprovesWithAnOptionalNoteAndRejectsWithAReason() {
        ActivityInstance pending = book("a1", PublisherClaim.Status.PENDING);
        when(access.canDecide("ion@e-uvt.ro", head)).thenReturn(true);

        service.approve("a1", head, "  ");
        assertEquals(PublisherClaim.Status.APPROVED, pending.getPublisherClaim().getStatus());
        assertEquals("director@e-uvt.ro", pending.getPublisherClaim().getDecidedBy());
        assertEquals(null, pending.getPublisherClaim().getDecisionNote());

        ActivityInstance other = book("a2", PublisherClaim.Status.PENDING);
        assertEquals(PublisherClaimReviewService.Refusal.NOTE_REQUIRED,
                assertThrows(PublisherClaimReviewService.ClaimRefused.class, () -> service.reject("a2", head, " ")).refusal());
        service.reject("a2", head, "No WorldCat holdings.");
        assertEquals(PublisherClaim.Status.REJECTED, other.getPublisherClaim().getStatus());
        assertEquals(PublisherClaim.Action.REJECTED, other.getPublisherClaim().getHistory().getLast().getAction());
    }

    @Test
    void onlyAPendingRequestIsDecidedAndOnlyAnApprovalRevoked() {
        book("a1", PublisherClaim.Status.APPROVED);
        when(access.canDecide(any(), any())).thenReturn(true);
        assertEquals(PublisherClaimReviewService.Refusal.NOT_PENDING,
                assertThrows(PublisherClaimReviewService.ClaimRefused.class, () -> service.approve("a1", head, null)).refusal());
        ActivityInstance revoked = service.revoke("a1", head, "The holdings were of another edition.");
        assertEquals(PublisherClaim.Status.REJECTED, revoked.getPublisherClaim().getStatus());
        assertEquals(PublisherClaimReviewService.Refusal.NOT_APPROVED,
                assertThrows(PublisherClaimReviewService.ClaimRefused.class, () -> service.revoke("a1", head, "again")).refusal());
        when(repository.findById("missing")).thenReturn(Optional.empty());
        assertEquals(PublisherClaimReviewService.Refusal.NOT_FOUND,
                assertThrows(PublisherClaimReviewService.ClaimRefused.class, () -> service.approve("missing", head, null)).refusal());
    }

    @Test
    void aHeadOfAnotherUnitDecidesNothingAndSeesNothing() {
        ActivityInstance pending = book("a1", PublisherClaim.Status.PENDING);
        when(access.canDecide("ion@e-uvt.ro", head)).thenReturn(false);
        assertEquals(PublisherClaimReviewService.Refusal.NOT_ALLOWED,
                assertThrows(PublisherClaimReviewService.ClaimRefused.class, () -> service.approve("a1", head, null)).refusal());
        verify(repository, never()).save(any());

        when(repository.findByPublisherClaim_StatusIn(anyCollection())).thenReturn(List.of(pending));
        assertEquals(List.of(), service.pendingFor(head));
        when(access.canDecide(eq("ion@e-uvt.ro"), any())).thenReturn(true);
        assertEquals("a1", service.pendingFor(head).getFirst().activityId());
        assertEquals("O carte", service.pendingFor(head).getFirst().title());
    }
}
