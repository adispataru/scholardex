package ro.uvt.pokedex.core.service.importing.scopus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PublicationMergeAliasRegistryTest {

    @BeforeEach
    @AfterEach
    void clear() {
        PublicationMergeAliasRegistry.clear();
    }

    @Test
    void resolvesTransitivelyAndIsCycleSafe() {
        PublicationMergeAliasRegistry.register("spub_a", List.of(), "spub_b");
        PublicationMergeAliasRegistry.register("spub_b", List.of(), "spub_c");
        assertEquals("spub_c", PublicationMergeAliasRegistry.resolveCanonicalId("spub_a"));
        assertEquals("spub_x", PublicationMergeAliasRegistry.resolveCanonicalId("spub_x")); // unaliased passes through

        // A (mis)configured cycle must terminate rather than spin.
        PublicationMergeAliasRegistry.register("spub_c", List.of(), "spub_a");
        PublicationMergeAliasRegistry.resolveCanonicalId("spub_a"); // no exception, bounded hops
    }

    @Test
    void sourceRefLookupIsCaseInsensitiveAndFollowsTheAliasChain() {
        PublicationMergeAliasRegistry.register("spub_dup", List.of("OPENALEX:W1480837697"), "spub_mid");
        PublicationMergeAliasRegistry.register("spub_mid", List.of(), "spub_final");

        assertEquals("spub_final",
                PublicationMergeAliasRegistry.survivorForSourceRecord("openalex", "w1480837697"));
        assertNull(PublicationMergeAliasRegistry.survivorForSourceRecord("OPENALEX", "W_other"));
    }

    @Test
    void unregisterRemovesBothKeys() {
        PublicationMergeAliasRegistry.register("spub_dup", List.of("SCOPUS:2-s2.0-1"), "spub_surv");
        PublicationMergeAliasRegistry.unregister("spub_dup", List.of("SCOPUS:2-s2.0-1"));

        assertEquals("spub_dup", PublicationMergeAliasRegistry.resolveCanonicalId("spub_dup"));
        assertNull(PublicationMergeAliasRegistry.survivorForSourceRecord("SCOPUS", "2-s2.0-1"));
    }
}
