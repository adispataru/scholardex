package ro.uvt.pokedex.core.service.application;

import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

@Service
public class ReportingLookupMemoization {

    private final ThreadLocal<Map<String, Object>> refreshScope = new ThreadLocal<>();

    public <T> T withRefreshScope(Supplier<T> supplier) {
        Map<String, Object> existing = refreshScope.get();
        boolean owner = existing == null;
        if (owner) {
            refreshScope.set(new HashMap<>());
        }
        try {
            return supplier.get();
        } finally {
            if (owner) {
                refreshScope.remove();
            }
        }
    }

    public void withRefreshScope(Runnable runnable) {
        withRefreshScope(() -> {
            runnable.run();
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    public <T> T getOrCompute(String storeNamespace, String lookupName, String lookupKey, Supplier<T> supplier) {
        Map<String, Object> scope = refreshScope.get();
        if (scope == null) {
            return supplier.get();
        }
        String cacheKey = storeNamespace + "|" + lookupName + "|" + lookupKey;
        if (scope.containsKey(cacheKey)) {
            return (T) scope.get(cacheKey);
        }
        T value = supplier.get();
        scope.put(cacheKey, value);
        return value;
    }
}
