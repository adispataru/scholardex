package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat;
import ro.uvt.pokedex.core.service.reporting.transfer.ReportExportFacade;
import ro.uvt.pokedex.core.service.reporting.transfer.ReportExportReadinessValidator;
import ro.uvt.pokedex.core.service.reporting.transfer.ReportImportRegistry;
import ro.uvt.pokedex.core.service.reporting.transfer.ReportImportVerificationFacade;
import ro.uvt.pokedex.core.service.reporting.transfer.compare.ReportScoreComparison;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportTransferFacadeTest {

    @Mock private ReportExportFacade reportExportFacade;
    @Mock private ReportImportRegistry reportImportRegistry;
    @Mock private ReportExportReadinessValidator reportExportReadinessValidator;
    @Mock private ReportImportVerificationFacade reportImportVerificationFacade;
    @InjectMocks private ReportTransferFacade facade;

    @Test
    void exportFailureReasonEnumsStayInLockstepWithTheZ3Enum() {
        // The facade maps by name — a Z3 value missing here would be a runtime IllegalArgumentException.
        for (ReportExportFacade.ExportFailureReason z3 : ReportExportFacade.ExportFailureReason.values()) {
            assertEquals(z3.name(), ReportTransferFacade.ExportFailureReason.valueOf(z3.name()).name());
        }
        assertEquals(ReportExportFacade.ExportFailureReason.values().length,
                ReportTransferFacade.ExportFailureReason.values().length);
    }

    @Test
    void verificationFailureReasonEnumsStayInLockstepWithTheZ3Enum() {
        for (ReportImportVerificationFacade.VerificationFailureReason z3
                : ReportImportVerificationFacade.VerificationFailureReason.values()) {
            assertEquals(z3.name(), ReportTransferFacade.VerificationFailureReason.valueOf(z3.name()).name());
        }
        assertEquals(ReportImportVerificationFacade.VerificationFailureReason.values().length,
                ReportTransferFacade.VerificationFailureReason.values().length);
    }

    @Test
    void verifyRunExtractsOrderedDistinctReferencedActivityIds() {
        var optA = new ReportScoreComparison.ActivityOption("act-a", "A");
        var optB = new ReportScoreComparison.ActivityOption("act-b", "B");
        var optADup = new ReportScoreComparison.ActivityOption("act-a", "A again");
        var block1 = new ReportScoreComparison.ActivityBlockComparison(
                "Block 1", 0.0, 0.0, 0.0, ReportScoreComparison.Status.MATCH,
                List.of(), List.of(), List.of(), List.of(optA, optB));
        var block2 = new ReportScoreComparison.ActivityBlockComparison(
                "Block 2", 0.0, 0.0, 0.0, ReportScoreComparison.Status.MATCH,
                List.of(), List.of(), List.of(), List.of(optADup));
        var comparison = new ReportScoreComparison(List.of(), List.of(block1, block2), 0, 0, 0, 0, 0);
        when(reportImportVerificationFacade.verifyOutcome(any(), any(), any(), any(), any()))
                .thenReturn(ReportImportVerificationFacade.VerificationOutcome.success(
                        new ReportImportVerificationFacade.VerificationResult(
                                comparison, null, "run-1", null, List.of())));

        var outcome = facade.verifyRun("u@uvt.ro", "rep-1", "run-1", ReportFormat.XLSX,
                new ByteArrayInputStream(new byte[0]));

        assertTrue(outcome.isSuccess());
        assertEquals(List.of("act-a", "act-b"), outcome.view().referencedActivityIds());
        assertEquals("run-1", outcome.view().displayedRunId());
    }
}
