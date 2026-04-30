package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import com.fasterxml.jackson.databind.ObjectMapper;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.UserIndicatorResult;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.reporting.IndicatorRepository;
import ro.uvt.pokedex.core.repository.reporting.UserIndicatorResultRepository;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.reporting.Score;
import ro.uvt.pokedex.core.service.application.model.IndicatorApplyResultDto;
import ro.uvt.pokedex.core.service.application.model.UserIndicatorApplyViewModel;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserIndicatorResultServiceTest {

    @Mock
    private UserIndicatorResultRepository userIndicatorResultRepository;
    @Mock
    private IndicatorRepository indicatorRepository;
    @Mock
    private UserService userService;
    @Mock
    private UserReportFacade userReportFacade;

    private UserIndicatorResultService service;

    @BeforeEach
    void setUp() {
        service = new UserIndicatorResultService(
                userIndicatorResultRepository,
                indicatorRepository,
                userService,
                userReportFacade,
                new IndicatorPayloadSerializer(new ObjectMapper())
        );
    }

    @Test
    void getOrCreateLatestReusesPersistedResultWhenPresent() {
        Indicator indicator = new Indicator();
        indicator.setId("ind-1");
        indicator.setOutputType(Indicator.Type.PUBLICATIONS);
        indicator.setScoringStrategy(Indicator.Strategy.GENERIC_COUNT);
        indicator.setFormula("S");

        UserIndicatorResult persisted = new UserIndicatorResult();
        persisted.setId("r1");
        persisted.setIndicatorId("ind-1");
        persisted.setMode(UserIndicatorResult.Mode.LATEST);
        persisted.setFingerprint("ind-1|PUBLICATIONS|GENERIC_COUNT|S||||payload-v2-scoring-provenance");
        persisted.setViewName("user/indicators-apply-publications");
        persisted.setRawGraph(new IndicatorPayloadSerializer(new ObjectMapper()).serialize(Map.of("total", "1.00")));
        persisted.setCreatedAt(Instant.now());
        persisted.setUpdatedAt(Instant.now());

        when(indicatorRepository.findById("ind-1")).thenReturn(Optional.of(indicator));
        when(userIndicatorResultRepository.findByUserEmailAndIndicatorIdAndMode("u@uvt.ro", "ind-1", UserIndicatorResult.Mode.LATEST))
                .thenReturn(Optional.of(persisted));

        IndicatorApplyResultDto dto = service.getOrCreateLatest("u@uvt.ro", "ind-1");

        assertEquals("r1", dto.resultId());
        assertEquals(IndicatorApplyResultDto.Source.PERSISTED, dto.source());
        verify(userReportFacade, times(0)).buildIndicatorApplyView(any(), any());
    }

    @Test
    void getOrCreateLatestComputesAndPersistsWhenMissing() {
        when(userIndicatorResultRepository.findByUserEmailAndIndicatorIdAndMode("u@uvt.ro", "ind-1", UserIndicatorResult.Mode.LATEST))
                .thenReturn(Optional.empty());

        Indicator indicator = new Indicator();
        indicator.setId("ind-1");
        indicator.setOutputType(Indicator.Type.PUBLICATIONS);
        indicator.setScoringStrategy(Indicator.Strategy.GENERIC_COUNT);
        indicator.setFormula("S");
        when(indicatorRepository.findById("ind-1")).thenReturn(Optional.of(indicator));

        User user = new User();
        user.setEmail("u@uvt.ro");

        when(userReportFacade.buildIndicatorApplyView("u@uvt.ro", "ind-1"))
                .thenReturn(new UserIndicatorApplyViewModel("user/indicators-apply-publications", Map.of("indicator", indicator, "total", "2.50", "allQuarters", List.of("Q1"), "allValues", List.of(1))));

        when(userIndicatorResultRepository.save(any(UserIndicatorResult.class))).thenAnswer(invocation -> {
            UserIndicatorResult entity = invocation.getArgument(0);
            entity.setId("new-id");
            return entity;
        });

        IndicatorApplyResultDto dto = service.getOrCreateLatest("u@uvt.ro", "ind-1");

        assertEquals("new-id", dto.resultId());
        assertEquals(2.5, dto.summary().totalScore());
        assertNotNull(dto.rawGraph().get("indicator"));
        ArgumentCaptor<UserIndicatorResult> captor = ArgumentCaptor.forClass(UserIndicatorResult.class);
        verify(userIndicatorResultRepository).save(captor.capture());
        UserIndicatorResult saved = captor.getValue();
        assertEquals("u@uvt.ro", saved.getUserEmail());
        assertEquals("u@uvt.ro", saved.getResearcherId());
        assertEquals("ind-1", saved.getIndicatorId());
        assertEquals(UserIndicatorResult.Mode.LATEST, saved.getMode());
        assertEquals(UserIndicatorResult.SourceType.APPLY_PAGE, saved.getSourceType());
        assertEquals("user/indicators-apply-publications", saved.getViewName());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());
        verify(userIndicatorResultRepository).save(any(UserIndicatorResult.class));
    }

    @Test
    void getOrCreateLatestParsesCommaDecimalTotal() {
        when(userIndicatorResultRepository.findByUserEmailAndIndicatorIdAndMode("u@uvt.ro", "ind-1", UserIndicatorResult.Mode.LATEST))
                .thenReturn(Optional.empty());

        Indicator indicator = new Indicator();
        indicator.setId("ind-1");
        indicator.setOutputType(Indicator.Type.PUBLICATIONS);
        indicator.setScoringStrategy(Indicator.Strategy.GENERIC_COUNT);
        indicator.setFormula("S");
        when(indicatorRepository.findById("ind-1")).thenReturn(Optional.of(indicator));

        User user = new User();
        user.setEmail("u@uvt.ro");

        when(userReportFacade.buildIndicatorApplyView("u@uvt.ro", "ind-1"))
                .thenReturn(new UserIndicatorApplyViewModel(
                        "user/indicators-apply-publications",
                        Map.of("indicator", indicator, "total", "2,50", "allQuarters", List.of("Q1"), "allValues", List.of(1))
                ));

        when(userIndicatorResultRepository.save(any(UserIndicatorResult.class))).thenAnswer(invocation -> {
            UserIndicatorResult entity = invocation.getArgument(0);
            entity.setId("new-id");
            return entity;
        });

        IndicatorApplyResultDto dto = service.getOrCreateLatest("u@uvt.ro", "ind-1");

        assertEquals(2.5, dto.summary().totalScore());
    }

    @Test
    void refreshLatestIncrementsVersion() {
        UserIndicatorResult existing = new UserIndicatorResult();
        existing.setRefreshVersion(3);

        when(userIndicatorResultRepository.findByUserEmailAndIndicatorIdAndMode("u@uvt.ro", "ind-1", UserIndicatorResult.Mode.LATEST))
                .thenReturn(Optional.of(existing));
        when(indicatorRepository.findById("ind-1")).thenReturn(Optional.of(new Indicator()));
        when(userReportFacade.buildIndicatorApplyView("u@uvt.ro", "ind-1"))
                .thenReturn(new UserIndicatorApplyViewModel("user/indicators", Map.of("indicator", new Indicator(), "total", "0.00")));
        when(userIndicatorResultRepository.save(any(UserIndicatorResult.class))).thenAnswer(invocation -> invocation.getArgument(0));

        IndicatorApplyResultDto dto = service.refreshLatest("u@uvt.ro", "ind-1");

        assertEquals(4, dto.refreshVersion());
    }

    @Test
    void refreshLatestStartsAtOneWhenNoExistingLatest() {
        when(userIndicatorResultRepository.findByUserEmailAndIndicatorIdAndMode("u@uvt.ro", "ind-1", UserIndicatorResult.Mode.LATEST))
                .thenReturn(Optional.empty());
        Indicator indicator = new Indicator();
        indicator.setId("ind-1");
        when(indicatorRepository.findById("ind-1")).thenReturn(Optional.of(indicator));
        when(userReportFacade.buildIndicatorApplyView("u@uvt.ro", "ind-1"))
                .thenReturn(new UserIndicatorApplyViewModel("user/indicators", Map.of("indicator", indicator, "total", "0.00")));
        when(userIndicatorResultRepository.save(any(UserIndicatorResult.class))).thenAnswer(invocation -> invocation.getArgument(0));

        IndicatorApplyResultDto dto = service.refreshLatest("u@uvt.ro", "ind-1");

        assertEquals(1, dto.refreshVersion());
    }

    @Test
    void invalidateLatestResultsDeletesLatestOnly() {
        when(userIndicatorResultRepository.deleteByUserEmailAndMode("u@uvt.ro", UserIndicatorResult.Mode.LATEST))
                .thenReturn(3L);

        long deleted = service.invalidateLatestResults("u@uvt.ro");

        assertEquals(3L, deleted);
        verify(userIndicatorResultRepository).deleteByUserEmailAndMode("u@uvt.ro", UserIndicatorResult.Mode.LATEST);
        verify(userReportFacade, never()).buildIndicatorApplyView(any(), any());
    }

    @Test
    void createSnapshotFromComputedPersistsReportScopedPayloadWithoutReadingLatest() {
        Indicator indicator = new Indicator();
        indicator.setId("ind-1");
        indicator.setOutputType(Indicator.Type.CITATIONS);
        when(indicatorRepository.findById("ind-1")).thenReturn(Optional.of(indicator));

        User user = new User();

        when(userIndicatorResultRepository.save(any(UserIndicatorResult.class))).thenAnswer(invocation -> {
            UserIndicatorResult entity = invocation.getArgument(0);
            entity.setId("snap-1");
            return entity;
        });

        IndicatorApplyResultDto computed = new IndicatorApplyResultDto(
                null,
                "ind-1",
                "user/indicators-apply-citations",
                Map.of("total", "9.50"),
                new IndicatorApplyResultDto.Summary(9.5, 3, List.of("Q1"), List.of(3)),
                IndicatorApplyResultDto.Source.COMPUTED,
                null,
                null,
                0
        );

        UserIndicatorResult snapshot = service.createSnapshotFromComputed("u@uvt.ro", "ind-1", "rep-1", computed, 7);

        assertEquals("snap-1", snapshot.getId());
        assertEquals("u@uvt.ro", snapshot.getUserEmail());
        assertEquals("u@uvt.ro", snapshot.getResearcherId());
        assertEquals("ind-1", snapshot.getIndicatorId());
        assertEquals(UserIndicatorResult.Mode.SNAPSHOT, snapshot.getMode());
        assertEquals(UserIndicatorResult.SourceType.REPORT_RUN, snapshot.getSourceType());
        assertEquals("rep-1", snapshot.getSourceReportId());
        assertNotNull(snapshot.getFingerprint());
        assertEquals("user/indicators-apply-citations", snapshot.getViewName());
        assertNotNull(snapshot.getRawGraph());
        assertEquals(9.5, snapshot.getTotalScore());
        assertEquals(3, snapshot.getTotalCount());
        assertEquals(List.of("Q1"), snapshot.getQuarterLabels());
        assertEquals(List.of(3), snapshot.getQuarterValues());
        assertNotNull(snapshot.getCreatedAt());
        assertNotNull(snapshot.getUpdatedAt());
        assertEquals(7, snapshot.getRefreshVersion());
        verify(userReportFacade, times(0)).buildIndicatorApplyView(any(), any());
    }

    @Test
    void createSnapshotFromLatestUsesLatestRefreshVersionAndSourceReportId() {
        Indicator indicator = new Indicator();
        indicator.setId("ind-1");
        indicator.setOutputType(Indicator.Type.PUBLICATIONS);
        indicator.setScoringStrategy(Indicator.Strategy.GENERIC_COUNT);
        indicator.setFormula("S");

        UserIndicatorResult latest = new UserIndicatorResult();
        latest.setId("latest");
        latest.setIndicatorId("ind-1");
        latest.setMode(UserIndicatorResult.Mode.LATEST);
        latest.setFingerprint("ind-1|PUBLICATIONS|GENERIC_COUNT|S||||payload-v2-scoring-provenance");
        latest.setViewName("user/indicators");
        latest.setRawGraph(new IndicatorPayloadSerializer(new ObjectMapper()).serialize(Map.of("total", "1.00")));
        latest.setRefreshVersion(9);

        when(indicatorRepository.findById("ind-1")).thenReturn(Optional.of(indicator));
        when(userIndicatorResultRepository.findByUserEmailAndIndicatorIdAndMode("u@uvt.ro", "ind-1", UserIndicatorResult.Mode.LATEST))
                .thenReturn(Optional.of(latest));
        when(userIndicatorResultRepository.save(any(UserIndicatorResult.class))).thenAnswer(invocation -> {
            UserIndicatorResult e = invocation.getArgument(0);
            if (e.getMode() == UserIndicatorResult.Mode.SNAPSHOT) {
                e.setId("snap-from-latest");
            }
            return e;
        });

        UserIndicatorResult snapshot = service.createSnapshotFromLatest("u@uvt.ro", "ind-1", "rep-9");

        assertEquals("snap-from-latest", snapshot.getId());
        assertEquals(9, snapshot.getRefreshVersion());
        assertEquals("rep-9", snapshot.getSourceReportId());
    }

    @Test
    void persistedPayloadKeepsScoreProvenanceFields() {
        Indicator indicator = new Indicator();
        indicator.setId("ind-1");
        indicator.setOutputType(Indicator.Type.PUBLICATIONS);
        indicator.setScoringStrategy(Indicator.Strategy.GENERIC_COUNT);
        indicator.setFormula("S");

        Score score = new Score();
        score.setCoreRankingEquivalent("A");
        score.setQuarter("NOT_FOUND");
        score.setScoringSource("DBLP+CORE");
        score.setScoringInfo(Map.of("matchSource", "DBLP", "resolvedRank", "A"));

        UserIndicatorResult persisted = new UserIndicatorResult();
        persisted.setId("r2");
        persisted.setIndicatorId("ind-1");
        persisted.setMode(UserIndicatorResult.Mode.LATEST);
        persisted.setFingerprint("ind-1|PUBLICATIONS|GENERIC_COUNT|S||||payload-v2-scoring-provenance");
        persisted.setViewName("user/indicators-apply-publications");
        persisted.setRawGraph(new IndicatorPayloadSerializer(new ObjectMapper()).serialize(
                Map.of("total", "1.00", "scores", Map.of("Paper", score))
        ));
        persisted.setCreatedAt(Instant.now());
        persisted.setUpdatedAt(Instant.now());

        when(indicatorRepository.findById("ind-1")).thenReturn(Optional.of(indicator));
        when(userIndicatorResultRepository.findByUserEmailAndIndicatorIdAndMode("u@uvt.ro", "ind-1", UserIndicatorResult.Mode.LATEST))
                .thenReturn(Optional.of(persisted));

        IndicatorApplyResultDto dto = service.getOrCreateLatest("u@uvt.ro", "ind-1");

        Object rawScore = ((Map<String, Object>) dto.rawGraph().get("scores")).get("Paper");
        assertInstanceOf(Score.class, rawScore);
        assertEquals("DBLP+CORE", ((Score) rawScore).getScoringSource());
        assertTrue(((Score) rawScore).getScoringInfo().containsKey("matchSource"));
    }

    @Test
    void computeAndSaveLatestReturnsZeroSummaryWhenIndicatorMissingFromAttributes() {
        Indicator indicator = new Indicator();
        indicator.setId("ind-x");
        when(indicatorRepository.findById("ind-x")).thenReturn(Optional.of(indicator));
        when(userIndicatorResultRepository.findByUserEmailAndIndicatorIdAndMode("u@uvt.ro", "ind-x", UserIndicatorResult.Mode.LATEST))
                .thenReturn(Optional.empty());
        when(userReportFacade.buildIndicatorApplyView("u@uvt.ro", "ind-x"))
                .thenReturn(new UserIndicatorApplyViewModel("user/indicators", Map.of("total", "99.99")));

        IndicatorApplyResultDto dto = service.getOrCreateLatest("u@uvt.ro", "ind-x");

        assertEquals(0.0, dto.summary().totalScore());
        assertEquals(0, dto.summary().totalCount());
        assertTrue(dto.summary().quarterLabels().isEmpty());
        verify(userIndicatorResultRepository, never()).save(any(UserIndicatorResult.class));
    }

    @Test
    void getByIdReturnsPersistedDtoWhenFoundAndEmptyWhenMissing() {
        UserIndicatorResult entity = new UserIndicatorResult();
        entity.setId("id-1");
        entity.setIndicatorId("ind");
        entity.setViewName("user/indicators");
        entity.setRawGraph(new IndicatorPayloadSerializer(new ObjectMapper()).serialize(Map.of("k", "v")));
        entity.setTotalScore(1.0);
        entity.setTotalCount(2);

        when(userIndicatorResultRepository.findById("id-1")).thenReturn(Optional.of(entity));
        when(userIndicatorResultRepository.findById("missing")).thenReturn(Optional.empty());

        Optional<IndicatorApplyResultDto> found = service.getById("id-1");
        Optional<IndicatorApplyResultDto> missing = service.getById("missing");

        assertTrue(found.isPresent());
        assertEquals(IndicatorApplyResultDto.Source.PERSISTED, found.orElseThrow().source());
        assertTrue(missing.isEmpty());
    }

    @Test
    void getByIdMapsNullQuarterListsToEmpty() {
        UserIndicatorResult entity = new UserIndicatorResult();
        entity.setId("id-2");
        entity.setIndicatorId("ind");
        entity.setViewName("user/indicators");
        entity.setRawGraph(new IndicatorPayloadSerializer(new ObjectMapper()).serialize(Map.of()));
        entity.setTotalScore(0.0);
        entity.setTotalCount(null);
        entity.setQuarterLabels(null);
        entity.setQuarterValues(null);
        when(userIndicatorResultRepository.findById("id-2")).thenReturn(Optional.of(entity));

        IndicatorApplyResultDto dto = service.getById("id-2").orElseThrow();
        assertTrue(dto.summary().quarterLabels().isEmpty());
        assertTrue(dto.summary().quarterValues().isEmpty());
    }

    @Test
    void getLatestRefreshVersionReturnsPersistedOrZero() {
        UserIndicatorResult latest = new UserIndicatorResult();
        latest.setRefreshVersion(6);
        when(userIndicatorResultRepository.findByUserEmailAndIndicatorIdAndMode("u@uvt.ro", "ind-1", UserIndicatorResult.Mode.LATEST))
                .thenReturn(Optional.of(latest))
                .thenReturn(Optional.empty());

        assertEquals(6, service.getLatestRefreshVersion("u@uvt.ro", "ind-1"));
        assertEquals(0, service.getLatestRefreshVersion("u@uvt.ro", "ind-1"));
    }

    @Test
    void parseSummaryHandlesDiverseNumericAndCollectionShapes() {
        Indicator indicator = new Indicator();
        indicator.setId("ind-shapes");
        when(indicatorRepository.findById("ind-shapes")).thenReturn(Optional.of(indicator));
        when(userIndicatorResultRepository.findByUserEmailAndIndicatorIdAndMode("u@uvt.ro", "ind-shapes", UserIndicatorResult.Mode.LATEST))
                .thenReturn(Optional.empty());
        when(userReportFacade.buildIndicatorApplyView("u@uvt.ro", "ind-shapes"))
                .thenReturn(new UserIndicatorApplyViewModel(
                        "user/indicators",
                        Map.of(
                                "indicator", indicator,
                                "total", "1.234,56",
                                "totalCit", "bad-int",
                                "allQuarters", List.of("Q1", "Q2"),
                                "allValues", List.of("3", "bad")
                        )
                ));
        when(userIndicatorResultRepository.save(any(UserIndicatorResult.class))).thenAnswer(invocation -> invocation.getArgument(0));

        IndicatorApplyResultDto dto = service.getOrCreateLatest("u@uvt.ro", "ind-shapes");

        assertEquals(1234.56, dto.summary().totalScore());
        assertEquals(null, dto.summary().totalCount());
        assertEquals(List.of("Q1", "Q2"), dto.summary().quarterLabels());
        assertEquals(List.of(3, 0), dto.summary().quarterValues());
    }

    @Test
    void buildFingerprintUsesMissingIndicatorConstant() {
        when(indicatorRepository.findById("missing-ind")).thenReturn(Optional.empty());
        when(userIndicatorResultRepository.findByUserEmailAndIndicatorIdAndMode("u@uvt.ro", "missing-ind", UserIndicatorResult.Mode.LATEST))
                .thenReturn(Optional.empty());
        when(userReportFacade.buildIndicatorApplyView("u@uvt.ro", "missing-ind"))
                .thenReturn(new UserIndicatorApplyViewModel("user/indicators", Map.of()));

        IndicatorApplyResultDto dto = service.getOrCreateLatest("u@uvt.ro", "missing-ind");

        assertEquals("missing-ind", dto.indicatorId());
    }

    @Test
    void buildFingerprintIncludesEmptySegmentsForNullFields() {
        Indicator indicator = new Indicator();
        indicator.setId("ind-null-segments");
        // leave outputType/strategy/formula/ranges/selector null

        when(indicatorRepository.findById("ind-null-segments")).thenReturn(Optional.of(indicator));
        when(userIndicatorResultRepository.findByUserEmailAndIndicatorIdAndMode("u@uvt.ro", "ind-null-segments", UserIndicatorResult.Mode.LATEST))
                .thenReturn(Optional.empty());
        when(userReportFacade.buildIndicatorApplyView("u@uvt.ro", "ind-null-segments"))
                .thenReturn(new UserIndicatorApplyViewModel("user/indicators", Map.of("indicator", indicator, "total", "0")));
        when(userIndicatorResultRepository.save(any(UserIndicatorResult.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.getOrCreateLatest("u@uvt.ro", "ind-null-segments");

        ArgumentCaptor<UserIndicatorResult> captor = ArgumentCaptor.forClass(UserIndicatorResult.class);
        verify(userIndicatorResultRepository).save(captor.capture());
        assertEquals("ind-null-segments|||||||payload-v2-scoring-provenance", captor.getValue().getFingerprint());
    }
}
