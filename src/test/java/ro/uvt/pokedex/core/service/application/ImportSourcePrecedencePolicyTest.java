package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ImportSourcePrecedencePolicyTest {

    private final ImportSourcePrecedencePolicy policy = new ImportSourcePrecedencePolicy();

    @Test
    void higherPrecedenceIncomingSourceAppliesAutomatically() {
        ImportSourcePrecedencePolicy.Decision decision = policy.decide(
                "SCOPUS",
                Instant.parse("2026-05-07T14:31:06Z"),
                "SCOPUS_JSON_BOOTSTRAP",
                Instant.parse("2026-05-07T14:53:24Z")
        );

        assertEquals(ImportSourcePrecedencePolicy.Decision.APPLY_INCOMING, decision);
    }

    @Test
    void lowerPrecedenceIncomingSourceKeepsExistingLink() {
        ImportSourcePrecedencePolicy.Decision decision = policy.decide(
                "SCOPUS_PYTHON_AUTHOR_WORKS",
                Instant.parse("2026-05-08T19:50:47Z"),
                "SCOPUS_JSON_BOOTSTRAP",
                Instant.parse("2026-05-09T19:50:47Z")
        );

        assertEquals(ImportSourcePrecedencePolicy.Decision.KEEP_EXISTING, decision);
    }

    @Test
    void equalPrecedenceUsesImportEventTimestamp() {
        ImportSourcePrecedencePolicy.Decision newerIncoming = policy.decide(
                "SCOPUS_PYTHON_AUTHOR_WORKS",
                Instant.parse("2026-05-08T19:50:47Z"),
                "SCOPUS_PYTHON_CITATIONS_PUBLICATION",
                Instant.parse("2026-05-08T19:51:54Z")
        );
        ImportSourcePrecedencePolicy.Decision olderIncoming = policy.decide(
                "SCOPUS_PYTHON_CITATIONS_PUBLICATION",
                Instant.parse("2026-05-08T19:51:54Z"),
                "SCOPUS_PYTHON_AUTHOR_WORKS",
                Instant.parse("2026-05-08T19:50:47Z")
        );

        assertEquals(ImportSourcePrecedencePolicy.Decision.APPLY_INCOMING, newerIncoming);
        assertEquals(ImportSourcePrecedencePolicy.Decision.KEEP_EXISTING, olderIncoming);
    }

    @Test
    void unknownSourcesRequireReviewInsteadOfGuessing() {
        ImportSourcePrecedencePolicy.Decision decision = policy.decide(
                "SCOPUS_PYTHON_AUTHOR_WORKS",
                Instant.parse("2026-05-08T19:50:47Z"),
                "UNMAPPED_IMPORT_SOURCE",
                Instant.parse("2026-05-08T19:51:54Z")
        );

        assertEquals(ImportSourcePrecedencePolicy.Decision.REQUIRE_REVIEW, decision);
    }
}
