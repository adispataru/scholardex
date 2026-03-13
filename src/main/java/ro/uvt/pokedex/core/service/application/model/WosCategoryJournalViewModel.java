package ro.uvt.pokedex.core.service.application.model;

public record WosCategoryJournalViewModel(
        String journalId,
        String journalName,
        String issn,
        String eIssn,
        Integer latestYear,
        String latestAisQuarter,
        String latestRisQuarter,
        String latestIfQuarter
) {
}
