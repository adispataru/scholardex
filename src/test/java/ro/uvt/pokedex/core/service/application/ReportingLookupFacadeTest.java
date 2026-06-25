package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportingLookupFacadeTest {

    @Mock
    private PostgresReportingLookupFacade postgresReportingLookupFacade;

    @InjectMocks
    private ReportingLookupFacade reportingLookupFacade;

    @Test
    void getForumDelegatesToPostgresFacade() {
        ScholardexForumView forum = new ScholardexForumView();
        when(postgresReportingLookupFacade.getForum("f1")).thenReturn(forum);

        ScholardexForumView result = reportingLookupFacade.getForum("f1");

        assertSame(forum, result);
        verify(postgresReportingLookupFacade).getForum("f1");
    }

    @Test
    void forumIndexMembershipChecksDelegateToPostgresFacade() {
        // Regression: the @Primary facade must delegate EVERY membership check, not just isForumInScopus —
        // a missing delegation silently falls through to the interface default (false), so ESCI/AHCI never showed.
        when(postgresReportingLookupFacade.isForumInScopus("f1")).thenReturn(true);
        when(postgresReportingLookupFacade.isForumInEsci("f1", 2026)).thenReturn(true);
        when(postgresReportingLookupFacade.isForumInAhci("f1", 2026)).thenReturn(true);

        org.junit.jupiter.api.Assertions.assertTrue(reportingLookupFacade.isForumInScopus("f1"));
        org.junit.jupiter.api.Assertions.assertTrue(reportingLookupFacade.isForumInEsci("f1", 2026));
        org.junit.jupiter.api.Assertions.assertTrue(reportingLookupFacade.isForumInAhci("f1", 2026));

        verify(postgresReportingLookupFacade).isForumInScopus("f1");
        verify(postgresReportingLookupFacade).isForumInEsci("f1", 2026);
        verify(postgresReportingLookupFacade).isForumInAhci("f1", 2026);
    }

    @Test
    void rankingLookupsDelegateToPostgresFacade() {
        WoSRanking ranking = new WoSRanking();
        CoreConferenceRanking conferenceRanking = new CoreConferenceRanking();
        when(postgresReportingLookupFacade.getRankingsByIssn("1234-5678")).thenReturn(List.of(ranking));
        when(postgresReportingLookupFacade.getConferenceRankings("ICSE")).thenReturn(List.of(conferenceRanking));
        when(postgresReportingLookupFacade.getTopRankings("cat", 2025)).thenReturn(7);

        assertEquals(List.of(ranking), reportingLookupFacade.getRankingsByIssn("1234-5678"));
        assertEquals(List.of(conferenceRanking), reportingLookupFacade.getConferenceRankings("ICSE"));
        assertEquals(7, reportingLookupFacade.getTopRankings("cat", 2025));

        verify(postgresReportingLookupFacade).getRankingsByIssn("1234-5678");
        verify(postgresReportingLookupFacade).getConferenceRankings("ICSE");
        verify(postgresReportingLookupFacade).getTopRankings("cat", 2025);
    }

    @Test
    void universityAuthorsDelegatesToPostgresFacade() {
        when(postgresReportingLookupFacade.getUniversityAuthorIds()).thenReturn(Set.of("a1", "a2"));

        Set<String> result = reportingLookupFacade.getUniversityAuthorIds();

        assertEquals(Set.of("a1", "a2"), result);
        verify(postgresReportingLookupFacade).getUniversityAuthorIds();
    }
}
