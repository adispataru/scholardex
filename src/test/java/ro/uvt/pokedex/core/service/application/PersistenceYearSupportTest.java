package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceYearSupportTest {

    private static final Logger LOG = LoggerFactory.getLogger(PersistenceYearSupportTest.class);

    @Test
    void extractYearReturnsYearForFullDate() {
        Optional<Integer> year = PersistenceYearSupport.extractYear("2024-05-10", "p1", LOG);
        assertEquals(Optional.of(2024), year);
    }

    @Test
    void extractYearReturnsYearForYearOnly() {
        Optional<Integer> year = PersistenceYearSupport.extractYear("2024", "p1", LOG);
        assertEquals(Optional.of(2024), year);
    }

    @Test
    void extractYearReturnsEmptyForNullBlankAndInvalid() {
        assertTrue(PersistenceYearSupport.extractYear(null, "p1", LOG).isEmpty());
        assertTrue(PersistenceYearSupport.extractYear("", "p1", LOG).isEmpty());
        assertTrue(PersistenceYearSupport.extractYear("20AB-??", "p1", LOG).isEmpty());
    }
}
