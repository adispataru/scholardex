package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.repository.reporting.IndicatorRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IndicatorDescriptionServiceTest {

    @Mock private IndicatorRepository indicatorRepository;
    @InjectMocks private IndicatorDescriptionService service;

    private static Indicator indicator(String name, String description) {
        Indicator indicator = new Indicator();
        indicator.setName(name);
        indicator.setDescription(description);
        return indicator;
    }

    @Test
    void dryRunReportsWhatWouldChangeWithoutSaving() {
        // Real committed file, mocked repository: Info_B has no description yet (would update),
        // and an off-catalog indicator is reported as lacking one rather than silently skipped.
        when(indicatorRepository.findAll()).thenReturn(List.of(
                indicator("Info_B", null),
                indicator("Some_Other_Report_Indicator", null)));

        IndicatorDescriptionService.ApplyReport report = service.apply(true);

        assertThat(report.dryRun()).isTrue();
        assertThat(report.updated()).isEqualTo(1);
        assertThat(report.indicatorsWithoutDescription()).containsExactly("Some_Other_Report_Indicator");
        verify(indicatorRepository, never()).save(any());
    }

    @Test
    void applyWritesOnlyTheChangedOnesAndCountsTheRestUnchanged() {
        Indicator stale = indicator("Info_B", "old text");
        Indicator missing = indicator("Info_C", null);
        when(indicatorRepository.findAll()).thenReturn(List.of(stale, missing));

        IndicatorDescriptionService.ApplyReport report = service.apply(false);

        assertThat(report.updated()).isEqualTo(2);
        assertThat(stale.getDescription()).startsWith("Producția științifică");
        assertThat(missing.getDescription()).startsWith("Impactul rezultatelor");
        verify(indicatorRepository).save(stale);
        verify(indicatorRepository).save(missing);
    }

    @Test
    void aDescriptionNamingNoLiveIndicatorIsReportedNotDropped() {
        // The committed file names 36 indicators; a repo holding only one leaves 35 unmatched — the
        // report must say so, because a silently dropped description is how docs rot.
        when(indicatorRepository.findAll()).thenReturn(List.of(indicator("Info_B", null)));

        IndicatorDescriptionService.ApplyReport report = service.apply(true);

        assertThat(report.unmatchedDescriptions()).isNotEmpty();
        assertThat(report.unmatchedDescriptions()).contains("Info_C_2026");
    }
}
