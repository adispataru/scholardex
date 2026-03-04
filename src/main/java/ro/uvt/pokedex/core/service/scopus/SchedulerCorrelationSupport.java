package ro.uvt.pokedex.core.service.scopus;

import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

public final class SchedulerCorrelationSupport {

    private SchedulerCorrelationSupport() {
    }

    public static AutoCloseable withSchedulerContext(String jobType, String taskId, String phase) {
        Map<String, String> previousValues = new HashMap<>();
        previousValues.put("jobType", MDC.get("jobType"));
        previousValues.put("taskId", MDC.get("taskId"));
        previousValues.put("phase", MDC.get("phase"));

        MDC.put("jobType", normalize(jobType));
        MDC.put("taskId", normalize(taskId));
        MDC.put("phase", normalize(phase));

        return () -> restore(previousValues);
    }

    private static void restore(Map<String, String> previousValues) {
        restoreOrRemove("jobType", previousValues.get("jobType"));
        restoreOrRemove("taskId", previousValues.get("taskId"));
        restoreOrRemove("phase", previousValues.get("phase"));
    }

    private static void restoreOrRemove(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim();
    }
}
