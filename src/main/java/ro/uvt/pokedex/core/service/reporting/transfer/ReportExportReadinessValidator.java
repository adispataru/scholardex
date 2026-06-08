package ro.uvt.pokedex.core.service.reporting.transfer;

import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.reporting.AbstractReport;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class ReportExportReadinessValidator {

    public static final String EXCLUDED_FROM_TEMPLATE = "__not_exported__";

    private final ReportImportRegistry registry;

    public ReportExportReadinessValidator(ReportImportRegistry registry) {
        this.registry = registry;
    }

    public List<String> validate(IndividualReport report, ReportFormat format) {
        List<String> problems = new ArrayList<>();
        if (report == null || report.getReportTypeKey() == null || report.getReportTypeKey().isBlank()) {
            return problems;
        }

        ReportTypeImportSupport support = registry.find(report.getReportTypeKey()).orElse(null);
        if (support == null) {
            problems.add("Report type key '" + report.getReportTypeKey() + "' is not registered.");
            return problems;
        }
        if (!support.supportedExportFormats().contains(format) && !support.supportedImportFormats().contains(format)) {
            problems.add("Report type key '" + report.getReportTypeKey() + "' does not support " + format + ".");
        }

        Set<String> roleKeys = new LinkedHashSet<>(support.declaredRoles());
        Set<String> blockNames = new LinkedHashSet<>();
        support.declaredBlocksByRole().values().forEach(blockNames::addAll);

        List<Indicator> indicators = report.getIndicators() != null ? report.getIndicators() : List.of();
        List<AbstractReport.Criterion> criteria = report.getCriteria() != null ? report.getCriteria() : List.of();
        for (int criterionIndex = 0; criterionIndex < criteria.size(); criterionIndex++) {
            AbstractReport.Criterion criterion = criteria.get(criterionIndex);
            if (criterion == null || criterion.getIndicatorIndices() == null) continue;
            for (Integer indicatorIndex : criterion.getIndicatorIndices()) {
                if (indicatorIndex == null || indicatorIndex < 0 || indicatorIndex >= indicators.size()) {
                    problems.add("Criterion " + (criterionIndex + 1) + " references missing indicator index " + indicatorIndex + ".");
                    continue;
                }
                Indicator indicator = indicators.get(indicatorIndex);
                if (indicator == null || indicator.getId() == null || indicator.getId().isBlank()) {
                    problems.add("Criterion " + (criterionIndex + 1) + " references an indicator without an id.");
                    continue;
                }
                String role = report.getIndicatorRolesByIndicatorId() != null
                        ? report.getIndicatorRolesByIndicatorId().get(indicator.getId()) : null;
                String block = report.getBlockByIndicatorId() != null
                        ? report.getBlockByIndicatorId().get(indicator.getId()) : null;
                boolean intentionallyExcluded = isExcludedFromTemplate(role) || isExcludedFromTemplate(block);
                boolean hasValidRole = role != null && !role.isBlank() && roleKeys.contains(role);
                boolean hasValidBlock = block != null && !block.isBlank() && blockNames.contains(block);
                if (!intentionallyExcluded && !hasValidRole && !hasValidBlock) {
                    problems.add("Criterion " + (criterionIndex + 1) + " indicator '" + indicator.getId()
                            + "' is not mapped to a valid export role or block.");
                }
            }
        }
        return problems;
    }

    public boolean isReady(IndividualReport report, ReportFormat format) {
        return validate(report, format).isEmpty();
    }

    public static boolean isExcludedFromTemplate(String value) {
        return EXCLUDED_FROM_TEMPLATE.equals(value);
    }

    public static boolean isExcludedFromTemplate(IndividualReport report, String indicatorId) {
        String role = report.getIndicatorRolesByIndicatorId() != null
                ? report.getIndicatorRolesByIndicatorId().get(indicatorId) : null;
        String block = report.getBlockByIndicatorId() != null
                ? report.getBlockByIndicatorId().get(indicatorId) : null;
        return isExcludedFromTemplate(role) || isExcludedFromTemplate(block);
    }
}
