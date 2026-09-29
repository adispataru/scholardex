package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.PrincipalAuthorDeclaration;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.repository.scopus.canonical.PrincipalAuthorDeclarationRepository;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Brings the APPROVED declarations of a researcher into the publications they are scored on. A declared
 * publication is handed on as a copy that lists one of the researcher's author ids among its corresponding
 * authors, which is exactly what the author-role filter of the reports reads — so every report that tells
 * principal authors from co-authors follows, and nothing stored is changed.
 *
 * <p>Reads only. The commands are in {@link PrincipalAuthorDeclarationService}; they are kept apart because
 * the read side is used by the service that loads a researcher's publications, which the commands need too.</p>
 */
@Service
@RequiredArgsConstructor
public class PrincipalAuthorDeclarationReadService {

    private static final Pattern YEAR = Pattern.compile("(\\d{4})");

    private final PrincipalAuthorDeclarationRepository repository;

    public List<PrincipalAuthorDeclaration> approvedFor(String userEmail) {
        if (userEmail == null || userEmail.isBlank()) {
            return List.of();
        }
        return repository.findByUserEmailAndStatus(userEmail, PrincipalAuthorDeclaration.Status.APPROVED);
    }

    /**
     * @param researcherAuthorIds the canonical author ids the researcher is known by
     * @return the same publications, the declared ones replaced by copies that name the researcher as a
     *         corresponding author; the list itself when nothing applies
     */
    public List<ScholardexPublicationView> applyApproved(List<PrincipalAuthorDeclaration> approved,
                                                         List<ScholardexPublicationView> publications,
                                                         Collection<String> researcherAuthorIds) {
        if (approved == null || approved.isEmpty() || publications == null || publications.isEmpty()
                || researcherAuthorIds == null || researcherAuthorIds.isEmpty()) {
            return publications;
        }
        List<ScholardexPublicationView> result = new ArrayList<>(publications.size());
        boolean changed = false;
        for (ScholardexPublicationView publication : publications) {
            if (publication != null && approved.stream().anyMatch(declaration -> isAbout(declaration, publication))) {
                result.add(asPrincipal(publication, researcherAuthorIds));
                changed = true;
            } else {
                result.add(publication);
            }
        }
        return changed ? result : publications;
    }

    /** True when the declaration speaks of this publication: by id, else by DOI, else by title and year. */
    public static boolean isAbout(PrincipalAuthorDeclaration declaration, ScholardexPublicationView publication) {
        if (declaration == null || publication == null) {
            return false;
        }
        if (declaration.getPublicationId() != null && declaration.getPublicationId().equals(publication.getId())) {
            return true;
        }
        String doi = firstNonBlank(publication.getDoiNormalized(),
                ScholardexPublicationCanonicalizationService.normalizeDoi(publication.getDoi()));
        if (declaration.getDoiNormalized() != null && !declaration.getDoiNormalized().isBlank()) {
            // A declaration made on a publication with a DOI never moves to one with another DOI.
            return declaration.getDoiNormalized().equals(doi);
        }
        String title = ScholardexPublicationCanonicalizationService.normalizeTitle(publication.getTitle());
        return declaration.getTitleNormalized() != null && !declaration.getTitleNormalized().isBlank()
                && declaration.getTitleNormalized().equals(title)
                && Objects.equals(declaration.getYear(), yearOf(publication).orElse(null));
    }

    public static Optional<Integer> yearOf(ScholardexPublicationView publication) {
        if (publication == null || publication.getCoverDate() == null) {
            return Optional.empty();
        }
        Matcher matcher = YEAR.matcher(publication.getCoverDate());
        return matcher.find() ? Optional.of(Integer.parseInt(matcher.group(1))) : Optional.empty();
    }

    private static ScholardexPublicationView asPrincipal(ScholardexPublicationView publication,
                                                        Collection<String> researcherAuthorIds) {
        ScholardexPublicationView copy = new ScholardexPublicationView();
        BeanUtils.copyProperties(publication, copy);
        Set<String> corresponding = new LinkedHashSet<>(
                publication.getCorrespondingAuthorIds() == null ? List.of() : publication.getCorrespondingAuthorIds());
        // The id the publication itself knows the researcher by, when there is one.
        String own = researcherAuthorIds.stream()
                .filter(Objects::nonNull)
                .filter(id -> publication.getAuthorIds() != null && publication.getAuthorIds().contains(id))
                .findFirst()
                .orElseGet(() -> researcherAuthorIds.stream().filter(Objects::nonNull).findFirst().orElse(null));
        if (own != null) {
            corresponding.add(own);
        }
        copy.setCorrespondingAuthorIds(new ArrayList<>(corresponding));
        return copy;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
