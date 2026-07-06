package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import ro.uvt.pokedex.core.model.reporting.ReportingDataEpoch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportingDataEpochServiceTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @Test
    void currentEpochIsZeroWhenSingletonMissing() {
        ReportingDataEpochService service = new ReportingDataEpochService(mongoTemplate, new ReportingLookupMemoization());
        when(mongoTemplate.findById(ReportingDataEpoch.SINGLETON_ID, ReportingDataEpoch.class)).thenReturn(null);

        assertEquals(0L, service.currentEpoch());
    }

    @Test
    void currentEpochReadsTheSingleton() {
        ReportingDataEpochService service = new ReportingDataEpochService(mongoTemplate, new ReportingLookupMemoization());
        ReportingDataEpoch doc = new ReportingDataEpoch();
        doc.setEpoch(7L);
        when(mongoTemplate.findById(ReportingDataEpoch.SINGLETON_ID, ReportingDataEpoch.class)).thenReturn(doc);

        assertEquals(7L, service.currentEpoch());
    }

    @Test
    void currentEpochIsMemoizedWithinARefreshScope() {
        ReportingLookupMemoization memoization = new ReportingLookupMemoization();
        ReportingDataEpochService service = new ReportingDataEpochService(mongoTemplate, memoization);
        ReportingDataEpoch doc = new ReportingDataEpoch();
        doc.setEpoch(3L);
        when(mongoTemplate.findById(ReportingDataEpoch.SINGLETON_ID, ReportingDataEpoch.class)).thenReturn(doc);

        memoization.withRefreshScope(() -> {
            assertEquals(3L, service.currentEpoch());
            assertEquals(3L, service.currentEpoch());
        });

        verify(mongoTemplate, times(1)).findById(ReportingDataEpoch.SINGLETON_ID, ReportingDataEpoch.class);
    }

    @Test
    void bumpUpsertsAndIncrementsTheSingleton() {
        ReportingDataEpochService service = new ReportingDataEpochService(mongoTemplate, new ReportingLookupMemoization());
        ReportingDataEpoch bumped = new ReportingDataEpoch();
        bumped.setEpoch(4L);
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                eq(ReportingDataEpoch.class))).thenReturn(bumped);

        assertEquals(4L, service.bump("test-rebuild"));
    }
}
