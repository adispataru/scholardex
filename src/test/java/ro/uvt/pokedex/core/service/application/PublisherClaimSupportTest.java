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
}
