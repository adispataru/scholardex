package ro.uvt.pokedex.core.service.importing.wos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.reporting.wos.WosIdentityConflict;
import ro.uvt.pokedex.core.model.reporting.wos.WosJournalIdentity;
import ro.uvt.pokedex.core.repository.reporting.WosIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.reporting.WosJournalIdentityRepository;
import ro.uvt.pokedex.core.service.importing.wos.model.IdentityResolutionResult;
import ro.uvt.pokedex.core.service.importing.wos.WosCanonicalContractSupport;
import ro.uvt.pokedex.core.service.importing.wos.model.WosIdentityResolutionStatus;
import ro.uvt.pokedex.core.service.importing.wos.model.WosIdentitySourceContext;
import ro.uvt.pokedex.core.model.reporting.wos.WosSourceType;
import ro.uvt.pokedex.core.service.importing.wos.model.WosParsedRecord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WosIdentityResolutionServiceTest {

    @Mock
    private WosJournalIdentityRepository journalIdentityRepository;
    @Mock
    private WosIdentityConflictRepository identityConflictRepository;

    private final Map<String, WosJournalIdentity> identitiesById = new ConcurrentHashMap<>();
    private final Map<String, WosJournalIdentity> identitiesByKey = new ConcurrentHashMap<>();
    private final List<WosIdentityConflict> conflicts = new ArrayList<>();
    private final AtomicInteger conflictSeq = new AtomicInteger(1);

    private WosIdentityResolutionService service;
    private WosTitleAuthority titleAuthority;

    @BeforeEach
    void setUp() {
        titleAuthority = new WosTitleAuthority();
        service = new WosIdentityResolutionService(journalIdentityRepository, identityConflictRepository, titleAuthority);

        lenient().when(journalIdentityRepository.findByIdentityKey(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(identitiesByKey.get(invocation.getArgument(0))));
        lenient().when(journalIdentityRepository.findCandidatesByIssnTokens(anyCollection()))
                .thenAnswer(invocation -> findByAnyIssn(invocation.getArgument(0)));
        lenient().when(journalIdentityRepository.save(any(WosJournalIdentity.class)))
                .thenAnswer(invocation -> persistIdentity(invocation.getArgument(0, WosJournalIdentity.class)));
        lenient().when(identityConflictRepository.save(any(WosIdentityConflict.class)))
                .thenAnswer(invocation -> persistConflict(invocation.getArgument(0, WosIdentityConflict.class)));
    }

    @Test
    void sameIssnDifferentTitlesMatchAndAppendAlternativeNames() {
        WosIdentitySourceContext context = new WosIdentitySourceContext(2024, "SCIE", "ev-1", "AIS_2024.xlsx", "v1", "1");

        IdentityResolutionResult first = service.resolveIdentity("1234-5678", null, "Old Journal Name", context);
        IdentityResolutionResult second = service.resolveIdentity("1234-5678", null, "New Journal Name", context);

        assertEquals(WosIdentityResolutionStatus.CREATED, first.status());
        assertEquals(WosIdentityResolutionStatus.MATCHED, second.status());
        assertEquals(first.journalId(), second.journalId());

        WosJournalIdentity identity = identitiesById.get(first.journalId());
        assertEquals("Old Journal Name", identity.getTitle());
        assertEquals(List.of("New Journal Name"), identity.getAlternativeNames());
        assertNotNull(identity.getId());
        assertNotNull(identity.getIdentityKey());
        assertEquals("12345678", identity.getPrimaryIssn());
        assertNotNull(identity.getNormalizedTitle());
        assertNotNull(identity.getCreatedAt());
        assertNotNull(identity.getUpdatedAt());
    }

    @Test
    void missingIssnAndEIssnReturnsUnresolvedAndDoesNotCreateIdentity() {
        WosIdentitySourceContext context = new WosIdentitySourceContext(2024, "SCIE", "ev-2", "AIS_2024.xlsx", "v1", "2");

        IdentityResolutionResult result = service.resolveIdentity(null, "", "Title Only", context);

        assertNull(result);
        assertEquals(0, identitiesById.size());
    }

    @Test
    void multiCandidateUsesIssnOverlapThenUpdatedAtToResolveDeterministically() {
        WosIdentitySourceContext context = new WosIdentitySourceContext(2024, "SCIE", "ev-3", "AIS_2024.xlsx", "v1", "3");

        WosJournalIdentity stronger = seedIdentity(
                "jid-stronger",
                Set.of("11112222", "33334444"),
                "Candidate A",
                Instant.parse("2026-03-07T10:00:00Z"),
                Instant.parse("2026-03-07T10:00:00Z")
        );
        seedIdentity(
                "jid-weaker",
                Set.of("11112222"),
                "Candidate B",
                Instant.parse("2026-03-07T11:00:00Z"),
                Instant.parse("2026-03-07T11:00:00Z")
        );

        IdentityResolutionResult resolved = service.resolveIdentity("1111-2222", "3333-4444", "Incoming", context);

        assertEquals(WosIdentityResolutionStatus.MATCHED, resolved.status());
        assertEquals(stronger.getId(), resolved.journalId());
        assertEquals(0, conflicts.size());
    }

    @Test
    void unresolvedTieCreatesConflictAndSeparateIdentity() {
        WosIdentitySourceContext context = new WosIdentitySourceContext(2024, "SCIE", "ev-4", "AIS_2024.xlsx", "v1", "4");

        seedIdentity(
                "jid-a",
                Set.of("99990000"),
                "Candidate A",
                Instant.parse("2026-03-07T10:00:00Z"),
                Instant.parse("2026-03-07T10:00:00Z")
        );
        seedIdentity(
                "jid-b",
                Set.of("99990000"),
                "Candidate B",
                Instant.parse("2026-03-07T10:00:00Z"),
                Instant.parse("2026-03-07T10:00:00Z")
        );

        IdentityResolutionResult conflictResult = service.resolveIdentity("9999-0000", null, "Incoming", context);

        assertEquals(WosIdentityResolutionStatus.CONFLICT, conflictResult.status());
        assertEquals(1, conflicts.size());
        WosIdentityConflict conflict = conflicts.get(0);
        assertEquals("ISSN overlap across multiple journal identities", conflict.getConflictReason());
        assertEquals("AMBIGUOUS_MATCH", conflict.getConflictType());
        assertNotNull(conflict.getConflictDetectedAt());
        assertEquals("ev-4", conflict.getSourceEventId());
        assertEquals("AIS_2024.xlsx", conflict.getSourceFile());
        assertEquals("v1", conflict.getSourceVersion());
        assertEquals("4", conflict.getSourceRowItem());
        assertEquals("Incoming", conflict.getInputTitle());
        assertNotNull(conflict.getInputNormalizedTitle());
        assertNotNull(conflict.getInputIdentityKey());
        assertTrue(conflict.getInputIssnTokens().contains("99990000"));
        assertTrue(conflict.getCandidateJournalIds().contains("jid-a"));
        assertTrue(conflict.getCandidateJournalIds().contains("jid-b"));
        assertTrue(conflict.getCandidateIdentityKeys().size() >= 2);
        assertNotEquals("jid-a", conflictResult.journalId());
        assertNotEquals("jid-b", conflictResult.journalId());

        WosJournalIdentity conflictIdentity = identitiesById.get(conflictResult.journalId());
        assertNotNull(conflictIdentity);
        assertEquals("AMBIGUOUS_MATCH", conflictIdentity.getConflictType());
        assertEquals("ISSN overlap across multiple journal identities", conflictIdentity.getConflictReason());
        assertNotNull(conflictIdentity.getConflictDetectedAt());
    }

    @Test
    void createdIdentityHasCorrectIssnFieldsWhenBothIssnAndEIssnProvided() {
        WosIdentitySourceContext context = new WosIdentitySourceContext(2024, "SCIE", "ev-issn", "AIS_2024.xlsx", "v1", "10");

        IdentityResolutionResult result = service.resolveIdentity("1111-2222", "3333-4444", "Dual ISSN Journal", context);

        assertEquals(WosIdentityResolutionStatus.CREATED, result.status());
        WosJournalIdentity identity = identitiesById.get(result.journalId());
        assertEquals("11112222", identity.getPrimaryIssn());
        assertEquals("33334444", identity.getEIssn());
        assertEquals("Dual ISSN Journal", identity.getTitle());
        assertNotNull(identity.getNormalizedTitle());
        assertNotNull(identity.getCreatedAt());
        assertNotNull(identity.getUpdatedAt());
        assertTrue(identity.getAliasIssns().isEmpty());
        assertTrue(identity.getAlternativeNames().isEmpty());
    }

    @Test
    void extraIssnTokenAddedToAliasIssnWhenPrimaryAndEIssnAlreadySet() {
        WosIdentitySourceContext context = new WosIdentitySourceContext(2024, "SCIE", "ev-alias", "AIS_2024.xlsx", "v1", "6");

        seedIdentity("jid-existing", Set.of("11112222", "33334444"), "Existing Journal",
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));

        IdentityResolutionResult result = service.resolveIdentity("1111-2222", "5555-6666", "Existing Journal", context);

        assertEquals(WosIdentityResolutionStatus.MATCHED, result.status());
        WosJournalIdentity identity = identitiesById.get(result.journalId());
        assertNotNull(identity.getPrimaryIssn());
        assertNotNull(identity.getEIssn());
        assertTrue(identity.getAliasIssns().contains("55556666"));
    }

    @Test
    void replayIsIdempotentAndDoesNotDuplicateNamesOrIdentities() {
        WosIdentitySourceContext context = new WosIdentitySourceContext(2024, "SCIE", "ev-5", "AIS_2024.xlsx", "v1", "5");

        IdentityResolutionResult created = service.resolveIdentity("1234-0000", null, "Replay Name", context);
        service.resolveIdentity("1234-0000", null, "Replay Name", context);
        service.resolveIdentity("1234-0000", null, "Replay Name", context);

        assertEquals(1, identitiesById.size());
        WosJournalIdentity identity = identitiesById.get(created.journalId());
        assertTrue(identity.getAlternativeNames().isEmpty());
    }

    @Test
    void resolveIdentityWithNullSourceContextUsesEmptyContextWithoutThrow() {
        // Line 47: null sourceContext → replaced with WosIdentitySourceContext.empty()
        IdentityResolutionResult result = service.resolveIdentity("1111-9999", null, "Some Journal", null);
        assertEquals(WosIdentityResolutionStatus.CREATED, result.status());
        assertNotNull(result.journalId());
    }

    @Test
    void createdIdentityJournalIdIsNonBlank() {
        // Line 440: buildJournalId must return a non-blank id (mutation returns "")
        WosIdentitySourceContext context = new WosIdentitySourceContext(2024, "SCIE", "ev-nblank", "f.xlsx", "v1", "1");
        IdentityResolutionResult result = service.resolveIdentity("2222-3333", null, "Non-blank Journal", context);
        assertEquals(WosIdentityResolutionStatus.CREATED, result.status());
        assertFalse(result.journalId().isBlank());
    }

    @Test
    void createdIdentityHasIdentityKeySetOnCreatedEntity() {
        // Line 416: removed call to setIdentityKey in createIdentity
        WosIdentitySourceContext context = new WosIdentitySourceContext(2024, "SCIE", "ev-idk", "f.xlsx", "v1", "1");
        IdentityResolutionResult result = service.resolveIdentity("3333-4444", null, "Identity Key Journal", context);
        assertEquals(WosIdentityResolutionStatus.CREATED, result.status());
        WosJournalIdentity identity = identitiesById.get(result.journalId());
        assertNotNull(identity.getIdentityKey());
        assertEquals(result.identityKey(), identity.getIdentityKey());
    }

    @Test
    void createIdentityWithNullTitleSetsNullTitle() {
        // Line 419: rawTitle == null conditional removed → NPE on rawTitle.trim()
        WosIdentitySourceContext context = new WosIdentitySourceContext(2024, "SCIE", "ev-nt", "f.xlsx", "v1", "1");
        IdentityResolutionResult result = service.resolveIdentity("4444-5555", null, null, context);
        assertEquals(WosIdentityResolutionStatus.CREATED, result.status());
        WosJournalIdentity identity = identitiesById.get(result.journalId());
        assertNull(identity.getTitle());
    }

    @Test
    void singleCandidateMatchSetsIdentityKeyOnCandidate() {
        // Line 172: removed call to setIdentityKey in single-candidate branch
        seedIdentity("jid-single", Set.of("5555-0000".replaceAll("-", "")),
                "Single Candidate", Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));

        WosIdentitySourceContext context = new WosIdentitySourceContext(2024, "SCIE", "ev-sc", "f.xlsx", "v1", "1");
        service.resolveIdentity("5555-0000", null, "Single Candidate", context);

        WosJournalIdentity identity = identitiesById.get("jid-single");
        assertNotNull(identity.getIdentityKey());
    }

    @Test
    void multiCandidateBestMatchSetsIdentityKeyOnWinner() {
        // Line 164: removed call to setIdentityKey in multi-candidate best-match branch
        seedIdentity("jid-winner", Set.of("66660000", "77770000"), "Winner",
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));
        seedIdentity("jid-loser", Set.of("66660000"), "Loser",
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));

        WosIdentitySourceContext context = new WosIdentitySourceContext(2024, "SCIE", "ev-mc", "f.xlsx", "v1", "1");
        IdentityResolutionResult result = service.resolveIdentity("6666-0000", "7777-0000", "Some Title", context);

        assertEquals(WosIdentityResolutionStatus.MATCHED, result.status());
        WosJournalIdentity winner = identitiesById.get("jid-winner");
        assertNotNull(winner.getIdentityKey());
    }

    @Test
    void multiCandidateEqualOverlapTiebreaksByUpdatedAt() {
        // Lines 289, 291: lambda and size==1 conditional for updatedAt tiebreak
        // jid-newer has more recent updatedAt but older createdAt → should win by updatedAt
        seedIdentity("jid-newer-upd", Set.of("88880000"), "Newer Updated",
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-03-15T00:00:00Z"));
        seedIdentity("jid-older-upd", Set.of("88880000"), "Older Updated",
                Instant.parse("2026-02-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));

        WosIdentitySourceContext context = new WosIdentitySourceContext(2024, "SCIE", "ev-upd", "f.xlsx", "v1", "1");
        IdentityResolutionResult result = service.resolveIdentity("8888-0000", null, "Incoming", context);

        assertEquals(WosIdentityResolutionStatus.MATCHED, result.status());
        assertEquals("jid-newer-upd", result.journalId());
        assertEquals(0, conflicts.size());
    }

    @Test
    void multiCandidateEqualUpdatedAtTiebreaksByCreatedAt() {
        // Lines 300, 302: lambda and size==1 conditional for createdAt tiebreak
        Instant sharedUpdatedAt = Instant.parse("2026-03-01T00:00:00Z");
        seedIdentity("jid-recent-cre", Set.of("99990001"), "Recent Created",
                Instant.parse("2026-02-15T00:00:00Z"), sharedUpdatedAt);
        seedIdentity("jid-old-cre", Set.of("99990001"), "Old Created",
                Instant.parse("2026-01-01T00:00:00Z"), sharedUpdatedAt);

        WosIdentitySourceContext context = new WosIdentitySourceContext(2024, "SCIE", "ev-cre", "f.xlsx", "v1", "1");
        IdentityResolutionResult result = service.resolveIdentity("9999-0001", null, "Incoming", context);

        assertEquals(WosIdentityResolutionStatus.MATCHED, result.status());
        assertEquals("jid-recent-cre", result.journalId());
    }

    @Test
    void maybeUpdateAliasesSetsPrimaryIssnWhenNullOnMatch() {
        // Line 195: identity.getPrimaryIssn() == null → should set primaryIssn on match
        WosJournalIdentity identity = new WosJournalIdentity();
        identity.setId("jid-null-primary");
        identity.setPrimaryIssn(null);
        identity.setEIssn(null);
        identity.setAliasIssns(new ArrayList<>(List.of("10001000")));
        identity.setTitle("Journal Null Primary");
        identity.setAlternativeNames(new ArrayList<>());
        identity.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        identity.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        persistIdentity(identity);

        WosIdentitySourceContext context = new WosIdentitySourceContext(2024, "SCIE", "ev-np", "f.xlsx", "v1", "1");
        service.resolveIdentity("1000-1000", null, "Journal Null Primary", context);

        assertNotNull(identity.getPrimaryIssn());
        assertEquals("10001000", identity.getPrimaryIssn());
    }

    @Test
    void maybeUpdateAliasesSetsEIssnWhenNullAndTwoTokensPresent() {
        // Line 199: identity.getEIssn() == null && normalizedIssns.size() > 1 → sets eIssn
        seedIdentity("jid-no-eissn", Set.of("20002000"), "Journal No EIssn",
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));

        WosIdentitySourceContext context = new WosIdentitySourceContext(2024, "SCIE", "ev-ei", "f.xlsx", "v1", "1");
        service.resolveIdentity("2000-2000", "3000-3000", "Journal No EIssn", context);

        WosJournalIdentity identity = identitiesById.get("jid-no-eissn");
        assertNotNull(identity.getEIssn());
        assertEquals("30003000", identity.getEIssn());
    }

    @Test
    void blankIncomingTitleIsIgnoredAndNotAddedAsAlternativeName() {
        // Lines 228-229: blank rawTitle trimmed to null, not added as alternative
        WosIdentitySourceContext context = new WosIdentitySourceContext(2024, "SCIE", "ev-bt", "f.xlsx", "v1", "1");
        IdentityResolutionResult created = service.resolveIdentity("4000-4000", null, "Original Title", context);
        service.resolveIdentity("4000-4000", null, "   ", context);

        WosJournalIdentity identity = identitiesById.get(created.journalId());
        assertEquals("Original Title", identity.getTitle());
        assertTrue(identity.getAlternativeNames().isEmpty());
    }

    @Test
    void blankExistingTitleGetsReplacedByIncomingTitle() {
        // Line 234: identity.getTitle() == null || identity.getTitle().isBlank() → updates title
        WosJournalIdentity identity = new WosJournalIdentity();
        identity.setId("jid-blank-title");
        identity.setPrimaryIssn("50005000");
        identity.setEIssn(null);
        identity.setAliasIssns(new ArrayList<>());
        identity.setTitle("   ");
        identity.setNormalizedTitle(null);
        identity.setAlternativeNames(new ArrayList<>());
        identity.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        identity.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        persistIdentity(identity);

        WosIdentitySourceContext context = new WosIdentitySourceContext(2024, "SCIE", "ev-blt", "f.xlsx", "v1", "1");
        service.resolveIdentity("5000-5000", null, "Real Title", context);

        assertEquals("Real Title", identity.getTitle());
        assertNotNull(identity.getNormalizedTitle());
    }

    @Test
    void unchangedMatchDoesNotModifyUpdatedAt() {
        // Lines 250-251: setUpdatedAt only called when changed=true
        WosIdentitySourceContext context = new WosIdentitySourceContext(2024, "SCIE", "ev-nc", "f.xlsx", "v1", "1");
        service.resolveIdentity("6000-6000", null, "Stable Title", context);
        WosJournalIdentity identity = identitiesById.values().stream()
                .filter(id -> "60006000".equals(id.getPrimaryIssn())).findFirst().orElseThrow();
        Instant updatedAtBefore = identity.getUpdatedAt();

        // Second call: same issn, same title, identity now matched by identityKey (fast path)
        service.resolveIdentity("6000-6000", null, "Stable Title", context);

        assertEquals(updatedAtBefore, identity.getUpdatedAt());
    }

    @Test
    void maybeUpdateAliasesUpdatesUpdatedAtWhenTitleChanges() {
        // Line 251: setUpdatedAt only when changed — verify it IS set when a new alternative title is added
        Instant pastTime = Instant.parse("2020-01-01T00:00:00Z");
        seedIdentity("jid-upd-title", Set.of("77770001"), "Original Title", pastTime, pastTime);

        WosIdentitySourceContext context = new WosIdentitySourceContext(2024, "SCIE", "ev-upd-t", "f.xlsx", "v1", "1");
        service.resolveIdentity("7777-0001", null, "Different Title", context);

        WosJournalIdentity identity = identitiesById.get("jid-upd-title");
        assertTrue(identity.getUpdatedAt().isAfter(pastTime));
    }

    @Test
    void multiCandidateBestMatchCallsMaybeUpdateAliasesOnWinner() {
        // Line 165: maybeUpdateAliases must be called on the best-match winner
        seedIdentity("jid-best-alias", Set.of("11119999", "22229999"), "Best Title",
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));
        seedIdentity("jid-worse-alias", Set.of("11119999"), "Worse Title",
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));

        WosIdentitySourceContext context = new WosIdentitySourceContext(2024, "SCIE", "ev-ma", "f.xlsx", "v1", "1");
        IdentityResolutionResult result = service.resolveIdentity("1111-9999", "2222-9999", "Incoming Title", context);

        assertEquals(WosIdentityResolutionStatus.MATCHED, result.status());
        assertEquals("jid-best-alias", result.journalId());
        WosJournalIdentity winner = identitiesById.get("jid-best-alias");
        assertTrue(winner.getAlternativeNames().contains("Incoming Title"));
    }

    private WosJournalIdentity seedIdentity(
            String id,
            Set<String> issnTokens,
            String title,
            Instant createdAt,
            Instant updatedAt
    ) {
        List<String> ordered = new ArrayList<>(issnTokens);
        String primary = ordered.isEmpty() ? null : ordered.getFirst();
        String eIssn = ordered.size() > 1 ? ordered.get(1) : null;
        List<String> aliases = ordered.size() > 2 ? ordered.subList(2, ordered.size()) : List.of();

        WosJournalIdentity identity = new WosJournalIdentity();
        identity.setId(id);
        identity.setPrimaryIssn(primary);
        identity.setEIssn(eIssn);
        identity.setAliasIssns(new ArrayList<>(aliases));
        identity.setTitle(title);
        identity.setNormalizedTitle(WosCanonicalContractSupport.normalizeTitleFingerprint(title));
        identity.setAlternativeNames(new ArrayList<>());
        identity.setCreatedAt(createdAt);
        identity.setUpdatedAt(updatedAt);
        persistIdentity(identity);
        return identity;
    }

    private List<WosJournalIdentity> findByAnyIssn(Collection<String> tokens) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String token : tokens) {
            String t = WosCanonicalContractSupport.normalizeIssnToken(token);
            if (t != null) {
                normalized.add(t);
            }
        }
        List<WosJournalIdentity> result = new ArrayList<>();
        for (WosJournalIdentity identity : identitiesById.values()) {
            Set<String> identityTokens = new LinkedHashSet<>();
            addToken(identityTokens, identity.getPrimaryIssn());
            addToken(identityTokens, identity.getEIssn());
            if (identity.getAliasIssns() != null) {
                for (String alias : identity.getAliasIssns()) {
                    addToken(identityTokens, alias);
                }
            }
            for (String token : normalized) {
                if (identityTokens.contains(token)) {
                    result.add(identity);
                    break;
                }
            }
        }
        return result;
    }

    private WosJournalIdentity persistIdentity(WosJournalIdentity identity) {
        if (identity.getAliasIssns() == null) {
            identity.setAliasIssns(new ArrayList<>());
        }
        if (identity.getAlternativeNames() == null) {
            identity.setAlternativeNames(new ArrayList<>());
        }
        identity.setAliasIssns(new ArrayList<>(new LinkedHashSet<>(identity.getAliasIssns())));
        identity.setAlternativeNames(new ArrayList<>(new LinkedHashSet<>(identity.getAlternativeNames())));
        identitiesById.put(identity.getId(), identity);
        if (identity.getIdentityKey() != null) {
            identitiesByKey.put(identity.getIdentityKey(), identity);
        }
        return identity;
    }

    private WosIdentityConflict persistConflict(WosIdentityConflict conflict) {
        if (conflict.getId() == null) {
            conflict.setId("conflict-" + conflictSeq.getAndIncrement());
        }
        conflicts.add(conflict);
        return conflict;
    }


    // --- JCR title-authority naming rules (H-WoS naming: JCR+MJL-seeded resolution) ---

    private void loadAuthority() {
        titleAuthority.load(List.of(
                jcrRecord("ACOUST AUST", "ACOUSTICS AUSTRALIA"),
                jcrRecord("NATURE", "NATURE")
        ));
    }

    private WosParsedRecord jcrRecord(String title20, String fullTitle) {
        return new WosParsedRecord(fullTitle, null, null, 2025, null, null, null, "SCIE", null,
                null, null, null, "ev-jcr", WosSourceType.JCR_REFERENCE, "JCR 2025.csv", "2025", title20, title20);
    }

    @Test
    void abbreviatedTitleCreatesIdentityWithJcrFullTitle() {
        loadAuthority();
        WosIdentitySourceContext context = new WosIdentitySourceContext(2011, "SCIE", "ev-abbr", "AIS_2011.xlsx", "v2011", "1");

        IdentityResolutionResult result = service.resolveIdentity("0814-6039", null, "ACOUST AUST", context);

        assertEquals(WosIdentityResolutionStatus.CREATED, result.status());
        WosJournalIdentity identity = identitiesById.get(result.journalId());
        assertEquals("ACOUSTICS AUSTRALIA", identity.getTitle());
        assertEquals("ACOUST AUST", identity.getAbbreviatedTitle());
        assertTrue(identity.getAlternativeNames().isEmpty());
    }

    @Test
    void abbreviationArrivingOnFullTitledIdentityIsRecordedNotAliased() {
        loadAuthority();
        WosIdentitySourceContext mjl = new WosIdentitySourceContext(2025, "SCIE", "ev-mjl", "mjl.csv", "2025", "1");
        WosIdentitySourceContext gov = new WosIdentitySourceContext(2011, "SCIE", "ev-gov", "AIS_2011.xlsx", "v2011", "2");

        IdentityResolutionResult seeded = service.resolveIdentity("0814-6039", "1839-2571", "ACOUSTICS AUSTRALIA", mjl);
        IdentityResolutionResult matched = service.resolveIdentity("0814-6039", null, "ACOUST AUST", gov);

        assertEquals(seeded.journalId(), matched.journalId());
        WosJournalIdentity identity = identitiesById.get(seeded.journalId());
        assertEquals("ACOUSTICS AUSTRALIA", identity.getTitle());
        assertEquals("ACOUST AUST", identity.getAbbreviatedTitle());
        assertTrue(identity.getAlternativeNames().isEmpty());
    }

    @Test
    void sameFingerprintMixedCaseTitleUpgradesAllCapsDisplayTitle() {
        loadAuthority();
        WosIdentitySourceContext mjl = new WosIdentitySourceContext(2025, "SCIE", "ev-mjl", "mjl.csv", "2025", "1");
        WosIdentitySourceContext ris = new WosIdentitySourceContext(2019, "SCIE", "ev-ris", "RIS_2019.xlsx", "v2019", "2");

        IdentityResolutionResult seeded = service.resolveIdentity("0814-6039", "1839-2571", "ACOUSTICS AUSTRALIA", mjl);
        service.resolveIdentity("0814-6039", null, "Acoustics Australia", ris);

        WosJournalIdentity identity = identitiesById.get(seeded.journalId());
        assertEquals("Acoustics Australia", identity.getTitle());
        assertEquals("ACOUST AUST", identity.getAbbreviatedTitle());
        assertTrue(identity.getAlternativeNames().isEmpty());
    }

    @Test
    void fullTitlePromotesOverExistingAbbreviationTitle() {
        loadAuthority();
        WosIdentitySourceContext gov = new WosIdentitySourceContext(2011, "SCIE", "ev-gov", "AIS_2011.xlsx", "v2011", "1");
        WosIdentitySourceContext ris = new WosIdentitySourceContext(2019, "SCIE", "ev-ris", "RIS_2019.xlsx", "v2019", "2");

        // legacy-shaped identity: created while the authority was empty, so the abbreviation became the title
        titleAuthority.load(List.of());
        IdentityResolutionResult created = service.resolveIdentity("0814-6039", null, "ACOUST AUST", gov);
        assertEquals("ACOUST AUST", identitiesById.get(created.journalId()).getTitle());

        loadAuthority();
        service.resolveIdentity("0814-6039", null, "Acoustics Australia", ris);

        WosJournalIdentity identity = identitiesById.get(created.journalId());
        assertEquals("Acoustics Australia", identity.getTitle());
        assertEquals("ACOUST AUST", identity.getAbbreviatedTitle());
        assertTrue(identity.getAlternativeNames().isEmpty());
    }

    @Test
    void shortTitleEqualToItsOwnTitle20IsNotTreatedAsAbbreviation() {
        loadAuthority();
        WosIdentitySourceContext context = new WosIdentitySourceContext(2019, "SCIE", "ev-nat", "RIS_2019.xlsx", "v2019", "1");

        IdentityResolutionResult result = service.resolveIdentity("0028-0836", null, "Nature", context);

        WosJournalIdentity identity = identitiesById.get(result.journalId());
        assertEquals("Nature", identity.getTitle());
        assertNull(identity.getAbbreviatedTitle());
    }

    @Test
    void sourceAbbreviationFromContextIsCaptured() {
        WosIdentitySourceContext context = new WosIdentitySourceContext(2018, "SCIE", "ev-json", "journals-SCIE-year-2018.json",
                "v2018", "1", "J SOUND VIB");

        IdentityResolutionResult result = service.resolveIdentity("0022-460X", null, "Journal of Sound and Vibration", context);

        WosJournalIdentity identity = identitiesById.get(result.journalId());
        assertEquals("Journal of Sound and Vibration", identity.getTitle());
        assertEquals("J SOUND VIB", identity.getAbbreviatedTitle());
    }

    private void addToken(Set<String> collector, String token) {
        String normalized = WosCanonicalContractSupport.normalizeIssnToken(token);
        if (normalized != null) {
            collector.add(normalized);
        }
    }
}
