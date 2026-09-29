package ro.uvt.pokedex.core.service.openalex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.service.openalex.OpenAlexLanguageBackfillJob.State;
import ro.uvt.pokedex.core.service.openalex.OpenAlexLanguageBackfillService.Result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenAlexLanguageBackfillJobTest {

    @Mock
    private OpenAlexLanguageBackfillService service;

    @Test
    void runsPassAfterPassUntilNothingIsLeft() {
        when(service.backfill(500))
                .thenReturn(new Result(500, 480, 20, 60, 50))
                .thenReturn(new Result(120, 100, 20, 10, 9))
                .thenReturn(new Result(0, 0, 0, 0, 0));
        OpenAlexLanguageBackfillJob job = new OpenAlexLanguageBackfillJob(service);

        job.run(500, 200);

        OpenAlexLanguageBackfillJob.Status status = job.status();
        assertEquals(State.DONE, status.state());
        assertEquals(2, status.passes());
        assertEquals(620, status.works());
        assertEquals(580, status.withLanguage());
        assertEquals(40, status.withoutLanguage());
        assertEquals(70, status.venuesAsked());
        assertEquals(59, status.venuesWithCountry());
        assertNotNull(status.finishedAt());
        assertNull(status.error());
        verify(service, times(3)).backfill(500);
    }

    @Test
    void stopsAtItsPassLimitAndSaysSo() {
        when(service.backfill(100)).thenReturn(new Result(100, 100, 0, 5, 5));
        OpenAlexLanguageBackfillJob job = new OpenAlexLanguageBackfillJob(service);

        job.run(100, 3);

        assertEquals(State.STOPPED_AT_LIMIT, job.status().state());
        assertEquals(3, job.status().passes());
        assertEquals(300, job.status().works());
    }

    @Test
    void aFailureKeepsWhatWasDoneAndNamesTheCause() {
        when(service.backfill(500))
                .thenReturn(new Result(500, 500, 0, 40, 40))
                .thenThrow(new IllegalStateException("OpenAlex away"));
        OpenAlexLanguageBackfillJob job = new OpenAlexLanguageBackfillJob(service);

        job.run(500, 200);

        assertEquals(State.FAILED, job.status().state());
        assertEquals(1, job.status().passes());
        assertEquals(500, job.status().works());
        assertTrue(job.status().error().contains("OpenAlex away"));
    }

    @Test
    void startedFromTheAdminPageItRunsInTheBackground() throws Exception {
        when(service.backfill(500)).thenReturn(new Result(0, 0, 0, 0, 0));
        OpenAlexLanguageBackfillJob job = new OpenAlexLanguageBackfillJob(service);
        assertEquals(State.IDLE, job.status().state());

        assertTrue(job.start(500, 200));

        for (int i = 0; i < 100 && job.status().state() == State.RUNNING; i++) {
            Thread.sleep(20);
        }
        assertEquals(State.DONE, job.status().state());
        job.shutdown();
    }

    @Test
    void aSecondStartWhileItRunsChangesNothing() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(service.backfill(500)).thenAnswer(call -> {
            release.await();
            return new Result(0, 0, 0, 0, 0);
        });
        OpenAlexLanguageBackfillJob job = new OpenAlexLanguageBackfillJob(service);

        assertTrue(job.start(500, 200));
        assertFalse(job.start(500, 200));
        assertEquals(State.RUNNING, job.status().state());

        release.countDown();
        for (int i = 0; i < 100 && job.status().state() == State.RUNNING; i++) {
            Thread.sleep(20);
        }
        assertEquals(State.DONE, job.status().state());
        verify(service, times(1)).backfill(500);
        job.shutdown();
    }
}
