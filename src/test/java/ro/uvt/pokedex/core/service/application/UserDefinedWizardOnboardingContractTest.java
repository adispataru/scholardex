package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserDefinedWizardOnboardingContractTest {

    @Test
    void contractLocksCanonicalSourceAndLineageEnvelope() {
        assertEquals("USER_DEFINED", UserDefinedWizardOnboardingContract.SOURCE);
        assertIterableEquals(
                java.util.List.of("source", "sourceRecordId", "sourceEventId", "sourceBatchId", "sourceCorrelationId"),
                UserDefinedWizardOnboardingContract.LINEAGE_FIELDS
        );
    }

    @Test
    void forumSourceRecordIdUsesIssnPrecedenceAndIsDeterministic() {
        String first = UserDefinedWizardOnboardingContract.deterministicForumSourceRecordId(
                "Journal of Testing",
                "12345678",
                "8765-4321",
                "Journal"
        );
        String second = UserDefinedWizardOnboardingContract.deterministicForumSourceRecordId(
                "Changed Name Should Not Affect IssnKeying",
                "1234-5678",
                "87654321",
                "Conference"
        );

        assertEquals(first, second);
        assertTrue(first.startsWith(UserDefinedWizardOnboardingContract.FORUM_SOURCE_RECORD_PREFIX));
    }

    @Test
    void forumSourceRecordIdFallsBackToNameAndTypeWhenNoIssn() {
        String first = UserDefinedWizardOnboardingContract.deterministicForumSourceRecordId(
                "  Journal   of   Testing ",
                "",
                null,
                " Journal "
        );
        String second = UserDefinedWizardOnboardingContract.deterministicForumSourceRecordId(
                "journal of testing",
                "bad",
                "123",
                "journal"
        );

        assertEquals(first, second);
        assertTrue(first.startsWith(UserDefinedWizardOnboardingContract.FORUM_SOURCE_RECORD_PREFIX));
    }

    @Test
    void publicationSourceRecordIdUsesNormalizedDoiWhenPresent() {
        String first = UserDefinedWizardOnboardingContract.deterministicPublicationSourceRecordId(
                "https://doi.org/10.1000/XYZ ",
                "Title One",
                "2026-03-08",
                "creator-1",
                "USER_DEFINED:FORUM:seed"
        );
        String second = UserDefinedWizardOnboardingContract.deterministicPublicationSourceRecordId(
                "doi:10.1000/xyz",
                "Another Title",
                "2025-01-01",
                "creator-2",
                "USER_DEFINED:FORUM:other"
        );

        assertEquals(first, second);
        assertTrue(first.startsWith(UserDefinedWizardOnboardingContract.PUBLICATION_SOURCE_RECORD_PREFIX));
    }

    @Test
    void publicationSourceRecordIdFallsBackToNormalizedMaterialWhenDoiMissing() {
        String first = UserDefinedWizardOnboardingContract.deterministicPublicationSourceRecordId(
                "",
                "  A Test Publication ",
                "2026-03-08",
                " Creator-1 ",
                "USER_DEFINED:FORUM:seed"
        );
        String second = UserDefinedWizardOnboardingContract.deterministicPublicationSourceRecordId(
                null,
                "a test publication",
                "2026-03-08",
                "creator-1",
                "user_defined:forum:seed"
        );

        assertEquals(first, second);
        assertTrue(first.startsWith(UserDefinedWizardOnboardingContract.PUBLICATION_SOURCE_RECORD_PREFIX));
    }
}
