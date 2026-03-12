package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReportingLookupMemoizationTest {

    @Test
    void getOrComputeCachesWithinActiveRefreshScope() {
        ReportingLookupMemoization memoization = new ReportingLookupMemoization();
        AtomicInteger calls = new AtomicInteger(0);

        Integer value = memoization.withRefreshScope(() -> {
            int first = memoization.getOrCompute("mongo", "rankingsByIssn", "1234", () -> calls.incrementAndGet());
            int second = memoization.getOrCompute("mongo", "rankingsByIssn", "1234", () -> calls.incrementAndGet());
            assertEquals(1, first);
            assertEquals(1, second);
            return second;
        });

        assertEquals(1, value);
        assertEquals(1, calls.get());
    }

    @Test
    void getOrComputeDoesNotReuseOutsideScope() {
        ReportingLookupMemoization memoization = new ReportingLookupMemoization();
        AtomicInteger calls = new AtomicInteger(0);

        int first = memoization.getOrCompute("postgres", "topRankings", "2024|ECONOMICS|SCIE", () -> calls.incrementAndGet());
        int second = memoization.getOrCompute("postgres", "topRankings", "2024|ECONOMICS|SCIE", () -> calls.incrementAndGet());

        assertEquals(1, first);
        assertEquals(2, second);
        assertEquals(2, calls.get());
    }

    @Test
    void refreshScopeIsClearedAfterException() {
        ReportingLookupMemoization memoization = new ReportingLookupMemoization();
        AtomicInteger calls = new AtomicInteger(0);

        assertThrows(IllegalStateException.class, () ->
                memoization.withRefreshScope(() -> {
                    memoization.getOrCompute("mongo", "rankingsByIssn", "5678", () -> calls.incrementAndGet());
                    throw new IllegalStateException("boom");
                })
        );

        int outside = memoization.getOrCompute("mongo", "rankingsByIssn", "5678", () -> calls.incrementAndGet());
        assertEquals(2, outside);
        assertEquals(2, calls.get());
    }
}
