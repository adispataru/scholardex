package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.service.application.ProvisionalAuthorResolutionService.CandidateAuthor;
import ro.uvt.pokedex.core.service.application.ProvisionalAuthorResolutionService.ProvisionalAuthorMatch;
import ro.uvt.pokedex.core.service.application.ProvisionalAuthorResolutionService.Status;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** H77 slice 1: the pure name resolver, exercised with the real Matematică cases. */
class ProvisionalAuthorResolutionServiceTest {

    private static CandidateAuthor c(String id, List<String> altNames, List<String> scopus) {
        return new CandidateAuthor(id, altNames, scopus);
    }

    @Test
    void uniqueFullNameMatchResolves() {
        // "Barbu, Dorel" — one candidate.
        ProvisionalAuthorMatch m = ProvisionalAuthorResolutionService.resolve(
                "dorel.barbu@e-uvt.ro", "Dorel", "Barbu",
                List.of(c("a1", List.of("Barbu, Dorel", "Dorel Barbu"), List.of("6602503208"))));
        assertEquals(Status.RESOLVED, m.status());
        assertEquals("a1", m.canonicalAuthorId());
        assertEquals(List.of("6602503208"), m.scopusAuthorIds());
    }

    @Test
    void homonymDisambiguatesByScopusPresence() {
        // "Adara Blaga" matches two: the real one (with Scopus) and an unlinked duplicate.
        ProvisionalAuthorMatch m = ProvisionalAuthorResolutionService.resolve(
                "adara.blaga@e-uvt.ro", "Adara", "Blaga",
                List.of(c("real", List.of("Blaga, Adara M.", "Adara M. Blaga"), List.of("23974236200")),
                        c("dup", List.of("Adara Blaga"), List.of())));
        assertEquals(Status.RESOLVED, m.status());
        assertEquals("real", m.canonicalAuthorId());
    }

    @Test
    void homonymWithMultipleScopusCandidatesIsFlaggedAmbiguous() {
        ProvisionalAuthorMatch m = ProvisionalAuthorResolutionService.resolve(
                "dan.popovici@e-uvt.ro", "Dan", "Popovici",
                List.of(c("p1", List.of("Popovici, Dan"), List.of("111")),
                        c("p2", List.of("Dan Popovici"), List.of("222")),
                        c("p3", List.of("Popovici, D."), List.of()))); // no full-name match → not a contender anyway
        assertEquals(Status.AMBIGUOUS, m.status());
        assertNull(m.canonicalAuthorId());
        assertTrue(m.candidateIds().containsAll(List.of("p1", "p2")));
    }

    @Test
    void diacriticInsensitiveOnGivenName() {
        // email "razvan" (ASCII) must match the accented "Răzvan" in alternativeNames.
        ProvisionalAuthorMatch m = ProvisionalAuthorResolutionService.resolve(
                "razvan.tudoran@e-uvt.ro", "Razvan", "Tudoran",
                List.of(c("t1", List.of("Tudoran, Răzvan"), List.of("999"))));
        assertEquals(Status.RESOLVED, m.status());
        assertEquals("t1", m.canonicalAuthorId());
    }

    @Test
    void noMatchIsUnresolved() {
        ProvisionalAuthorMatch m = ProvisionalAuthorResolutionService.resolve(
                "nobody.here@e-uvt.ro", "Nobody", "Here",
                List.of(c("x", List.of("Barbu, Dorel"), List.of("1"))));
        assertEquals(Status.UNRESOLVED, m.status());
    }

    @Test
    void nameFromEmailTakesFirstAndLastToken() {
        assertArrayEquals(new String[]{"dorel", "barbu"},
                ProvisionalAuthorResolutionService.nameFromEmail("dorel.barbu@e-uvt.ro"));
        assertArrayEquals(new String[]{"ana", "pop"},
                ProvisionalAuthorResolutionService.nameFromEmail("ana.maria.pop@e-uvt.ro"));
        assertNull(ProvisionalAuthorResolutionService.nameFromEmail("singletoken@e-uvt.ro"));
        assertNull(ProvisionalAuthorResolutionService.nameFromEmail("noatsign"));
    }
}
