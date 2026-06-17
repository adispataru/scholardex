package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusForumFact;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForumIndexTest {

    private static ScholardexForumFact forum(String id, String issn, String name) {
        ScholardexForumFact f = new ScholardexForumFact();
        f.setId(id);
        f.setIssn(issn);
        f.setName(name);
        f.setAggregationType("Journal");
        return f;
    }

    @Test
    void forumIdentityIndex_findsByIssnTokenAndByNameAndSeesIncrementalPut() {
        Map<String, ScholardexForumFact> byId = new LinkedHashMap<>();
        ScholardexForumFact a = forum("sforum_a", "0317-8471", "Journal of A");
        byId.put(a.getId(), a);
        ForumIdentityIndex index = new ForumIdentityIndex(byId);

        // by ISSN token
        assertEquals(List.of("sforum_a"),
                index.findCandidates(Set.of("0317-8471"), null).stream().map(ScholardexForumFact::getId).toList());
        // by name|agg when ISSN-less
        assertEquals(List.of("sforum_a"),
                index.findCandidates(Set.of(), "journal of a|journal").stream().map(ScholardexForumFact::getId).toList());
        // miss
        assertTrue(index.findCandidates(Set.of("2049-3630"), null).isEmpty());

        // incremental put is observed by subsequent lookups
        ScholardexForumFact b = forum("sforum_b", "2049-3630", "Journal of B");
        index.put(b);
        assertEquals(List.of("sforum_b"),
                index.findCandidates(Set.of("2049-3630"), null).stream().map(ScholardexForumFact::getId).toList());
        assertTrue(byId.containsKey("sforum_b")); // shared map mutated through the index
    }

    @Test
    void scopusForumIndex_findsByIssnThenFallsBackToNameAgg() {
        ScopusForumFact s1 = new ScopusForumFact();
        s1.setSourceId("s1");
        s1.setIssn("0317-8471");
        s1.setPublicationName("Journal of A");
        s1.setAggregationType("Journal");
        ScopusForumFact s2 = new ScopusForumFact();
        s2.setSourceId("s2");
        s2.setPublicationName("Conf X");
        s2.setAggregationType("Conference Proceeding");

        ScopusForumIndex index = new ScopusForumIndex(List.of(s1, s2));

        // ISSN match
        assertEquals(List.of("s1"),
                index.findCandidates(Set.of("0317-8471"), "journal of a", "journal")
                        .stream().map(ScopusForumFact::getSourceId).toList());
        // no ISSN match -> name|agg fallback (s2 has no ISSN)
        assertEquals(List.of("s2"),
                index.findCandidates(Set.of(), "conf x", "conference proceeding")
                        .stream().map(ScopusForumFact::getSourceId).toList());
        // ISSN-less + unknown name -> empty
        assertTrue(index.findCandidates(Set.of(), "nope", "journal").isEmpty());
    }
}
