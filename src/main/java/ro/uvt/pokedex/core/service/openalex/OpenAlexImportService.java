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
    private static final String ROR_ID_PREFIX = "https://ror.org/";
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
            upsert(work, workId, orcid, researcherAuthorId, batchId, correlationId);
            workIds.add(workId);
        }
        log.info("OpenAlex import for ORCID {} upserted {} source facts (batch {})", orcid, workIds.size(), batchId);
        return workIds;
    }

    private void upsert(OpenAlexWorksResponse.OpenAlexWork work, String workId, String syncOrcid, String researcherAuthorId,
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

        List<OpenAlexPublicationFact.AuthorRef> authorships = new ArrayList<>();
        if (work.getAuthorships() != null) {
            int position = 0;
            for (OpenAlexWorksResponse.Authorship authorship : work.getAuthorships()) {
                if (authorship == null || authorship.getAuthor() == null) {
                    continue;
                }
                OpenAlexPublicationFact.AuthorRef ref = new OpenAlexPublicationFact.AuthorRef();
                ref.setPosition(position++);
                ref.setDisplayName(authorship.getAuthor().getDisplay_name());
                ref.setOrcid(OrcidSupport.normalize(authorship.getAuthor().getOrcid()));
                ref.setOpenAlexAuthorId(stripPrefix(authorship.getAuthor().getId(), OPENALEX_ID_PREFIX));
                ref.setCorresponding(Boolean.TRUE.equals(authorship.getIs_corresponding()));
                // H71: capture per-authorship affiliation — the reconciler's cross-source dedup signal.
                if (authorship.getInstitutions() != null) {
                    authorship.getInstitutions().stream()
                            .filter(i -> i != null && i.getDisplay_name() != null && !i.getDisplay_name().isBlank())
                            .forEach(i -> ref.getInstitutionNames().add(i.getDisplay_name().trim()));
                    // H72 slice 3a: bare ROR id (strip the https://ror.org/ prefix) — the cross-source affiliation key.
                    authorship.getInstitutions().stream()
                            .filter(i -> i != null && i.getRor() != null && !i.getRor().isBlank())
                            .forEach(i -> ref.getInstitutionRors().add(stripPrefix(i.getRor().trim(), ROR_ID_PREFIX)));
                }
                if (authorship.getRaw_affiliation_strings() != null) {
                    authorship.getRaw_affiliation_strings().stream()
                            .filter(s -> s != null && !s.isBlank())
                            .forEach(s -> ref.getRawAffiliations().add(s.trim()));
                }
                if (authorship.getCountries() != null && !authorship.getCountries().isEmpty()) {
                    ref.setCountryCode(authorship.getCountries().getFirst());
                }
                authorships.add(ref);
            }
        }
        fact.setAuthorships(authorships);
        fact.setAuthorCount(authorships.isEmpty() ? null : authorships.size());
        fact.setCreator(authorships.isEmpty() ? null : authorships.getFirst().getDisplayName());

        if (work.getPrimary_location() != null && work.getPrimary_location().getSource() != null) {
            OpenAlexWorksResponse.OpenAlexSource src = work.getPrimary_location().getSource();
            fact.setHostVenueName(src.getDisplay_name());
            fact.setHostVenueOpenAlexId(stripPrefix(src.getId(), OPENALEX_ID_PREFIX));
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

        // Append-only set of syncing researchers (a work can be synced by several co-authors on the platform),
        // keyed by canonical author id and carrying the ORCID that drove the sync.
        List<OpenAlexPublicationFact.SyncedResearcher> synced = new ArrayList<>(
                fact.getSyncedResearchers() == null ? List.of() : fact.getSyncedResearchers());
        if (researcherAuthorId != null && !researcherAuthorId.isBlank()
                && synced.stream().noneMatch(s -> researcherAuthorId.equals(s.getCanonicalAuthorId()))) {
            OpenAlexPublicationFact.SyncedResearcher sr = new OpenAlexPublicationFact.SyncedResearcher();
            sr.setCanonicalAuthorId(researcherAuthorId);
            sr.setOrcid(syncOrcid);
            synced.add(sr);
        }
        fact.setSyncedResearchers(synced);

        fact.setBuilderVersion(BUILDER_VERSION);
        fact.setLastMaterializedAt(now);
        fact.setUpdatedAt(now);
        openAlexPublicationFactRepository.save(fact);
    }

    /**
     * Upsert "neighbor" works (cited/citing third-party papers, H66B Phase 4a Ext A/B) as <b>bare</b> source-facts:
     * bibliographic + venue + {@code referencedWorks} only — NO authorships (so no corresponding-author resolution /
     * bridge) and NO synced researchers. {@code referencedWorks} ARE kept so a <i>citing</i> neighbor's edges to our
     * papers re-derive on a full rebuild; recursion is prevented by only ever <i>minting</i> one level out (the edge
     * build links existing canonical pubs, it never mints). Existing source-facts (already synced or previously
     * minted) are skipped — returns the touched ids.
     */
    public List<String> upsertNeighborWorks(List<OpenAlexWorksResponse.OpenAlexWork> works, String batchId, String correlationId) {
        List<String> workIds = new ArrayList<>();
        Instant now = Instant.now();
        for (OpenAlexWorksResponse.OpenAlexWork work : works) {
            String workId = stripPrefix(work.getId(), OPENALEX_ID_PREFIX);
            if (workId == null || workId.isBlank()) {
                continue;
            }
            workIds.add(workId);
            if (openAlexPublicationFactRepository.findBySourceRecordId(workId).isPresent()) {
                continue; // already a source-fact (synced or previously minted) — don't downgrade it to bare
            }
            OpenAlexPublicationFact fact = new OpenAlexPublicationFact();
            fact.setCreatedAt(now);
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
            if (work.getAuthorships() != null && !work.getAuthorships().isEmpty()
                    && work.getAuthorships().getFirst().getAuthor() != null) {
                fact.setCreator(work.getAuthorships().getFirst().getAuthor().getDisplay_name());
                fact.setAuthorCount(work.getAuthorships().size());
            }
            if (work.getPrimary_location() != null && work.getPrimary_location().getSource() != null) {
                fact.setHostVenueName(work.getPrimary_location().getSource().getDisplay_name());
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
            fact.setBuilderVersion(BUILDER_VERSION);
            fact.setLastMaterializedAt(now);
            fact.setUpdatedAt(now);
            openAlexPublicationFactRepository.save(fact);
        }
        return workIds;
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
