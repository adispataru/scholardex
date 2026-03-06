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
    void sameIssnAcrossRowsMapsToSameJournalId() {
        WosIdentitySourceContext context = new WosIdentitySourceContext(2023, "SCIE", "ev-1", "AIS_2023.xlsx", "v1", "12");

        IdentityResolutionResult first = service.resolveIdentity("1234-5678", null, "Journal Of Testing", context);
        IdentityResolutionResult second = service.resolveIdentity("12345678", null, "Journal Of Testing", context);

        assertEquals(WosIdentityResolutionStatus.CREATED, first.status());
        assertEquals(WosIdentityResolutionStatus.MATCHED, second.status());
        assertEquals(first.journalId(), second.journalId());
        assertEquals(1, identitiesById.size());
    }

    @Test
    void missingIssnUsesDeterministicTitleFallback() {
        WosIdentitySourceContext context = new WosIdentitySourceContext(2019, "SCIENCE", "ev-2", "wos-2019.json", "v1", "item-4");

        IdentityResolutionResult first = service.resolveIdentity(null, null, "  Journal: Systems & AI ", context);
        IdentityResolutionResult second = service.resolveIdentity("", " ", "Journal Systems AI", context);

        assertEquals(first.journalId(), second.journalId());
        assertEquals(WosIdentityResolutionStatus.MATCHED, second.status());
        assertEquals(1, identitiesById.size());
    }

    @Test
    void ambiguousOverlapCreatesSeparateIdentityAndConflictLog() {
        WosIdentitySourceContext contextA = new WosIdentitySourceContext(2024, "SCIE", "ev-10", "AIS_2024.xlsx", "v3", "row-77");
        WosIdentitySourceContext contextB = new WosIdentitySourceContext(2024, "SCIE", "ev-11", "AIS_2024.xlsx", "v3", "row-78");

        IdentityResolutionResult first = service.resolveIdentity("1111-2222", "3333-4444", "Alpha Journal", contextA);
        IdentityResolutionResult second = service.resolveIdentity("1111-2222", "5555-6666", "Completely Different Journal", contextB);

        assertEquals(WosIdentityResolutionStatus.CREATED, first.status());
        assertEquals(WosIdentityResolutionStatus.CONFLICT, second.status());
        assertNotEquals(first.journalId(), second.journalId());
        assertEquals(1, conflicts.size());
        assertEquals("ev-11", conflicts.get(0).getSourceEventId());
        assertTrue(conflicts.get(0).getCandidateJournalIds().contains(first.journalId()));
    }

    @Test
    void replayIsIdempotentAndDoesNotDuplicateIdentities() {
        WosIdentitySourceContext context = new WosIdentitySourceContext(2022, "SSCI", "ev-20", "RIS_2022.xlsx", "v1", "row-5");

        service.resolveIdentity("2100-0001", "2100-0002", "Replay Journal", context);
        service.resolveIdentity("2100-0001", "2100-0002", "Replay Journal", context);
        service.resolveIdentity("2100-0001", "2100-0002", "Replay Journal", context);

        assertEquals(1, identitiesById.size());
    }

    @Test
    void aliasUpdatesAreIdempotent() {
        WosIdentitySourceContext context = new WosIdentitySourceContext(2025, "SCIE", "ev-30", "AIS_2025.xlsx", "v1", "row-6");

        IdentityResolutionResult created = service.resolveIdentity("5000-0001", "5000-0002", "Alias Journal", context);
        service.resolveIdentity("5000-0001", "5000-0003", "Alias Journal", context);
        service.resolveIdentity("5000-0001", "5000-0003", "Alias Journal", context);

        WosJournalIdentity identity = identitiesById.get(created.journalId());
        assertEquals(List.of("50000003"), identity.getAliasIssns());
    }

    @Test
    void conflictPersistenceIncludesLineage() {
        WosIdentitySourceContext contextA = new WosIdentitySourceContext(2026, "SCIE", "ev-40", "AIS_2026.xlsx", "v2", "row-8");
        WosIdentitySourceContext contextB = new WosIdentitySourceContext(2026, "SCIE", "ev-41", "AIS_2026.xlsx", "v2", "row-9");

        service.resolveIdentity("8000-0001", "8000-0002", "Lineage A", contextA);
        IdentityResolutionResult conflictResult = service.resolveIdentity("8000-0001", "8000-0003", "Lineage B", contextB);

        verify(identityConflictRepository).save(any(WosIdentityConflict.class));
        assertEquals(WosIdentityResolutionStatus.CONFLICT, conflictResult.status());
        assertEquals("row-9", conflicts.get(0).getSourceRowItem());
        assertEquals(conflicts.get(0).getId(), conflictResult.conflictId());
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
        identity.setAliasIssns(new ArrayList<>(new LinkedHashSet<>(identity.getAliasIssns())));
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
