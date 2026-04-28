package ro.uvt.pokedex.core.service.scopus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SchedulerCorrelationSupportTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void withSchedulerContextSetsAndRestoresMdcValues() throws Exception {
        assertNull(MDC.get("jobType"));
        assertNull(MDC.get("taskId"));
        assertNull(MDC.get("phase"));

        AutoCloseable scope = SchedulerCorrelationSupport.withSchedulerContext("job-a", "task-1", "start");
        assertEquals("job-a", MDC.get("jobType"));
        assertEquals("task-1", MDC.get("taskId"));
        assertEquals("start", MDC.get("phase"));

        scope.close();
        assertNull(MDC.get("jobType"));
        assertNull(MDC.get("taskId"));
        assertNull(MDC.get("phase"));
    }

    @Test
    void nestedContextRestoresParentValues() throws Exception {
        AutoCloseable outer = SchedulerCorrelationSupport.withSchedulerContext("job-outer", "task-outer", "progress");
        assertEquals("job-outer", MDC.get("jobType"));
        assertEquals("task-outer", MDC.get("taskId"));
        assertEquals("progress", MDC.get("phase"));

        AutoCloseable inner = SchedulerCorrelationSupport.withSchedulerContext("job-inner", "task-inner", "failed");
        assertEquals("job-inner", MDC.get("jobType"));
        assertEquals("task-inner", MDC.get("taskId"));
        assertEquals("failed", MDC.get("phase"));

        inner.close();
        assertEquals("job-outer", MDC.get("jobType"));
        assertEquals("task-outer", MDC.get("taskId"));
        assertEquals("progress", MDC.get("phase"));

        outer.close();
        assertNull(MDC.get("jobType"));
        assertNull(MDC.get("taskId"));
        assertNull(MDC.get("phase"));
    }

    @Test
    void contextNormalizesNullBlankAndWhitespaceValues() throws Exception {
        AutoCloseable scope = SchedulerCorrelationSupport.withSchedulerContext(null, "  ", " complete ");

        assertEquals("unknown", MDC.get("jobType"));
        assertEquals("unknown", MDC.get("taskId"));
        assertEquals("complete", MDC.get("phase"));

        scope.close();
        assertNull(MDC.get("jobType"));
        assertNull(MDC.get("taskId"));
        assertNull(MDC.get("phase"));
    }

    @Test
    void contextRestoresExistingValues() throws Exception {
        MDC.put("jobType", "existing-job");
        MDC.put("taskId", "existing-task");
        MDC.put("phase", "existing-phase");

        AutoCloseable scope = SchedulerCorrelationSupport.withSchedulerContext("new-job", "new-task", "new-phase");
        assertEquals("new-job", MDC.get("jobType"));
        assertEquals("new-task", MDC.get("taskId"));
        assertEquals("new-phase", MDC.get("phase"));

        scope.close();
        assertEquals("existing-job", MDC.get("jobType"));
        assertEquals("existing-task", MDC.get("taskId"));
        assertEquals("existing-phase", MDC.get("phase"));
    }
}
