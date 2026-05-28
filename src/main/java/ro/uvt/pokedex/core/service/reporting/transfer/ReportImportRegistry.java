package ro.uvt.pokedex.core.service.reporting.transfer;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class ReportImportRegistry {

    private final Map<String, ReportTypeImportSupport> byKey;

    public ReportImportRegistry(List<ReportTypeImportSupport> supports) {
        Map<String, ReportTypeImportSupport> map = new LinkedHashMap<>();
        for (ReportTypeImportSupport s : supports) {
            ReportTypeImportSupport prev = map.put(s.reportTypeKey(), s);
            if (prev != null) {
                throw new IllegalStateException(
                        "Duplicate ReportTypeImportSupport for key '" + s.reportTypeKey()
                                + "': " + prev.getClass().getName() + " and " + s.getClass().getName());
            }
        }
        this.byKey = Map.copyOf(map);
    }

    public Optional<ReportTypeImportSupport> find(String reportTypeKey) {
        if (reportTypeKey == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byKey.get(reportTypeKey));
    }

    public ReportTypeImportSupport require(String reportTypeKey) {
        ReportTypeImportSupport s = byKey.get(reportTypeKey);
        if (s == null) {
            throw new IllegalArgumentException("No ReportTypeImportSupport registered for key '" + reportTypeKey + "'");
        }
        return s;
    }

    public Set<String> registeredKeys() {
        return byKey.keySet();
    }
}
