package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusForumFact;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H66B M2 — the source→record mappers feeding {@link ForumMergeEngine#ingest}. They must reproduce the
 * exact inputs the former per-source methods built, including the Scopus SIAM eISSN correction and the WoS
 * default aggregation type.
 */
class ForumSourceRecordTest {

    @Test
    void ofScopusMapsFieldsAndCorrectsSiamEissn() {
        ScopusForumFact f = new ScopusForumFact();
        f.setSourceId("s-1");
        f.setPublicationName("Journal Name");
        f.setIssn("0036-1410");
        f.setEIssn("1095-7111"); // the known SIAM Math Analysis mislabel
        f.setAggregationType("Journal");
        f.setForumType("journal");
        f.setAsjc(List.of("2600"));

        ForumSourceRecord record = ForumSourceRecord.ofScopus(f);

        assertEquals(ForumSourceRecord.ForumIdType.SCOPUS, record.idType());
        assertEquals("s-1", record.externalId());
        assertEquals("Journal Name", record.name());
        assertEquals("0036-1410", record.issn());
        // correctedScopusEIssn rewrites the mislabeled SIAM eISSN to 1095-7154.
        assertEquals("1095-7154", record.eIssn());
        assertTrue(record.aliasIssns().isEmpty());
        assertEquals("Journal", record.aggregationType());
        assertEquals("journal", record.forumType());
        assertEquals(List.of("2600"), record.asjc());
    }

    @Test
    void ofWosMapsFieldsAndLeavesAggregationDefaultsToEngine() {
        WosRankingView v = new WosRankingView();
        v.setId("wos-1");
        v.setName("WoS Journal");
        v.setIssn("1234-5679");
        v.setEIssn("8765-4326");
        v.setAlternativeIssns(List.of("1111-2220"));

        ForumSourceRecord record = ForumSourceRecord.ofWos(v);

        assertEquals(ForumSourceRecord.ForumIdType.WOS, record.idType());
        assertEquals("wos-1", record.externalId());
        assertEquals("WoS Journal", record.name());
        assertEquals("1234-5679", record.issn());
        assertEquals("8765-4326", record.eIssn());
        assertEquals(List.of("1111-2220"), record.aliasIssns());
        // null aggregation -> the engine applies the "Journal" default; WoS carries no Scopus C-scalars.
        assertNull(record.aggregationType());
        assertNull(record.forumType());
        assertNull(record.asjc());
    }
}
