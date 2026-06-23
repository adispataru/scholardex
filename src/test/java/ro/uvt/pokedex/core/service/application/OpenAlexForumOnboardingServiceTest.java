package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexPublicationFact;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenAlexForumOnboardingServiceTest {

    @Mock private ForumMergeEngine forumMergeEngine;
    @InjectMocks private OpenAlexForumOnboardingService service;

    @Test
    void onboardsIssnVenuesOnceAndSkipsConferencesAndVenuelessPubs() {
        ForumMergeEngine.Context ctx = mock(ForumMergeEngine.Context.class);
        when(forumMergeEngine.startCreateOrTagRun()).thenReturn(ctx);

        OpenAlexPublicationFact journal = pub("S1", "Journal A", List.of("1234-5678"));
        OpenAlexPublicationFact journalSameVenue = pub("S1", "Journal A", List.of("1234-5678")); // dup venue
        OpenAlexPublicationFact conference = pub("S2", "Some Conference", List.of());            // no ISSN → skip
        OpenAlexPublicationFact venueless = pub(null, "Unknown", List.of("9999-0000"));          // no venue id → skip

        service.onboard(List.of(journal, journalSameVenue, conference, venueless));

        // Exactly one ingest — venue S1 — carrying an OPENALEX record keyed by the venue id; conference + dup skipped.
        verify(forumMergeEngine, times(1)).ingestCreateOrTag(
                argThat(r -> r.idType() == ForumSourceRecord.ForumIdType.OPENALEX && "S1".equals(r.externalId())
                        && "1234-5678".equals(r.issn())),
                eq(ctx), any(), any(), any(), any());
        verify(forumMergeEngine).flush(ctx);
    }

    @Test
    void carriesOpenAlexVenueSourceTypeAsAggregationType() {
        ForumMergeEngine.Context ctx = mock(ForumMergeEngine.Context.class);
        when(forumMergeEngine.startCreateOrTagRun()).thenReturn(ctx);

        // A book series (LNCS / "Studies in Big Data") carries an ISSN and was previously defaulted to "Journal".
        OpenAlexPublicationFact bookSeries = pub("S1", "Studies in Big Data", List.of("2197-6503"));
        bookSeries.setHostVenueSourceType("book series");

        service.onboard(List.of(bookSeries));

        verify(forumMergeEngine, times(1)).ingestCreateOrTag(
                argThat(r -> r.idType() == ForumSourceRecord.ForumIdType.OPENALEX
                        && "Book Series".equals(r.aggregationType())),
                eq(ctx), any(), any(), any(), any());
        verify(forumMergeEngine).flush(ctx);
    }

    @Test
    void noVenuesIngestsNothingButStillFlushes() {
        ForumMergeEngine.Context ctx = mock(ForumMergeEngine.Context.class);
        when(forumMergeEngine.startCreateOrTagRun()).thenReturn(ctx);

        service.onboard(List.of(pub("S9", "Conf", List.of())));

        verify(forumMergeEngine, never()).ingestCreateOrTag(any(), any(), any(), any(), any(), any());
        verify(forumMergeEngine).flush(ctx);
    }

    private OpenAlexPublicationFact pub(String venueId, String name, List<String> issns) {
        OpenAlexPublicationFact p = new OpenAlexPublicationFact();
        p.setHostVenueOpenAlexId(venueId);
        p.setHostVenueName(name);
        p.setHostVenueIssns(new ArrayList<>(issns));
        return p;
    }
}
