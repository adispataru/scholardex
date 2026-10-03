package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.registry.RegistryItem;
import ro.uvt.pokedex.core.model.registry.RegistryKind;
import ro.uvt.pokedex.core.model.registry.RegistryStatus;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.service.reporting.ArtisticEventRankSupport;
import ro.uvt.pokedex.core.service.reporting.RegistryScoringSupport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * H142 slice 3, H144 — the researcher's side of the registries: the names to pick from when declaring a record (ranked
 * entries and their spellings, and the names already waiting for the experts, so that a second person joins the same
 * proposal), and what the registries say about the entities each record names.
 */
@Service
@RequiredArgsConstructor
public class RegistryLookupFacade {

    private static final int MAX_SUGGESTIONS = 15;

    private final RegistryStores stores;
    private final ActivityInstanceRepository activityInstanceRepository;

    /** One name to pick: the name as written, its level (null while waiting), its domain, and the entry it spells. */
    public record Suggestion(String name, String level, String domain, String status, String spells) {
    }

    /** What a registry says about an entity a record names. */
    public record EntityLevel(RegistryKind kind, String entity, String status, String level, String basis, String note,
                              String category, List<String> criteria, String country) {
    }

    /** What the app's lists say about the journal and the university a record names (null when it names none). */
    public record ListFacts(String issn, RegistryScoringSupport.JournalFacts journal, String university,
                            RegistryScoringSupport.UniversityFacts universityFacts) {
        /** The databases indexing the journal that the general rule counts (Web of Science once). */
        public List<String> databaseNames() {
            return journal == null ? List.of() : RegistryScoringSupport.recognisedDatabaseNames(journal.databases());
        }
    }

    /** Per record: the registries' entities, and the lists' facts. */
    public record RecordLevels(List<EntityLevel> entities, ListFacts facts) {
    }

    public List<Suggestion> search(RegistryKind kind, String query) {
        String q = ArtisticEventRankSupport.normalize(query);
        if (kind == null || q.length() < 2) {
            return List.of();
        }
        List<Suggestion> out = new ArrayList<>();
        for (RegistryItem e : stores.all(kind)) {
            boolean ranked = e.isConfirmed() && e.getLevel() != null;
            boolean waiting = e.getStatus() == RegistryStatus.PROPOSED;
            if (!ranked && !waiting) {
                continue;
            }
            List<String> names = new ArrayList<>();
            names.add(e.getName());
            if (e.getAliases() != null) names.addAll(e.getAliases());
            for (String name : names) {
                if (name != null && ArtisticEventRankSupport.normalize(name).contains(q)) {
                    out.add(new Suggestion(name, ranked ? e.getLevel() : null, e.getDomainId(),
                            ranked ? "RANKED" : "AWAITING_RANK", name.equals(e.getName()) ? null : e.getName()));
                }
            }
        }
        out.sort(Comparator.comparing((Suggestion s) -> !ArtisticEventRankSupport.normalize(s.name()).startsWith(q))
                .thenComparing(s -> s.level() == null).thenComparing(Suggestion::name));
        return out.size() > MAX_SUGGESTIONS ? out.subList(0, MAX_SUGGESTIONS) : out;
    }

    /**
     * Per record of the researcher: what the registries say about each entity it names, and what the app's lists say
     * about the journal (by ISSN) and the university it names — what the scoring reads instead of a picked level.
     */
    public Map<String, RecordLevels> levelsFor(String researcherEmail) {
        Map<String, RecordLevels> out = new LinkedHashMap<>();
        if (researcherEmail == null || researcherEmail.isBlank()) {
            return out;
        }
        Map<RegistryKind, List<RegistryItem>> loaded = new EnumMap<>(RegistryKind.class);
        for (ActivityInstance r : activityInstanceRepository.findAllByResearcherId(researcherEmail)) {
            List<EntityLevel> entities = new ArrayList<>();
            for (RegistryKind kind : RegistryKind.values()) {
                String name = RegistryStores.nameIn(r, kind);
                if (name.isBlank()) {
                    continue;
                }
                List<RegistryItem> all = loaded.computeIfAbsent(kind, stores::all);
                entities.add(levelOf(kind, name, all));
            }
            ListFacts facts = facts(r);
            if (!entities.isEmpty() || facts != null) {
                out.put(r.getId(), new RecordLevels(entities, facts));
            }
        }
        return out;
    }

    private static ListFacts facts(ActivityInstance r) {
        Map<Activity.ReferenceField, String> refs = r.getReferenceFields() == null ? Map.of() : r.getReferenceFields();
        String issn = blankToNull(refs.get(Activity.ReferenceField.FORUM_ISSN));
        String university = blankToNull(refs.get(Activity.ReferenceField.UNIVERSITY_NAME));
        if (issn == null && university == null) {
            return null;
        }
        int year = r.getYear();
        return new ListFacts(issn, issn == null ? null : RegistryScoringSupport.journal(issn, year).orElse(null),
                university, university == null ? null : RegistryScoringSupport.university(university, year));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static EntityLevel levelOf(RegistryKind kind, String name, List<RegistryItem> all) {
        String key = ArtisticEventRankSupport.normalize(name);
        RegistryItem decided = all.stream().filter(e -> RegistryReviewService.keysOf(e).contains(key))
                .filter(e -> e.isConfirmed() || e.getStatus() == RegistryStatus.REJECTED)
                .min(Comparator.comparing((RegistryItem e) -> e.isConfirmed() ? 0 : 1)).orElse(null);
        if (decided != null && decided.isConfirmed() && decided.getLevel() != null) {
            return new EntityLevel(kind, decided.getName(), "RANKED", decided.getLevel(), decided.effectiveBasis(),
                    decided.getNote(), decided.getCategory(), decided.getCriteria(), decided.getCountry());
        }
        if (decided != null) {
            return new EntityLevel(kind, name, "REJECTED", null, null, decided.getNote(), null, List.of(), null);
        }
        return new EntityLevel(kind, name, "AWAITING_RANK", null, null, null, null, List.of(), null);
    }
}
