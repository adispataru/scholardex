package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.controller.dto.WosCategoryPageResponse;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;

@Service
@RequiredArgsConstructor
public class WosCategoryQueryService {

    private final PostgresWosCategoryReadPort postgresWosCategoryReadPort;

    public WosCategoryPageResponse search(int page, int size, String sort, String direction, String q, String metric) {
        MetricType metricType = parseMetric(metric);
        return postgresWosCategoryReadPort.search(page, size, sort, direction, q, metricType);
    }

    private MetricType parseMetric(String metric) {
        if (metric == null || metric.isBlank()) {
            return MetricType.AIS;
        }
        return switch (metric.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "AIS" -> MetricType.AIS;
            case "IF" -> MetricType.IF;
            default -> throw new IllegalArgumentException("Invalid metric parameter. Allowed: AIS, IF.");
        };
    }
}
