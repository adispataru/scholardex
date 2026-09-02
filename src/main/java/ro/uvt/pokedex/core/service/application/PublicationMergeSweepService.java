package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationMergeDecision;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * H84 S4 — the UVT-authored duplicate sweep. The corpus-wide version of the workspace's same-title
 * suggestions, restricted to publications of REGISTERED researchers (users with a researcher profile):
 * measured against prod, the unrestricted sweep yields ~1,398 candidates of which only ~10 touch a UVT
 * author — this restriction is what keeps the queue reviewable ("structurally cannot flood") while
 * still growing naturally with onboarding.
 *
 * <p>Grouping rule (same as the measurement that re-scoped S4): same normalized title, years within ±1,
 * minus three exclusions — generic titles of ≤3 words (initials-level noise), pairs whose two sides carry
 * DIFFERENT DOIs (genuinely distinct records; a typo'd-DOI true duplicate stays reachable through the
 * researcher/admin flows), and preprint-vs-anything pairs (folding an arXiv preprint into its published
 * version changes a score — a policy call, excluded by decision 2026-07-25).</p>
 *
 * <p>Candidates are written PENDING through {@link PublicationMergeService#requestMerge}, which is
 * idempotent: a pair with an existing decision (any status) is left untouched, so re-running the sweep
 * never resurrects a rejected pair or clobbers a live one. Survivor is picked by record richness,
 * matching the workspace suggestions.</p>
 */
@Service
@RequiredArgsConstructor
public class PublicationMergeSweepService {

    private static final Logger log = LoggerFactory.getLogger(PublicationMergeSweepService.class);

    /** requestedByEmail stamped on sweep-created rows so the queue shows their provenance. */
    static final String SWEEP_PRINCIPAL = "merge-sweep";
    static final String SWEEP_NOTE = "Corpus sweep: UVT-authored same-title pair (±1 year)";

    private final UserService userService;
    private final ResearcherAuthorLookupService researcherAuthorLookupService;
    private final PostgresScholardexProjectionReadPort postgresProjectionReadPort;
    private final PublicationMergeService publicationMergeService;

    /** Default ON in every environment: the write path is idempotent and PENDING-only (admin still reviews). */
    @Value("${core.merge-sweep.enabled:true}")
    private boolean enabled;

    /** Weekly, Sunday 04:30 — after the nightly projection rebuild has settled. */
    @Scheduled(cron = "${core.merge-sweep.cron:0 30 4 * * SUN}")
    public void scheduledSweep() {
        if (!enabled) {
            return;
        }
        try {
            SweepResult result = sweep(false);
            log.info("Merge sweep complete: researchers={} publications={} titleGroups={} newPending={} alreadyDecided={}",
                    result.researchers(), result.publications(), result.titleGroups(),
                    result.pairs().stream().filter(SweepPair::requested).count(),
                    result.pairs().stream().filter(SweepPair::alreadyDecided).count());
        } catch (RuntimeException ex) {
            log.error("Merge sweep failed", ex);
        }
    }

    public SweepResult sweep(boolean dryRun) {
        Set<String> authorIds = new LinkedHashSet<>();
        List<User> researchers = userService.findUsersWithResearcherProfile();
        for (User user : researchers) {
            authorIds.addAll(researcherAuthorLookupService.resolveAuthorLookupKeys(user.getResearcherProfile()));
        }
        Set<String> publicationIds = authorIds.isEmpty()
                ? Set.of()
                : postgresProjectionReadPort.findPublicationIdsByAuthorIdIn(authorIds);
        Map<String, ScholardexPublicationView> byId = new LinkedHashMap<>();
        if (!publicationIds.isEmpty()) {
            postgresProjectionReadPort.findPublicationsByIdIn(publicationIds)
                    .forEach(pub -> byId.putIfAbsent(pub.getId(), pub));
        }

        Map<String, List<ScholardexPublicationView>> byTitle = new LinkedHashMap<>();
        for (ScholardexPublicationView pub : byId.values()) {
            String normalized = ScholardexPublicationCanonicalizationService.normalizeTitle(pub.getTitle());
            if (normalized == null || normalized.isBlank() || normalized.split("\\s+").length <= 3) {
                continue; // generic ≤3-word titles are initials-level noise, excluded by the S4 measurement
            }
            byTitle.computeIfAbsent(normalized, ignored -> new ArrayList<>()).add(pub);
        }

        List<SweepPair> pairs = new ArrayList<>();
        int titleGroups = 0;
        for (List<ScholardexPublicationView> group : byTitle.values()) {
            if (group.size() < 2) {
                continue;
            }
            titleGroups++;
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    ScholardexPublicationView a = group.get(i);
                    ScholardexPublicationView b = group.get(j);
                    if (!PublicationMergeWorkspaceFacade.yearsCompatible(a, b)) {
                        continue;
                    }
                    if (distinctDois(a, b) || isPreprint(a) || isPreprint(b)) {
                        continue;
                    }
                    boolean aSurvives = PublicationMergeWorkspaceFacade.richness(a) >= PublicationMergeWorkspaceFacade.richness(b);
                    ScholardexPublicationView survivor = aSurvives ? a : b;
                    ScholardexPublicationView duplicate = aSurvives ? b : a;
                    boolean alreadyDecided = publicationMergeService
                            .findDecision(survivor.getId(), duplicate.getId()).isPresent();
                    boolean requested = false;
                    if (!dryRun && !alreadyDecided) {
                        publicationMergeService.requestMerge(
                                survivor.getId(), duplicate.getId(), SWEEP_PRINCIPAL, null, SWEEP_NOTE);
                        requested = true;
                    }
                    pairs.add(new SweepPair(survivor.getId(), duplicate.getId(),
                            survivor.getTitle(), requested, alreadyDecided));
                }
            }
        }
        return new SweepResult(researchers.size(), byId.size(), titleGroups, List.copyOf(pairs), dryRun);
    }

    /** Both sides carry a DOI and they differ (normalized): genuinely distinct records, not a duplicate. */
    private static boolean distinctDois(ScholardexPublicationView a, ScholardexPublicationView b) {
        String doiA = normalizeDoi(a.getDoi());
        String doiB = normalizeDoi(b.getDoi());
        return !doiA.isBlank() && !doiB.isBlank() && !doiA.equals(doiB);
    }

    private static String normalizeDoi(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim()
                .replaceFirst("(?i)^https?://(dx\\.)?doi\\.org/", "")
                .replaceFirst("(?i)^doi:", "")
                .toLowerCase(Locale.ROOT);
    }

    /** Preprint-vs-published is a policy call, not a merge — excluded by decision (2026-07-25). */
    private static boolean isPreprint(ScholardexPublicationView pub) {
        String subtype = pub.getSubtype() == null ? "" : pub.getSubtype().trim().toLowerCase(Locale.ROOT);
        String scopusSubtype = pub.getScopusSubtype() == null ? "" : pub.getScopusSubtype().trim().toLowerCase(Locale.ROOT);
        return "preprint".equals(subtype) || "preprint".equals(scopusSubtype);
    }

    /** One candidate pair, survivor-first; {@code requested} = a new PENDING row was written this run. */
    public record SweepPair(String survivorId, String duplicateId, String title,
                            boolean requested, boolean alreadyDecided) {}

    public record SweepResult(int researchers, int publications, int titleGroups,
                              List<SweepPair> pairs, boolean dryRun) {}
}
