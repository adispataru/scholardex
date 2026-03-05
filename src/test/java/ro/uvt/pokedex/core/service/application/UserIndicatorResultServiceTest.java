package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.UserIndicatorResult;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.reporting.IndicatorRepository;
import ro.uvt.pokedex.core.repository.reporting.UserIndicatorResultRepository;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.model.IndicatorApplyResultDto;
import ro.uvt.pokedex.core.service.application.model.UserIndicatorApplyViewModel;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
                new IndicatorPayloadSerializer()
        );
    }

    @Test
    void getOrCreateLatestReusesPersistedResultWhenPresent() {
        UserIndicatorResult persisted = new UserIndicatorResult();
        persisted.setId("r1");
        persisted.setIndicatorId("ind-1");
        persisted.setMode(UserIndicatorResult.Mode.LATEST);
        persisted.setViewName("user/indicators-apply-publications");
        persisted.setRawGraph(new IndicatorPayloadSerializer().serialize(Map.of("total", "1.00")));
        persisted.setCreatedAt(Instant.now());
        persisted.setUpdatedAt(Instant.now());

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
        user.setResearcherId("r-1");
        when(userService.getUserByEmail("u@uvt.ro")).thenReturn(Optional.of(user));

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
        verify(userIndicatorResultRepository).save(any(UserIndicatorResult.class));
    }

    @Test
    void refreshLatestIncrementsVersion() {
        UserIndicatorResult existing = new UserIndicatorResult();
        existing.setRefreshVersion(3);

        when(userIndicatorResultRepository.findByUserEmailAndIndicatorIdAndMode("u@uvt.ro", "ind-1", UserIndicatorResult.Mode.LATEST))
                .thenReturn(Optional.of(existing));
        when(indicatorRepository.findById("ind-1")).thenReturn(Optional.of(new Indicator()));
        when(userService.getUserByEmail("u@uvt.ro")).thenReturn(Optional.of(new User()));
        when(userReportFacade.buildIndicatorApplyView("u@uvt.ro", "ind-1"))
                .thenReturn(new UserIndicatorApplyViewModel("user/indicators", Map.of("indicator", new Indicator(), "total", "0.00")));
        when(userIndicatorResultRepository.save(any(UserIndicatorResult.class))).thenAnswer(invocation -> invocation.getArgument(0));

        IndicatorApplyResultDto dto = service.refreshLatest("u@uvt.ro", "ind-1");

        assertEquals(4, dto.refreshVersion());
    }
}
