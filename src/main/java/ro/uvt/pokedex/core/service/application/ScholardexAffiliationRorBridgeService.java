package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationAuthorAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationAuthorAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationService;
import ro.uvt.pokedex.core.service.openalex.OpenAlexAuthorResolver;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * H72 slice 3b — bridge OpenAlex ROR ids onto verified Scopus affiliations. ROR is the cross-source affiliation
 * key Scopus lacks (its institution names fragment across language, so they can't be matched directly). On a
 * DOI-linked pub the OpenAlex work and the Scopus pub describe the same paper, and the two sources agree on author
 * order, so author {@code i} is the same person on each side — and that person's Scopus affiliation on the paper is
 * the same real institution as their OpenAlex authorship's ROR.
 *
 * <p>Conservative, only tags when unambiguous: equal author count (positions trustable), per-position surname match
 * (catches reorders / "Last, First" vs "First Last"), the OpenAlex authorship has exactly one ROR, and the Scopus
 * author has exactly one affiliation on that pub. Idempotent (skips RORs already present). The tagged
 * {@code rorIds} make OpenAlex/ROR resolve onto a single verified affiliation later, instead of fighting the
 * cross-language Scopus names.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScholardexAffiliationRorBridgeService {

    private static final String SOURCE_OPENALEX = "OPENALEX";

    private final OpenAlexPublicationFactRepository openAlexPublicationFactRepository;
    private final ScholardexPublicationFactRepository publicationRepository;
    private final ScholardexAuthorFactRepository authorRepository;
    private final ScholardexAffiliationFactRepository affiliationRepository;
    private final ScholardexPublicationAuthorAffiliationFactRepository pubAuthorAffiliationRepository;

    /** Bridge ROR onto verified Scopus affiliations from every DOI-linked OpenAlex work. Returns affiliations tagged. */
    public int bridgeRorsFromOpenAlex(String reason) {
        int processedPubs = 0;
        int tagged = 0;
        for (OpenAlexPublicationFact source : openAlexPublicationFactRepository.findAll()) {
            List<OpenAlexPublicationFact.AuthorRef> authorships = source.getAuthorships();
            if (authorships == null || authorships.isEmpty()) {
                continue;
            }
            String doi = ScholardexPublicationCanonicalizationService.normalizeDoi(source.getDoi());
            if (doi == null) {
                continue;
            }
            List<ScholardexPublicationFact> byDoi = publicationRepository.findAllByDoiNormalized(doi);
            if (byDoi.size() != 1) {
                continue; // no match, or ambiguous shared DOI — don't guess
            }
            ScholardexPublicationFact pub = byDoi.getFirst();
            if (SOURCE_OPENALEX.equals(pub.getSource())) {
                continue; // OpenAlex-owned pub has no Scopus affiliations to bridge onto
            }
            List<String> authorIds = pub.getAuthorIds();
            if (authorIds == null || authorIds.isEmpty() || authorIds.size() != authorships.size()) {
                continue; // count guard — positions can't be trusted when the lists differ
            }
            processedPubs++;
            tagged += bridgePub(pub, authorIds, authorships);
        }
        log.info("Affiliation ROR bridge ({}) complete: doiLinkedPubsProcessed={} affiliationsTagged={}",
                reason, processedPubs, tagged);
        return tagged;
    }

    private int bridgePub(ScholardexPublicationFact pub, List<String> authorIds,
                          List<OpenAlexPublicationFact.AuthorRef> authorships) {
        Map<String, ScholardexAuthorFact> authorsById = authorRepository.findByIdIn(authorIds).stream()
                .collect(Collectors.toMap(ScholardexAuthorFact::getId, Function.identity(), (a, b) -> a));
        int tagged = 0;
        for (int i = 0; i < authorIds.size(); i++) {
            OpenAlexPublicationFact.AuthorRef ref = authorships.get(i);
            if (ref.getInstitutionRors() == null || ref.getInstitutionRors().size() != 1) {
                continue; // exactly-one-ROR guard (can't align which ROR ↔ which affiliation otherwise)
            }
            String ror = ref.getInstitutionRors().getFirst();
            ScholardexAuthorFact author = authorsById.get(authorIds.get(i));
            if (author == null
                    || !OpenAlexAuthorResolver.surnameMatches(author.getDisplayName(), ref.getDisplayName())) {
                continue; // per-position verification failed — don't tag a possibly-misaligned position
            }
            Set<String> affiliationIds = pubAuthorAffiliationRepository
                    .findByPublicationIdAndAuthorIdIn(pub.getId(), List.of(author.getId())).stream()
                    .map(ScholardexPublicationAuthorAffiliationFact::getAffiliationId)
                    .filter(id -> id != null && !id.isBlank())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (affiliationIds.size() != 1) {
                continue; // exactly-one-affiliation guard
            }
            ScholardexAffiliationFact affiliation = affiliationRepository.findById(affiliationIds.iterator().next())
                    .orElse(null);
            if (affiliation == null) {
                continue;
            }
            if (affiliation.getRorIds() == null) {
                affiliation.setRorIds(new java.util.ArrayList<>());
            }
            if (!affiliation.getRorIds().contains(ror)) {
                affiliation.getRorIds().add(ror);
                affiliationRepository.save(affiliation);
                tagged++;
            }
        }
        return tagged;
    }
}
