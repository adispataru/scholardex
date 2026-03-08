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

    // For newly created forums across multi-step wizard redirects.
    private String wizardForumPublicationName;
    private String wizardForumIssn;
    private String wizardForumEIssn;
    private String wizardForumIsbn;
    private String wizardForumAggregationType;
    private String wizardForumPublisher;
}
