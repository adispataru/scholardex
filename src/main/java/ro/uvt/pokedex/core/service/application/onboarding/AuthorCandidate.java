package ro.uvt.pokedex.core.service.application.onboarding;

import java.util.List;

/**
 * A canonical Scholardex author record offered to the researcher in the H70 author-match step, with the
 * confidence signals that make the "is this me?" call obvious: how many publications hang off it and the
 * affiliations it carries (names, not author names — names have artefacts).
 *
 * @param authorId         canonical author id
 * @param name             display name on the canonical record
 * @param publicationCount publications attributed to this author (the strongest "is this me" signal)
 * @param affiliations     up to a few affiliation labels ("Name (City, Country)") observed on this author
 * @param alreadyConfirmed already in the researcher's confirmed set
 */
public record AuthorCandidate(
        String authorId,
        String name,
        int publicationCount,
        List<String> affiliations,
        boolean alreadyConfirmed) {
}
