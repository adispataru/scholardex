package ro.uvt.pokedex.core.service.openalex;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexPublicationFactRepository;
import ro.uvt.pokedex.core.service.openalex.dto.OpenAlexWorksResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * H66B Phase 4a — fetch a researcher's OpenAlex works by ORCID and upsert them into the durable
 * {@code openalex.publication_facts} source table (idempotent on the OpenAlex work id). Appends the syncing
 * researcher's canonical author id so the canonicalization can later attach a self-authorship edge. Returns the
 * touched work ids for the on-demand canonicalization pass.
 */
@Service
@RequiredArgsConstructor
public class OpenAlexImportService {

    private static final Logger log = LoggerFactory.getLogger(OpenAlexImportService.class);
    private static final String SOURCE_OPENALEX = "OPENALEX";
    private static final String OPENALEX_ID_PREFIX = "https://openalex.org/";
    private static final String BUILDER_VERSION = "openalex-source-fact-v1";

    private final OpenAlexClient openAlexClient;
    private final OpenAlexPublicationFactRepository openAlexPublicationFactRepository;

    public List<String> importByOrcid(String orcid, String researcherAuthorId, String batchId, String correlationId) {
        List<OpenAlexWorksResponse.OpenAlexWork> works = openAlexClient.fetchWorksByOrcid(orcid);
        List<String> workIds = new ArrayList<>(works.size());
        for (OpenAlexWorksResponse.OpenAlexWork work : works) {
            String workId = stripPrefix(work.getId(), OPENALEX_ID_PREFIX);
            if (workId == null || workId.isBlank()) {
                continue;
            }
            upsert(work, workId, researcherAuthorId, batchId, correlationId);
            workIds.add(workId);
        }
        log.info("OpenAlex import for ORCID {} upserted {} source facts (batch {})", orcid, workIds.size(), batchId);
        return workIds;
    }

    private void upsert(OpenAlexWorksResponse.OpenAlexWork work, String workId, String researcherAuthorId,
                        String batchId, String correlationId) {
        OpenAlexPublicationFact fact = openAlexPublicationFactRepository.findBySourceRecordId(workId)
                .orElseGet(OpenAlexPublicationFact::new);
        Instant now = Instant.now();
        if (fact.getCreatedAt() == null) {
            fact.setCreatedAt(now);
        }
        fact.setSource(SOURCE_OPENALEX);
        fact.setSourceRecordId(workId);
        fact.setSourceEventId(workId);
        fact.setSourceBatchId(batchId);
        fact.setSourceCorrelationId(correlationId);
        fact.setOpenalexWorkId(workId);
        fact.setDoi(work.getDoi());
        fact.setTitle(firstNonBlank(work.getTitle(), work.getDisplay_name()));
        fact.setPublicationYear(work.getPublication_year());
        fact.setCoverDate(work.getPublication_year() == null ? null : work.getPublication_year() + "-01-01");
        fact.setType(work.getType());
        fact.setCitedByCount(work.getCited_by_count());
        fact.setOpenAccess(work.getOpen_access() == null ? null : work.getOpen_access().getIs_oa());

        List<String> authorNames = new ArrayList<>();
        List<String> authorOrcids = new ArrayList<>();
        List<String> correspondingNames = new ArrayList<>();
        if (work.getAuthorships() != null) {
            for (OpenAlexWorksResponse.Authorship authorship : work.getAuthorships()) {
                if (authorship == null || authorship.getAuthor() == null) {
                    continue;
                }
                String name = authorship.getAuthor().getDisplay_name();
                if (name != null && !name.isBlank()) {
                    authorNames.add(name);
                    if (Boolean.TRUE.equals(authorship.getIs_corresponding())) {
                        correspondingNames.add(name);
                    }
                }
                String authorOrcid = OrcidSupport.normalize(authorship.getAuthor().getOrcid());
                if (authorOrcid != null) {
                    authorOrcids.add(authorOrcid);
                }
            }
        }
        fact.setAuthorDisplayNames(authorNames);
        fact.setAuthorOrcids(authorOrcids);
        fact.setCorrespondingAuthorNames(correspondingNames);
        fact.setAuthorCount(authorNames.isEmpty() ? null : authorNames.size());
        fact.setCreator(authorNames.isEmpty() ? null : authorNames.getFirst());

        if (work.getPrimary_location() != null && work.getPrimary_location().getSource() != null) {
            OpenAlexWorksResponse.OpenAlexSource src = work.getPrimary_location().getSource();
            fact.setHostVenueName(src.getDisplay_name());
            Set<String> issns = new LinkedHashSet<>();
            if (src.getIssn() != null) {
                issns.addAll(src.getIssn());
            }
            if (src.getIssn_l() != null && !src.getIssn_l().isBlank()) {
                issns.add(src.getIssn_l());
            }
            fact.setHostVenueIssns(new ArrayList<>(issns));
        }

        List<String> referenced = new ArrayList<>();
        if (work.getReferenced_works() != null) {
            for (String ref : work.getReferenced_works()) {
                String refId = stripPrefix(ref, OPENALEX_ID_PREFIX);
                if (refId != null && !refId.isBlank()) {
                    referenced.add(refId);
                }
            }
        }
        fact.setReferencedWorks(referenced);

        // Append-only set of syncing researchers (a work can be synced by several co-authors on the platform).
        Set<String> synced = new LinkedHashSet<>(
                fact.getSyncedResearcherAuthorIds() == null ? List.of() : fact.getSyncedResearcherAuthorIds());
        if (researcherAuthorId != null && !researcherAuthorId.isBlank()) {
            synced.add(researcherAuthorId);
        }
        fact.setSyncedResearcherAuthorIds(new ArrayList<>(synced));

        fact.setBuilderVersion(BUILDER_VERSION);
        fact.setLastMaterializedAt(now);
        fact.setUpdatedAt(now);
        openAlexPublicationFactRepository.save(fact);
    }

    private static String stripPrefix(String value, String prefix) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.startsWith(prefix) ? trimmed.substring(prefix.length()) : trimmed;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b != null && !b.isBlank() ? b : null;
    }
}
