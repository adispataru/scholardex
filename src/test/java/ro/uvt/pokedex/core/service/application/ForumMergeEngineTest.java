package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusForumFact;
import ro.uvt.pokedex.core.repository.reporting.WosJournalIdentityRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusForumFactRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * H66B M1c white-box coverage for the forum find-or-create/merge core, lifted from
 * {@code WosScholardexOnboardingServiceTest} when the engine was extracted. The end-to-end behavior of the
 * engine (driven through {@code runWosOnboarding} / {@code runScopusForumCanonicalization}) still lives in
 * {@code WosScholardexOnboardingServiceTest}; these exercise the merge/ISSN-hygiene/id-minting branches
 * directly. ISSN/name normalization is covered by {@code ForumIdentityNormalizationTest}; candidate lookup
 * by {@code ForumIndexTest}.
 */
class ForumMergeEngineTest {

    private ForumMergeEngine engine() {
        return new ForumMergeEngine(
                mock(WosJournalIdentityRepository.class),
                mock(ScopusForumFactRepository.class),
                mock(ScholardexForumFactRepository.class),
                mock(ScholardexSourceLinkService.class),
                mock(ScholardexIdentityConflictRepository.class),
                new ForumMergeSafetyRule(),
                mock(ConflictRecorder.class)
        );
    }

    @Test
    void singleTagTargetPicksOneForumWhenSeveralMatch_andIsIdempotent() {
        // H73 slice 3: OpenAlex openAlexIds is a UNIQUE index, so a venue matching a split journal's two forum
        // records must tag AT MOST ONE — fan-out would violate uniqueness (the live DuplicateKey crash).
        ScholardexForumFact a = new ScholardexForumFact();
        a.setId("sforum_a");
        ScholardexForumFact b = new ScholardexForumFact();
        b.setId("sforum_b");

        // neither tagged yet → exactly one chosen (deterministic: lowest id)
        List<ScholardexForumFact> chosen = ForumMergeEngine.singleTagTarget(List.of(b, a), "S100");
        assertEquals(1, chosen.size());
        assertEquals("sforum_a", chosen.getFirst().getId());

        // already tagged on one match → that one returned (idempotent no-op)
        b.setOpenAlexIds(new ArrayList<>(List.of("S100")));
        List<ScholardexForumFact> existing = ForumMergeEngine.singleTagTarget(List.of(a, b), "S100");
        assertEquals(1, existing.size());
        assertEquals("sforum_b", existing.getFirst().getId());
    }

    @Test
    void mergeForumAppliesScopusPreferredIssnNameAggAndAliases() {
        ForumMergeEngine engine = engine();
        ScholardexForumFact target = new ScholardexForumFact();
        target.setAliasIssns(new ArrayList<>(List.of("1111-1119")));
        target.setIssn("1111-1119");
        target.setEIssn(null);
        target.setName("Old Name");
        target.setAggregationType("JOURNAL");

        ScopusForumFact scopusPreferred = new ScopusForumFact();
        scopusPreferred.setIssn("22223339");
        scopusPreferred.setEIssn("44445555");
        scopusPreferred.setPublicationName("Scopus Name");
        scopusPreferred.setAggregationType("BOOK");

        ReflectionTestUtils.invokeMethod(
                engine,
                "mergeForum",
                target,
                "wos-id-1",
                new LinkedHashSet<>(List.of("2222-3339", "6666-7771")),
                "Wos Name",
                "wos name",
                "JOURNAL",
                "journal",
                new ScopusForumIndex(List.of(scopusPreferred)),
                Instant.parse("2026-04-30T00:00:00Z"),
                "batch-1",
                "corr-1"
        );

        assertEquals("2222-3339", target.getIssn());
        assertEquals("4444-5555", target.getEIssn());
        assertEquals("Wos Name", target.getName()); // H66B M4: WoS title wins over the Scopus name
        assertEquals("BOOK", target.getAggregationType());
        assertTrue(target.getAliasIssns().contains("1111-1119"));
        assertTrue(target.getAliasIssns().contains("6666-7771"));
        assertEquals("WOS", target.getSource());
        assertEquals("wos-id-1", target.getSourceRecordId());
        assertEquals("batch-1", target.getSourceBatchId());
        assertEquals("corr-1", target.getSourceCorrelationId());
        assertEquals(Instant.parse("2026-04-30T00:00:00Z"), target.getUpdatedAt());
    }

