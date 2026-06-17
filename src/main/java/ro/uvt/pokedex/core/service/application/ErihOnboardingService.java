package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.erih.ErihJournalFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.repository.erih.ErihJournalFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * H66 A5 — populate canonical forums with their ERIH+ id ({@code erihIds}) by matching ERIH journals to
 * existing forums on normalized ISSN. Match-only: it never creates forums (an ERIH-only journal with no
 * Scopus/WoS forum is left for a future registry-expansion decision). Idempotent.
 *
 * <p>When one ERIH journal matches several distinct forums, each gets the same erihId — those forums now
 * share an erihId, the signal that they are the same journal split apart. The forum dedup
 * ({@link ScholardexForumDeduplicationService}, C1 part 2) then clusters and merges them. Onboarding only
 * writes the FK; dedup does the merging. Run order on a rebuild: rebuild forums → onboard ERIH → dedup.
 */
@Service
@RequiredArgsConstructor
public class ErihOnboardingService {

    private static final Logger log = LoggerFactory.getLogger(ErihOnboardingService.class);
    private static final Pattern ISSN_NON_ALNUM = Pattern.compile("[^0-9Xx]");

    private final ScholardexForumFactRepository forumRepository;
    private final ErihJournalFactRepository erihJournalFactRepository;

    public ImportProcessingResult onboardErih() {
        ImportProcessingResult result = new ImportProcessingResult(20);
        List<ScholardexForumFact> forums = forumRepository.findAll();

        // ISSN token -> forums carrying it. A token can in principle map to >1 forum (the split-journal case).
        Map<String, List<ScholardexForumFact>> forumsByIssnToken = new LinkedHashMap<>();
        for (ScholardexForumFact forum : forums) {
            for (String token : forumIssnTokens(forum)) {
                forumsByIssnToken.computeIfAbsent(token, k -> new ArrayList<>()).add(forum);
            }
        }

        Map<String, ScholardexForumFact> dirtyById = new LinkedHashMap<>();
        Instant now = Instant.now();
        for (ErihJournalFact erih : erihJournalFactRepository.findAll()) {
            result.markProcessed();
            Set<ScholardexForumFact> matched = new LinkedHashSet<>();
            for (String token : List.of(safe(normalizeIssn(erih.getIssn())), safe(normalizeIssn(erih.getEIssn())))) {
                if (token.isEmpty()) {
                    continue;
                }
                List<ScholardexForumFact> hits = forumsByIssnToken.get(token);
                if (hits != null) {
                    matched.addAll(hits);
                }
            }
            if (matched.isEmpty()) {
                result.markSkipped("erih-no-forum-match erihId=" + erih.getId());
                continue;
            }
            for (ScholardexForumFact forum : matched) {
                if (addErihId(forum, erih.getId())) {
                    forum.setUpdatedAt(now);
                    dirtyById.put(forum.getId(), forum);
                }
            }
        }

        if (!dirtyById.isEmpty()) {
            forumRepository.saveAll(new ArrayList<>(dirtyById.values()));
            dirtyById.values().forEach(f -> result.markUpdated());
        }
        log.info("ERIH onboarding complete: erihProcessed={} forumsUpdated={} (erihIds written)",
                result.getProcessedCount(), dirtyById.size());
        return result;
    }

    /** Add the erihId to the forum's list if absent; returns true when it changed. Keeps the list sorted. */
    private boolean addErihId(ScholardexForumFact forum, String erihId) {
        List<String> ids = forum.getErihIds();
        if (ids == null) {
            ids = new ArrayList<>();
            forum.setErihIds(ids);
        }
        if (ids.contains(erihId)) {
            return false;
        }
        ids.add(erihId);
        ids.sort(Comparator.naturalOrder());
        return true;
    }

    private static Set<String> forumIssnTokens(ScholardexForumFact forum) {
        Set<String> tokens = new LinkedHashSet<>();
        addToken(tokens, forum.getIssn());
        addToken(tokens, forum.getEIssn());
        if (forum.getAliasIssns() != null) {
            for (String alias : forum.getAliasIssns()) {
                addToken(tokens, alias);
            }
        }
        return tokens;
    }

    private static void addToken(Set<String> tokens, String raw) {
        String token = normalizeIssn(raw);
        if (token != null) {
            tokens.add(token);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String normalizeIssn(String raw) {
        if (raw == null) {
            return null;
        }
        String compact = ISSN_NON_ALNUM.matcher(raw).replaceAll("").toUpperCase(Locale.ROOT);
        return compact.length() == 8 ? compact : null;
    }
}
