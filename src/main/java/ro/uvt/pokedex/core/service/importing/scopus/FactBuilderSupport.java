package ro.uvt.pokedex.core.service.importing.scopus;

import ro.uvt.pokedex.core.model.scopus.canonical.HasLineageFields;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEvent;

public final class FactBuilderSupport {

    private FactBuilderSupport() {}

    public static void applyLineage(HasLineageFields fact, ScopusImportEvent event) {
        fact.setSourceEventId(event.getId());
        fact.setSource(event.getSource());
        fact.setSourceRecordId(event.getSourceRecordId());
        fact.setSourceBatchId(event.getBatchId());
        fact.setSourceCorrelationId(event.getCorrelationId());
    }
}
