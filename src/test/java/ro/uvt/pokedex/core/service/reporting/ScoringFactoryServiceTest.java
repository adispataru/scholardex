package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import ro.uvt.pokedex.core.model.reporting.Indicator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class ScoringFactoryServiceTest {

    @Test
    void returnsConfiguredServiceForKnownStrategy() {
        ScoringFactoryService factory = new ScoringFactoryService();
        ComputerScienceScoringService csService = mock(ComputerScienceScoringService.class);

        ReflectionTestUtils.setField(factory, "computerScienceScoringService", csService);

        ScoringService resolved = factory.getScoringService(Indicator.Strategy.CS);

        assertSame(csService, resolved);
    }

    @Test
    void throwsForUnmappedStrategy() {
        ScoringFactoryService factory = new ScoringFactoryService();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getScoringService(Indicator.Strategy.GENERIC_ACTIVITY)
        );

        assertEquals("Unsupported scoring strategy: GENERIC_ACTIVITY", exception.getMessage());
    }

    @Test
    void throwsForNullStrategy() {
        ScoringFactoryService factory = new ScoringFactoryService();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getScoringService(null)
        );

        assertEquals("Scoring strategy cannot be null", exception.getMessage());
    }
}
