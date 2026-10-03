package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.activities.PublisherClaim;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** H143 — a request for a publisher category follows what the record asks for. */
class PublisherClaimSupportTest {

    private static final String ASK = "Minimum 6 biblioteci în WorldCat (asimilat Listei A2)";

    private static ActivityInstance record(Map<String, String> fields) {
        ActivityInstance instance = new ActivityInstance();
        instance.setResearcherId("ion@e-uvt.ro");
        instance.setFields(new HashMap<>(fields));
        return instance;
    }

    @Test
    void askingSendsTheRequestToAHead() {
        ActivityInstance book = record(Map.of("Incadrare_solicitata", ASK, "Dovada_incadrarii", "https://worldcat.org/x"));
        assertTrue(PublisherClaimSupport.reconcile(book, "ion@e-uvt.ro"));
        assertEquals(PublisherClaim.Status.PENDING, book.getPublisherClaim().getStatus());
        assertEquals(ASK, book.getPublisherClaim().getRequested());
        assertEquals("https://worldcat.org/x", book.getPublisherClaim().getEvidence());
        assertEquals(PublisherClaim.Action.REQUESTED, book.getPublisherClaim().getHistory().getFirst().getAction());
    }

    @Test
    void savingTheSameRequestKeepsItsDecisionAndChangingItAsksAgain() {
        ActivityInstance book = record(Map.of("Incadrare_solicitata", ASK, "Dovada_incadrarii", "8 libraries"));
        PublisherClaimSupport.reconcile(book, "ion@e-uvt.ro");
        book.getPublisherClaim().setStatus(PublisherClaim.Status.APPROVED);
        book.getPublisherClaim().setDecidedBy("director@e-uvt.ro");

        assertFalse(PublisherClaimSupport.reconcile(book, "ion@e-uvt.ro"), "saved unchanged");
        assertEquals(PublisherClaim.Status.APPROVED, book.getPublisherClaim().getStatus());

        book.getFields().put("Dovada_incadrarii", "12 libraries");
        assertTrue(PublisherClaimSupport.reconcile(book, "ion@e-uvt.ro"));
        assertEquals(PublisherClaim.Status.PENDING, book.getPublisherClaim().getStatus(), "other evidence: a head looks again");
        assertNull(book.getPublisherClaim().getDecidedBy());
    }

    @Test
    void clearingTheRequestWithdrawsItAndNotAskingLeavesNoRequest() {
        ActivityInstance book = record(Map.of("Incadrare_solicitata", ASK));
        PublisherClaimSupport.reconcile(book, "ion@e-uvt.ro");
        book.getFields().remove("Incadrare_solicitata");
        assertTrue(PublisherClaimSupport.reconcile(book, "ion@e-uvt.ro"));
        assertNull(book.getPublisherClaim().getStatus());
        assertEquals(PublisherClaim.Action.WITHDRAWN, book.getPublisherClaim().getHistory().getLast().getAction());

        ActivityInstance plain = record(Map.of("Editura", "Polirom"));
        assertFalse(PublisherClaimSupport.reconcile(plain, "ion@e-uvt.ro"));
        assertNull(plain.getPublisherClaim());
    }

    // ── H145: a decision is about the facts it saw ─────────────────────────────────────────────────────

    @Test
    void anApprovalGoesBackToTheHeadWhenTheBookItWasAboutChanges() {
        ActivityInstance book = record(Map.of("Incadrare_solicitata", ASK, "Dovada_incadrarii", "8 libraries",
                "Editura", "Mirton", "Tip", "Capitol în volum colectiv", "N_autori", "3"));
        PublisherClaimSupport.reconcile(book, "ion@e-uvt.ro");
        book.getPublisherClaim().setStatus(PublisherClaim.Status.APPROVED);
        book.getPublisherClaim().setDecidedBy("director@e-uvt.ro");
        assertEquals(book.getPublisherClaim(), PublisherClaim.inForce(book));

        // the same request, the same evidence — but now a single-author book at another publisher
        book.getFields().put("Tip", "Carte");
        book.getFields().remove("N_autori");
        assertNull(PublisherClaim.inForce(book), "scoring ignores it at once");
        assertTrue(PublisherClaimSupport.reconcile(book, "ion@e-uvt.ro"));
        assertEquals(PublisherClaim.Status.PENDING, book.getPublisherClaim().getStatus());
        assertNull(book.getPublisherClaim().getDecidedBy());

        book.setReferenceFields(new java.util.EnumMap<>(Map.of(
                ro.uvt.pokedex.core.model.activities.Activity.ReferenceField.FORUM_ISSN, "1234-5679")));
        assertNull(PublisherClaim.inForce(book) == null ? null : "unchanged", "a reference is a fact too");
    }

    @Test
    void aRequestMadeBeforeH145IsGivenItsFactsAndKeepsItsDecision() {
        ActivityInstance book = record(Map.of("Incadrare_solicitata", ASK, "Dovada_incadrarii", "8 libraries", "Editura", "Mirton"));
        PublisherClaimSupport.reconcile(book, "ion@e-uvt.ro");
        book.getPublisherClaim().setStatus(PublisherClaim.Status.APPROVED);
        book.getPublisherClaim().setFacts(null);
        assertEquals(book.getPublisherClaim(), PublisherClaim.inForce(book), "no facts yet: counts");

        assertTrue(PublisherClaimSupport.reconcile(book, "ion@e-uvt.ro"), "stamped");
        assertEquals(PublisherClaim.Status.APPROVED, book.getPublisherClaim().getStatus());
        assertEquals(PublisherClaim.factsOf(book), book.getPublisherClaim().getFacts());
    }

    @Test
    void theRequestsOwnFieldsAreNamedAlikeEverywhere() {
        assertEquals(ro.uvt.pokedex.core.service.reporting.PublisherRules.FIELD_CLAIM, PublisherClaim.REQUEST_FIELD);
        assertEquals(ro.uvt.pokedex.core.service.reporting.PublisherRules.FIELD_CLAIM_EVIDENCE, PublisherClaim.EVIDENCE_FIELD);
    }
}
