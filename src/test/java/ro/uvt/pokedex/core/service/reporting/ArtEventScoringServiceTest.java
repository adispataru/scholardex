package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.ArtisticEvent;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.repository.ArtisticEventRepository;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArtEventScoringServiceTest {

    @Mock
    private ArtisticEventRepository artisticEventRepository;
    @Mock
    private ReportingLookupPort lookupPort;

    @Test
    void activityEventNameMapsToInternationalTopScore() {
        ArtEventScoringService service = new ArtEventScoringService(artisticEventRepository, lookupPort);
        when(artisticEventRepository.findAllByNameIgnoreCase("Biennale")).thenReturn(List.of(event(ArtisticEvent.Rank.INTERNATIONAL_TOP)));

        Score score = service.getScore(activityWithEvent("Biennale"), indicator());

        assertEquals(3.0, score.getScore());
    }

    @Test
    void activityWithNoMatchingEventScoresZero() {
        ArtEventScoringService service = new ArtEventScoringService(artisticEventRepository, lookupPort);
        when(artisticEventRepository.findAllByNameIgnoreCase("Unknown Event")).thenReturn(List.of());

        Score score = service.getScore(activityWithEvent("Unknown Event"), indicator());

        assertEquals(0.0, score.getScore());
    }

    @Test
    void publicationPathIsExplicitlyNoScoring() {
        ArtEventScoringService service = new ArtEventScoringService(artisticEventRepository, lookupPort);

        Score score = service.getScore(
                new ro.uvt.pokedex.core.model.reporting.ScoringPublication(
                        "p1", "e1", "f1", "2024-01-01", "ar", null, List.of(), 0, null, null, "title", 0, java.util.Set.of()
                ),
                indicator()
        );

        assertEquals(0.0, score.getScore());
    }

    private ActivityInstance activityWithEvent(String eventName) {
        ActivityInstance activity = new ActivityInstance();
        activity.setDate("2024-06-01");
        activity.setReferenceFields(Map.of(Activity.ReferenceField.EVENT_NAME, eventName));
        return activity;
    }

    private Indicator indicator() {
        Domain domain = new Domain();
        domain.setName("ARTS");
        Indicator indicator = new Indicator();
        indicator.setDomain(domain);
        indicator.setScoreYearRange("IY");
        return indicator;
    }

    private ArtisticEvent event(ArtisticEvent.Rank rank) {
        ArtisticEvent event = new ArtisticEvent();
        event.setRank(rank);
        return event;
    }
}
