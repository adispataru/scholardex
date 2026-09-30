package ro.uvt.pokedex.core.service.application.model;

import ro.uvt.pokedex.core.model.reporting.cnfis.CnfisSheetHeader;
import ro.uvt.pokedex.core.model.reporting.cnfis.CnfisSheetSnapshot;
import ro.uvt.pokedex.core.service.reporting.CnfisDomainCatalog.CnfisDomain;

import java.util.List;

/** H129 — the CNFIS page of one edition: the head of the sheet, the rows, what was left out, the copies. */
public record CnfisSheetViewModel(
        CnfisEditionViewModel edition,
        CnfisSheetHeader header,
        List<CnfisDomain> domains,
        List<ReportChoice> cnatdcuReports,
        Double cnatdcuScore,
        List<Row> rows,
        List<LeftOut> leftOut,
        List<Patent> patents,
        Arts arts,
        List<String> staffRecordMissing,
        List<Snapshot> snapshots,
        Counts counts
) {
    public record ReportChoice(String id, String title) {
    }

    public record Row(String publicationId, String year, String title, String venue, String doi, String wosCode,
                      String category, String classifiedBy, Integer listYear, int authorCount, int universityAuthorCount) {
    }

    public record LeftOut(String publicationId, String year, String title, String venue, String doi, String reason) {
    }

    public record Patent(String activityInstanceId, String year, String title, String code, String office, String type,
                         int authorCount, int universityAuthorCount) {
    }

    /** Anexa 5.1: shown to a person of an artistic domain (and to anyone who declared performances). */
    public record Arts(boolean applies, List<ArtsRow> rows, List<LeftOut> leftOut) {
    }

    public record ArtsRow(String activityInstanceId, String year, String work, String event, String level, String kind,
                          int universityParticipants) {
    }

    public record Snapshot(String id, String createdAt, int rows, int patents, boolean locked, boolean provisional) {
    }

    /** The totals of the form's classification columns, in its order. */
    public record Counts(int q1, int q2, int q3, int q4, int artsHumanities, int esci, int erihPlus,
                         int isiProceedings, int ieeeProceedings, int patents) {
    }

    /** The one-word category of a row, as the form's columns name it. */
    public static String category(ro.uvt.pokedex.core.model.reporting.CNFISReport2025 r) {
        if (r.isIsiQ1()) return "ISI Q1";
        if (r.isIsiQ2()) return "ISI Q2";
        if (r.isIsiQ3()) return "ISI Q3";
        if (r.isIsiQ4()) return "ISI Q4";
        if (r.isIsiArtsHumanities()) return "ISI Arts & Humanities";
        if (r.isIsiEmergingSourcesCitationIndex()) return "ISI Emerging Sources";
        if (r.isErihPlus()) return "ERIH+";
        if (r.isIsiProceedings()) return "ISI Proceedings";
        if (r.isIeeeProceedings()) return "IEEE Proceedings";
        return "";
    }
}
