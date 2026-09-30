package ro.uvt.pokedex.core.service.application.model;

/** H129 — one edition of the CNFIS reporting, as the CNFIS page shows it. */
public record CnfisEditionViewModel(int reportingYear, int windowStart, int windowEnd, int lastListYear,
                                    boolean provisional) {
}
