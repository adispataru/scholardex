package ro.uvt.pokedex.core.service.reporting.transfer;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.AbstractReport;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportExportReadinessValidatorTest {

    @Test
    void missingRoleOrBlockForCriteriaIndicatorFailsReadiness() {
        ReportImportRegistry registry = mock(ReportImportRegistry.class);
        ReportTypeImportSupport support = mock(ReportTypeImportSupport.class);
        when(registry.find("informatica-2016")).thenReturn(Optional.of(support));
        when(support.supportedExportFormats()).thenReturn(Set.of(ReportFormat.XLSX));
        when(support.supportedImportFormats()).thenReturn(Set.of(ReportFormat.XLSX));
        when(support.declaredRoles()).thenReturn(List.of("journal-publications"));
        when(support.declaredBlocksByRole()).thenReturn(Map.of("activities-perspectiva-d", List.of("Granturi")));

        IndividualReport report = reportWithCriterionIndicator();

        List<String> problems = new ReportExportReadinessValidator(registry).validate(report, ReportFormat.XLSX);

        assertTrue(problems.stream().anyMatch(problem -> problem.contains("ind-1")));
    }

    @Test
    void validRoleForCriteriaIndicatorPassesReadiness() {
        ReportImportRegistry registry = mock(ReportImportRegistry.class);
        ReportTypeImportSupport support = mock(ReportTypeImportSupport.class);
        when(registry.find("informatica-2016")).thenReturn(Optional.of(support));
        when(support.supportedExportFormats()).thenReturn(Set.of(ReportFormat.XLSX));
        when(support.supportedImportFormats()).thenReturn(Set.of(ReportFormat.XLSX));
        when(support.declaredRoles()).thenReturn(List.of("journal-publications"));
        when(support.declaredBlocksByRole()).thenReturn(Map.of());

        IndividualReport report = reportWithCriterionIndicator();
        report.setIndicatorRolesByIndicatorId(Map.of("ind-1", "journal-publications"));

        assertTrue(new ReportExportReadinessValidator(registry).validate(report, ReportFormat.XLSX).isEmpty());
    }

    @Test
    void explicitlyExcludedCriteriaIndicatorPassesReadiness() {
        ReportImportRegistry registry = mock(ReportImportRegistry.class);
        ReportTypeImportSupport support = mock(ReportTypeImportSupport.class);
        when(registry.find("informatica-2016")).thenReturn(Optional.of(support));
        when(support.supportedExportFormats()).thenReturn(Set.of(ReportFormat.XLSX));
        when(support.supportedImportFormats()).thenReturn(Set.of(ReportFormat.XLSX));
        when(support.declaredRoles()).thenReturn(List.of("journal-publications"));
        when(support.declaredBlocksByRole()).thenReturn(Map.of("activities-perspectiva-d", List.of("Granturi")));

        IndividualReport report = reportWithCriterionIndicator();
        report.setIndicatorRolesByIndicatorId(Map.of("ind-1", "__not_exported__"));

        assertTrue(new ReportExportReadinessValidator(registry).validate(report, ReportFormat.XLSX).isEmpty());
    }

    @Test
    void blockExcludedCriteriaIndicatorPassesReadiness() {
        ReportImportRegistry registry = mock(ReportImportRegistry.class);
        ReportTypeImportSupport support = mock(ReportTypeImportSupport.class);
        when(registry.find("informatica-2016")).thenReturn(Optional.of(support));
        when(support.supportedExportFormats()).thenReturn(Set.of(ReportFormat.XLSX));
        when(support.supportedImportFormats()).thenReturn(Set.of(ReportFormat.XLSX));
        when(support.declaredRoles()).thenReturn(List.of("journal-publications"));
        when(support.declaredBlocksByRole()).thenReturn(Map.of("activities-perspectiva-d", List.of("Granturi")));

        IndividualReport report = reportWithCriterionIndicator();
        report.setBlockByIndicatorId(Map.of("ind-1", "__not_exported__"));

        assertTrue(new ReportExportReadinessValidator(registry).validate(report, ReportFormat.XLSX).isEmpty());
    }

    private static IndividualReport reportWithCriterionIndicator() {
        Indicator indicator = new Indicator();
        indicator.setId("ind-1");
        IndividualReport report = new IndividualReport();
        report.setId("report-1");
        report.setReportTypeKey("informatica-2016");
        report.setIndicators(List.of(indicator));
        AbstractReport.Criterion criterion = new AbstractReport.Criterion();
        criterion.setName("Criterion");
        criterion.setIndicatorIndices(List.of(0));
        report.setCriteria(List.of(criterion));
        return report;
    }
}
