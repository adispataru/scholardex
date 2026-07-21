package ro.uvt.pokedex.core.service.importing.scopus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorshipFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationAuthorAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorshipFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationAuthorAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusPublicationFactRepository;
import ro.uvt.pokedex.core.service.application.ScholardexEdgeWriterService;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScholardexPublicationCanonicalizationServiceTest {

    @Mock
    private ScopusPublicationFactRepository scopusPublicationFactRepository;
    @Mock
    private ScholardexPublicationFactRepository scholardexPublicationFactRepository;
    @Mock
    private ScholardexAuthorshipFactRepository scholardexAuthorshipFactRepository;
    @Mock
    private ScholardexPublicationAuthorAffiliationFactRepository scholardexPublicationAuthorAffiliationFactRepository;
    @Mock
    private ScholardexIdentityConflictRepository identityConflictRepository;
    @Mock
    private ScholardexSourceLinkService sourceLinkService;
    @Mock
    private ScholardexEdgeWriterService edgeWriterService;
    @Mock
    private ScholardexCanonicalBuildCheckpointService checkpointService;
    @Mock
    private ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository scholardexForumFactRepository;

    private ScholardexPublicationCanonicalizationService service;

    @BeforeEach
    void setUp() {
        service = new ScholardexPublicationCanonicalizationService(
                scopusPublicationFactRepository,
                scholardexPublicationFactRepository,
                scholardexAuthorshipFactRepository,
                scholardexPublicationAuthorAffiliationFactRepository,
                edgeWriterService,
                sourceLinkService,
                identityConflictRepository,
                checkpointService,
                scholardexForumFactRepository
        );
        // H56: findSourceLink now normalizes the probe source via the link service; give the mock the
        // real normalization shape (SCOPUS-family -> SCOPUS, otherwise trimmed upper-case).
        lenient().when(sourceLinkService.normalizeSource(any())).thenAnswer(invocation -> {
            String raw = invocation.getArgument(0);
            if (raw == null || raw.trim().isEmpty()) {
                return null;
            }
            String upper = raw.trim().toUpperCase(java.util.Locale.ROOT);
            return upper.equals("SCOPUS") || upper.startsWith("SCOPUS_") ? "SCOPUS" : upper;
        });
    }

    @Test
    void exposesPublicationPipelineContractAndBridgeHelpers() {
        ReflectionTestUtils.setField(service, "heartbeatSeconds", 29L);
        ScholardexPublicationCanonicalizationService.ChunkContext context =
                (ScholardexPublicationCanonicalizationService.ChunkContext) ReflectionTestUtils.invokeMethod(service, "createChunkContext");
        int defaultChunkSize = ReflectionTestUtils.invokeMethod(service, "getDefaultChunkSize");
        long heartbeatSeconds = ReflectionTestUtils.invokeMethod(service, "getHeartbeatSeconds");
        String fallbackIdUpper = ReflectionTestUtils.invokeMethod(service, "buildCanonicalAuthorFallbackId", "SCOPUS", " AU-1 ", context);
        String fallbackIdLower = ReflectionTestUtils.invokeMethod(service, "buildCanonicalAuthorFallbackId", " scopus ", "au-1", context);
        String authorshipSourceRecordId = ReflectionTestUtils.invokeMethod(service, "buildAuthorshipSourceRecordId", " 2-s2.0-123 ", " AU-1 ", context);

        assertEquals(
                ScholardexCanonicalBuildCheckpointService.PUBLICATION_PIPELINE_KEY,
                ReflectionTestUtils.invokeMethod(service, "getPipelineKey")
        );
        assertEquals("publication", ReflectionTestUtils.invokeMethod(service, "getEntityTypeLabel"));
        assertEquals(ScholardexEntityType.PUBLICATION, ReflectionTestUtils.invokeMethod(service, "getEntityType"));
        assertEquals(1_000, defaultChunkSize);
        assertEquals("scopus-publication-facts-v1", ReflectionTestUtils.invokeMethod(service, "getDefaultSourceVersion"));
        assertEquals(29L, heartbeatSeconds);

        assertEquals(fallbackIdUpper, fallbackIdLower);
        assertEquals("2-s2.0-123::author::au-1", authorshipSourceRecordId);
    }

    @Test
    void canonicalIdIsDeterministicAndUsesDoiPrecedence() {
        // H66B Decision 0: DOI is the primary identity. Two records sharing a DOI collapse to the same
        // canonical id regardless of differing EID/WoS/user/title — that is the cross-source merge win.
        String withDoi = service.buildCanonicalPublicationId(
                "2-s2.0-123",
                "WOS:1",
                "GS:1",
                "U:1",
                "10.1000/abc",
                "paper",
                "2024-01-01",
                "creator",
                "forum-1"
        );
        String sameDoiDifferentOthers = service.buildCanonicalPublicationId(
                "2-s2.0-999",
                "WOS:2",
                "GS:2",
                "U:2",
                "10.1000/abc",
                "other",
                "1999-01-01",
                "other",
                "forum-2"
        );
        String differentDoi = service.buildCanonicalPublicationId(
                "2-s2.0-123",
                "WOS:1",
                "GS:1",
                "U:1",
                "10.1000/xyz",
                "paper",
                "2024-01-01",
                "creator",
                "forum-1"
        );

        assertEquals(withDoi, sameDoiDifferentOthers);
        assertNotEquals(withDoi, differentDoi);
    }

    @Test
    void canonicalIdFallsBackThroughEidWosUserAndTitleBranchesWhenNoDoi() {
        // Cascade order: doi -> eid -> wos -> user -> title+date. The legacy gs| slot is retired.
        String eid = service.buildCanonicalPublicationId("2-s2.0-1", "WOS:1", "GS:1", "U:1", null, "t", "2024", "c", "f");
        String sameEid = service.buildCanonicalPublicationId(" 2-s2.0-1 ", "WOS:9", "GS:9", "U:9", null, "x", "1999", "z", "g");
        String wos = service.buildCanonicalPublicationId(null, "WOS:1", null, null, null, null, null, null, null);
        String sameWos = service.buildCanonicalPublicationId(null, " wos:1 ", "GS:2", "U:2", null, "title", "2024", "creator", "forum");
        String user = service.buildCanonicalPublicationId(null, null, null, "U:1", null, null, null, null, null);
        String doi = service.buildCanonicalPublicationId(null, null, null, null, "10.1000/abc", null, null, null, null);
        String title = service.buildCanonicalPublicationId(null, null, null, null, null, "title", "2024", "creator", "forum");
        String sameTitle = service.buildCanonicalPublicationId(null, null, null, null, null, " title ", " 2024 ", " creator ", " forum ");

        assertEquals(eid, sameEid);
        assertEquals(wos, sameWos);
        assertNotEquals(eid, wos);
        assertNotEquals(wos, user);
        assertNotEquals(user, doi);
        assertEquals(title, sameTitle);
    }

    @Test
    void googleScholarIdIsNoLongerAnIdentitySource() {
        // gs| slot retired: a record whose only identifier is a Google Scholar id falls through to the
        // title+date branch rather than minting a gs-keyed id.
        String gsOnly = service.buildCanonicalPublicationId(null, null, "GS:1", null, null, "shared title", "2024", "creator", "forum");
        String noGs = service.buildCanonicalPublicationId(null, null, null, null, null, "shared title", "2024", "creator", "forum");

        assertEquals(noGs, gsOnly);
    }

    // ── H66B Decision 0: shared-DOI false-merge guard ───────────────────────────

    @Test
    void sharedDoiGuardBlocklistsContainerDoisButNotSamePaperVariants() {
        when(identityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(java.util.Optional.empty());

        // DOI A — a container/proceedings DOI: a "Preface" and a real chapter share almost no title tokens → BLOCK.
        // DOI B — same paper imported twice, identical title but different cover years → must NOT block (a DOI is
        //         globally unique to one work; the year difference is a Scopus online-first/print artifact).
        // DOI C — single record → never a candidate.
        List<ScopusPublicationFact> facts = List.of(
                sourceFact("2-s2.0-A1", "10.1007/978-3-319-11728-7", "Preface", "2015-01-01"),
                sourceFact("2-s2.0-A2", "10.1007/978-3-319-11728-7", "Nanoparticles promises and risks characterization and potential hazards", "2015-01-01"),
                sourceFact("2-s2.0-B1", "10.1016/b978-0-08-096513-0.00001-7", "Crystal Growth and Surfaces", "2010-01-01"),
                sourceFact("2-s2.0-B2", "10.1016/b978-0-08-096513-0.00001-7", "Crystal Growth and Surfaces", "2009-01-01"),
                sourceFact("2-s2.0-C1", "10.1000/solo", "A lonely paper with no DOI siblings", "2021-01-01")
        );

        Set<String> blocklist = service.computeSharedDoiBlocklist(facts);

        assertTrue(blocklist.contains("10.1007/978-3-319-11728-7"), "container DOI must be blocklisted");
        assertFalse(blocklist.contains("10.1016/b978-0-08-096513-0.00001-7"),
                "same-title/different-year pair must NOT be blocklisted (no year gate)");
        assertFalse(blocklist.contains("10.1000/solo"), "single-record DOI is never a candidate");
        assertEquals(1, blocklist.size());

        // A blocklisted DOI falls through to the eid| slot — exactly the pre-Decision-0 separation.
        String blockedDoiId = service.buildCanonicalPublicationId(
                "2-s2.0-A1", null, null, null, "10.1007/978-3-319-11728-7", "Preface", "2015", "c", "f", blocklist);
        String eidOnlyId = service.buildCanonicalPublicationId(
                "2-s2.0-A1", null, null, null, null, "Preface", "2015", "c", "f");
        assertEquals(eidOnlyId, blockedDoiId, "blocklisted DOI must derive identity from the EID slot");
    }

    private ScopusPublicationFact sourceFact(String eid, String doi, String title, String coverDate) {
        ScopusPublicationFact fact = new ScopusPublicationFact();
        fact.setEid(eid);
        fact.setDoi(doi);
        fact.setTitle(title);
        fact.setCoverDate(coverDate);
        return fact;
    }

    @Test
    void edgeWritersAreInvokedWithoutFallbackLookup() {
        // H58: edge facts are preloaded by natural key (findByPublicationIdIn) which is complete for the
        // chunk, so the edge writer is always invoked with allowFallbackLookup=false — a cache miss is
        // authoritative (no per-edge existence check). (Replaces the H57 cleanBuild-gated variant.)
        ScopusPublicationFact scopusFact = new ScopusPublicationFact();
        scopusFact.setEid("2-s2.0-abc");
        scopusFact.setDoi("https://doi.org/10.1000/AbC");
        scopusFact.setTitle("A Title");
        scopusFact.setSource("SCOPUS_JSON_BOOTSTRAP");
        scopusFact.setSourceRecordId("2-s2.0-abc");
        scopusFact.setAuthors(List.of("au-1"));

        when(scopusPublicationFactRepository.count()).thenReturn(1L);
        when(scopusPublicationFactRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(scopusFact)));
        when(scholardexPublicationFactRepository.findAllByEidIn(any())).thenReturn(List.of());
        when(scholardexPublicationFactRepository.findAllByDoiNormalizedIn(any())).thenReturn(List.of());
        when(scholardexAuthorshipFactRepository.findByPublicationIdIn(any())).thenReturn(List.of());
        when(checkpointService.readCheckpoint(anyString())).thenReturn(Optional.empty());
        when(edgeWriterService.batchUpsertAuthorshipEdges(any(), any(), eq(false)))
                .thenReturn(new ScholardexEdgeWriterService.BatchEdgeWriteResult(1, 0, 1, 0, 0));

        service.rebuildCanonicalPublicationFactsFromScopusFacts(fullRescanOptions());

        verify(edgeWriterService, atLeastOnce()).batchUpsertAuthorshipEdges(any(), any(), eq(false));
        verify(edgeWriterService, never()).batchUpsertAuthorshipEdges(any(), any(), eq(true));
    }

    @Test
    void normalizeDoiStripsScopusStrayColonSoUrlAndScopusFormsDedup() {
        // Same paper, two source formats: OpenAlex URL form vs Scopus stray-colon form ("10.12694:/…") must
        // normalise to the SAME value, otherwise they get distinct doiNormalized and fail to dedup.
        String openAlexForm = ScholardexPublicationCanonicalizationService.normalizeDoi("https://doi.org/10.12694/scpe.v21i4.1838");
        String scopusForm = ScholardexPublicationCanonicalizationService.normalizeDoi("10.12694:/scpe.v21i4.1838");
        assertEquals("10.12694/scpe.v21i4.1838", openAlexForm);
        assertEquals("10.12694/scpe.v21i4.1838", scopusForm);
        assertEquals(openAlexForm, scopusForm);
    }

    @Test
    void rebuildCanonicalPublicationFactsFromScopusFactsUpsertsDeterministically() {
        ScopusPublicationFact scopusFact = new ScopusPublicationFact();
        scopusFact.setEid("2-s2.0-abc");
        scopusFact.setDoi("https://doi.org/10.1000/AbC");
        scopusFact.setTitle("A Title");
        scopusFact.setSource("SCOPUS_JSON_BOOTSTRAP");
        scopusFact.setSourceRecordId("2-s2.0-abc");
        scopusFact.setAuthors(List.of("au-1"));

        when(scopusPublicationFactRepository.count()).thenReturn(1L);
        when(scopusPublicationFactRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(scopusFact)));
        when(scholardexPublicationFactRepository.findAllByEidIn(any())).thenReturn(List.of());
        when(scholardexPublicationFactRepository.findAllByDoiNormalizedIn(any())).thenReturn(List.of());
        when(scholardexAuthorshipFactRepository.findByPublicationIdIn(any())).thenReturn(List.of());
        when(checkpointService.readCheckpoint(anyString())).thenReturn(Optional.empty());
        when(sourceLinkService.findByKey(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(edgeWriterService.batchUpsertAuthorshipEdges(any(), any(), eq(false)))
                .thenReturn(new ScholardexEdgeWriterService.BatchEdgeWriteResult(1, 0, 1, 0, 0));

        ImportProcessingResult result = service.rebuildCanonicalPublicationFactsFromScopusFacts(fullRescanOptions());

        assertEquals(1, result.getProcessedCount());
        assertEquals(1, result.getImportedCount());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ScholardexPublicationFact>> insertCaptor = ArgumentCaptor.forClass(List.class);
        verify(scholardexPublicationFactRepository).insert(insertCaptor.capture());
        ScholardexPublicationFact inserted = insertCaptor.getValue().getFirst();
        assertEquals("2-s2.0-abc", inserted.getEid());
        assertEquals("10.1000/abc", inserted.getDoiNormalized());
        assertEquals("a title", inserted.getTitleNormalized());
        assertEquals("SCOPUS_JSON_BOOTSTRAP", inserted.getSource());
        assertEquals("2-s2.0-abc", inserted.getSourceRecordId());
        assertNotNull(inserted.getId());
        assertNotNull(inserted.getCreatedAt());
        assertNotNull(inserted.getUpdatedAt());
        verify(sourceLinkService, atLeastOnce()).batchUpsertWithState(any(), any(), eq(false));
        verify(edgeWriterService, atLeastOnce()).batchUpsertAuthorshipEdges(any(), any(), eq(false));
        // H56: findSourceLink normalizes the probe source before hitting findByKey.
        verify(sourceLinkService, atLeastOnce()).findByKey(
                eq(ScholardexEntityType.AUTHOR), eq("SCOPUS"), eq("au-1"));
    }

    @Test
    void deferToOpenAlexEnrichesOwnedPubWithScopusEidWithoutClobberingOpenAlexFields() {
        // H73 S2.2: when the existing DOI-keyed canonical pub was minted by the OpenAlex-first canon, Scopus
        // enriches (adds eid + Scopus-only fields) but must NOT overwrite OpenAlex's title/citations/source.
        ScopusPublicationFact scopusFact = new ScopusPublicationFact();
        scopusFact.setEid("2-s2.0-xyz");
        scopusFact.setDoi("10.1/shared");
        scopusFact.setTitle("Scopus title");
        scopusFact.setCitedByCount(5);
        scopusFact.setVolume("12");
        scopusFact.setSource("SCOPUS");
        scopusFact.setSourceRecordId("2-s2.0-xyz");
        scopusFact.setAuthors(List.of());

        ScholardexPublicationFact openAlexPub = new ScholardexPublicationFact();
        openAlexPub.setId("spub_shared");
        openAlexPub.setDoiNormalized("10.1/shared");
        openAlexPub.setSource("OPENALEX");
        openAlexPub.setTitle("OpenAlex title");
        openAlexPub.setCitedByCount(99);

        when(scopusPublicationFactRepository.count()).thenReturn(1L);
        when(scopusPublicationFactRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(scopusFact)));
        when(scholardexPublicationFactRepository.findAllByEidIn(any())).thenReturn(List.of());
        when(scholardexPublicationFactRepository.findAllByDoiNormalizedIn(any())).thenReturn(List.of(openAlexPub));
        when(checkpointService.readCheckpoint(anyString())).thenReturn(Optional.empty());

        service.rebuildCanonicalPublicationFactsFromScopusFacts(fullRescanOptions());

        // The preloaded OpenAlex pub is mutated in place: OpenAlex content preserved, Scopus fields enriched.
        assertEquals("OpenAlex title", openAlexPub.getTitle());
        assertEquals(Integer.valueOf(99), openAlexPub.getCitedByCount()); // 5 < 99 -> monotonic max keeps OpenAlex
        assertEquals("OPENALEX", openAlexPub.getSource());
        assertEquals("2-s2.0-xyz", openAlexPub.getEid());
        assertEquals("12", openAlexPub.getVolume());
    }

    @Test
    void normalizeDoi_nullReturnsNull() {
        assertNull(ScholardexPublicationCanonicalizationService.normalizeDoi(null));
    }

    @Test
    void normalizeDoi_blankReturnsNull() {
        assertNull(ScholardexPublicationCanonicalizationService.normalizeDoi("   "));
    }

    @Test
    void normalizeDoi_stripsUrlPrefixAndLowercases() {
        assertEquals("10.1000/abc", ScholardexPublicationCanonicalizationService.normalizeDoi("https://doi.org/10.1000/AbC"));
        assertEquals("10.1000/abc", ScholardexPublicationCanonicalizationService.normalizeDoi("http://dx.doi.org/10.1000/AbC"));
        assertEquals("10.1000/abc", ScholardexPublicationCanonicalizationService.normalizeDoi("doi:10.1000/AbC"));
    }

    @Test
    void normalizeTitle_nullReturnsNull() {
        assertNull(ScholardexPublicationCanonicalizationService.normalizeTitle(null));
    }

    @Test
    void normalizeTitle_onlySpecialCharsReturnsNull() {
        assertNull(ScholardexPublicationCanonicalizationService.normalizeTitle("...---"));
    }

    @Test
    void normalizeTitle_stripsSpecialCharsAndLowercases() {
        assertEquals("a title with diacritics", ScholardexPublicationCanonicalizationService.normalizeTitle("A Title With Diacritics"));
        assertEquals("co2 emissions", ScholardexPublicationCanonicalizationService.normalizeTitle("CO₂ Emissions"));
    }

    @Test
    void toScopusBridgeFactCopiesPublicationFieldsNeededForRecovery() {
        ScholardexPublicationFact fact = new ScholardexPublicationFact();
        fact.setEid("2-s2.0-bridge");
        fact.setDoi("10.1000/bridge");
        fact.setTitle("Bridge title");
        fact.setSubtype("ar");
        fact.setSubtypeDescription("article");
        fact.setScopusSubtype("article");
        fact.setScopusSubtypeDescription("Article");
        fact.setCreator("Author, A.");
        fact.setAuthorCount(3);
        fact.setCorrespondingAuthors(List.of("sauth_1"));
        fact.setAffiliationIds(List.of("saff_1"));
        fact.setForumId("sforum_1");
        fact.setVolume("42");
        fact.setIssueIdentifier("7");
        fact.setCoverDate("2024-02-01");
        fact.setCoverDisplayDate("Feb 2024");
        fact.setDescription("Bridge description");
        fact.setCitedByCount(12);
        fact.setOpenAccess(true);
        fact.setFreetoread("all");
        fact.setFreetoreadLabel("repository");
        fact.setFundingId("fund_1");
        fact.setArticleNumber("A-10");
        fact.setPageRange("10-20");
        fact.setApproved(Boolean.TRUE);
        fact.setSourceEventId("evt-1");
        fact.setSource("SCOPUS_JSON_UPLOAD");
        fact.setSourceRecordId("2-s2.0-bridge");
        fact.setSourceBatchId("batch-1");
        fact.setSourceCorrelationId("corr-1");

        ScopusPublicationFact bridge = ReflectionTestUtils.invokeMethod(service, "toScopusBridgeFact", fact);

        assertEquals("2-s2.0-bridge", bridge.getEid());
        assertEquals("10.1000/bridge", bridge.getDoi());
        assertEquals("Bridge title", bridge.getTitle());
        assertEquals("ar", bridge.getSubtype());
        assertEquals("article", bridge.getSubtypeDescription());
        assertEquals("article", bridge.getScopusSubtype());
        assertEquals("Article", bridge.getScopusSubtypeDescription());
        assertEquals("Author, A.", bridge.getCreator());
        assertEquals(3, bridge.getAuthorCount());
        assertEquals(List.of("sauth_1"), bridge.getCorrespondingAuthors());
        assertEquals(List.of("saff_1"), bridge.getAffiliations());
        assertEquals("sforum_1", bridge.getForumId());
        assertEquals("42", bridge.getVolume());
        assertEquals("7", bridge.getIssueIdentifier());
        assertEquals("2024-02-01", bridge.getCoverDate());
        assertEquals("Feb 2024", bridge.getCoverDisplayDate());
        assertEquals("Bridge description", bridge.getDescription());
        assertEquals(12, bridge.getCitedByCount());
        assertEquals(true, bridge.getOpenAccess());
        assertEquals("all", bridge.getFreetoread());
        assertEquals("repository", bridge.getFreetoreadLabel());
        assertEquals("fund_1", bridge.getFundingId());
        assertEquals("A-10", bridge.getArticleNumber());
        assertEquals("10-20", bridge.getPageRange());
        assertEquals(true, bridge.getApproved());
        assertEquals("evt-1", bridge.getSourceEventId());
        assertEquals("SCOPUS_JSON_UPLOAD", bridge.getSource());
        assertEquals("2-s2.0-bridge", bridge.getSourceRecordId());
        assertEquals("batch-1", bridge.getSourceBatchId());
        assertEquals("corr-1", bridge.getSourceCorrelationId());
    }

    @Test
    void sortSourceFactsOrdersByEidThenSourceRecordId() {
        ScopusPublicationFact factZzz = new ScopusPublicationFact();
        factZzz.setEid("2-s2.0-zzz");
        factZzz.setSource("SCOPUS_JSON_BOOTSTRAP");
        factZzz.setSourceRecordId("2-s2.0-zzz");
        factZzz.setAuthors(List.of());

        ScopusPublicationFact factAaa = new ScopusPublicationFact();
        factAaa.setEid("2-s2.0-aaa");
        factAaa.setSource("SCOPUS_JSON_BOOTSTRAP");
        factAaa.setSourceRecordId("2-s2.0-aaa");
        factAaa.setAuthors(List.of());

        when(scopusPublicationFactRepository.count()).thenReturn(2L);
        // zzz returned first — sort should produce aaa first
        when(scopusPublicationFactRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(factZzz, factAaa)));
        lenient().when(scholardexPublicationFactRepository.findAllByEidIn(any())).thenReturn(List.of());
        lenient().when(scholardexPublicationFactRepository.findAllByDoiNormalizedIn(any())).thenReturn(List.of());
        lenient().when(scholardexAuthorshipFactRepository.findByPublicationIdIn(any())).thenReturn(List.of());
        when(checkpointService.readCheckpoint(anyString())).thenReturn(Optional.empty());
        lenient().when(sourceLinkService.findByKey(any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(edgeWriterService.batchUpsertAuthorshipEdges(any(), any(), eq(false)))
                .thenReturn(new ScholardexEdgeWriterService.BatchEdgeWriteResult(0, 0, 0, 0, 0));

        service.rebuildCanonicalPublicationFactsFromScopusFacts(fullRescanOptions());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ScholardexPublicationFact>> insertCaptor = ArgumentCaptor.forClass(List.class);
        verify(scholardexPublicationFactRepository).insert(insertCaptor.capture());
        List<ScholardexPublicationFact> inserted = insertCaptor.getValue();
        assertEquals("2-s2.0-aaa", inserted.get(0).getEid());
        assertEquals("2-s2.0-zzz", inserted.get(1).getEid());
    }

    @Test
    void loadSourceFactsStopsAfterLastPageInsteadOfPollingIndefinitely() {
        ScopusPublicationFact first = new ScopusPublicationFact();
        first.setEid("2-s2.0-001");
        ScopusPublicationFact second = new ScopusPublicationFact();
        second.setEid("2-s2.0-002");
        @SuppressWarnings("unchecked")
        Page<ScopusPublicationFact> firstPage = org.mockito.Mockito.mock(Page.class);
        @SuppressWarnings("unchecked")
        Page<ScopusPublicationFact> secondPage = org.mockito.Mockito.mock(Page.class);

        ReflectionTestUtils.setField(service, "loadProgressRecordInterval", 1);
        when(scopusPublicationFactRepository.count()).thenReturn(2L);
        when(firstPage.isEmpty()).thenReturn(false);
        when(firstPage.getContent()).thenReturn(List.of(first));
        when(firstPage.getNumberOfElements()).thenReturn(1);
        when(firstPage.hasNext()).thenReturn(true);
        when(secondPage.isEmpty()).thenReturn(false);
        when(secondPage.getContent()).thenReturn(List.of(second));
        when(secondPage.getNumberOfElements()).thenReturn(1);
        when(secondPage.hasNext()).thenReturn(false);
        when(scopusPublicationFactRepository.findAll(any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(0);
            return switch (pageable.getPageNumber()) {
                case 0 -> firstPage;
                case 1 -> secondPage;
                default -> throw new AssertionError("Unexpected extra page fetch: " + pageable);
            };
        });

        @SuppressWarnings("unchecked")
        List<ScopusPublicationFact> facts =
                (List<ScopusPublicationFact>) ReflectionTestUtils.invokeMethod(service, "loadSourceFacts", fullRescanOptions());
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        assertEquals(List.of(first, second), facts);
        verify(scopusPublicationFactRepository, times(2)).findAll(pageableCaptor.capture());
        assertEquals(List.of(0, 1), pageableCaptor.getAllValues().stream().map(Pageable::getPageNumber).toList());
    }

    @Test
    void rebuildCanonicalPublicationFactsFromScopusFactsDefaultWrapperReturnsEmptyResult() {
        when(checkpointService.readCheckpoint(anyString())).thenReturn(Optional.empty());
        when(scopusPublicationFactRepository.count()).thenReturn(0L);
        when(scopusPublicationFactRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        ImportProcessingResult result = service.rebuildCanonicalPublicationFactsFromScopusFacts();

        assertEquals(0, result.getProcessedCount());
        assertEquals(0, result.getImportedCount());
    }

    @Test
    void applyCanonicalPublicationFieldsIsIdempotentForReplayAndPreservesCreatedAt() {
        ScholardexPublicationFact fact = new ScholardexPublicationFact();
        Instant createdAt = Instant.parse("2024-01-01T00:00:00Z");
        Instant firstUpdate = Instant.parse("2024-02-01T00:00:00Z");
        Instant secondUpdate = Instant.parse("2024-03-01T00:00:00Z");
        fact.setCreatedAt(createdAt);
        fact.setWosId("WOS:1");
        fact.setGoogleScholarId("GS:1");
        fact.setUserSourceId("USR:1");
        fact.setDoi("old-doi");
        fact.setTitle("old title");

        ScopusPublicationFact scopusFact = new ScopusPublicationFact();
        scopusFact.setEid("2-s2.0-replay");
        scopusFact.setDoi("https://doi.org/10.1000/Replay");
        scopusFact.setTitle("Replay Title");
        scopusFact.setSubtype("ar");
        scopusFact.setSubtypeDescription("article");
        scopusFact.setScopusSubtype("article");
        scopusFact.setScopusSubtypeDescription("Article");
        scopusFact.setCreator("Author, A.");
        scopusFact.setAuthorCount(2);
        scopusFact.setCorrespondingAuthors(List.of("sauth_1"));
        scopusFact.setAffiliations(List.of());
        scopusFact.setForumId("sforum_1");
        scopusFact.setVolume("42");
        scopusFact.setIssueIdentifier("7");
        scopusFact.setCoverDate("2024-02-02");
        scopusFact.setCoverDisplayDate("2 Feb 2024");
        scopusFact.setDescription("Replay description");
        scopusFact.setCitedByCount(5);
        scopusFact.setOpenAccess(true);
        scopusFact.setFreetoread("all");
        scopusFact.setFreetoreadLabel("repository");
        scopusFact.setFundingId("fund-1");
        scopusFact.setArticleNumber("A-1");
        scopusFact.setPageRange("1-10");
        scopusFact.setApproved(Boolean.TRUE);
        scopusFact.setSourceEventId("evt-1");
        scopusFact.setSource("SCOPUS_JSON_UPLOAD");
        scopusFact.setSourceRecordId("2-s2.0-replay");
        scopusFact.setSourceBatchId("batch-1");
        scopusFact.setSourceCorrelationId("corr-1");

        ScholardexPublicationCanonicalizationService.AuthorBridgeResult bridgeResult =
                new ScholardexPublicationCanonicalizationService.AuthorBridgeResult(
                        List.of("sauth_1"),
                        List.of("au-2"),
                        List.of(
                                new ScholardexPublicationCanonicalizationService.AuthorBridgeEntry("sauth_1", "au-1", false),
                                new ScholardexPublicationCanonicalizationService.AuthorBridgeEntry("sauth_fallback", "au-2", true)
                        )
                );
        ScholardexPublicationCanonicalizationService.ChunkContext context =
                ReflectionTestUtils.invokeMethod(service, "createChunkContext");

        ReflectionTestUtils.invokeMethod(service, "applyCanonicalPublicationFields", fact, scopusFact, bridgeResult, firstUpdate, context);
        String firstCanonicalId = fact.getId();
        ReflectionTestUtils.invokeMethod(service, "applyCanonicalPublicationFields", fact, scopusFact, bridgeResult, secondUpdate, context);

        assertEquals(firstCanonicalId, fact.getId());
        assertEquals(createdAt, fact.getCreatedAt());
        assertEquals(secondUpdate, fact.getUpdatedAt());
        assertEquals("https://doi.org/10.1000/Replay", fact.getDoi());
        assertEquals("10.1000/replay", fact.getDoiNormalized());
        assertEquals("Replay Title", fact.getTitle());
        assertEquals("replay title", fact.getTitleNormalized());
        assertEquals("2-s2.0-replay", fact.getEid());
        assertEquals("ar", fact.getSubtype());
        assertEquals("article", fact.getSubtypeDescription());
        assertEquals("article", fact.getScopusSubtype());
        assertEquals("Article", fact.getScopusSubtypeDescription());
        assertEquals("Author, A.", fact.getCreator());
        assertEquals(2, fact.getAuthorCount());
        assertEquals(List.of("sauth_1"), fact.getAuthorIds());
        assertEquals(List.of("au-2"), fact.getPendingAuthorSourceIds());
        assertEquals(List.of("sauth_1"), fact.getCorrespondingAuthors());
        assertEquals(List.of(), fact.getAffiliationIds());
        assertEquals("sforum_1", fact.getForumId());
        assertEquals("42", fact.getVolume());
        assertEquals("7", fact.getIssueIdentifier());
        assertEquals("2024-02-02", fact.getCoverDate());
        assertEquals("2 Feb 2024", fact.getCoverDisplayDate());
        assertEquals("Replay description", fact.getDescription());
        assertEquals(5, fact.getCitedByCount());
        assertEquals(true, fact.getOpenAccess());
        assertEquals("all", fact.getFreetoread());
        assertEquals("repository", fact.getFreetoreadLabel());
        assertEquals("fund-1", fact.getFundingId());
        assertEquals("A-1", fact.getArticleNumber());
        assertEquals("1-10", fact.getPageRange());
        assertEquals(true, fact.getApproved());
        assertEquals("evt-1", fact.getSourceEventId());
        assertEquals("SCOPUS_JSON_UPLOAD", fact.getSource());
        assertEquals("2-s2.0-replay", fact.getSourceRecordId());
        assertEquals("batch-1", fact.getSourceBatchId());
        assertEquals("corr-1", fact.getSourceCorrelationId());
    }

    @Test
    void applyCanonicalPublicationFieldsResolvesRawScopusForumIdToCanonicalForumId() {
        // H55.2: the stored forumId is re-pointed from the raw Scopus forum id to the canonical
        // sforum_ id via the FORUM/SCOPUS source link minted by H55.1.
        ScholardexPublicationFact fact = new ScholardexPublicationFact();
        ScopusPublicationFact scopusFact = new ScopusPublicationFact();
        scopusFact.setEid("2-s2.0-forum-replay");
        scopusFact.setTitle("Forum Replay");
        scopusFact.setSource("SCOPUS");
        scopusFact.setSourceRecordId("2-s2.0-forum-replay");
        scopusFact.setForumId("1000147102");

        ScholardexPublicationCanonicalizationService.AuthorBridgeResult bridgeResult =
                new ScholardexPublicationCanonicalizationService.AuthorBridgeResult(List.of(), List.of(), List.of());
        ScholardexPublicationCanonicalizationService.ChunkContext context =
                ReflectionTestUtils.invokeMethod(service, "createChunkContext");
        ScholardexSourceLink forumLink = new ScholardexSourceLink();
        forumLink.setCanonicalEntityId("sforum_abc123");
        when(sourceLinkService.findByKey(ScholardexEntityType.FORUM, "SCOPUS", "1000147102"))
                .thenReturn(Optional.of(forumLink));

        ReflectionTestUtils.invokeMethod(
                service, "applyCanonicalPublicationFields", fact, scopusFact, bridgeResult, Instant.now(), context);

        assertEquals("sforum_abc123", fact.getForumId());
    }

    @Test
    void applyCanonicalPublicationFieldsNeverReplacesDblpStampedForum() {
        // Mirror of the OpenAlex guard: a pub whose forumId was re-stamped from DBLP evidence onto a
        // conf/X stream forum keeps it across a Scopus refresh — the incremental task path never runs
        // rebuildFromEvidence, so re-pointing here would silently revert to the Scopus host venue.
        ScholardexPublicationFact fact = new ScholardexPublicationFact();
        fact.setSource("SCOPUS_JSON_BOOTSTRAP"); // NOT OpenAlex-owned: the full Scopus field set applies
        fact.setForumId("sforum_dblpsynasc");
        ScopusPublicationFact scopusFact = new ScopusPublicationFact();
        scopusFact.setEid("2-s2.0-dblp-guard");
        scopusFact.setTitle("DBLP Guard");
        scopusFact.setSource("SCOPUS");
        scopusFact.setSourceRecordId("2-s2.0-dblp-guard");
        scopusFact.setForumId("1000147102"); // the Scopus host venue the merge would otherwise re-point to

        ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact dblpForum =
                new ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact();
        dblpForum.setId("sforum_dblpsynasc");
        dblpForum.setDblpIds(new java.util.ArrayList<>(List.of("conf/synasc")));
        when(scholardexForumFactRepository.findByDblpIdsNotNull()).thenReturn(List.of(dblpForum));

        ScholardexPublicationCanonicalizationService.AuthorBridgeResult bridgeResult =
                new ScholardexPublicationCanonicalizationService.AuthorBridgeResult(List.of(), List.of(), List.of());
        ScholardexPublicationCanonicalizationService.ChunkContext context =
                ReflectionTestUtils.invokeMethod(service, "createChunkContext");

        ReflectionTestUtils.invokeMethod(
                service, "applyCanonicalPublicationFields", fact, scopusFact, bridgeResult, Instant.now(), context);

        assertEquals("sforum_dblpsynasc", fact.getForumId());
    }

    @Test
    void applyCanonicalPublicationFieldsKeepsRawForumIdWhenUnresolvedRatherThanNulling() {
        // H55.2 contract: an unresolved Scopus forum id is kept (never silently nulled). H55.1
        // guarantees coverage, so this defensive path should stay empty in practice.
        ScholardexPublicationFact fact = new ScholardexPublicationFact();
        ScopusPublicationFact scopusFact = new ScopusPublicationFact();
        scopusFact.setEid("2-s2.0-forum-unresolved");
        scopusFact.setTitle("Forum Unresolved");
        scopusFact.setSource("SCOPUS");
        scopusFact.setSourceRecordId("2-s2.0-forum-unresolved");
        scopusFact.setForumId("9999999999");

        ScholardexPublicationCanonicalizationService.AuthorBridgeResult bridgeResult =
                new ScholardexPublicationCanonicalizationService.AuthorBridgeResult(List.of(), List.of(), List.of());
        ScholardexPublicationCanonicalizationService.ChunkContext context =
                ReflectionTestUtils.invokeMethod(service, "createChunkContext");
        when(sourceLinkService.findByKey(ScholardexEntityType.FORUM, "SCOPUS", "9999999999"))
                .thenReturn(Optional.empty());

        ReflectionTestUtils.invokeMethod(
                service, "applyCanonicalPublicationFields", fact, scopusFact, bridgeResult, Instant.now(), context);

        assertEquals("9999999999", fact.getForumId());
    }

    @Test
    void applyCanonicalPublicationFieldsUsesEmptyCorrespondingAuthorsAndResolvesDistinctAffiliations() {
        ScholardexPublicationFact fact = new ScholardexPublicationFact();
        ScopusPublicationFact scopusFact = new ScopusPublicationFact();
        scopusFact.setEid("2-s2.0-aff-replay");
        scopusFact.setDoi("10.1000/aff");
        scopusFact.setTitle("Aff Replay");
        scopusFact.setSource("SCOPUS");
        scopusFact.setSourceRecordId("2-s2.0-aff-replay");
        scopusFact.setAuthors(List.of("au-1"));
        scopusFact.setCorrespondingAuthors(null);
        scopusFact.setAffiliations(List.of("af1", "af1"));
        scopusFact.setAuthorAffiliationSourceIds(List.of("af2-af1"));

        ScholardexPublicationCanonicalizationService.AuthorBridgeResult bridgeResult =
                new ScholardexPublicationCanonicalizationService.AuthorBridgeResult(
                        List.of("sauth_1"),
                        List.of(),
                        List.of(new ScholardexPublicationCanonicalizationService.AuthorBridgeEntry("sauth_1", "au-1", false))
                );
        ScholardexPublicationCanonicalizationService.ChunkContext context =
                ReflectionTestUtils.invokeMethod(service, "createChunkContext");
        ScholardexSourceLink aff1 = new ScholardexSourceLink();
        aff1.setCanonicalEntityId("saff_1");
        ScholardexSourceLink aff2 = new ScholardexSourceLink();
        aff2.setCanonicalEntityId("saff_2");

        when(sourceLinkService.findByKey(ScholardexEntityType.AFFILIATION, "SCOPUS", "af1")).thenReturn(Optional.of(aff1));
        when(sourceLinkService.findByKey(ScholardexEntityType.AFFILIATION, "SCOPUS", "af2")).thenReturn(Optional.of(aff2));

        ReflectionTestUtils.invokeMethod(
                service,
                "applyCanonicalPublicationFields",
                fact,
                scopusFact,
                bridgeResult,
                Instant.parse("2024-04-01T00:00:00Z"),
                context
        );

        assertEquals(List.of(), fact.getCorrespondingAuthors());
        assertEquals(List.of("saff_1", "saff_2"), fact.getAffiliationIds());
    }

    private CanonicalBuildOptions fullRescanOptions() {
        return new CanonicalBuildOptions(null, null, true, null, null, false, false);
    }

    @Test
    void bridgeAuthorIdsReturnsDeterministicFallbackAndPendingMarkerWhenNoCanonicalLinkExists() {
        when(sourceLinkService.findByKey(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(sourceLinkService.normalizeSource("SCOPUS_JSON_BOOTSTRAP")).thenReturn("SCOPUS");

        ScholardexPublicationCanonicalizationService.AuthorBridgeResult bridged = service.bridgeAuthorIds(
                List.of("  au-1 ", "au-1"),
                "SCOPUS_JSON_BOOTSTRAP"
        );

        assertEquals(1, bridged.canonicalAuthorIds().size());
        assertEquals("au-1", bridged.pendingSourceIds().getFirst());
        assertEquals("au-1", bridged.entries().getFirst().sourceAuthorId());
        assertEquals(true, bridged.entries().getFirst().pendingResolution());
    }

    @Test
    void bridgeAuthorIdsUsesStableFallbackIdsAcrossScopusVariants() {
        when(sourceLinkService.findByKey(any(), any(), any())).thenReturn(Optional.empty());
        when(sourceLinkService.normalizeSource("SCOPUS_JSON_BOOTSTRAP")).thenReturn("SCOPUS");
        when(sourceLinkService.normalizeSource("SCOPUS_PYTHON_AUTHOR_WORKS")).thenReturn("SCOPUS");

        ScholardexPublicationCanonicalizationService.AuthorBridgeResult bootstrap = service.bridgeAuthorIds(
                List.of("au-1"),
                "SCOPUS_JSON_BOOTSTRAP"
        );
        ScholardexPublicationCanonicalizationService.AuthorBridgeResult python = service.bridgeAuthorIds(
                List.of("au-1"),
                "SCOPUS_PYTHON_AUTHOR_WORKS"
        );

        assertEquals(bootstrap.canonicalAuthorIds(), python.canonicalAuthorIds());
        assertEquals(bootstrap.pendingSourceIds(), python.pendingSourceIds());
    }

    @Test
    void bridgeAuthorIdsReturnsEmptyResultForNullOrEmptyInput() {
        ScholardexPublicationCanonicalizationService.AuthorBridgeResult nullResult = service.bridgeAuthorIds(null, "SCOPUS");
        ScholardexPublicationCanonicalizationService.AuthorBridgeResult emptyResult = service.bridgeAuthorIds(List.of(), "SCOPUS");

        assertEquals(List.of(), nullResult.canonicalAuthorIds());
        assertEquals(List.of(), nullResult.pendingSourceIds());
        assertEquals(List.of(), nullResult.entries());
        assertEquals(List.of(), emptyResult.canonicalAuthorIds());
        assertEquals(List.of(), emptyResult.pendingSourceIds());
        assertEquals(List.of(), emptyResult.entries());
    }

    @Test
    void bridgeAuthorIdsInternalQueuesFallbackLinksAndDedupesResolvedAuthors() {
        ScholardexPublicationCanonicalizationService.ChunkContext context =
                ReflectionTestUtils.invokeMethod(service, "createChunkContext");
        ScholardexSourceLink resolvedLink = new ScholardexSourceLink();
        resolvedLink.setCanonicalEntityId("sauth_resolved");

        // H56: findSourceLink normalizes the probe source, so findByKey is always called with SCOPUS.
        when(sourceLinkService.normalizeSource("SCOPUS_JSON_BOOTSTRAP")).thenReturn("SCOPUS");
        when(sourceLinkService.findByKey(ScholardexEntityType.AUTHOR, "SCOPUS", "au-1"))
                .thenReturn(Optional.of(resolvedLink));
        when(sourceLinkService.findByKey(ScholardexEntityType.AUTHOR, "SCOPUS", "au-2"))
                .thenReturn(Optional.empty());

        ScholardexPublicationCanonicalizationService.AuthorBridgeResult bridged =
                ReflectionTestUtils.invokeMethod(
                        service,
                        "bridgeAuthorIdsInternal",
                        List.of("au-1", " au-1 ", "au-2", "au-2", "   "),
                        "SCOPUS_JSON_BOOTSTRAP",
                        context
                );

        assertEquals(List.of("sauth_resolved", bridged.entries().get(1).canonicalAuthorId()), bridged.canonicalAuthorIds());
        assertEquals(List.of("au-2"), bridged.pendingSourceIds());
        assertEquals(false, bridged.entries().getFirst().pendingResolution());
        assertEquals(true, bridged.entries().get(1).pendingResolution());

        @SuppressWarnings("unchecked")
        Map<Object, Object> pendingCommands = (Map<Object, Object>) ReflectionTestUtils.getField(context, "pendingSourceLinkCommands");
        assertEquals(1, pendingCommands.size());
    }

    @Test
    void findSingleByDoiReturnsEmptyAndMarksSkippedWhenAmbiguous() {
        ScholardexPublicationFact zzz = new ScholardexPublicationFact();
        zzz.setId("spub_zzz");
        ScholardexPublicationFact aaa = new ScholardexPublicationFact();
        aaa.setId("spub_aaa");
        when(scholardexPublicationFactRepository.findAllByDoiNormalized("10.1000/abc")).thenReturn(List.of(zzz, aaa));

        ImportProcessingResult result = new ImportProcessingResult(10);
        Optional<ScholardexPublicationFact> found =
                ReflectionTestUtils.invokeMethod(service, "findSingleByDoi", "10.1000/abc", result);

        assertEquals(false, found.isPresent());
        assertEquals(1, result.getSkippedCount());
    }

    @Test
    void upsertPublicationEdgesQueuesAuthorshipAndConflictForUnresolvedAffiliation() {
        ScholardexPublicationFact fact = new ScholardexPublicationFact();
        fact.setId("spub_1");
        fact.setSource("SCOPUS");
        fact.setSourceRecordId("2-s2.0-1");
        fact.setSourceEventId("evt-1");
        fact.setSourceBatchId("batch-1");
        fact.setSourceCorrelationId("corr-1");

        ScopusPublicationFact sourceFact = new ScopusPublicationFact();
        sourceFact.setSource("SCOPUS");
        sourceFact.setSourceRecordId("2-s2.0-1");
        sourceFact.setAuthors(List.of("au-1", "au-2"));
        sourceFact.setAuthorAffiliationSourceIds(List.of("af1", "af2"));

        ScholardexPublicationCanonicalizationService.AuthorBridgeResult bridge =
                new ScholardexPublicationCanonicalizationService.AuthorBridgeResult(
                        List.of("sauth_1", "sauth_2"),
                        List.of(),
                        List.of(
                                new ScholardexPublicationCanonicalizationService.AuthorBridgeEntry("sauth_1", "au-1", false),
                                new ScholardexPublicationCanonicalizationService.AuthorBridgeEntry("sauth_2", "au-2", false)
                        )
                );
        ScholardexPublicationCanonicalizationService.ChunkContext context =
                ReflectionTestUtils.invokeMethod(service, "createChunkContext");
        ScholardexSourceLink aff1 = new ScholardexSourceLink();
        aff1.setCanonicalEntityId("saff_1");

        when(sourceLinkService.findByKey(ScholardexEntityType.AFFILIATION, "SCOPUS", "af1")).thenReturn(Optional.of(aff1));
        when(sourceLinkService.findByKey(ScholardexEntityType.AFFILIATION, "SCOPUS", "af2")).thenReturn(Optional.empty());

        ReflectionTestUtils.invokeMethod(service, "upsertPublicationEdges", fact, sourceFact, bridge, context);

        @SuppressWarnings("unchecked")
        Map<String, Object> pendingAuthorship = (Map<String, Object>) ReflectionTestUtils.getField(context, "pendingAuthorshipCommands");
        @SuppressWarnings("unchecked")
        Map<String, Object> pendingPaa = (Map<String, Object>) ReflectionTestUtils.getField(context, "pendingPublicationAuthorAffiliationCommands");
        @SuppressWarnings("unchecked")
        Map<String, Object> pendingConflicts = (Map<String, Object>) ReflectionTestUtils.getField(context, "pendingIdentityConflicts");

        assertEquals(2, pendingAuthorship.size());
        assertEquals(1, pendingPaa.size());
        assertEquals(1, pendingConflicts.size());
    }

    @Test
    void upsertPublicationEdgesDedupesResolvedAffiliationsAndCapturesConflictMetadata() {
        ScholardexPublicationFact fact = new ScholardexPublicationFact();
        fact.setId("spub_1");
        fact.setSource("SCOPUS_JSON_UPLOAD");
        fact.setSourceRecordId("2-s2.0-1");
        fact.setSourceEventId("evt-1");
        fact.setSourceBatchId("batch-1");
        fact.setSourceCorrelationId("corr-1");

        ScopusPublicationFact sourceFact = new ScopusPublicationFact();
        sourceFact.setSource("SCOPUS_JSON_UPLOAD");
        sourceFact.setSourceRecordId("2-s2.0-1");
        sourceFact.setAuthors(List.of("au-1", "au-2"));
        sourceFact.setAuthorAffiliationSourceIds(List.of("af1-af1", "missing"));

        ScholardexPublicationCanonicalizationService.AuthorBridgeResult bridge =
                new ScholardexPublicationCanonicalizationService.AuthorBridgeResult(
                        List.of("sauth_1", "sauth_2"),
                        List.of(),
                        List.of(
                                new ScholardexPublicationCanonicalizationService.AuthorBridgeEntry("sauth_1", "au-1", false),
                                new ScholardexPublicationCanonicalizationService.AuthorBridgeEntry("sauth_2", "au-2", false)
                        )
                );
        ScholardexPublicationCanonicalizationService.ChunkContext context =
                ReflectionTestUtils.invokeMethod(service, "createChunkContext");
        ScholardexSourceLink affiliationLink = new ScholardexSourceLink();
        affiliationLink.setCanonicalEntityId("saff_1");

        // H56: findSourceLink normalizes the probe source, so findByKey is always called with SCOPUS.
        when(sourceLinkService.findByKey(ScholardexEntityType.AFFILIATION, "SCOPUS", "af1"))
                .thenReturn(Optional.of(affiliationLink));
        when(sourceLinkService.findByKey(ScholardexEntityType.AFFILIATION, "SCOPUS", "missing"))
                .thenReturn(Optional.empty());

        ReflectionTestUtils.invokeMethod(service, "upsertPublicationEdges", fact, sourceFact, bridge, context);

        @SuppressWarnings("unchecked")
        Map<String, ScholardexEdgeWriterService.EdgeWriteCommand> pendingAuthorship =
                (Map<String, ScholardexEdgeWriterService.EdgeWriteCommand>) ReflectionTestUtils.getField(context, "pendingAuthorshipCommands");
        @SuppressWarnings("unchecked")
        Map<String, ScholardexEdgeWriterService.EdgeWriteCommand> pendingPaa =
                (Map<String, ScholardexEdgeWriterService.EdgeWriteCommand>) ReflectionTestUtils.getField(context, "pendingPublicationAuthorAffiliationCommands");
        @SuppressWarnings("unchecked")
        Map<String, ScholardexIdentityConflict> pendingConflicts =
                (Map<String, ScholardexIdentityConflict>) ReflectionTestUtils.getField(context, "pendingIdentityConflicts");

        assertEquals(2, pendingAuthorship.size());
        ScholardexEdgeWriterService.EdgeWriteCommand authorship = pendingAuthorship.values().iterator().next();
        assertEquals("spub_1", authorship.leftId());
        assertEquals("SCOPUS_JSON_UPLOAD", authorship.source());
        assertEquals("LINKED", authorship.linkState());

        assertEquals(1, pendingPaa.size());
        ScholardexEdgeWriterService.EdgeWriteCommand publicationAffiliation = pendingPaa.values().iterator().next();
        assertEquals("spub_1", publicationAffiliation.publicationId());
        assertEquals("sauth_1", publicationAffiliation.leftId());
        assertEquals("saff_1", publicationAffiliation.rightId());
        assertEquals("2-s2.0-1::author::au-1::affiliation::af1", publicationAffiliation.sourceRecordId());
        assertEquals("publication-author-affiliation-bridge", publicationAffiliation.linkReason());

        assertEquals(1, pendingConflicts.size());
        ScholardexIdentityConflict conflict = pendingConflicts.values().iterator().next();
        assertEquals(ScholardexEntityType.PUBLICATION_AUTHOR_AFFILIATION, conflict.getEntityType());
        assertEquals("SCOPUS_JSON_UPLOAD", conflict.getIncomingSource());
        assertEquals("2-s2.0-1::author::au-2::affiliation::missing", conflict.getIncomingSourceRecordId());
        assertEquals("PUBLICATION_AUTHOR_AFFILIATION_UNRESOLVED", conflict.getReasonCode());
        assertEquals("OPEN", conflict.getStatus());
        assertEquals(List.of(), conflict.getCandidateCanonicalIds());
        assertEquals("evt-1", conflict.getSourceEventId());
        assertEquals("batch-1", conflict.getSourceBatchId());
        assertEquals("corr-1", conflict.getSourceCorrelationId());
        assertNotNull(conflict.getDetectedAt());
    }

    @Test
    void findSourceLinkReturnsEmptyForMissingKeyAndCachesResolvedLink() {
        ScholardexPublicationCanonicalizationService.ChunkContext context =
                ReflectionTestUtils.invokeMethod(service, "createChunkContext");
        ScholardexSourceLink resolved = new ScholardexSourceLink();
        resolved.setCanonicalEntityId("sauth_1");

        Optional<ScholardexSourceLink> blank =
                ReflectionTestUtils.invokeMethod(service, "findSourceLink", ScholardexEntityType.AUTHOR, " ", "au-1", context);
        when(sourceLinkService.findByKey(ScholardexEntityType.AUTHOR, "SCOPUS", "au-1")).thenReturn(Optional.of(resolved));
        Optional<ScholardexSourceLink> fetched =
                ReflectionTestUtils.invokeMethod(service, "findSourceLink", ScholardexEntityType.AUTHOR, "SCOPUS", "au-1", context);
        Optional<ScholardexSourceLink> cached =
                ReflectionTestUtils.invokeMethod(service, "findSourceLink", ScholardexEntityType.AUTHOR, "SCOPUS", "au-1", context);

        assertEquals(true, blank.isEmpty());
        assertEquals(true, fetched.isPresent());
        assertEquals(true, cached.isPresent());
    }

    @Test
    void findSourceLinkShortCircuitsBlankKeysAndRememberedMisses() {
        ScholardexPublicationCanonicalizationService.ChunkContext context =
                ReflectionTestUtils.invokeMethod(service, "createChunkContext");
        @SuppressWarnings("unchecked")
        java.util.Set<ScholardexSourceLinkService.SourceLinkKey> missingKeys =
                (java.util.Set<ScholardexSourceLinkService.SourceLinkKey>) ReflectionTestUtils.getField(context, "missingSourceLinkKeys");
        ScholardexSourceLinkService.SourceLinkKey missingKey =
                ScholardexSourceLinkService.SourceLinkKey.of(ScholardexEntityType.AUTHOR, "SCOPUS", "au-missing");
        missingKeys.add(missingKey);

        Optional<ScholardexSourceLink> blankEntity =
                ReflectionTestUtils.invokeMethod(service, "findSourceLink", null, "SCOPUS", "au-1", context);
        Optional<ScholardexSourceLink> blankSource =
                ReflectionTestUtils.invokeMethod(service, "findSourceLink", ScholardexEntityType.AUTHOR, " ", "au-1", context);
        Optional<ScholardexSourceLink> blankRecord =
                ReflectionTestUtils.invokeMethod(service, "findSourceLink", ScholardexEntityType.AUTHOR, "SCOPUS", " ", context);
        Optional<ScholardexSourceLink> rememberedMissing =
                ReflectionTestUtils.invokeMethod(service, "findSourceLink", ScholardexEntityType.AUTHOR, "SCOPUS", "au-missing", context);

        assertTrue(blankEntity.isEmpty());
        assertTrue(blankSource.isEmpty());
        assertTrue(blankRecord.isEmpty());
        assertTrue(rememberedMissing.isEmpty());
        verify(sourceLinkService, never()).findByKey(eq(ScholardexEntityType.AUTHOR), eq("SCOPUS"), eq("au-missing"));
    }

    @Test
    void resolveAuthorSourceLinkFallsBackToScopusAndPrefersDirectSourceWhenPresent() {
        // H56: findSourceLink probes with the NORMALIZED source, so for SCOPUS-family raw sources the
        // direct and fallback probes are the same key. The meaningful direct-vs-fallback distinction is
        // a non-SCOPUS source: its own link wins when present, else the SCOPUS link is used.
        ScholardexPublicationCanonicalizationService.ChunkContext context =
                ReflectionTestUtils.invokeMethod(service, "createChunkContext");
        ScholardexSourceLink direct = new ScholardexSourceLink();
        direct.setCanonicalEntityId("sauth_direct");
        ScholardexSourceLink fallback = new ScholardexSourceLink();
        fallback.setCanonicalEntityId("sauth_fallback");

        when(sourceLinkService.findByKey(ScholardexEntityType.AUTHOR, "USER_DEFINED", "au-1"))
                .thenReturn(Optional.of(direct));
        when(sourceLinkService.findByKey(ScholardexEntityType.AUTHOR, "USER_DEFINED", "au-2"))
                .thenReturn(Optional.empty());
        when(sourceLinkService.findByKey(ScholardexEntityType.AUTHOR, "SCOPUS", "au-2"))
                .thenReturn(Optional.of(fallback));

        Optional<ScholardexSourceLink> directResolved =
                ReflectionTestUtils.invokeMethod(service, "resolveAuthorSourceLink", "USER_DEFINED", "au-1", context);
        Optional<ScholardexSourceLink> fallbackResolved =
                ReflectionTestUtils.invokeMethod(service, "resolveAuthorSourceLink", "USER_DEFINED", "au-2", context);

        assertTrue(directResolved.isPresent());
        assertEquals("sauth_direct", directResolved.get().getCanonicalEntityId());
        assertTrue(fallbackResolved.isPresent());
        assertEquals("sauth_fallback", fallbackResolved.get().getCanonicalEntityId());
    }

    @Test
    void preloadChunkContextIndexesSourceLinksAndExistingPublicationFacts() {
        ScopusPublicationFact sourceFact = new ScopusPublicationFact();
        sourceFact.setEid("2-s2.0-1");
        sourceFact.setDoi("https://doi.org/10.1000/ABC");
        sourceFact.setSourceRecordId("src-1");
        sourceFact.setAuthors(List.of("au-1"));
        sourceFact.setAffiliations(List.of("af-1"));
        sourceFact.setAuthorAffiliationSourceIds(List.of("af-2-af-3"));

        ScholardexSourceLink authorLink = new ScholardexSourceLink();
        authorLink.setEntityType(ScholardexEntityType.AUTHOR);
        authorLink.setSource("SCOPUS");
        authorLink.setSourceRecordId("au-1");
        authorLink.setCanonicalEntityId("sauth_1");
        ScholardexSourceLink affiliationLink = new ScholardexSourceLink();
        affiliationLink.setEntityType(ScholardexEntityType.AFFILIATION);
        affiliationLink.setSource("SCOPUS");
        affiliationLink.setSourceRecordId("af-1");
        affiliationLink.setCanonicalEntityId("saff_1");
        ScholardexSourceLink publicationLink = new ScholardexSourceLink();
        publicationLink.setEntityType(ScholardexEntityType.PUBLICATION);
        publicationLink.setSource("SCOPUS");
        publicationLink.setSourceRecordId("src-1");
        publicationLink.setCanonicalEntityId("spub_linked");

        ScholardexPublicationFact byEid = new ScholardexPublicationFact();
        byEid.setId("spub_eid");
        byEid.setEid("2-s2.0-1");
        ScholardexPublicationFact byDoi = new ScholardexPublicationFact();
        byDoi.setId("spub_doi");
        byDoi.setDoiNormalized("10.1000/abc");

        when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.AUTHOR), any()))
                .thenReturn(List.of(authorLink));
        when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.AFFILIATION), any()))
                .thenReturn(List.of(affiliationLink));
        when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.PUBLICATION), any()))
                .thenReturn(List.of(publicationLink));
        when(scholardexPublicationFactRepository.findAllByEidIn(any())).thenReturn(List.of(byEid));
        when(scholardexPublicationFactRepository.findAllByDoiNormalizedIn(any())).thenReturn(List.of(byDoi));

        ScholardexPublicationCanonicalizationService.ChunkContext context =
                ReflectionTestUtils.invokeMethod(service, "createChunkContext");
        ReflectionTestUtils.invokeMethod(service, "preloadChunkContext", List.of(sourceFact), context);

        @SuppressWarnings("unchecked")
        Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink> sourceLinkCache =
                (Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink>) ReflectionTestUtils.getField(context, "sourceLinkCache");
        @SuppressWarnings("unchecked")
        Map<String, ScholardexPublicationFact> publicationByEid =
                (Map<String, ScholardexPublicationFact>) ReflectionTestUtils.getField(context, "publicationByEid");
        @SuppressWarnings("unchecked")
        Map<String, ScholardexPublicationFact> publicationByDoi =
                (Map<String, ScholardexPublicationFact>) ReflectionTestUtils.getField(context, "publicationByDoi");

        assertTrue((Boolean) ReflectionTestUtils.getField(context, "preloaded"));
        assertEquals("sauth_1", sourceLinkCache.get(
                ScholardexSourceLinkService.SourceLinkKey.of(ScholardexEntityType.AUTHOR, "SCOPUS", "au-1")
        ).getCanonicalEntityId());
        assertEquals("saff_1", sourceLinkCache.get(
                ScholardexSourceLinkService.SourceLinkKey.of(ScholardexEntityType.AFFILIATION, "SCOPUS", "af-1")
        ).getCanonicalEntityId());
        assertEquals("spub_linked", sourceLinkCache.get(
                ScholardexSourceLinkService.SourceLinkKey.of(ScholardexEntityType.PUBLICATION, "SCOPUS", "src-1")
        ).getCanonicalEntityId());
        assertEquals("spub_eid", publicationByEid.get("2-s2.0-1").getId());
        assertEquals("spub_doi", publicationByDoi.get("10.1000/abc").getId());
    }

    @Test
    void resolveCanonicalAffiliationIdReturnsNullForBlankAndUsesCanonicalOrResolvedIds() {
        ScholardexPublicationCanonicalizationService.ChunkContext context =
                ReflectionTestUtils.invokeMethod(service, "createChunkContext");
        ScholardexSourceLink resolved = new ScholardexSourceLink();
        resolved.setCanonicalEntityId("saff_resolved");

        when(sourceLinkService.findByKey(ScholardexEntityType.AFFILIATION, "SCOPUS", "af-1"))
                .thenReturn(Optional.of(resolved));
        when(sourceLinkService.findByKey(ScholardexEntityType.AFFILIATION, "SCOPUS", "missing"))
                .thenReturn(Optional.empty());

        assertNull(ReflectionTestUtils.invokeMethod(service, "resolveCanonicalAffiliationId", "SCOPUS", " ", context));
        assertEquals("saff_prebuilt", ReflectionTestUtils.invokeMethod(service, "resolveCanonicalAffiliationId", "SCOPUS", "saff_prebuilt", context));
        assertEquals("saff_resolved", ReflectionTestUtils.invokeMethod(service, "resolveCanonicalAffiliationId", "SCOPUS", "af-1", context));
        assertNull(ReflectionTestUtils.invokeMethod(service, "resolveCanonicalAffiliationId", "SCOPUS", "missing", context));
    }

    @Test
    void loadExistingByEidOrDoiPrefersContextAndFallsBackToNewFact() {
        ScholardexPublicationCanonicalizationService.ChunkContext context =
                ReflectionTestUtils.invokeMethod(service, "createChunkContext");
        ScholardexPublicationFact contextByEid = new ScholardexPublicationFact();
        contextByEid.setId("spub_eid");
        contextByEid.setEid("2-s2.0-eid");
        ScholardexPublicationFact contextByDoi = new ScholardexPublicationFact();
        contextByDoi.setId("spub_doi");
        contextByDoi.setDoiNormalized("10.1000/ctx");
        @SuppressWarnings("unchecked")
        Map<String, ScholardexPublicationFact> publicationByEid =
                (Map<String, ScholardexPublicationFact>) ReflectionTestUtils.getField(context, "publicationByEid");
        @SuppressWarnings("unchecked")
        Map<String, ScholardexPublicationFact> publicationByDoi =
                (Map<String, ScholardexPublicationFact>) ReflectionTestUtils.getField(context, "publicationByDoi");
        publicationByEid.put("2-s2.0-eid", contextByEid);
        publicationByDoi.put("10.1000/ctx", contextByDoi);
        ReflectionTestUtils.setField(context, "preloaded", true);

        ScholardexPublicationFact foundByEid =
                ReflectionTestUtils.invokeMethod(service, "loadExistingByEidOrDoi", "2-s2.0-eid", "10.1000/other", new ImportProcessingResult(10), context);
        ScholardexPublicationFact foundByDoi =
                ReflectionTestUtils.invokeMethod(service, "loadExistingByEidOrDoi", "2-s2.0-miss", "10.1000/ctx", new ImportProcessingResult(10), context);

        when(scholardexPublicationFactRepository.findByEid("2-s2.0-new")).thenReturn(Optional.empty());
        when(scholardexPublicationFactRepository.findAllByDoiNormalized("10.1000/new")).thenReturn(List.of());
        ScholardexPublicationFact created =
                ReflectionTestUtils.invokeMethod(service, "loadExistingByEidOrDoi", "2-s2.0-new", "10.1000/new", new ImportProcessingResult(10), null);

        assertEquals("spub_eid", foundByEid.getId());
        assertEquals("spub_doi", foundByDoi.getId());
        assertNull(created.getId());
    }

    @Test
    void upsertFromScopusFactFallsBackToExistingCanonicalRecordByNormalizedDoi() {
        ScopusPublicationFact scopusFact = new ScopusPublicationFact();
        scopusFact.setEid("2-s2.0-new");
        scopusFact.setDoi("https://doi.org/10.1000/XYZ");
        scopusFact.setTitle("A Title");
        scopusFact.setSource("SCOPUS_JSON_BOOTSTRAP");
        scopusFact.setSourceRecordId("2-s2.0-new");
        scopusFact.setAuthors(List.of("au-1"));

        ScholardexPublicationFact existingByDoi = new ScholardexPublicationFact();
        existingByDoi.setId("spub_existing");
        existingByDoi.setDoiNormalized("10.1000/xyz");

        when(scholardexPublicationFactRepository.findByEid("2-s2.0-new")).thenReturn(Optional.empty());
        when(scholardexPublicationFactRepository.findAllByDoiNormalized("10.1000/xyz")).thenReturn(List.of(existingByDoi));
        when(scholardexAuthorshipFactRepository.findByPublicationIdIn(any())).thenReturn(List.of());
        when(sourceLinkService.findByKey(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(edgeWriterService.batchUpsertAuthorshipEdges(any(), any(), eq(false)))
                .thenReturn(new ScholardexEdgeWriterService.BatchEdgeWriteResult(1, 0, 1, 0, 0));

        ImportProcessingResult result = new ImportProcessingResult(10);
        service.upsertFromScopusFact(scopusFact, result);

        assertEquals(1, result.getUpdatedCount());
        verify(scholardexPublicationFactRepository).saveAll(any());
    }

    @Test
    void upsertFromScopusFactEmitsPublicationAuthorAffiliationEdgesWhenMappingsResolve() {
        ScopusPublicationFact scopusFact = new ScopusPublicationFact();
        scopusFact.setEid("2-s2.0-edge");
        scopusFact.setSource("SCOPUS");
        scopusFact.setSourceRecordId("2-s2.0-edge");
        scopusFact.setAuthors(List.of("au-1"));
        scopusFact.setAuthorAffiliationSourceIds(List.of("af1"));

        ScholardexSourceLink authorLink = new ScholardexSourceLink();
        authorLink.setCanonicalEntityId("sauth_1");
        ScholardexSourceLink affiliationLink = new ScholardexSourceLink();
        affiliationLink.setCanonicalEntityId("saff_1");

        when(sourceLinkService.findByKey(eq(ScholardexEntityType.AUTHOR), eq("SCOPUS"), eq("au-1")))
                .thenReturn(Optional.of(authorLink));
        when(sourceLinkService.findByKey(eq(ScholardexEntityType.AFFILIATION), eq("SCOPUS"), eq("af1")))
                .thenReturn(Optional.of(affiliationLink));
        when(scholardexAuthorshipFactRepository.findByPublicationIdIn(any())).thenReturn(List.of());
        when(scholardexPublicationAuthorAffiliationFactRepository.findByPublicationIdIn(any())).thenReturn(List.of());
        when(edgeWriterService.batchUpsertAuthorshipEdges(any(), any(), eq(false)))
                .thenReturn(new ScholardexEdgeWriterService.BatchEdgeWriteResult(1, 0, 1, 0, 0));
        when(edgeWriterService.batchUpsertPublicationAuthorAffiliationEdges(any(), any(), eq(false)))
                .thenReturn(new ScholardexEdgeWriterService.BatchEdgeWriteResult(1, 0, 1, 0, 0));

        service.upsertFromScopusFact(scopusFact, new ImportProcessingResult(10));

        verify(edgeWriterService).batchUpsertPublicationAuthorAffiliationEdges(any(), any(), eq(false));
    }

    @Test
    void upsertFromScopusFactCanonicalizesPublicationAffiliationIdsAndDropsUnresolvedSourceIds() {
        ScopusPublicationFact scopusFact = new ScopusPublicationFact();
        scopusFact.setEid("2-s2.0-aff");
        scopusFact.setSource("SCOPUS");
        scopusFact.setSourceRecordId("2-s2.0-aff");
        scopusFact.setAuthors(List.of("au-1"));
        scopusFact.setAffiliations(List.of("60000434", "missing", "60000434"));
        scopusFact.setAuthorAffiliationSourceIds(List.of("af2-60000434"));

        ScholardexSourceLink authorLink = new ScholardexSourceLink();
        authorLink.setCanonicalEntityId("sauth_1");
        ScholardexSourceLink uvtAffiliationLink = new ScholardexSourceLink();
        uvtAffiliationLink.setCanonicalEntityId("saff_uvt");
        ScholardexSourceLink partnerAffiliationLink = new ScholardexSourceLink();
        partnerAffiliationLink.setCanonicalEntityId("saff_partner");

        when(scholardexPublicationFactRepository.findByEid("2-s2.0-aff")).thenReturn(Optional.empty());
        when(sourceLinkService.findByKey(eq(ScholardexEntityType.AUTHOR), eq("SCOPUS"), eq("au-1")))
                .thenReturn(Optional.of(authorLink));
        when(sourceLinkService.findByKey(eq(ScholardexEntityType.AFFILIATION), eq("SCOPUS"), eq("60000434")))
                .thenReturn(Optional.of(uvtAffiliationLink));
        when(sourceLinkService.findByKey(eq(ScholardexEntityType.AFFILIATION), eq("SCOPUS"), eq("af2")))
                .thenReturn(Optional.of(partnerAffiliationLink));
        when(sourceLinkService.findByKey(eq(ScholardexEntityType.AFFILIATION), eq("SCOPUS"), eq("missing")))
                .thenReturn(Optional.empty());
        when(scholardexAuthorshipFactRepository.findByPublicationIdIn(any())).thenReturn(List.of());
        when(scholardexPublicationAuthorAffiliationFactRepository.findByPublicationIdIn(any())).thenReturn(List.of());
        when(edgeWriterService.batchUpsertAuthorshipEdges(any(), any(), eq(false)))
                .thenReturn(new ScholardexEdgeWriterService.BatchEdgeWriteResult(1, 0, 1, 0, 0));
        when(edgeWriterService.batchUpsertPublicationAuthorAffiliationEdges(any(), any(), eq(false)))
                .thenReturn(new ScholardexEdgeWriterService.BatchEdgeWriteResult(2, 0, 2, 0, 0));

        service.upsertFromScopusFact(scopusFact, new ImportProcessingResult(10));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<ScholardexPublicationFact>> insertedFactsCaptor =
                ArgumentCaptor.forClass((Class<Iterable<ScholardexPublicationFact>>) (Class<?>) Iterable.class);

        verify(scholardexPublicationFactRepository).insert(insertedFactsCaptor.capture());

        List<ScholardexPublicationFact> insertedFacts = new java.util.ArrayList<>();
        insertedFactsCaptor.getValue().forEach(insertedFacts::add);
        assertEquals(List.of("saff_uvt", "saff_partner"), insertedFacts.getFirst().getAffiliationIds());
    }

    @Test
    void rebuildCanonicalPublicationFactsPreloadsPersistedAuthorshipEdgesForReplay() {
        ScopusPublicationFact scopusFact = new ScopusPublicationFact();
        scopusFact.setEid("2-s2.0-replay");
        scopusFact.setTitle("Replay Title");
        scopusFact.setSource("SCOPUS_JSON_UPLOAD");
        scopusFact.setSourceRecordId("2-s2.0-replay");
        scopusFact.setAuthors(List.of("au-1"));

        ScholardexPublicationFact existingPublication = new ScholardexPublicationFact();
        existingPublication.setId("spub_replay");
        existingPublication.setEid("2-s2.0-replay");

        ScholardexSourceLink authorLink = new ScholardexSourceLink();
        authorLink.setCanonicalEntityId("sauth_1");

        ScholardexAuthorshipFact persistedEdge = new ScholardexAuthorshipFact();
        persistedEdge.setId("sae_existing");
        persistedEdge.setPublicationId("spub_replay");
        persistedEdge.setAuthorId("sauth_1");
        persistedEdge.setSource("SCOPUS_JSON_UPLOAD");

        lenient().when(scopusPublicationFactRepository.count()).thenReturn(1L);
        lenient().when(scopusPublicationFactRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(scopusFact)));
        lenient().when(scholardexPublicationFactRepository.findAllByEidIn(any())).thenReturn(List.of(existingPublication));
        lenient().when(scholardexPublicationFactRepository.findAllByDoiNormalizedIn(any())).thenReturn(List.of());
        lenient().when(scholardexAuthorshipFactRepository.findByPublicationIdIn(any())).thenReturn(List.of(persistedEdge));
        lenient().when(scholardexPublicationAuthorAffiliationFactRepository.findByPublicationIdIn(any())).thenReturn(List.of());
        lenient().when(checkpointService.readCheckpoint(anyString())).thenReturn(Optional.empty());
        authorLink.setEntityType(ScholardexEntityType.AUTHOR);
        authorLink.setSource("SCOPUS_JSON_UPLOAD");
        authorLink.setSourceRecordId("au-1");
        lenient().when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.AUTHOR), any()))
                .thenReturn(List.of(authorLink));
        lenient().when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.AFFILIATION), any()))
                .thenReturn(List.of());
        lenient().when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.PUBLICATION), any()))
                .thenReturn(List.of());
        lenient().when(edgeWriterService.batchUpsertAuthorshipEdges(any(), any(), eq(false)))
                .thenReturn(new ScholardexEdgeWriterService.BatchEdgeWriteResult(1, 0, 0, 1, 0));

        service.rebuildCanonicalPublicationFactsFromScopusFacts(fullRescanOptions());

        verify(edgeWriterService).batchUpsertAuthorshipEdges(
                any(),
                argThat(map -> map instanceof Map<?, ?> typedMap
                        && typedMap.containsKey("spub_replay|sauth_1|SCOPUS_JSON_UPLOAD")
                        && typedMap.get("spub_replay|sauth_1|SCOPUS_JSON_UPLOAD") == persistedEdge),
                eq(false)
        );
    }

    @Test
    void rebuildCanonicalPublicationFactsPreloadsPersistedPublicationAuthorAffiliationEdgesForReplay() {
        ScopusPublicationFact scopusFact = new ScopusPublicationFact();
        scopusFact.setEid("2-s2.0-replay-paf");
        scopusFact.setTitle("Replay Title");
        scopusFact.setSource("SCOPUS_JSON_UPLOAD");
        scopusFact.setSourceRecordId("2-s2.0-replay-paf");
        scopusFact.setAuthors(List.of("au-1"));
        scopusFact.setAuthorAffiliationSourceIds(List.of("af1"));

        ScholardexPublicationFact existingPublication = new ScholardexPublicationFact();
        existingPublication.setId("spub_replay");
        existingPublication.setEid("2-s2.0-replay-paf");

        // Persisted source links carry the NORMALIZED source (the link service normalizes on write) —
        // H56 keys the resolve cache the same way.
        ScholardexSourceLink authorLink = new ScholardexSourceLink();
        authorLink.setEntityType(ScholardexEntityType.AUTHOR);
        authorLink.setSource("SCOPUS");
        authorLink.setSourceRecordId("au-1");
        authorLink.setCanonicalEntityId("sauth_1");

        ScholardexSourceLink affiliationLink = new ScholardexSourceLink();
        affiliationLink.setEntityType(ScholardexEntityType.AFFILIATION);
        affiliationLink.setSource("SCOPUS");
        affiliationLink.setSourceRecordId("af1");
        affiliationLink.setCanonicalEntityId("saff_1");

        ScholardexPublicationAuthorAffiliationFact persistedEdge = new ScholardexPublicationAuthorAffiliationFact();
        persistedEdge.setId("spaaf_existing");
        persistedEdge.setPublicationId("spub_replay");
        persistedEdge.setAuthorId("sauth_1");
        persistedEdge.setAffiliationId("saff_1");
        persistedEdge.setSource("SCOPUS_JSON_UPLOAD");

        lenient().when(scopusPublicationFactRepository.count()).thenReturn(1L);
        lenient().when(scopusPublicationFactRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(scopusFact)));
        lenient().when(scholardexPublicationFactRepository.findAllByEidIn(any())).thenReturn(List.of(existingPublication));
        lenient().when(scholardexPublicationFactRepository.findAllByDoiNormalizedIn(any())).thenReturn(List.of());
        lenient().when(scholardexAuthorshipFactRepository.findByPublicationIdIn(any())).thenReturn(List.of());
        lenient().when(scholardexPublicationAuthorAffiliationFactRepository.findByPublicationIdIn(any())).thenReturn(List.of(persistedEdge));
        lenient().when(checkpointService.readCheckpoint(anyString())).thenReturn(Optional.empty());
        lenient().when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.AUTHOR), any()))
                .thenReturn(List.of(authorLink));
        lenient().when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.AFFILIATION), any()))
                .thenReturn(List.of(affiliationLink));
        lenient().when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.PUBLICATION), any()))
                .thenReturn(List.of());
        lenient().when(edgeWriterService.batchUpsertAuthorshipEdges(any(), any(), eq(false)))
                .thenReturn(new ScholardexEdgeWriterService.BatchEdgeWriteResult(1, 0, 0, 1, 0));
        lenient().when(edgeWriterService.batchUpsertPublicationAuthorAffiliationEdges(any(), any(), eq(false)))
                .thenReturn(new ScholardexEdgeWriterService.BatchEdgeWriteResult(1, 0, 0, 1, 0));

        service.rebuildCanonicalPublicationFactsFromScopusFacts(fullRescanOptions());

        verify(edgeWriterService).batchUpsertPublicationAuthorAffiliationEdges(
                any(),
                argThat(map -> map instanceof Map<?, ?> typedMap
                        && typedMap.containsKey("spub_replay|sauth_1|saff_1|SCOPUS_JSON_UPLOAD")
                        && typedMap.get("spub_replay|sauth_1|saff_1|SCOPUS_JSON_UPLOAD") == persistedEdge),
                eq(false)
        );
    }

    @Test
    void replayReusesExistingOpenPublicationAuthorAffiliationConflict() {
        ScopusPublicationFact scopusFact = new ScopusPublicationFact();
        scopusFact.setEid("2-s2.0-conflict");
        scopusFact.setTitle("Replay Title");
        scopusFact.setSource("SCOPUS_JSON_UPLOAD");
        scopusFact.setSourceRecordId("2-s2.0-105000527065");
        scopusFact.setAuthors(List.of("36057720300"));
        scopusFact.setAuthorAffiliationSourceIds(List.of("60024417"));

        ScholardexPublicationFact existingPublication = new ScholardexPublicationFact();
        existingPublication.setId("spub_conflict");
        existingPublication.setEid("2-s2.0-conflict");

        ScholardexSourceLink authorLink = new ScholardexSourceLink();
        authorLink.setEntityType(ScholardexEntityType.AUTHOR);
        authorLink.setSource("SCOPUS_JSON_UPLOAD");
        authorLink.setSourceRecordId("36057720300");
        authorLink.setCanonicalEntityId("sauth_1");

        ScholardexIdentityConflict existingConflict = new ScholardexIdentityConflict();
        existingConflict.setId("sic_existing");
        existingConflict.setEntityType(ScholardexEntityType.PUBLICATION_AUTHOR_AFFILIATION);
        existingConflict.setIncomingSource("SCOPUS_JSON_UPLOAD");
        existingConflict.setIncomingSourceRecordId("2-s2.0-105000527065::author::36057720300::affiliation::60024417");
        existingConflict.setReasonCode("PUBLICATION_AUTHOR_AFFILIATION_UNRESOLVED");
        existingConflict.setStatus("OPEN");

        lenient().when(scopusPublicationFactRepository.count()).thenReturn(1L);
        lenient().when(scopusPublicationFactRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(scopusFact)));
        lenient().when(scholardexPublicationFactRepository.findAllByEidIn(any())).thenReturn(List.of(existingPublication));
        lenient().when(scholardexPublicationFactRepository.findAllByDoiNormalizedIn(any())).thenReturn(List.of());
        lenient().when(scholardexAuthorshipFactRepository.findByPublicationIdIn(any())).thenReturn(List.of());
        lenient().when(scholardexPublicationAuthorAffiliationFactRepository.findByPublicationIdIn(any())).thenReturn(List.of());
        lenient().when(checkpointService.readCheckpoint(anyString())).thenReturn(Optional.empty());
        lenient().when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.AUTHOR), any()))
                .thenReturn(List.of(authorLink));
        lenient().when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.AFFILIATION), any()))
                .thenReturn(List.of());
        lenient().when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.PUBLICATION), any()))
                .thenReturn(List.of());
        lenient().when(edgeWriterService.batchUpsertAuthorshipEdges(any(), any(), eq(false)))
                .thenReturn(new ScholardexEdgeWriterService.BatchEdgeWriteResult(1, 0, 0, 1, 0));
        lenient().when(identityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                eq(ScholardexEntityType.PUBLICATION_AUTHOR_AFFILIATION),
                eq("SCOPUS_JSON_UPLOAD"),
                eq("2-s2.0-105000527065::author::36057720300::affiliation::60024417"),
                eq("PUBLICATION_AUTHOR_AFFILIATION_UNRESOLVED"),
                eq("OPEN")
        )).thenReturn(Optional.of(existingConflict));

        service.rebuildCanonicalPublicationFactsFromScopusFacts(fullRescanOptions());

        verify(identityConflictRepository).saveAll(argThat(conflicts -> {
            for (Object candidate : conflicts) {
                if (candidate == existingConflict) {
                    return true;
                }
            }
            return false;
        }));
    }

    @Test
    void recoverPublicationWriteReplaysIntoExistingDoiMatchAndQueuesRecoveredFact() {
        ScholardexPublicationFact fact = new ScholardexPublicationFact();
        fact.setId("spub_new");
        fact.setEid("2-s2.0-new");
        fact.setDoi("https://doi.org/10.1000/recover");
        fact.setTitle("Recovered title");
        fact.setAuthorIds(List.of("sauth_1"));
        fact.setPendingAuthorSourceIds(List.of("au-2"));
        fact.setSource("SCOPUS_JSON_UPLOAD");
        fact.setSourceRecordId("2-s2.0-new");
        fact.setSourceBatchId("batch-1");
        fact.setSourceCorrelationId("corr-1");
        fact.setCreatedAt(Instant.parse("2024-01-01T00:00:00Z"));

        ScholardexPublicationFact existingByDoi = new ScholardexPublicationFact();
        existingByDoi.setId("spub_existing");
        existingByDoi.setCreatedAt(Instant.parse("2023-01-01T00:00:00Z"));

        ScholardexPublicationCanonicalizationService.ChunkContext context =
                ReflectionTestUtils.invokeMethod(service, "createChunkContext");

        org.mockito.Mockito.doAnswer(invocation -> {
            ScholardexPublicationFact argument = invocation.getArgument(0);
            if (argument == fact) {
                throw new org.springframework.dao.DuplicateKeyException("dup");
            }
            return argument;
        }).when(scholardexPublicationFactRepository).save(any(ScholardexPublicationFact.class));
        when(scholardexPublicationFactRepository.findAllByDoiNormalized("10.1000/recover")).thenReturn(List.of(existingByDoi));

        ReflectionTestUtils.invokeMethod(service, "recoverPublicationWrite", fact, context);

        verify(scholardexPublicationFactRepository).save(existingByDoi);
        assertEquals("2-s2.0-new", existingByDoi.getEid());
        assertEquals("Recovered title", existingByDoi.getTitle());
        assertEquals("10.1000/recover", existingByDoi.getDoiNormalized());
        assertEquals(List.of("sauth_1"), existingByDoi.getAuthorIds());
        assertEquals(List.of("au-2"), existingByDoi.getPendingAuthorSourceIds());

        @SuppressWarnings("unchecked")
        Map<String, ScholardexPublicationFact> pendingFacts =
                (Map<String, ScholardexPublicationFact>) ReflectionTestUtils.getField(context, "pendingPublicationFacts");
        @SuppressWarnings("unchecked")
        java.util.Set<String> pendingInsertIds =
                (java.util.Set<String>) ReflectionTestUtils.getField(context, "pendingInsertPublicationIds");

        assertTrue(pendingFacts.containsValue(existingByDoi));
        assertFalse(pendingInsertIds.contains("spub_existing"));
        assertEquals(1, ReflectionTestUtils.getField(context, "publicationRecoverCount"));
    }

    @Test
    void flushPublicationFactsFallsBackToRecoveryForInsertAndUpdateDuplicates() {
        ScholardexPublicationFact insertFact = new ScholardexPublicationFact();
        insertFact.setId("spub_insert");
        insertFact.setEid("2-s2.0-insert");
        insertFact.setDoi("10.1000/insert");
        insertFact.setTitle("Insert title");
        insertFact.setSource("SCOPUS");
        insertFact.setSourceRecordId("2-s2.0-insert");

        ScholardexPublicationFact updateFact = new ScholardexPublicationFact();
        updateFact.setId("spub_update");
        updateFact.setEid("2-s2.0-update");
        updateFact.setDoi("10.1000/update");
        updateFact.setTitle("Update title");
        updateFact.setSource("SCOPUS");
        updateFact.setSourceRecordId("2-s2.0-update");

        ScholardexPublicationFact recoveredInsert = new ScholardexPublicationFact();
        recoveredInsert.setId("spub_recovered_insert");
        ScholardexPublicationFact recoveredUpdate = new ScholardexPublicationFact();
        recoveredUpdate.setId("spub_recovered_update");

        ScholardexPublicationCanonicalizationService.ChunkContext context =
                ReflectionTestUtils.invokeMethod(service, "createChunkContext");
        @SuppressWarnings("unchecked")
        Map<String, ScholardexPublicationFact> pendingFacts =
                (Map<String, ScholardexPublicationFact>) ReflectionTestUtils.getField(context, "pendingPublicationFacts");
        @SuppressWarnings("unchecked")
        java.util.Set<String> pendingInsertIds =
                (java.util.Set<String>) ReflectionTestUtils.getField(context, "pendingInsertPublicationIds");
        @SuppressWarnings("unchecked")
        java.util.Set<String> existingIds =
                (java.util.Set<String>) ReflectionTestUtils.getField(context, "existingPublicationIds");

        pendingFacts.put(insertFact.getId(), insertFact);
        pendingFacts.put(updateFact.getId(), updateFact);
        pendingInsertIds.add(insertFact.getId());
        existingIds.add(updateFact.getId());

        when(scholardexPublicationFactRepository.insert(anyList())).thenThrow(new org.springframework.dao.DuplicateKeyException("dup-insert"));
        when(scholardexPublicationFactRepository.saveAll(anyList())).thenThrow(new org.springframework.dao.DuplicateKeyException("dup-update"));
        org.mockito.Mockito.doAnswer(invocation -> {
            ScholardexPublicationFact argument = invocation.getArgument(0);
            if (argument == insertFact) {
                throw new org.springframework.dao.DuplicateKeyException("dup-insert-save");
            }
            if (argument == updateFact) {
                throw new org.springframework.dao.DuplicateKeyException("dup-update-save");
            }
            return argument;
        }).when(scholardexPublicationFactRepository).save(any(ScholardexPublicationFact.class));
        when(scholardexPublicationFactRepository.findAllByDoiNormalized("10.1000/insert")).thenReturn(List.of(recoveredInsert));
        when(scholardexPublicationFactRepository.findAllByDoiNormalized("10.1000/update")).thenReturn(List.of(recoveredUpdate));

        ReflectionTestUtils.invokeMethod(service, "flushPublicationFacts", context);

        verify(scholardexPublicationFactRepository).save(recoveredInsert);
        verify(scholardexPublicationFactRepository).save(recoveredUpdate);
        assertEquals("2-s2.0-insert", recoveredInsert.getEid());
        assertEquals("2-s2.0-update", recoveredUpdate.getEid());
        assertEquals(2, ReflectionTestUtils.getField(context, "publicationRecoverCount"));
        assertTrue(pendingFacts.containsValue(recoveredInsert));
        assertTrue(pendingFacts.containsValue(recoveredUpdate));
    }
}
