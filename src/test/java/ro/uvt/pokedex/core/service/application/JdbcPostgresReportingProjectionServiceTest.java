package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcPostgresReportingProjectionServiceTest {

    @Test
    void shouldRebuildSliceReturnsTrueForFullRebuild() {
        assertTrue(JdbcPostgresReportingProjectionService.shouldRebuildSlice(true, "abc", "abc"));
    }

    @Test
    void shouldRebuildSliceReturnsFalseWhenFingerprintsMatchInIncrementalMode() {
        assertFalse(JdbcPostgresReportingProjectionService.shouldRebuildSlice(false, "abc", "abc"));
    }

    @Test
    void shouldRebuildSliceReturnsTrueWhenFingerprintsDifferInIncrementalMode() {
        assertTrue(JdbcPostgresReportingProjectionService.shouldRebuildSlice(false, "abc", "def"));
    }
}