    @Test
    void mergeForumCoversScopusPreferredAndAliasPruningBranches() {
        ForumMergeEngine engine = engine();
        ScholardexForumFact target = new ScholardexForumFact();
        target.setAliasIssns(List.of("9999-9994"));
        target.setWosForumIds(List.of("wos-old"));
        target.setScopusForumIds(List.of("scopus-old"));

        ScopusForumFact preferred = new ScopusForumFact();
        preferred.setSourceId("scopus-new");
        preferred.setIssn("1234-5679");
        preferred.setEIssn("8765-4326");
        preferred.setPublicationName("Scopus Preferred Journal");
        preferred.setAggregationType("JOURNAL");

        LinkedHashSet<String> normalizedIssns = new LinkedHashSet<>(List.of("1234-5679", "8765-4326", "2222-2227"));
        ReflectionTestUtils.invokeMethod(
                engine,
                "mergeForum",
                target,
                "wos-new",
                normalizedIssns,
                "WOS Journal Name",
                "wos journal name",
                "JOURNAL",
                "journal",
                new ScopusForumIndex(List.of(preferred)),
                Instant.parse("2025-01-01T00:00:00Z"),
                "batch-m",
                "corr-m"
        );

        // H66B M8: the WoS merge no longer claims the scopus-enrichment's source id (that caused the WoS-last
        // EXTERNAL_ID churn); scopusForumIds stays what Scopus canon set, here the pre-existing "scopus-old".
        assertEquals(List.of("scopus-old"), target.getScopusForumIds());
        assertTrue(target.getWosForumIds().contains("wos-new"));
        assertEquals("1234-5679", target.getIssn());
        assertEquals("8765-4326", target.getEIssn());
        assertEquals("WOS Journal Name", target.getName()); // H66B M4: WoS title wins (mixed-case, left as-is)
        assertEquals("journal", target.getAggregationTypeNormalized());
        assertTrue(target.getAliasIssns().contains("2222-2227"));
        assertTrue(!target.getAliasIssns().contains("1234-5679"));
        assertTrue(!target.getAliasIssns().contains("8765-4326"));
        assertTrue(target.getCreatedAt() != null);
        assertTrue(target.getUpdatedAt() != null);
    }

    @Test
    void mergeForumTitleCasesAllCapsWosName() {
        ForumMergeEngine engine = engine();
        ScholardexForumFact target = new ScholardexForumFact();
        ReflectionTestUtils.invokeMethod(
                engine, "mergeForum", target, "wos-tc",
                new LinkedHashSet<>(List.of("1234-5679")),
                "NOISE CONTROL ENGINEERING JOURNAL", "noise control engineering journal",
                "JOURNAL", "journal",
                new ScopusForumIndex(List.of()), // no Scopus match
                Instant.parse("2026-06-17T00:00:00Z"), "b", "c");
        assertEquals("Noise Control Engineering Journal", target.getName());
    }

    @Test
    void mergeForumFallsBackToScopusNameWhenWosTitleBlank() {
        // ingest passes name=sourceRecordId when the WoS title is blank; that id-fallback must not become
        // the display name — fall through to the Scopus name.
        ForumMergeEngine engine = engine();
        ScholardexForumFact target = new ScholardexForumFact();
        ScopusForumFact scopus = new ScopusForumFact();
        scopus.setSourceId("s-1");
        scopus.setIssn("1234-5679");
        scopus.setPublicationName("Proper Scopus Title");
        scopus.setAggregationType("JOURNAL");
        ReflectionTestUtils.invokeMethod(
                engine, "mergeForum", target, "wos-blank",
                new LinkedHashSet<>(List.of("1234-5679")),
                "wos-blank", "wos-blank", // wosName == wosForumId → title was blank
                "JOURNAL", "journal",
                new ScopusForumIndex(List.of(scopus)),
                Instant.parse("2026-06-17T00:00:00Z"), "b", "c");
        assertEquals("Proper Scopus Title", target.getName());
    }

    @Test
    void normalizedIssnSetNormalizesAndDropsInvalidTokens() {
        ForumMergeEngine engine = engine();
        LinkedHashSet<String> normalized = ReflectionTestUtils.invokeMethod(
                engine,
                "normalizedIssnSet",
                Set.of(),
                "1234-5679",
                "8765-4326",
                List.of("12345679", "bad"),
                null,
                null,
                List.of("87654326")
        );
        assertTrue(normalized.contains("1234-5679"));
        assertTrue(normalized.contains("8765-4326"));
    }

    @Test
    void normalizedIssnSetDropsSecondaryTokenThatIsAnotherJournalsPrimaryIssn() {
        // H57 Layer 2: an eISSN that equals a different journal's primary print ISSN is dropped.
        ForumMergeEngine engine = engine();
        LinkedHashSet<String> normalized = ReflectionTestUtils.invokeMethod(
                engine,
                "normalizedIssnSet",
                Set.of("0022-474X"),
                "0960-0760",
                "0022-474X",
                List.of(),
                null,
                null,
                List.of()
        );
        assertTrue(normalized.contains("0960-0760"));
        assertTrue(!normalized.contains("0022-474X"));
    }

    @Test
    void buildCanonicalForumIdDiffersBetweenIssnAndNameAggMaterial() {
        ForumMergeEngine engine = engine();
        String byIssnId = ReflectionTestUtils.invokeMethod(engine, "buildCanonicalForumId", "1234-5679", null, List.of(), "name", "journal");
        String byNameId = ReflectionTestUtils.invokeMethod(engine, "buildCanonicalForumId", null, null, List.of(), "name", "journal");
        assertTrue(byIssnId.startsWith("sforum_"));
        assertTrue(byNameId.startsWith("sforum_"));
        assertTrue(!byIssnId.equals(byNameId));
    }
}
