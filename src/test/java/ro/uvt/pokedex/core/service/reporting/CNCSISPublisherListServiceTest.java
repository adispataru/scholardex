package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.CNCSISPublisher;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublication;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.repository.reporting.CNCSISPublisherRepository;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.BeforeEach;

@ExtendWith(MockitoExtension.class)
class CNCSISPublisherListServiceTest {

    @Mock
    private CNCSISPublisherRepository publisherRepository;
    @Mock
    private ReportingLookupPort lookupPort;


    @BeforeEach
    void stubMaxAvailableYear() {
        org.mockito.Mockito.lenient().when(lookupPort.maxAvailableYear()).thenReturn(2023);
    }
    @Test
    void bookSubtypeScoresOneWhenPublisherExists() {
        CNCSISPublisherListService service = new CNCSISPublisherListService(publisherRepository, lookupPort);
        when(lookupPort.getForum("forum-1")).thenReturn(forumWithPublisher("Polirom"));
        when(publisherRepository.findAllByNameIgnoreCase("Polirom")).thenReturn(List.of(new CNCSISPublisher()));

        Score score = service.getScore(publication("bk"), indicator());

        assertEquals(1.0, score.getScore());
    }

    @Test
    void chapterSubtypeUsesScopusSubtypeAndHalvesScore() {
        CNCSISPublisherListService service = new CNCSISPublisherListService(publisherRepository, lookupPort);
        when(lookupPort.getForum("forum-1")).thenReturn(forumWithPublisher("Polirom"));
        when(publisherRepository.findAllByNameIgnoreCase("Polirom")).thenReturn(List.of(new CNCSISPublisher()));

        Score score = service.getScore(publicationWithScopusSubtype("bk", "ch"), indicator());

        assertEquals(0.5, score.getScore());
    }

    @Test
    void activityPathUsesCacheForRepeatedPublisherLookup() {
        CNCSISPublisherListService service = new CNCSISPublisherListService(publisherRepository, lookupPort);
        when(publisherRepository.findAllByNameIgnoreCase("Humanitas")).thenReturn(List.of(new CNCSISPublisher()));

        ActivityInstance activity = new ActivityInstance();
        activity.setDate("2023-09-01");
        activity.setReferenceFields(Map.of(Activity.ReferenceField.FORUM_PUBLISHER, "Humanitas"));

        Score first = service.getScore(activity, indicator());
        Score second = service.getScore(activity, indicator());

        assertEquals(1.0, first.getScore());
        assertEquals(1.0, second.getScore());
        verify(publisherRepository, times(1)).findAllByNameIgnoreCase("Humanitas");
    }

    private Indicator indicator() {
        Domain domain = new Domain();
        domain.setName("ALL");
        Indicator indicator = new Indicator();
        indicator.setDomain(domain);
        indicator.setScoreYearRange("IY");
        return indicator;
    }

    private ScoringPublication publication(String subtype) {
        return publicationWithScopusSubtype(subtype, null);
    }

    private ScoringPublication publicationWithScopusSubtype(String subtype, String scopusSubtype) {
        return new ScoringPublication(
                "pub-1",
                "eid-1",
                "forum-1",
                "2023-05-01",
                subtype,
                scopusSubtype,
                List.of("a1"),
                1,
                "10.1/x",
                null,
                "Book Title",
                0,
                java.util.Set.of()
        );
    }

    private ScholardexForumView forumWithPublisher(String publisher) {
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublisher(publisher);
        return forum;
    }
}
