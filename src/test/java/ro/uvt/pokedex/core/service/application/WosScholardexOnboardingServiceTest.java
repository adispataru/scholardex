package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.test.util.ReflectionTestUtils;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusForumFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusForumFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.sql.Array;
import java.sql.ResultSet;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class WosScholardexOnboardingServiceTest {

    @Mock private NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    @Mock private ScopusForumFactRepository scopusForumFactRepository;
    @Mock private ScholardexForumFactRepository scholardexForumFactRepository;
    @Mock private ScholardexSourceLinkService sourceLinkService;
    @Mock private ScholardexIdentityConflictRepository scholardexIdentityConflictRepository;
    @Mock private ScholardexPublicationFactRepository scholardexPublicationFactRepository;

    private WosScholardexOnboardingService service() {
        return new WosScholardexOnboardingService(
                namedParameterJdbcTemplate,
                scopusForumFactRepository,
                scholardexForumFactRepository,
                sourceLinkService,
                scholardexIdentityConflictRepository,
                scholardexPublicationFactRepository
        );
    }

    @Test
    void runWosOnboardingCreatesCanonicalForumForWosOnlyJournal() {
        WosScholardexOnboardingService service = service();

        WosRankingView rankingView = new WosRankingView();
        rankingView.setId("wos-j-1");
        rankingView.setName("Journal of Testing");
        rankingView.setIssn("1234567X");

        when(namedParameterJdbcTemplate.query(any(String.class), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(rankingView));
        when(scopusForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexPublicationFactRepository.findAll()).thenReturn(List.of());
        when(sourceLinkService.findByKey(
                ScholardexEntityType.FORUM, "WOS", "wos-j-1")).thenReturn(Optional.empty());
        when(scholardexForumFactRepository.save(any(ScholardexForumFact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ImportProcessingResult result = service.runWosOnboarding("batch-1", "corr-1");

        assertEquals(1, result.getImportedCount());
        ArgumentCaptor<ScholardexForumFact> forumCaptor = ArgumentCaptor.forClass(ScholardexForumFact.class);
        verify(scholardexForumFactRepository).save(forumCaptor.capture());
        ScholardexForumFact savedForum = forumCaptor.getValue();
        assertTrue(savedForum.getId().startsWith("sforum_"));
        assertEquals(List.of("wos-j-1"), savedForum.getWosForumIds());
        assertEquals("1234-567X", savedForum.getIssn());
    }

    @Test
    void runWosOnboardingQuarantinesPublicationSourceLinkCollision() {
        WosScholardexOnboardingService service = service();

        ScholardexPublicationFact publication = new ScholardexPublicationFact();
        publication.setId("spub_1");
        publication.setWosId("WOS:123");

        ScholardexSourceLink existing = new ScholardexSourceLink();
        existing.setCanonicalEntityId("spub_other");

        when(namedParameterJdbcTemplate.query(any(String.class), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        when(scopusForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexPublicationFactRepository.findAll()).thenReturn(List.of(publication));
        when(sourceLinkService.findByEntityTypeAndSourceRecordId(
                ScholardexEntityType.PUBLICATION,
                "WOS:123"
        )).thenReturn(List.of(existing));
        when(scholardexIdentityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                eq(ScholardexEntityType.PUBLICATION), eq("WOS"), eq("WOS:123"), eq("SOURCE_ID_COLLISION"), eq("OPEN")
        )).thenReturn(Optional.empty());

        service.runWosOnboarding("batch-1", "corr-1");

        ArgumentCaptor<ScholardexIdentityConflict> conflictCaptor = ArgumentCaptor.forClass(ScholardexIdentityConflict.class);
        verify(scholardexIdentityConflictRepository).save(conflictCaptor.capture());
        assertEquals("SOURCE_ID_COLLISION", conflictCaptor.getValue().getReasonCode());
        assertEquals(ScholardexEntityType.PUBLICATION, conflictCaptor.getValue().getEntityType());
    }

    @Test
    void runWosOnboardingSkipsJournalWhenIdMissing() {
        WosScholardexOnboardingService service = service();

        WosRankingView rankingView = new WosRankingView();
        rankingView.setId("   ");
        rankingView.setName("No Id Journal");
        rankingView.setIssn("1234-5678");

        when(namedParameterJdbcTemplate.query(any(String.class), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(rankingView));
        when(scopusForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexPublicationFactRepository.findAll()).thenReturn(List.of());

        ImportProcessingResult result = service.runWosOnboarding("batch-1", "corr-1");

        assertEquals(1, result.getProcessedCount());
        assertEquals(1, result.getSkippedCount());
        verify(scholardexForumFactRepository, never()).save(any());
        verify(sourceLinkService, never()).link(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void runWosOnboardingMarksConflictForAmbiguousCanonicalForumCandidatesByIssn() {
        WosScholardexOnboardingService service = service();

        WosRankingView rankingView = new WosRankingView();
        rankingView.setId("wos-j-amb");
        rankingView.setName("Journal X");
        rankingView.setIssn("12345678");

        ScholardexForumFact f1 = new ScholardexForumFact();
        f1.setId("cf1");
        f1.setIssn("1234-5678");
        ScholardexForumFact f2 = new ScholardexForumFact();
        f2.setId("cf2");
        f2.setEIssn("1234-5678");

        when(namedParameterJdbcTemplate.query(any(String.class), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(rankingView));
        when(scopusForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of(f1, f2));
        when(scholardexPublicationFactRepository.findAll()).thenReturn(List.of());
        when(sourceLinkService.findByKey(ScholardexEntityType.FORUM, "WOS", "wos-j-amb")).thenReturn(Optional.empty());
        when(scholardexIdentityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                eq(ScholardexEntityType.FORUM), eq("WOS"), eq("wos-j-amb"), eq("AMBIGUOUS_ISSN_MATCH"), eq("OPEN")
        )).thenReturn(Optional.empty());

        ImportProcessingResult result = service.runWosOnboarding("batch-1", "corr-1");

        assertEquals(1, result.getSkippedCount());
        verify(sourceLinkService).markConflict(
                eq(ScholardexEntityType.FORUM), eq("WOS"), eq("wos-j-amb"), eq("AMBIGUOUS_ISSN_MATCH"),
                isNull(), eq("batch-1"), eq("corr-1"), eq(false)
        );
        verify(scholardexIdentityConflictRepository).save(argThat(conflict ->
                conflict.getEntityType() == ScholardexEntityType.FORUM
                        && "AMBIGUOUS_ISSN_MATCH".equals(conflict.getReasonCode())
                        && conflict.getCandidateCanonicalIds().containsAll(List.of("cf1", "cf2"))
        ));
    }

    @Test
    void runWosOnboardingUsesExistingForumSourceLinkAndMarksUpdated() {
        WosScholardexOnboardingService service = service();

        WosRankingView rankingView = new WosRankingView();
        rankingView.setId("wos-j-2");
        rankingView.setName("Journal Existing");
        rankingView.setIssn("12345678");

        ScholardexForumFact existingForum = new ScholardexForumFact();
        existingForum.setId("cf-existing");
        existingForum.setName("Existing");
        existingForum.setAggregationType("JOURNAL");

        ScholardexSourceLink existingLink = new ScholardexSourceLink();
        existingLink.setCanonicalEntityId("cf-existing");

        when(namedParameterJdbcTemplate.query(any(String.class), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(rankingView));
        when(scopusForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of(existingForum));
        when(scholardexPublicationFactRepository.findAll()).thenReturn(List.of());
        when(sourceLinkService.findByKey(ScholardexEntityType.FORUM, "WOS", "wos-j-2")).thenReturn(Optional.of(existingLink));
        when(scholardexForumFactRepository.save(any(ScholardexForumFact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ImportProcessingResult result = service.runWosOnboarding("batch-2", "corr-2");

        assertEquals(1, result.getUpdatedCount());
        verify(scholardexForumFactRepository).save(argThat(f -> "cf-existing".equals(f.getId())));
        verify(sourceLinkService).link(
                eq(ScholardexEntityType.FORUM), eq("WOS"), eq("wos-j-2"), eq("cf-existing"),
                eq("wos-forum-onboarding"), isNull(), eq("batch-2"), eq("corr-2"), eq(false)
        );
    }

    @Test
    void runWosOnboardingOpensInvalidIssnConflictButStillImportsForum() {
        WosScholardexOnboardingService service = service();

        WosRankingView rankingView = new WosRankingView();
        rankingView.setId("wos-j-badissn");
        rankingView.setName("Bad ISSN Journal");
        rankingView.setIssn("??");

        when(namedParameterJdbcTemplate.query(any(String.class), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(rankingView));
        when(scopusForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexPublicationFactRepository.findAll()).thenReturn(List.of());
        when(sourceLinkService.findByKey(ScholardexEntityType.FORUM, "WOS", "wos-j-badissn")).thenReturn(Optional.empty());
        when(scholardexIdentityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                eq(ScholardexEntityType.FORUM), eq("WOS"), eq("wos-j-badissn"), eq("NORMALIZATION_INVALID_ISSN"), eq("OPEN")
        )).thenReturn(Optional.empty());
        when(scholardexForumFactRepository.save(any(ScholardexForumFact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ImportProcessingResult result = service.runWosOnboarding("batch-3", "corr-3");

        assertEquals(1, result.getImportedCount());
        verify(scholardexIdentityConflictRepository).save(argThat(conflict ->
                conflict.getEntityType() == ScholardexEntityType.FORUM
                        && "NORMALIZATION_INVALID_ISSN".equals(conflict.getReasonCode())
        ));
    }

    @Test
    void runWosOnboardingSkipsNonWosPublicationIdsAndLinksOnlyValidWosIds() {
        WosScholardexOnboardingService service = service();

        ScholardexPublicationFact nonWos = new ScholardexPublicationFact();
        nonWos.setId("spub-non");
        nonWos.setWosId("NON_WOS");
        ScholardexPublicationFact valid = new ScholardexPublicationFact();
        valid.setId("spub-ok");
        valid.setWosId("WOS:OK");

        when(namedParameterJdbcTemplate.query(any(String.class), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        when(scopusForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexPublicationFactRepository.findAll()).thenReturn(List.of(nonWos, valid));
        when(sourceLinkService.findByEntityTypeAndSourceRecordId(eq(ScholardexEntityType.PUBLICATION), anyString()))
                .thenReturn(List.of());

        service.runWosOnboarding("batch-4", "corr-4");
        verify(sourceLinkService, times(1)).link(
                eq(ScholardexEntityType.PUBLICATION), eq("WOS"), eq("WOS:OK"), eq("spub-ok"),
                eq("wos-publication-link"), isNull(), eq("batch-4"), eq("corr-4"), eq(false)
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void privateHelpersCoverNormalizationAndCandidateBranches() throws Exception {
        WosScholardexOnboardingService service = service();

        assertEquals("1234-567X", ReflectionTestUtils.invokeMethod(service, "normalizeIssn", "1234 567x"));
        assertNull(ReflectionTestUtils.invokeMethod(service, "normalizeIssn", "12"));
        assertEquals("journal de test", ReflectionTestUtils.invokeMethod(service, "normalizeName", "Journál, de Test!"));
        assertEquals("", ReflectionTestUtils.invokeMethod(service, "normalizeToken", "   "));
        assertNull(ReflectionTestUtils.invokeMethod(service, "normalizeBlank", "   "));
        assertEquals("a", ReflectionTestUtils.invokeMethod(service, "firstNonBlank", new Object[]{new String[]{" ", "a", "b"}}));
        assertTrue((boolean) ReflectionTestUtils.invokeMethod(service, "hasAnyNonBlank", new Object[]{new String[]{" ", "x"}}));
        assertEquals("a,b", ReflectionTestUtils.invokeMethod(service, "join", List.of("a", "b")));
        assertEquals(List.of(), ReflectionTestUtils.invokeMethod(service, "safeList", (Object) null));

        LinkedHashSet<String> normalized = ReflectionTestUtils.invokeMethod(
                service,
                "normalizedIssnSet",
                "1234-5678",
                "8765-4321",
                List.of("12345678", "bad"),
                null,
                null,
                List.of("87654321")
        );
        assertTrue(normalized.contains("1234-5678"));
        assertTrue(normalized.contains("8765-4321"));

        String byIssnId = ReflectionTestUtils.invokeMethod(service, "buildCanonicalForumId", "1234-5678", null, List.of(), "name", "journal");
        String byNameId = ReflectionTestUtils.invokeMethod(service, "buildCanonicalForumId", null, null, List.of(), "name", "journal");
        assertTrue(byIssnId.startsWith("sforum_"));
        assertTrue(byNameId.startsWith("sforum_"));
        assertTrue(!byIssnId.equals(byNameId));

        ScopusForumFact scopus = new ScopusForumFact();
        scopus.setSourceId("s1");
        scopus.setIssn("1234-5678");
        scopus.setPublicationName("Journal de Test");
        scopus.setAggregationType("JOURNAL");
        List<ScopusForumFact> byIssn = ReflectionTestUtils.invokeMethod(
                service, "findScopusCandidates", List.of(scopus), List.of("1234-5678"), "journal de test", "journal"
        );
        assertEquals(1, byIssn.size());
        List<ScopusForumFact> byName = ReflectionTestUtils.invokeMethod(
                service, "findScopusCandidates", List.of(scopus), List.of(), "journal de test", "journal"
        );
        assertEquals(1, byName.size());

        Array sqlArray = org.mockito.Mockito.mock(Array.class);
        when(sqlArray.getArray()).thenReturn(new Object[]{"a", "b"});
        List<String> parsed = ReflectionTestUtils.invokeMethod(service, "toStringList", sqlArray);
        assertEquals(List.of(), parsed);
        assertEquals(List.of(), ReflectionTestUtils.invokeMethod(service, "toStringList", (Object) null));
    }

    @Test
    void toStringListReturnsValuesWhenSqlArrayContainsStringArray() throws Exception {
        WosScholardexOnboardingService service = service();
        Array sqlArray = mock(Array.class);
        when(sqlArray.getArray()).thenReturn(new String[]{"x", "y"});

        @SuppressWarnings("unchecked")
        List<String> parsed = ReflectionTestUtils.invokeMethod(service, "toStringList", sqlArray);

        assertEquals(List.of("x", "y"), parsed);
    }

    @Test
    void runWosOnboardingPublicationCollisionReusesExistingOpenConflict() {
        WosScholardexOnboardingService service = service();

        ScholardexPublicationFact publication = new ScholardexPublicationFact();
        publication.setId("spub_1");
        publication.setWosId("WOS:dup");

        ScholardexSourceLink existing = new ScholardexSourceLink();
        existing.setCanonicalEntityId("spub_other");

        ScholardexIdentityConflict existingConflict = new ScholardexIdentityConflict();
        existingConflict.setId("conf_existing");
        existingConflict.setDetectedAt(java.time.Instant.parse("2024-01-01T00:00:00Z"));

        when(namedParameterJdbcTemplate.query(any(String.class), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        when(scopusForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexPublicationFactRepository.findAll()).thenReturn(List.of(publication));
        when(sourceLinkService.findByEntityTypeAndSourceRecordId(ScholardexEntityType.PUBLICATION, "WOS:dup"))
                .thenReturn(List.of(existing));
        when(scholardexIdentityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                eq(ScholardexEntityType.PUBLICATION), eq("WOS"), eq("WOS:dup"), eq("SOURCE_ID_COLLISION"), eq("OPEN")
        )).thenReturn(Optional.of(existingConflict));

        service.runWosOnboarding("batch-z", "corr-z");

        verify(scholardexIdentityConflictRepository).save(argThat(conflict ->
                "conf_existing".equals(conflict.getId())
                        && conflict.getDetectedAt() != null
                        && "batch-z".equals(conflict.getSourceBatchId())
                        && "corr-z".equals(conflict.getSourceCorrelationId())
        ));
    }

    @Test
    @SuppressWarnings("unchecked")
    void runWosOnboardingRowMapperParsesAlternativeIssnAndNameArrays() throws Exception {
        WosScholardexOnboardingService service = service();

        ResultSet rs = mock(ResultSet.class);
        Array issnArray = mock(Array.class);
        Array namesArray = mock(Array.class);
        when(issnArray.getArray()).thenReturn(new String[]{"11112222", "3333-4444"});
        when(namesArray.getArray()).thenReturn(new String[]{"Alt Name A", "Alt Name B"});
        when(rs.getString("journal_id")).thenReturn("wos-map-1");
        when(rs.getString("name")).thenReturn("Mapped Journal");
        when(rs.getString("issn")).thenReturn("1234-5678");
        when(rs.getString("e_issn")).thenReturn("8765-4321");
        when(rs.getArray("alternative_issns")).thenReturn(issnArray);
        when(rs.getArray("alternative_names")).thenReturn(namesArray);

        when(namedParameterJdbcTemplate.query(any(String.class), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<WosRankingView> mapper = invocation.getArgument(2);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(scopusForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexPublicationFactRepository.findAll()).thenReturn(List.of());
        when(sourceLinkService.findByKey(ScholardexEntityType.FORUM, "WOS", "wos-map-1")).thenReturn(Optional.empty());
        when(scholardexForumFactRepository.save(any(ScholardexForumFact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.runWosOnboarding("batch-map", "corr-map");

        verify(scholardexForumFactRepository).save(argThat(f ->
                f.getWosForumIds() != null
                        && f.getWosForumIds().contains("wos-map-1")
                        && f.getAliasIssns() != null
                        && f.getAliasIssns().contains("3333-4444")
        ));
    }

    @Test
    void runWosOnboardingPublicationLinksProcessedInSortedIdOrder() {
        WosScholardexOnboardingService service = service();

        ScholardexPublicationFact p2 = new ScholardexPublicationFact();
        p2.setId("spub-b");
        p2.setWosId("WOS:B");
        ScholardexPublicationFact p1 = new ScholardexPublicationFact();
        p1.setId("spub-a");
        p1.setWosId("WOS:A");

        when(namedParameterJdbcTemplate.query(any(String.class), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        when(scopusForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexPublicationFactRepository.findAll()).thenReturn(List.of(p2, p1));
        when(sourceLinkService.findByEntityTypeAndSourceRecordId(eq(ScholardexEntityType.PUBLICATION), anyString()))
                .thenReturn(List.of());

        service.runWosOnboarding("batch-order", "corr-order");

        var order = inOrder(sourceLinkService);
        order.verify(sourceLinkService).link(
                eq(ScholardexEntityType.PUBLICATION), eq("WOS"), eq("WOS:A"), eq("spub-a"),
                eq("wos-publication-link"), isNull(), eq("batch-order"), eq("corr-order"), eq(false)
        );
        order.verify(sourceLinkService).link(
                eq(ScholardexEntityType.PUBLICATION), eq("WOS"), eq("WOS:B"), eq("spub-b"),
                eq("wos-publication-link"), isNull(), eq("batch-order"), eq("corr-order"), eq(false)
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void mergeForumCoversScopusPreferredAndAliasPruningBranches() {
        WosScholardexOnboardingService service = service();
        ScholardexForumFact target = new ScholardexForumFact();
        target.setAliasIssns(List.of("9999-9999"));
        target.setWosForumIds(List.of("wos-old"));
        target.setScopusForumIds(List.of("scopus-old"));

        ScopusForumFact preferred = new ScopusForumFact();
        preferred.setSourceId("scopus-new");
        preferred.setIssn("1234-5678");
        preferred.setEIssn("8765-4321");
        preferred.setPublicationName("Scopus Preferred Journal");
        preferred.setAggregationType("JOURNAL");

        LinkedHashSet<String> normalizedIssns = new LinkedHashSet<>(List.of("1234-5678", "8765-4321", "2222-2222"));
        ReflectionTestUtils.invokeMethod(
                service,
                "mergeForum",
                target,
                "wos-new",
                normalizedIssns,
                "WOS Journal Name",
                "wos journal name",
                "JOURNAL",
                "journal",
                List.of(preferred),
                java.time.Instant.parse("2025-01-01T00:00:00Z"),
                "batch-m",
                "corr-m"
        );

        assertEquals("scopus-new", target.getScopusForumIds().getLast());
        assertTrue(target.getWosForumIds().contains("wos-new"));
        assertEquals("1234-5678", target.getIssn());
        assertEquals("8765-4321", target.getEIssn());
        assertEquals("Scopus Preferred Journal", target.getName());
        assertEquals("journal", target.getAggregationTypeNormalized());
        assertTrue(target.getAliasIssns().contains("2222-2222"));
        assertTrue(!target.getAliasIssns().contains("1234-5678"));
        assertTrue(!target.getAliasIssns().contains("8765-4321"));
        assertTrue(target.getCreatedAt() != null);
        assertTrue(target.getUpdatedAt() != null);
    }

    @Test
    void openConflictSetsDetectedAtWhenMissingAndPreservesWhenPresent() {
        WosScholardexOnboardingService service = service();

        ScholardexIdentityConflict missingDetectedAt = new ScholardexIdentityConflict();
        missingDetectedAt.setId("conf-missing");
        missingDetectedAt.setDetectedAt(null);
        when(scholardexIdentityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                eq(ScholardexEntityType.FORUM), eq("WOS"), eq("WOS:ONE"), eq("SOURCE_ID_COLLISION"), eq("OPEN")
        )).thenReturn(Optional.of(missingDetectedAt));

        ReflectionTestUtils.invokeMethod(
                service,
                "openConflict",
                ScholardexEntityType.FORUM,
                "WOS",
                "WOS:ONE",
                "SOURCE_ID_COLLISION",
                null,
                "batch-c1",
                "corr-c1"
        );

        ScholardexIdentityConflict withDetectedAt = new ScholardexIdentityConflict();
        withDetectedAt.setId("conf-set");
        withDetectedAt.setDetectedAt(java.time.Instant.parse("2024-01-01T00:00:00Z"));
        when(scholardexIdentityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                eq(ScholardexEntityType.FORUM), eq("WOS"), eq("WOS:TWO"), eq("SOURCE_ID_COLLISION"), eq("OPEN")
        )).thenReturn(Optional.of(withDetectedAt));

        ReflectionTestUtils.invokeMethod(
                service,
                "openConflict",
                ScholardexEntityType.FORUM,
                "WOS",
                "WOS:TWO",
                "SOURCE_ID_COLLISION",
                List.of("c1", "c2"),
                "batch-c2",
                "corr-c2"
        );

        verify(scholardexIdentityConflictRepository, times(2)).save(any(ScholardexIdentityConflict.class));
        verify(scholardexIdentityConflictRepository).save(argThat(conflict ->
                "conf-missing".equals(conflict.getId())
                        && conflict.getDetectedAt() != null
                        && conflict.getCandidateCanonicalIds().isEmpty()
        ));
        verify(scholardexIdentityConflictRepository).save(argThat(conflict ->
                "conf-set".equals(conflict.getId())
                        && java.time.Instant.parse("2024-01-01T00:00:00Z").equals(conflict.getDetectedAt())
                        && conflict.getCandidateCanonicalIds().equals(List.of("c1", "c2"))
        ));
    }

}
