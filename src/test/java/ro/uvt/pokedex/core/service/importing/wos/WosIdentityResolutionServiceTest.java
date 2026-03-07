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
import ro.uvt.pokedex.core.service.importing.wos.model.WosIdentityResolutionStatus;
import ro.uvt.pokedex.core.service.importing.wos.model.WosIdentitySourceContext;

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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    @BeforeEach
    void setUp() {
        service = new WosIdentityResolutionService(journalIdentityRepository, identityConflictRepository);

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
        assertEquals("ISSN overlap across multiple journal identities", conflicts.get(0).getConflictReason());
        assertTrue(conflicts.get(0).getCandidateJournalIds().contains("jid-a"));
        assertTrue(conflicts.get(0).getCandidateJournalIds().contains("jid-b"));
        assertNotEquals("jid-a", conflictResult.journalId());
        assertNotEquals("jid-b", conflictResult.journalId());
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

    private void addToken(Set<String> collector, String token) {
        String normalized = WosCanonicalContractSupport.normalizeIssnToken(token);
        if (normalized != null) {
            collector.add(normalized);
        }
    }
}
