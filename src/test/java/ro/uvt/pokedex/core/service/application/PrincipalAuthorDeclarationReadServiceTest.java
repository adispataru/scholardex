package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.scopus.canonical.PrincipalAuthorDeclaration;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.repository.scopus.canonical.PrincipalAuthorDeclarationRepository;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class PrincipalAuthorDeclarationReadServiceTest {

    private final PrincipalAuthorDeclarationReadService service =
            new PrincipalAuthorDeclarationReadService(mock(PrincipalAuthorDeclarationRepository.class));

    private static ScholardexPublicationView publication(String id, String doi, String title, String coverDate) {
        ScholardexPublicationView publication = new ScholardexPublicationView();
        publication.setId(id);
        publication.setDoi(doi);
        publication.setDoiNormalized(doi);
        publication.setTitle(title);
        publication.setCoverDate(coverDate);
        publication.setAuthors(List.of("first", "me", "third"));
        return publication;
    }

    private static PrincipalAuthorDeclaration declaration(String publicationId, String doi, String titleNormalized, Integer year) {
        PrincipalAuthorDeclaration declaration = new PrincipalAuthorDeclaration();
        declaration.setPublicationId(publicationId);
        declaration.setDoiNormalized(doi);
        declaration.setTitleNormalized(titleNormalized);
        declaration.setYear(year);
        declaration.setStatus(PrincipalAuthorDeclaration.Status.APPROVED);
        return declaration;
    }

    @Test
    void aDeclarationFindsItsPublicationByIdThenByDoiThenByTitleAndYear() {
        ScholardexPublicationView paper = publication("spub_new", "10.1000/abc", "Émotions at Work: A Study", "2023-05-01");

        assertTrue(PrincipalAuthorDeclarationReadService.isAbout(declaration("spub_new", null, null, null), paper));
        // After a rebuild gave the publication another id:
        assertTrue(PrincipalAuthorDeclarationReadService.isAbout(
                declaration("spub_old", "10.1000/abc", "whatever", 1999), paper));
        ScholardexPublicationView withoutDoi = publication("spub_new", null, "Émotions at Work: A Study", "2023-05-01");
        assertTrue(PrincipalAuthorDeclarationReadService.isAbout(
                declaration("spub_old", null, "emotions at work a study", 2023), withoutDoi));
    }

    @Test
    void aDeclarationNeverMovesToAnotherPublication() {
        ScholardexPublicationView paper = publication("spub_new", "10.1000/abc", "Emotions at Work", "2023-05-01");

        assertFalse(PrincipalAuthorDeclarationReadService.isAbout(
                declaration("spub_old", "10.1000/other", "emotions at work", 2023), paper), "another DOI");
        ScholardexPublicationView withoutDoi = publication("spub_new", null, "Emotions at Work", "2023-05-01");
        assertFalse(PrincipalAuthorDeclarationReadService.isAbout(
                declaration("spub_old", null, "emotions at work", 2021), withoutDoi), "same title, another year");
        assertFalse(PrincipalAuthorDeclarationReadService.isAbout(
                declaration("spub_old", null, null, null), withoutDoi), "nothing to go by");
        assertFalse(PrincipalAuthorDeclarationReadService.isAbout(null, paper));
    }

    @Test
    void theDeclaredPublicationNamesTheResearcherAmongItsCorrespondingAuthors() {
        ScholardexPublicationView declared = publication("p1", "10.1000/abc", "Paper", "2023-01-01");
        declared.setCorrespondingAuthorIds(new ArrayList<>(List.of("first")));
        ScholardexPublicationView other = publication("p2", "10.1000/def", "Other", "2022-01-01");

        List<ScholardexPublicationView> result = service.applyApproved(
                List.of(declaration("p1", null, null, null)), List.of(declared, other), List.of("me-old-id", "me"));

        assertEquals(List.of("first", "me"), result.get(0).getCorrespondingAuthorIds(),
                "the id the publication itself knows the researcher by");
        assertNotSame(declared, result.get(0));
        assertEquals(List.of("first"), declared.getCorrespondingAuthorIds(), "the original is left as it was");
        assertSame(other, result.get(1));
    }

    @Test
    void theListItselfComesBackWhenNothingApplies() {
        List<ScholardexPublicationView> publications = List.of(publication("p1", "10.1000/abc", "Paper", "2023-01-01"));

        assertSame(publications, service.applyApproved(List.of(), publications, List.of("me")));
        assertSame(publications, service.applyApproved(
                List.of(declaration("p9", "10.1000/zzz", null, null)), publications, List.of("me")));
        assertSame(publications, service.applyApproved(
                List.of(declaration("p1", null, null, null)), publications, List.of()));
    }

    /**
     * The copy handed to the scoring engine must carry EVERYTHING the original does — the number of authors
     * the formulas divide by, the citations, the venue. Every field of the view is filled here by reflection,
     * so a field added to the view later is covered without anyone remembering this test.
     */
    @Test
    void theCopyDiffersFromTheOriginalOnlyInItsCorrespondingAuthors() throws Exception {
        ScholardexPublicationView original = new ScholardexPublicationView();
        int n = 1;
        for (Field field : ScholardexPublicationView.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            Class<?> type = field.getType();
            n++;
            if (type == String.class) field.set(original, field.getName() + "-" + n);
            else if (type == int.class) field.setInt(original, 100 + n);
            else if (type == Integer.class) field.set(original, 200 + n);
            else if (type == boolean.class) field.setBoolean(original, true);
            else if (type == Instant.class) field.set(original, Instant.parse("2026-01-01T00:00:00Z").plusSeconds(n));
            else if (type == List.class) field.set(original, new ArrayList<>(List.of(field.getName() + "-a", "me")));
            else if (type == Set.class) field.set(original, new LinkedHashSet<>(List.of(field.getName() + "-a")));
            else throw new AssertionError("fill a value for " + field.getName() + " of type " + type);
        }
        original.setId("p1");

        ScholardexPublicationView copy = service.applyApproved(
                List.of(declaration("p1", null, null, null)), List.of(original), List.of("me")).getFirst();

        assertNotSame(original, copy);
        assertTrue(copy.getCorrespondingAuthorIds().contains("me"));
        copy.setCorrespondingAuthorIds(original.getCorrespondingAuthorIds());
        assertEquals(original, copy);
        assertEquals(original.getAuthorCount(), copy.getAuthorCount());
    }
}
