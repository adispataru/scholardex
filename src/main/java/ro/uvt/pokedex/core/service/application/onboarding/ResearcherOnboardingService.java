package ro.uvt.pokedex.core.service.application.onboarding;

import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.openalex.OrcidSupport;

import java.util.ArrayList;
import java.util.List;

/**
 * H70 — computes a researcher's onboarding progress purely from their {@link User.ResearcherProfile}: which steps
 * are done, which step the wizard should resume at, and whether onboarding is complete. Pure function of the
 * profile (no I/O) so it is cheap to call on every workspace load and trivially unit-testable.
 *
 * <p>A step's data prerequisite:
 * <ul>
 *   <li>{@code IDENTITY_IDS} — at least one Scopus or WoS author id.</li>
 *   <li>{@code ORCID} — a parseable ORCID on the profile.</li>
 *   <li>{@code AFFILIATIONS} — {@code affiliationsConfirmedAt} set.</li>
 *   <li>{@code AUTHOR_MATCH} — at least one confirmed canonical author id.</li>
 *   <li>{@code PUBLICATION_CLAIM} — {@code onboardingCompletedAt} set (the wizard's terminal finish).</li>
 * </ul>
 */
@Service
public class ResearcherOnboardingService {

    /** The wizard's steps in flow order. */
    private static final OnboardingStep[] STEPS = OnboardingStep.values();

    public OnboardingStatus evaluate(User.ResearcherProfile profile) {
        List<OnboardingStep> completed = new ArrayList<>();
        OnboardingStep next = null;
        for (OnboardingStep step : STEPS) {
            if (isComplete(step, profile)) {
                completed.add(step);
            } else if (next == null) {
                next = step;
            }
        }
        boolean complete = next == null;
        int percent = (int) Math.round(100.0 * completed.size() / STEPS.length);
        return new OnboardingStatus(next, List.copyOf(completed), percent, complete);
    }

    private boolean isComplete(OnboardingStep step, User.ResearcherProfile profile) {
        if (profile == null) {
            return false;
        }
        return switch (step) {
            case IDENTITY_IDS -> notEmpty(profile.getScopusId()) || notEmpty(profile.getWosId());
            case ORCID -> OrcidSupport.normalize(profile.getOrcid()) != null;
            case AFFILIATIONS -> profile.getAffiliationsConfirmedAt() != null;
            case AUTHOR_MATCH -> notEmpty(profile.getConfirmedScholardexAuthorIds());
            case PUBLICATION_CLAIM -> profile.getOnboardingCompletedAt() != null;
        };
    }

    private static boolean notEmpty(List<String> values) {
        if (values == null) {
            return false;
        }
        return values.stream().anyMatch(v -> v != null && !v.isBlank());
    }

    /**
     * @param nextStep      the first incomplete step (where the wizard resumes), or {@code null} when complete
     * @param completedSteps steps whose data prerequisite is satisfied, in flow order
     * @param percentComplete completed steps over total, 0–100
     * @param complete      true when every step is satisfied (onboarding finished)
     */
    public record OnboardingStatus(
            OnboardingStep nextStep,
            List<OnboardingStep> completedSteps,
            int percentComplete,
            boolean complete) {
    }
}
