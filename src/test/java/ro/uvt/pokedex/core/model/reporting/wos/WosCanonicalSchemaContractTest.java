package ro.uvt.pokedex.core.model.reporting.wos;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WosCanonicalSchemaContractTest {

    @Test
    void metricFactContainsRequiredContractFields() {
        Set<String> fields = fieldsOf(WosMetricFact.class);
        assertTrue(fields.contains("editionRaw"));
        assertTrue(fields.contains("editionNormalized"));
        assertTrue(fields.contains("sourceType"));
        assertTrue(fields.contains("sourceEventId"));
        assertTrue(fields.contains("sourceFile"));
        assertTrue(fields.contains("sourceVersion"));
        assertTrue(fields.contains("sourceRowItem"));
    }

    @Test
    void categoryFactContainsRequiredContractFields() {
        Set<String> fields = fieldsOf(WosCategoryFact.class);
        assertTrue(fields.contains("categoryNameCanonical"));
        assertTrue(fields.contains("editionRaw"));
        assertTrue(fields.contains("editionNormalized"));
        assertTrue(fields.contains("metricType"));
        assertTrue(fields.contains("sourceType"));
        assertTrue(fields.contains("sourceEventId"));
    }

    @Test
    void importEventContainsLineageAndPayloadFields() {
        Set<String> fields = fieldsOf(WosImportEvent.class);
        assertTrue(fields.contains("sourceType"));
        assertTrue(fields.contains("sourceFile"));
        assertTrue(fields.contains("sourceVersion"));
        assertTrue(fields.contains("checksum"));
        assertTrue(fields.contains("payload"));
        assertTrue(fields.contains("sourceRowItem"));
    }

    @Test
    void journalIdentityContainsIdentityAndConflictFields() {
        Set<String> fields = fieldsOf(WosJournalIdentity.class);
        assertTrue(fields.contains("identityKey"));
        assertTrue(fields.contains("aliasIssns"));
        assertTrue(fields.contains("mergeGroupId"));
        assertTrue(fields.contains("conflictType"));
        assertTrue(fields.contains("conflictReason"));
        assertTrue(fields.contains("conflictDetectedAt"));
    }

    @Test
    void identityConflictContainsLineageAndSignatureFields() {
        Set<String> fields = fieldsOf(WosIdentityConflict.class);
        assertTrue(fields.contains("sourceEventId"));
        assertTrue(fields.contains("sourceFile"));
        assertTrue(fields.contains("sourceVersion"));
        assertTrue(fields.contains("sourceRowItem"));
        assertTrue(fields.contains("inputIdentityKey"));
        assertTrue(fields.contains("inputIssnTokens"));
        assertTrue(fields.contains("candidateJournalIds"));
    }

    private Set<String> fieldsOf(Class<?> type) {
        return Stream.of(type.getDeclaredFields()).map(Field::getName).collect(Collectors.toSet());
    }
}
