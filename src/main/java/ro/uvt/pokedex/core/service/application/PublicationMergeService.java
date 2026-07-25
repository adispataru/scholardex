package ro.uvt.pokedex.core.service.application;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationAuthorshipDecision;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationMergeDecision;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorshipFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationAuthorAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationDblpEvidence;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.repository.scopus.canonical.PublicationAuthorshipDecisionRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.PublicationMergeDecisionRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorshipFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexCitationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationAuthorAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationDblpEvidenceRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexSourceLinkRepository;
import ro.uvt.pokedex.core.service.importing.scopus.PublicationMergeAliasRegistry;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * H84 — the publication-merge executor, shared by the live admin approval and the rebuild re-apply pass
 * (chained into the full-maintenance materialization after the DBLP evidence re-link). Idempotent: a decision
 * whose duplicate no longer resolves is a verified no-op, so re-running the pass after every rebuild is safe.
 *
 * <p>Re-keying is duplicate-aware everywhere a unique index exists (citation pairs, authorship triples,
 * per-user authorship decisions, per-publication DBLP evidence): when the survivor already holds the equivalent
 * row, the duplicate's row is dropped instead of moved — this is what collapses two near-identical citation
 * lists into one deduplicated union.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublicationMergeService {

    private static final Pattern YEAR = Pattern.compile("(19|20)\\d{2}");
    private static final String REF_SCOPUS = "SCOPUS";

    private final PublicationMergeDecisionRepository decisionRepository;
    private final ScholardexPublicationFactRepository publicationFactRepository;
    private final ScholardexSourceLinkRepository sourceLinkRepository;
    private final ScholardexAuthorshipFactRepository authorshipFactRepository;
    private final ScholardexPublicationAuthorAffiliationFactRepository publicationAuthorAffiliationFactRepository;
    private final ScholardexCitationFactRepository citationFactRepository;
    private final PublicationAuthorshipDecisionRepository authorshipDecisionRepository;
    private final ScholardexPublicationDblpEvidenceRepository dblpEvidenceRepository;
    private final ScholardexProjectionDirtyService projectionDirtyService;

    /** Load every APPROVED decision into the static alias registry (resurrection guard) at startup. */
    @PostConstruct
    public void loadAliasRegistry() {
        for (PublicationMergeDecision decision : decisionRepository.findByStatus(PublicationMergeDecision.Status.APPROVED)) {
            registerAlias(decision);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Decision lifecycle                                                */
    /* ------------------------------------------------------------------ */

    /** Researcher-flagged request (S3 entry point): creates a PENDING decision awaiting admin approval. */
    public PublicationMergeDecision requestMerge(String survivorId, String duplicateId,
                                                 String requestedByEmail, String requestedByResearcherId,
                                                 String note) {
        ScholardexPublicationFact survivor = requireFact(survivorId, "survivor");
        ScholardexPublicationFact duplicate = requireFact(duplicateId, "duplicate");
        String pairKey = PublicationMergeDecision.pairKeyOf(survivorId, duplicateId);
        Optional<PublicationMergeDecision> existing = decisionRepository.findByPairKey(pairKey);
        if (existing.isPresent()) {
            return existing.get(); // already requested/decided — surface the standing decision, don't duplicate it
        }
        PublicationMergeDecision decision = newDecision(survivor, duplicate, pairKey);
        decision.setStatus(PublicationMergeDecision.Status.PENDING);
        decision.setRequestedByEmail(requestedByEmail);
        decision.setRequestedByResearcherId(requestedByResearcherId);
        decision.setRequestNote(note);
        return decisionRepository.save(decision);
    }

    /** Admin approval of a pending request; {@code swapSides} lets the admin flip which side survives. */
    public MergeApplyResult approve(String decisionId, String decidedBy, String note, boolean swapSides) {
        PublicationMergeDecision decision = decisionRepository.findById(decisionId)
                .orElseThrow(() -> new IllegalArgumentException("merge decision not found: " + decisionId));
        if (swapSides) {
            PublicationMergeDecision.Side survivor = decision.getSurvivor();
            decision.setSurvivor(decision.getDuplicate());
            decision.setDuplicate(survivor);
        }
        decision.setStatus(PublicationMergeDecision.Status.APPROVED);
        decision.setDecidedBy(decidedBy);
        decision.setDecidedAt(Instant.now());
        decision.setDecisionNote(note);
        decision.setUpdatedAt(Instant.now());
        decisionRepository.save(decision);
        return apply(decision, true);
    }

    public PublicationMergeDecision reject(String decisionId, String decidedBy, String note) {
        PublicationMergeDecision decision = decisionRepository.findById(decisionId)
                .orElseThrow(() -> new IllegalArgumentException("merge decision not found: " + decisionId));
        decision.setStatus(PublicationMergeDecision.Status.REJECTED);
        decision.setDecidedBy(decidedBy);
        decision.setDecidedAt(Instant.now());
        decision.setDecisionNote(note);
        decision.setUpdatedAt(Instant.now());
        PublicationMergeDecision saved = decisionRepository.save(decision);
        PublicationMergeAliasRegistry.unregister(
                saved.getDuplicate().getCanonicalId(), saved.getDuplicate().getSourceRecordRefs());
        return saved;
    }

    /** Admin-initiated merge of two known canonical ids: records an APPROVED decision and applies it now. */
    public MergeApplyResult directMerge(String survivorId, String duplicateId, String decidedBy, String note) {
        ScholardexPublicationFact survivor = requireFact(survivorId, "survivor");
        ScholardexPublicationFact duplicate = requireFact(duplicateId, "duplicate");
        String pairKey = PublicationMergeDecision.pairKeyOf(survivorId, duplicateId);
        PublicationMergeDecision decision = decisionRepository.findByPairKey(pairKey)
                .orElseGet(() -> newDecision(survivor, duplicate, pairKey));
        if (!survivorId.equals(decision.getSurvivor().getCanonicalId())) {
            // Standing decision has the sides the other way around — the explicit admin call wins.
            PublicationMergeDecision.Side side = decision.getSurvivor();
            decision.setSurvivor(decision.getDuplicate());
            decision.setDuplicate(side);
        }
        decision.setStatus(PublicationMergeDecision.Status.APPROVED);
        decision.setDecidedBy(decidedBy);
        decision.setDecidedAt(Instant.now());
        decision.setDecisionNote(note);
        decision.setUpdatedAt(Instant.now());
        decisionRepository.save(decision);
        return apply(decision, true);
    }

    /**
     * Rebuild durability: re-apply every APPROVED decision. Chained into the full-maintenance materialization
     * right after {@code rebuildFromEvidence()} — the canonical replay has just re-minted both sides from source,
     * so each merge re-executes; projections are NOT dirty-marked here because the full view rebuild follows.
     */
    public ReapplySummary reapplyApproved() {
        int merged = 0;
        int verified = 0;
        int skipped = 0;
        for (PublicationMergeDecision decision : decisionRepository.findByStatus(PublicationMergeDecision.Status.APPROVED)) {
            MergeApplyResult result = apply(decision, false);
            switch (result.outcome()) {
                case MERGED -> merged++;
                case ALREADY_MERGED, DUPLICATE_NOT_FOUND -> verified++;
                case SURVIVOR_NOT_FOUND -> skipped++;
            }
        }
        log.info("Publication merge re-apply: merged={} verified={} skipped={}", merged, verified, skipped);
        return new ReapplySummary(merged, verified, skipped);
    }

    /**
     * The standing decision for a pair, if any. Lets a caller distinguish "I created this request" from
     * "there was already one" without re-deriving the pair key, since {@link #requestMerge} deliberately
     * returns the existing decision rather than failing.
     */
    public Optional<PublicationMergeDecision> findDecision(String idA, String idB) {
        return decisionRepository.findByPairKey(PublicationMergeDecision.pairKeyOf(idA, idB));
    }

    public List<PublicationMergeDecision> listDecisions(PublicationMergeDecision.Status status) {
        return status == null
                ? decisionRepository.findAllByOrderByUpdatedAtDesc()
                : decisionRepository.findByStatusOrderByUpdatedAtDesc(status);
    }

    /* ------------------------------------------------------------------ */
    /*  The executor                                                      */
    /* ------------------------------------------------------------------ */

    public MergeApplyResult apply(PublicationMergeDecision decision, boolean markProjectionsDirty) {
        String survivorId = resolveLiveId(decision.getSurvivor(), decision.getIdentityHint());
        String duplicateId = resolveLiveId(decision.getDuplicate(), null); // hint may match the survivor — never use it for the duplicate
        registerAlias(decision);

        if (duplicateId == null || duplicateId.equals(survivorId)) {
            // Already merged (duplicate gone, or its links now resolve to the survivor) — verified no-op.
            stampApplied(decision);
            return new MergeApplyResult(MergeOutcome.ALREADY_MERGED, survivorId, duplicateId, 0, 0);
        }
        if (survivorId == null) {
            log.warn("Publication merge {}: survivor unresolvable — skipping (duplicate={})",
                    decision.getId(), duplicateId);
            return new MergeApplyResult(MergeOutcome.SURVIVOR_NOT_FOUND, null, duplicateId, 0, 0);
        }
        ScholardexPublicationFact survivor = publicationFactRepository.findById(survivorId).orElse(null);
        ScholardexPublicationFact duplicate = publicationFactRepository.findById(duplicateId).orElse(null);
        if (survivor == null || duplicate == null) {
            stampApplied(decision);
            return new MergeApplyResult(MergeOutcome.ALREADY_MERGED, survivorId, duplicateId, 0, 0);
        }

        int moved = 0;
        int dropped = 0;

        // --- authorship edges ---
        for (ScholardexAuthorshipFact edge : authorshipFactRepository.findByPublicationId(duplicateId)) {
            boolean collides = authorshipFactRepository
                    .findByPublicationIdAndAuthorIdAndSource(survivorId, edge.getAuthorId(), edge.getSource())
                    .isPresent();
            if (collides) {
                authorshipFactRepository.delete(edge);
                dropped++;
            } else {
                edge.setPublicationId(survivorId);
                edge.setUpdatedAt(Instant.now());
                authorshipFactRepository.save(edge);
                moved++;
            }
        }

        // --- publication-author-affiliation edges ---
        for (ScholardexPublicationAuthorAffiliationFact edge
                : publicationAuthorAffiliationFactRepository.findByPublicationIdIn(List.of(duplicateId))) {
            boolean collides = publicationAuthorAffiliationFactRepository
                    .findByPublicationIdAndAuthorIdAndAffiliationIdAndSource(
                            survivorId, edge.getAuthorId(), edge.getAffiliationId(), edge.getSource())
                    .isPresent();
            if (collides) {
                publicationAuthorAffiliationFactRepository.delete(edge);
                dropped++;
            } else {
                edge.setPublicationId(survivorId);
                publicationAuthorAffiliationFactRepository.save(edge);
                moved++;
            }
        }

        // --- citation edges, both directions (unique pair index; self-citations after re-key are dropped) ---
        for (ScholardexCitationFact edge : citationFactRepository.findByCitedPublicationId(duplicateId)) {
            if (survivorId.equals(edge.getCitingPublicationId())
                    || citationFactRepository
                            .findByCitedPublicationIdAndCitingPublicationId(survivorId, edge.getCitingPublicationId())
                            .isPresent()) {
                citationFactRepository.delete(edge);
                dropped++;
            } else {
                edge.setCitedPublicationId(survivorId);
                edge.setUpdatedAt(Instant.now());
                citationFactRepository.save(edge);
                moved++;
            }
        }
        for (ScholardexCitationFact edge : citationFactRepository.findByCitingPublicationIdIn(List.of(duplicateId))) {
            if (survivorId.equals(edge.getCitedPublicationId())
                    || citationFactRepository
                            .findByCitedPublicationIdAndCitingPublicationId(edge.getCitedPublicationId(), survivorId)
                            .isPresent()) {
                citationFactRepository.delete(edge);
                dropped++;
            } else {
                edge.setCitingPublicationId(survivorId);
                edge.setUpdatedAt(Instant.now());
                citationFactRepository.save(edge);
                moved++;
            }
        }

        // --- per-user authorship decisions (unique userEmail+publicationId; survivor's decision wins) ---
        for (PublicationAuthorshipDecision userDecision
                : authorshipDecisionRepository.findByPublicationIdIn(List.of(duplicateId))) {
            boolean collides = authorshipDecisionRepository
                    .findByUserEmailAndPublicationId(userDecision.getUserEmail(), survivorId)
                    .isPresent();
            if (collides) {
                authorshipDecisionRepository.delete(userDecision);
                dropped++;
            } else {
                userDecision.setPublicationId(survivorId);
                userDecision.setUpdatedAt(Instant.now());
                authorshipDecisionRepository.save(userDecision);
                moved++;
            }
        }

        // --- DBLP evidence (unique per publication; survivor's evidence wins) ---
        dblpEvidenceRepository.findByPublicationId(duplicateId).ifPresent(evidence -> {
            if (dblpEvidenceRepository.findByPublicationId(survivorId).isPresent()) {
                dblpEvidenceRepository.delete(evidence);
            } else {
                evidence.setPublicationId(survivorId);
                evidence.setUpdatedAt(Instant.now());
                dblpEvidenceRepository.save(evidence);
            }
        });

        // --- source links: the duplicate's records now belong to the survivor ---
        for (ScholardexSourceLink link
                : sourceLinkRepository.findByEntityTypeAndCanonicalEntityId(ScholardexEntityType.PUBLICATION, duplicateId)) {
            link.setCanonicalEntityId(survivorId);
            link.setUpdatedAt(Instant.now());
            sourceLinkRepository.save(link);
        }

        // --- enrich the survivor with what only the duplicate has, then retire the duplicate ---
        // The duplicate is deleted BEFORE the survivor save: wosId/googleScholarId/eid carry sparse unique
        // indexes, so the moved value must not exist on two documents at once.
        publicationFactRepository.delete(duplicate);
        enrichSurvivor(survivor, duplicate);
        publicationFactRepository.save(survivor);

        stampApplied(decision);
        if (markProjectionsDirty) {
            // Batchless marker on purpose: the dirty rebuild then runs the FULL view rebuild, which both
            // re-projects every re-keyed edge and drops the duplicate's now-orphaned view rows (TRUNCATE+reload).
            projectionDirtyService.markDirty(ScholardexEntityType.PUBLICATION, survivorId,
                    null, null, null, "publication-merge " + duplicateId + " -> " + survivorId);
        }
        log.info("Publication merge {}: {} -> {} (rows moved={} deduped={})",
                decision.getId(), duplicateId, survivorId, moved, dropped);
        return new MergeApplyResult(MergeOutcome.MERGED, survivorId, duplicateId, moved, dropped);
    }

    private void enrichSurvivor(ScholardexPublicationFact survivor, ScholardexPublicationFact duplicate) {
        if (isBlank(survivor.getDoi()) && !isBlank(duplicate.getDoi())) {
            survivor.setDoi(duplicate.getDoi());
            survivor.setDoiNormalized(ScholardexPublicationCanonicalizationService.normalizeDoi(duplicate.getDoi()));
        }
        if (isBlank(survivor.getWosId())) {
            survivor.setWosId(duplicate.getWosId());
        }
        if (isBlank(survivor.getGoogleScholarId())) {
            survivor.setGoogleScholarId(duplicate.getGoogleScholarId());
        }
        if (isBlank(survivor.getPii())) {
            survivor.setPii(duplicate.getPii());
        }
        if (isBlank(survivor.getPubmedId())) {
            survivor.setPubmedId(duplicate.getPubmedId());
        }
        if (isBlank(survivor.getDescription())) {
            survivor.setDescription(duplicate.getDescription());
        }
        if ((survivor.getAuthKeywords() == null || survivor.getAuthKeywords().isEmpty())
                && duplicate.getAuthKeywords() != null && !duplicate.getAuthKeywords().isEmpty()) {
            survivor.setAuthKeywords(new ArrayList<>(duplicate.getAuthKeywords()));
        }
        if (isBlank(survivor.getForumId()) && isBlank(survivor.getBookId())) {
            survivor.setForumId(duplicate.getForumId());
            survivor.setBookId(duplicate.getBookId());
        }
        if (isBlank(survivor.getOriginalForumId())) {
            survivor.setOriginalForumId(duplicate.getOriginalForumId());
        }
        if (survivor.getOpenAccess() == null) {
            survivor.setOpenAccess(duplicate.getOpenAccess());
        }
        if (survivor.getFwci() == null) {
            survivor.setFwci(duplicate.getFwci());
        }
        if (survivor.getCitationNormalizedPercentile() == null) {
            survivor.setCitationNormalizedPercentile(duplicate.getCitationNormalizedPercentile());
        }
        if (isBlank(survivor.getPrimaryTopicId())) {
            survivor.setPrimaryTopicId(duplicate.getPrimaryTopicId());
            survivor.setPrimaryTopicName(duplicate.getPrimaryTopicName());
        }
        if (Boolean.TRUE.equals(duplicate.getRetracted())) {
            survivor.setRetracted(true); // research-ethics gate: a retraction on either record sticks
        }
        int survivorCites = survivor.getCitedByCount() == null ? 0 : survivor.getCitedByCount();
        int duplicateCites = duplicate.getCitedByCount() == null ? 0 : duplicate.getCitedByCount();
        survivor.setCitedByCount(Math.max(survivorCites, duplicateCites));
        survivor.setUpdatedAt(Instant.now());
    }

    /* ------------------------------------------------------------------ */
    /*  Resolution                                                        */
    /* ------------------------------------------------------------------ */

    /** Live canonical id for a decision side: stored id, else source-record refs, else the identity hint. */
    private String resolveLiveId(PublicationMergeDecision.Side side, PublicationMergeDecision.IdentityHint hint) {
        if (side.getCanonicalId() != null && publicationFactRepository.existsById(side.getCanonicalId())) {
            return side.getCanonicalId();
        }
        for (String ref : side.getSourceRecordRefs()) {
            int split = ref == null ? -1 : ref.indexOf(':');
            if (split <= 0) {
                continue;
            }
            Optional<ScholardexSourceLink> link = sourceLinkRepository
                    .findFirstByEntityTypeAndSourceAndSourceRecordIdOrderByUpdatedAtDesc(
                            ScholardexEntityType.PUBLICATION, ref.substring(0, split), ref.substring(split + 1));
            if (link.isPresent() && !isBlank(link.get().getCanonicalEntityId())
                    && publicationFactRepository.existsById(link.get().getCanonicalEntityId())) {
                return link.get().getCanonicalEntityId();
            }
        }
        if (hint != null && !isBlank(hint.getTitleNormalized())) {
            List<ScholardexPublicationFact> byTitle = publicationFactRepository
                    .findAllByTitleNormalized(hint.getTitleNormalized()).stream()
                    .filter(fact -> yearMatches(hint.getCoverYear(), fact.getCoverDate()))
                    .filter(fact -> creatorMatches(hint.getCreatorNormalized(), fact.getCreator()))
                    .toList();
            if (byTitle.size() == 1) {
                return byTitle.getFirst().getId();
            }
        }
        return null;
    }

    private static boolean yearMatches(Integer hintYear, String coverDate) {
        if (hintYear == null) {
            return true;
        }
        Integer year = parseYear(coverDate);
        return year != null && Math.abs(year - hintYear) <= 1;
    }

    /**
     * Loose on purpose: the sources format creators differently ("Moscato F." vs "Francesco Moscato"), so match on
     * a shared name token of length >= 4 (the surname). Only ever used to disambiguate an exact-normalized-title,
     * year-tolerant candidate set down to a SINGLE publication.
     */
    private static boolean creatorMatches(String hintCreatorNormalized, String creator) {
        if (isBlank(hintCreatorNormalized)) {
            return true;
        }
        String candidate = normalizeCreator(creator);
        if (isBlank(candidate)) {
            return false;
        }
        Set<String> hintTokens = new LinkedHashSet<>(List.of(hintCreatorNormalized.split(" ")));
        for (String token : candidate.split(" ")) {
            if (token.length() >= 4 && hintTokens.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /* ------------------------------------------------------------------ */
    /*  Decision construction                                             */
    /* ------------------------------------------------------------------ */

    private PublicationMergeDecision newDecision(ScholardexPublicationFact survivor,
                                                 ScholardexPublicationFact duplicate,
                                                 String pairKey) {
        PublicationMergeDecision decision = new PublicationMergeDecision();
        decision.setPairKey(pairKey);
        decision.setSurvivor(buildSide(survivor));
        decision.setDuplicate(buildSide(duplicate));
        decision.setIdentityHint(buildIdentityHint(survivor));
        decision.setCreatedAt(Instant.now());
        decision.setUpdatedAt(Instant.now());
        return decision;
    }

    private PublicationMergeDecision.Side buildSide(ScholardexPublicationFact fact) {
        PublicationMergeDecision.Side side = new PublicationMergeDecision.Side();
        side.setCanonicalId(fact.getId());
        side.setSource(fact.getSource());
        Set<String> refs = new LinkedHashSet<>();
        if (!isBlank(fact.getEid())) {
            refs.add(REF_SCOPUS + ":" + fact.getEid());
        }
        for (ScholardexSourceLink link
                : sourceLinkRepository.findByEntityTypeAndCanonicalEntityId(ScholardexEntityType.PUBLICATION, fact.getId())) {
            if (!isBlank(link.getSource()) && !isBlank(link.getSourceRecordId())) {
                refs.add(link.getSource() + ":" + link.getSourceRecordId());
            }
        }
        side.setSourceRecordRefs(new ArrayList<>(refs));
        PublicationMergeDecision.Snapshot snapshot = side.getSnapshot();
        snapshot.setTitle(fact.getTitle());
        snapshot.setEid(fact.getEid());
        snapshot.setDoi(fact.getDoi());
        snapshot.setCoverDate(fact.getCoverDate());
        snapshot.setCitedByCount(fact.getCitedByCount());
        return side;
    }

    private static PublicationMergeDecision.IdentityHint buildIdentityHint(ScholardexPublicationFact survivor) {
        PublicationMergeDecision.IdentityHint hint = new PublicationMergeDecision.IdentityHint();
        hint.setTitleNormalized(survivor.getTitleNormalized() != null
                ? survivor.getTitleNormalized()
                : ScholardexPublicationCanonicalizationService.normalizeTitle(survivor.getTitle()));
        hint.setCoverYear(parseYear(survivor.getCoverDate()));
        hint.setCreatorNormalized(normalizeCreator(survivor.getCreator()));
        return hint;
    }

    /* ------------------------------------------------------------------ */
    /*  Support                                                           */
    /* ------------------------------------------------------------------ */

    private void registerAlias(PublicationMergeDecision decision) {
        if (decision.getStatus() != PublicationMergeDecision.Status.APPROVED) {
            return;
        }
        PublicationMergeAliasRegistry.register(
                decision.getDuplicate().getCanonicalId(),
                decision.getDuplicate().getSourceRecordRefs(),
                decision.getSurvivor().getCanonicalId());
    }

    private void stampApplied(PublicationMergeDecision decision) {
        decision.setLastAppliedAt(Instant.now());
        decision.setUpdatedAt(Instant.now());
        decisionRepository.save(decision);
    }

    private ScholardexPublicationFact requireFact(String id, String role) {
        return publicationFactRepository.findById(id == null ? "" : id)
                .orElseThrow(() -> new IllegalArgumentException(role + " publication not found: " + id));
    }

    /** Surname-first token, lowercased, diacritics-insensitive-ish: "Moscato F." and "Francesco Moscato" both → contain "moscato". */
    private static String normalizeCreator(String creator) {
        if (creator == null) {
            return null;
        }
        String normalized = creator.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ").trim().replaceAll("\\s+", " ");
        return normalized.isEmpty() ? null : normalized;
    }

    private static Integer parseYear(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = YEAR.matcher(value);
        return matcher.find() ? Integer.valueOf(matcher.group()) : null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public enum MergeOutcome {
        MERGED,
        ALREADY_MERGED,
        DUPLICATE_NOT_FOUND,
        SURVIVOR_NOT_FOUND
    }

    public record MergeApplyResult(MergeOutcome outcome, String survivorId, String duplicateId,
                                   int rowsMoved, int rowsDeduplicated) {
    }

    public record ReapplySummary(int merged, int verified, int skipped) {
        public String describe() {
            return "merged=" + merged + " verified=" + verified + " skipped=" + skipped;
        }
    }
}
