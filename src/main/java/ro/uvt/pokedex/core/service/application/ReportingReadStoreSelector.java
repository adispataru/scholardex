package ro.uvt.pokedex.core.service.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ReportingReadStoreSelector {

    private final ReportingReadStore readStore;

    public ReportingReadStoreSelector(@Value("${app.reporting.read-store:mongo}") String readStore) {
        this.readStore = ReportingReadStore.fromProperty(readStore);
    }

    public ReportingReadStore readStore() {
        return readStore;
    }

    public boolean isPostgres() {
        return readStore == ReportingReadStore.POSTGRES;
    }
}
