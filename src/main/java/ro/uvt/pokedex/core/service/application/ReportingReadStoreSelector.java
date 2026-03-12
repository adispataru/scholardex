package ro.uvt.pokedex.core.service.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
public class ReportingReadStoreSelector {

    private final ReportingReadStore readStore;
    private final ThreadLocal<ReportingReadStore> readStoreOverride = new ThreadLocal<>();

    public ReportingReadStoreSelector(@Value("${app.reporting.read-store:mongo}") String readStore) {
        this.readStore = ReportingReadStore.fromProperty(readStore);
    }

    public ReportingReadStore readStore() {
        ReportingReadStore override = readStoreOverride.get();
        return override == null ? readStore : override;
    }

    public boolean isPostgres() {
        return readStore() == ReportingReadStore.POSTGRES;
    }

    public <T> T withReadStoreOverride(ReportingReadStore store, Supplier<T> supplier) {
        ReportingReadStore previous = readStoreOverride.get();
        readStoreOverride.set(store);
        try {
            return supplier.get();
        } finally {
            if (previous == null) {
                readStoreOverride.remove();
            } else {
                readStoreOverride.set(previous);
            }
        }
    }

    public void withReadStoreOverride(ReportingReadStore store, Runnable runnable) {
        withReadStoreOverride(store, () -> {
            runnable.run();
            return null;
        });
    }
}
