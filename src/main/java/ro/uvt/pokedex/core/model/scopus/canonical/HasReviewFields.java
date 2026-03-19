package ro.uvt.pokedex.core.model.scopus.canonical;

import java.time.Instant;

public interface HasReviewFields {
    void setWizardSubmittedAt(Instant wizardSubmittedAt);
    void setWizardSubmitterEmail(String wizardSubmitterEmail);
    void setWizardSubmitterResearcherId(String wizardSubmitterResearcherId);
    void setReviewState(String reviewState);
    void setReviewReason(String reviewReason);
    void setReviewStateUpdatedAt(Instant reviewStateUpdatedAt);
    void setReviewStateUpdatedBy(String reviewStateUpdatedBy);
    void setModerationFlow(String moderationFlow);
}
