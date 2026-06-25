package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.org.DepartmentAffiliation;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.repository.org.DepartmentAffiliationRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.utils.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * H77 slice 1: resolve the people on a department's staff roster ({@code department_affiliations}, keyed by UVT email)
 * to canonical Scholardex authors <b>by name</b> — the bridge for provisional, admin-run scoring of researchers who
 * have not validated their canonical author. The matched canonical author already carries the Scopus author ids and
 * publications from the import; this just joins the org roster to it.
 *
 * <p>Matching is diacritic-insensitive on the given+surname ({@link StringUtils#normalize}) against the author's
 * {@code alternativeNames}; homonyms are disambiguated by preferring the candidate(s) with Scopus author ids, and a
 * still-ambiguous person is flagged rather than guessed. Candidate fetch is via the projection's name search, which
 * is diacritic-<i>sensitive</i> on the surname — so a surname carrying diacritics in the corpus but not in the email
 * (e.g. Caşu vs casu) currently resolves to {@code UNRESOLVED} (documented limitation; revisit with an unaccented
 * name index).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProvisionalAuthorResolutionService {

    private static final Pattern EMAIL_SPLIT = Pattern.compile("[._-]");

    private final DepartmentAffiliationRepository departmentAffiliationRepository;
    private final ScholardexProjectionReadService projectionReadService;
    private final ScholardexAuthorFactRepository authorFactRepository;

    public enum Status { RESOLVED, AMBIGUOUS, UNRESOLVED }

    /** The resolution outcome for one roster person. */
    public record ProvisionalAuthorMatch(
            String email,
            String firstName,
            String lastName,
            Status status,
            String canonicalAuthorId,
            List<String> candidateIds,
            List<String> scopusAuthorIds
    ) {
    }

    /** A canonical-author candidate, reduced to what the resolver needs (pure-function input). */
    public record CandidateAuthor(String id, List<String> alternativeNames, List<String> scopusAuthorIds) {
    }

    /** Resolve every (currently-valid) affiliation of a department to a canonical author. */
    public List<ProvisionalAuthorMatch> resolveDepartment(String departmentId) {
        List<DepartmentAffiliation> affiliations =
                departmentAffiliationRepository.findByDepartmentIdAndValidToIsNull(departmentId);
        List<ProvisionalAuthorMatch> out = new ArrayList<>(affiliations.size());
        for (DepartmentAffiliation affiliation : affiliations) {
            out.add(resolvePerson(affiliation.getUserId()));
        }
        return out;
    }

    /** Resolve one person (by UVT email) to a canonical author. */
    public ProvisionalAuthorMatch resolvePerson(String email) {
        String[] names = nameFromEmail(email);
        if (names == null) {
            return new ProvisionalAuthorMatch(email, null, null, Status.UNRESOLVED, null, List.of(), List.of());
        }
        String first = names[0];
        String last = names[1];

        List<ScholardexAuthorView> views = projectionReadService.findAuthorsByNameContainsIgnoreCase(last);
        List<CandidateAuthor> candidates = enrichWithScopus(views);
        return resolve(email, first, last, candidates);
    }

    /** Attach each candidate's Scopus author ids (from the author fact) — the view does not carry them. */
    private List<CandidateAuthor> enrichWithScopus(List<ScholardexAuthorView> views) {
        List<String> ids = views.stream().map(ScholardexAuthorView::getId).filter(java.util.Objects::nonNull).toList();
        Map<String, List<String>> scopusById = ids.isEmpty()
                ? Map.of()
                : authorFactRepository.findAllById(ids).stream()
                        .filter(f -> f.getId() != null)
                        .collect(Collectors.toMap(ScholardexAuthorFact::getId,
                                f -> f.getScopusAuthorIds() == null ? List.of() : f.getScopusAuthorIds(),
                                (a, b) -> a));
        return views.stream()
                .map(v -> new CandidateAuthor(v.getId(),
                        v.getAlternativeNames() == null ? List.of() : v.getAlternativeNames(),
                        scopusById.getOrDefault(v.getId(), List.of())))
                .toList();
    }

    /**
     * Pure resolver: pick the canonical author whose normalized {@code alternativeNames} contain both the given and
     * surname; disambiguate homonyms by preferring those with Scopus ids; flag if still ambiguous.
     */
    static ProvisionalAuthorMatch resolve(String email, String first, String last, List<CandidateAuthor> candidates) {
        String nf = StringUtils.normalize(first);
        String nl = StringUtils.normalize(last);
        List<CandidateAuthor> matched = candidates.stream()
                .filter(c -> c.alternativeNames().stream().anyMatch(an -> {
                    String n = StringUtils.normalize(an);
                    return containsWord(n, nf) && containsWord(n, nl);
                }))
                .toList();

        if (matched.isEmpty()) {
            return new ProvisionalAuthorMatch(email, first, last, Status.UNRESOLVED, null, List.of(), List.of());
        }
        if (matched.size() == 1) {
            return resolved(email, first, last, matched.get(0));
        }
        // Homonyms: prefer the linked (Scopus-bearing) author; exactly one → resolved, else flag ambiguous.
        List<CandidateAuthor> withScopus = matched.stream().filter(c -> !c.scopusAuthorIds().isEmpty()).toList();
        if (withScopus.size() == 1) {
            return resolved(email, first, last, withScopus.get(0));
        }
        List<CandidateAuthor> pool = withScopus.isEmpty() ? matched : withScopus;
        return new ProvisionalAuthorMatch(email, first, last, Status.AMBIGUOUS, null,
                pool.stream().map(CandidateAuthor::id).toList(), List.of());
    }

    private static ProvisionalAuthorMatch resolved(String email, String first, String last, CandidateAuthor c) {
        return new ProvisionalAuthorMatch(email, first, last, Status.RESOLVED, c.id(),
                List.of(c.id()), c.scopusAuthorIds());
    }

    /** Whether the normalized name contains {@code word} as a whole token. */
    private static boolean containsWord(String normalizedName, String word) {
        if (word == null || word.isBlank()) {
            return false;
        }
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(normalizedName).find();
    }

    /** {@code first.last@e-uvt.ro} → {first, last}; null if the local part has fewer than two tokens. */
    static String[] nameFromEmail(String email) {
        if (email == null || !email.contains("@")) {
            return null;
        }
        String local = email.substring(0, email.indexOf('@'));
        List<String> parts = java.util.Arrays.stream(EMAIL_SPLIT.split(local))
                .filter(p -> !p.isBlank())
                .toList();
        if (parts.size() < 2) {
            return null;
        }
        return new String[]{parts.get(0), parts.get(parts.size() - 1)};
    }
}
