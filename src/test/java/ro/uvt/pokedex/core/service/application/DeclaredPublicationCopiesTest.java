package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.activities.PublisherClaim;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeclaredPublicationCopiesTest {

    private static ScholardexPublicationView pub(String id, String title, String coverDate, String doi) {
        ScholardexPublicationView view = new ScholardexPublicationView();
        view.setId(id);
        view.setTitle(title);
        view.setCoverDate(coverDate);
        view.setDoi(doi);
        return view;
    }

    private static ActivityInstance record(String id, boolean publicationType, String date, Map<String, String> fields) {
        Activity type = new Activity();
        type.setId("type-" + id);
        type.setName("Articol declarat");
        type.setPublicationRecord(publicationType ? Boolean.TRUE : null);
        ActivityInstance instance = new ActivityInstance();
        instance.setId(id);
        instance.setActivity(type);
        instance.setDate(date);
        instance.setFields(new HashMap<>(fields));
        return instance;
    }

    private final List<ScholardexPublicationView> list = List.of(
            pub("spub_1", "Music analysis of the late sonatas", "2023-05-01", "10.1/ABC"),
            pub("spub_2", "Romanian folk modes in twentieth century composition: a survey", "2021-01-01", null),
            pub("spub_3", "Introduction", "2022-01-01", null));

    @Test
    void aDeclaredArticleTheListHoldsDoesNotCountByDoiOrByTitleWithinAYear() {
        ActivityInstance byDoi = record("a1", true, "2023-06-01", Map.of("Titlu", "Another wording", "DOI", "https://doi.org/10.1/abc"));
        ActivityInstance byTitle = record("a2", true, "2022-01-01", Map.of("Titlu", "Music Analysis of the Late Sonatas"));
        ActivityInstance byPrefix = record("a3", true, "2021-03-01", Map.of("Titlu", "Romanian folk modes in twentieth century composition"));
        ActivityInstance otherYear = record("a4", true, "2019-01-01", Map.of("Titlu", "Music analysis of the late sonatas"));
        ActivityInstance tooShort = record("a5", true, "2022-01-01", Map.of("Titlu", "Introduction"));
        ActivityInstance notAPublication = record("a6", false, "2023-01-01", Map.of("Titlu", "Music analysis of the late sonatas"));

        DeclaredPublicationCopies.Resolution r = DeclaredPublicationCopies.resolve(
                List.of(byDoi, byTitle, byPrefix, otherYear, tooShort, notAPublication), list);

        assertEquals(Set.of("a1", "a2", "a3"), r.declaredCopies());
        assertTrue(r.supersededPublications().isEmpty());
        assertSame(list, r.publications(list), "nothing taken from the list");
    }

    @Test
    void aRecordWhoseCategoryAHeadApprovedCountsInsteadOfTheListsCopy() {
        ActivityInstance volume = record("a1", true, "2023-01-01", new HashMap<>(Map.of(
                "Titlu", "Music analysis of the late sonatas", "Editura", "Editura Muzicală",
                PublisherClaim.REQUEST_FIELD, "A2 — echivalent")));
        PublisherClaim claim = new PublisherClaim();
        claim.setStatus(PublisherClaim.Status.APPROVED);
        claim.setRequested("A2 — echivalent");
        claim.setFacts(PublisherClaim.factsOf(volume));
        volume.setPublisherClaim(claim);

        DeclaredPublicationCopies.Resolution r = DeclaredPublicationCopies.resolve(List.of(volume), list);

        assertTrue(r.declaredCopies().isEmpty());
        assertEquals(Set.of("spub_1"), r.supersededPublications());
        assertEquals(List.of("spub_2", "spub_3"), r.publications(list).stream().map(ScholardexPublicationView::getId).toList());
    }

    @Test
    void aPlaceholderDoiMatchesNothing() {
        List<ScholardexPublicationView> placeholders = List.of(pub("spub_9", "Something entirely different here", "2023-01-01", "null"));
        ActivityInstance typed = record("a1", true, "2023-01-01", Map.of("Titlu", "Another title of four words", "DOI", "null"));

        assertSame(DeclaredPublicationCopies.Resolution.NONE, DeclaredPublicationCopies.resolve(List.of(typed), placeholders));
    }

    @Test
    void withoutPublicationTypesNothingIsCompared() {
        assertSame(DeclaredPublicationCopies.Resolution.NONE, DeclaredPublicationCopies.resolve(
                List.of(record("a1", false, "2023-01-01", Map.of("Titlu", "Music analysis of the late sonatas"))), list));
        assertSame(DeclaredPublicationCopies.Resolution.NONE, DeclaredPublicationCopies.resolve(List.of(), list));
    }
}
