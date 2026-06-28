package ro.uvt.pokedex.core.service.brainmap;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.BrainmapProjectFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexProjectFact;
import ro.uvt.pokedex.core.model.scopus.canonical.UserDefinedProjectFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.BrainmapProjectFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexProjectFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.UserDefinedProjectFactRepository;
import ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * H64 slice 1 — derive the canonical {@link ScholardexProjectFact} layer from {@code brainmap.project_facts}.
 * Wipe-first (like every canonical builder); idempotent; runs after the affiliation backbone exists so the brainmap
 * coordinator display name resolves to a canonical {@link ScholardexAffiliationFact} via the shared
 * {@link ScopusAffiliationRorMatcher}.
 *
 * <p>Merge key = EU grant id (the code's trailing numeric segment, only for {@code funder=EC}) when present, else the
 * brainmap code — so the same EU project from brainmap + CORDIS later collapses onto one {@code sproj_<hash>} id.
 */
@Service
@RequiredArgsConstructor
public class ProjectCanonicalizationService {

    private static final Logger log = LoggerFactory.getLogger(ProjectCanonicalizationService.class);
    private static final String SOURCE_BRAINMAP = "BRAINMAP";
    private static final String BUILDER_VERSION = "project-canon-v1";
    private static final String EC_FUNDER = "EC";

    private static final Pattern TRAILING_DIGITS = Pattern.compile("(\\d+)(?!.*\\d)");
    // brainmap appends a "(JUDEŢUL <county> - <CITY>)" location suffix to institution names; strip it before matching.
    private static final Pattern PAREN_TAIL = Pattern.compile("\\([^)]*\\)\\s*$");
    // Stopwords dropped from the match signature: RO/EN articles+prepositions that vary between the brainmap name
    // ("Universitatea de Vest Timisoara") and the OpenAlex/ROR aliases ("Universitatea de Vest din Timișoara").
    private static final Set<String> STOPWORDS = Set.of("de", "din", "la", "si", "a", "al", "the", "of", "and", "for");

    private final BrainmapProjectFactRepository brainmapProjectFactRepository;
    private final ScholardexAffiliationFactRepository affiliationFactRepository;
    private final ScholardexProjectFactRepository projectFactRepository;
    private final UserDefinedProjectFactRepository userDefinedProjectFactRepository;

    public record ProjectCanonResult(int sourceFacts, int canonicalProjects, int coordinatorsResolved) {
    }

    /**
     * Rebuild the canonical project layer from the brainmap source facts + the affiliation backbone, then overlay the
     * user-defined (admin/CORDIS) facts: same merge key (EU grant id else code), user-defined WINS for budget and fills
     * any gaps, and a user-defined-only project (e.g. a CORDIS project brainmap lacks) is added on its own.
     */
    public ProjectCanonResult rebuild(String batchId, String correlationId) {
        List<BrainmapProjectFact> sources = brainmapProjectFactRepository.findAll();
        List<UserDefinedProjectFact> userDefined = userDefinedProjectFactRepository.findAll();
        Map<String, String> coordinatorIndex = buildAffiliationSignatureIndex();

        projectFactRepository.deleteAll();
        if (sources.isEmpty() && userDefined.isEmpty()) {
            log.info("Project canon: no project source facts — canonical project layer empty.");
            return new ProjectCanonResult(0, 0, 0);
        }

        Map<String, ScholardexProjectFact> byId = new LinkedHashMap<>();
        int resolved = 0;
        for (BrainmapProjectFact s : sources) {
            String euGrantId = deriveEuGrantId(s.getCode(), s.getFunder());
            String id = canonicalId(euGrantId, s.getCode(), s.getId());

            ScholardexProjectFact fact = byId.get(id);
            if (fact == null) {
                fact = new ScholardexProjectFact();
                fact.setId(id);
                fact.setEuGrantId(euGrantId);
                fact.setCode(s.getCode());
                fact.setTitle(s.getTitle());
                fact.setTitleNormalized(CanonicalizationSupport.normalizeName(s.getTitle()));
                fact.setPlan(s.getPlan());
                fact.setCompetition(s.getCompetition());
                fact.setFunder(s.getFunder());
                fact.setDirectorFirst(s.getDirectorFirst());
                fact.setDirectorLast(s.getDirectorLast());
                fact.setDirectorRole(s.getDirectorRole());
                fact.setCoordinatorName(s.getCoordinator());
                String affId = coordinatorIndex.get(signature(stripLocationSuffix(s.getCoordinator())));
                if (affId != null) {
                    fact.setCoordinatorAffiliationId(affId);
                    resolved++;
                }
                fact.setStartYear(parseYear(s.getStartYear()));
                fact.setEndYear(parseYear(s.getEndYear()));
                fact.setBudget(null); // brainmap has no budget
                Instant now = Instant.now();
                fact.setCreatedAt(now);
                fact.setUpdatedAt(now);
                fact.setSource(SOURCE_BRAINMAP);
                fact.setSourceBatchId(batchId);
                fact.setSourceCorrelationId(correlationId);
                fact.setBuilderVersion(BUILDER_VERSION);
                byId.put(id, fact);
            }
            CanonicalizationSupport.addUnique(fact.getBrainmapProjectIds(), s.getId());
        }

        // Overlay user-defined (admin/CORDIS) facts: same merge key, user-defined wins for budget + fills gaps.
        for (UserDefinedProjectFact u : userDefined) {
            String id = canonicalId(u.getEuGrantId(), u.getCode(), u.getId());
            ScholardexProjectFact fact = byId.computeIfAbsent(id, k -> {
                ScholardexProjectFact f = newCanonical(id, batchId, correlationId);
                f.setSource("USER_DEFINED");
                return f;
            });
            // identity: fill gaps (brainmap wins when already present)
            if (CanonicalizationSupport.isBlank(fact.getEuGrantId())) fact.setEuGrantId(u.getEuGrantId());
            if (CanonicalizationSupport.isBlank(fact.getCode())) fact.setCode(u.getCode());
            if (CanonicalizationSupport.isBlank(fact.getTitle()) && u.getTitle() != null) {
                fact.setTitle(u.getTitle());
                fact.setTitleNormalized(CanonicalizationSupport.normalizeName(u.getTitle()));
            }
            if (CanonicalizationSupport.isBlank(fact.getFunder())) fact.setFunder(u.getFunder());
            if (CanonicalizationSupport.isBlank(fact.getDirectorLast())) {
                fact.setDirectorFirst(u.getDirectorFirst());
                fact.setDirectorLast(u.getDirectorLast());
            }
            if (CanonicalizationSupport.isBlank(fact.getCoordinatorName()) && u.getCoordinatorName() != null) {
                fact.setCoordinatorName(u.getCoordinatorName());
                String affId = coordinatorIndex.get(signature(stripLocationSuffix(u.getCoordinatorName())));
                if (affId != null && fact.getCoordinatorAffiliationId() == null) {
                    fact.setCoordinatorAffiliationId(affId);
                    resolved++;
                }
            }
            if (fact.getStartYear() == null) fact.setStartYear(u.getStartYear());
            if (fact.getEndYear() == null) fact.setEndYear(u.getEndYear());
            // budget: user-defined is the trusted source — always wins.
            if (u.getBudget() != null) {
                fact.setBudget(u.getBudget());
                fact.setBudgetSource(u.getOrigin() != null ? u.getOrigin() : "MANUAL");
            }
            CanonicalizationSupport.addUnique(fact.getUserDefinedProjectIds(), u.getId());
            fact.setUpdatedAt(Instant.now());
        }

        projectFactRepository.saveAll(new ArrayList<>(byId.values()));
        log.info("Project canon: {} brainmap + {} user-defined source facts -> {} canonical projects ({} coordinators resolved).",
                sources.size(), userDefined.size(), byId.size(), resolved);
        return new ProjectCanonResult(sources.size() + userDefined.size(), byId.size(), resolved);
    }

    /** Canonical id: {@code sproj_<hash>} keyed by EU grant id when present, else code, else a source-local fallback. */
    private static String canonicalId(String euGrantId, String code, String fallback) {
        boolean hasEu = euGrantId != null && !euGrantId.isBlank();
        String key = hasEu ? euGrantId : (CanonicalizationSupport.isBlank(code) ? fallback : code);
        return "sproj_" + CanonicalizationSupport.shortHash((hasEu ? "eu:" : "code:") + key);
    }

    private static ScholardexProjectFact newCanonical(String id, String batchId, String correlationId) {
        ScholardexProjectFact f = new ScholardexProjectFact();
        f.setId(id);
        Instant now = Instant.now();
        f.setCreatedAt(now);
        f.setUpdatedAt(now);
        f.setSourceBatchId(batchId);
        f.setSourceCorrelationId(correlationId);
        f.setBuilderVersion(BUILDER_VERSION);
        return f;
    }

    /** EU grant id = the code's trailing numeric segment, only for EC-funded projects (RO codes also end in digits). */
    static String deriveEuGrantId(String code, String funder) {
        if (code == null || funder == null || !EC_FUNDER.equalsIgnoreCase(funder.trim())) {
            return null;
        }
        Matcher m = TRAILING_DIGITS.matcher(code);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Index the affiliation backbone by token signature (over name + every alias), so a brainmap coordinator display
     * name resolves despite article/preposition/word-order variance. First writer wins (deterministic).
     */
    private Map<String, String> buildAffiliationSignatureIndex() {
        Map<String, String> index = new HashMap<>();
        for (ScholardexAffiliationFact aff : affiliationFactRepository.findAll()) {
            if (aff == null || aff.getId() == null) {
                continue;
            }
            putSignature(index, aff.getName(), aff.getId());
            if (aff.getAliases() != null) {
                for (String alias : aff.getAliases()) {
                    putSignature(index, alias, aff.getId());
                }
            }
        }
        return index;
    }

    private static void putSignature(Map<String, String> index, String value, String affiliationId) {
        String sig = signature(value);
        if (sig != null && !sig.isEmpty()) {
            index.putIfAbsent(sig, affiliationId);
        }
    }

    /** Sorted set of normalized, stopword-stripped tokens, joined — a word-order/article-insensitive name key. */
    static String signature(String value) {
        String norm = CanonicalizationSupport.normalizeName(value);
        if (norm == null || norm.isBlank()) {
            return null;
        }
        Set<String> tokens = new TreeSet<>(Arrays.asList(norm.split(" ")));
        tokens.removeAll(STOPWORDS);
        tokens.removeIf(String::isEmpty);
        return String.join(" ", tokens);
    }

    /** Drop brainmap's trailing "(JUDEŢUL … - CITY)" location suffix so it doesn't pollute the name signature. */
    static String stripLocationSuffix(String coordinator) {
        if (coordinator == null) {
            return null;
        }
        return PAREN_TAIL.matcher(coordinator.trim()).replaceFirst("").trim();
    }

    private static Integer parseYear(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        if (!t.matches("\\d{4}")) {
            return null;
        }
        return Integer.valueOf(t);
    }
}
