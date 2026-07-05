package ro.uvt.pokedex.core.service.importing.wos.model;

import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosSourceType;

public record WosParsedRecord(
        String title,
        String issn,
        String eIssn,
        Integer year,
        MetricType metricType,
        Double value,
        String categoryNameCanonical,
        String editionRaw,
        EditionNormalized editionNormalized,
        String quarter,
        Integer quartileRank,
        Integer rank,
        String sourceEventId,
        WosSourceType sourceType,
        String sourceFile,
        String sourceVersion,
        String sourceRowItem,
        String abbreviatedTitle
) {
    /** Legacy shape without abbreviatedTitle — sources that don't carry the WoS abbreviation. */
    public WosParsedRecord(
            String title,
            String issn,
            String eIssn,
            Integer year,
            MetricType metricType,
            Double value,
            String categoryNameCanonical,
            String editionRaw,
            EditionNormalized editionNormalized,
            String quarter,
            Integer quartileRank,
            Integer rank,
            String sourceEventId,
            WosSourceType sourceType,
            String sourceFile,
            String sourceVersion,
            String sourceRowItem
    ) {
        this(title, issn, eIssn, year, metricType, value, categoryNameCanonical, editionRaw, editionNormalized,
                quarter, quartileRank, rank, sourceEventId, sourceType, sourceFile, sourceVersion, sourceRowItem,
                null);
    }
}
