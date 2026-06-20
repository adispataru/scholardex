package ro.uvt.pokedex.core.service.application.onboarding;

import java.util.List;

/**
 * H70 step-5 publication auto-claim recommendations. A candidate publication is recommended for confirmation
 * iff it is attributed to one of the researcher's confirmed author records AND shares an affiliation with their
 * confirmed affiliation set (author-id + affiliation; names are ignored — they carry artefacts). Everything
 * else is left for review (never auto-denied).
 *
 * @param recommendedConfirmIds publication ids recommended for bulk confirmation
 * @param reviewCount           candidates not recommended (left for the claim tool)
 * @param alreadyDecidedCount   candidates the researcher already confirmed/rejected
 * @param sampleTitles          a few titles of the recommended set, for the UI
 */
public record ClaimRecommendations(
        List<String> recommendedConfirmIds,
        int reviewCount,
        int alreadyDecidedCount,
        List<String> sampleTitles) {

    public static ClaimRecommendations empty() {
        return new ClaimRecommendations(List.of(), 0, 0, List.of());
    }

    public int recommendedConfirmCount() {
        return recommendedConfirmIds.size();
    }
}
