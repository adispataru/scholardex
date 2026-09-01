package ro.uvt.pokedex.core.service.application.model;

import lombok.Data;

import java.util.List;

@Data
public class WizardPublicationCommand {
    private String title;
    private String doi;
    private String creator;
    private String subtypeDescription;
    private String subtype;
    private String coverDate;
    private String volume;
    private String issueIdentifier;
    private String forum;
    private String authorIdsCsv;
    private List<String> authorIds;

    // H99 item 7 — book/chapter path: the selected book_facts id, or draft fields for a book absent
    // from the Scopus book list (Mirton/Eubeea). Books are entities, not forums (H66B M7).
    private String bookId;
    private String wizardBookTitle;
    private String wizardBookIsbn;
    private String wizardBookPublisher;
    private String pageRange;
    // Free-text co-authors NOT in the canonical graph (one per line / comma-separated). Never minted as
    // authors — they flow into author_names and count toward author_count so the scoring divisor stays
    // honest (the H99 item 9 lesson: author_count is bibliographic truth, resolved ids are not).
    private String externalAuthorNames;

    // For newly created forums across multi-step wizard redirects.
    private String wizardForumPublicationName;
    private String wizardForumIssn;
    private String wizardForumEIssn;
    private String wizardForumIsbn;
    private String wizardForumAggregationType;
    private String wizardForumPublisher;
}
