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
        f.setPublisher("Some Publisher");
        f.setIsbn("978-1-2345-6789-0");

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
        // publisher + isbn now threaded (were silently dropped → 0% populated for Scopus/OpenAlex forums).
        assertEquals("Some Publisher", record.publisher());
        assertEquals("978-1-2345-6789-0", record.isbn());
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
        // WoS ranks journals — it now contributes an explicit "Journal" view (for the multi-type forum map).
        assertEquals("Journal", record.aggregationType());
        assertNull(record.forumType());
        assertNull(record.asjc());
    }

    @Test
    void ofOpenAlexMapsVenueSourceTypeToAggregationType() {
        assertEquals("Journal",
                ForumSourceRecord.ofOpenAlex("S1", "Some Journal", List.of("1234-5678"), "journal", null).aggregationType());
        assertEquals("Conference Proceeding",
                ForumSourceRecord.ofOpenAlex("S2", "Some Proceedings", List.of(), "conference", null).aggregationType());
        // Book series (LNCS, "Studies in Big Data", …) carry ISSNs and were previously defaulted to "Journal".
        assertEquals("Book Series",
                ForumSourceRecord.ofOpenAlex("S3", "Studies in Big Data", List.of("2197-6503"), "Book Series", null).aggregationType());
        assertEquals("Book",
                ForumSourceRecord.ofOpenAlex("S4", "Some Ebook Platform", List.of(), "ebook platform", null).aggregationType());
    }

    @Test
    void ofOpenAlexCarriesPublisherForBookSenseClassification() {
        ForumSourceRecord r = ForumSourceRecord.ofOpenAlex(
                "S3", "Studies in Big Data", List.of("2197-6503"), "Book Series", "Springer Nature");
        assertEquals("Springer Nature", r.publisher());
    }

    @Test
    void ofOpenAlexLeavesAggregationNullForUnknownOrMissingSourceType() {
        // null -> the merge engine keeps any existing value / falls back to its "Journal" default.
        assertNull(ForumSourceRecord.ofOpenAlex("S5", "Preprint Repo", List.of(), "repository", null).aggregationType());
        assertNull(ForumSourceRecord.ofOpenAlex("S6", "Unknown", List.of(), null, null).aggregationType());
        assertNull(ForumSourceRecord.ofOpenAlex("S7", "Legacy 3-arg call", List.of("1111-2222")).aggregationType());
    }
}
