package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.ArtisticEvent;
import ro.uvt.pokedex.core.model.registry.RegistryStatus;
import ro.uvt.pokedex.core.repository.ArtisticEventRepository;
import ro.uvt.pokedex.core.service.reporting.ArtisticEventRankRegistrar;
import ro.uvt.pokedex.core.service.reporting.ArtisticEventRankSupport;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * H142 slice 7 — ranks the artistic events of a faculty's submitted CNFIS reports (Anexa 5.1) at the level the faculty
 * reported (Adrian, 2026-10-03: the faculty submitted them, so its classification stands until the experts change it;
 * basis {@link ArtisticEvent.Basis#FACULTY_CNFIS_REPORT}). An event the registry already ranks (the CNFIS list, an
 * expert) keeps its rank; one the files report at different levels becomes — or stays — a proposal for the experts,
 * the levels in its note; so does a new name that holds every distinctive word of a ranked event ("Festivalul
 * Internațional GEORGE ENESCU" and the CNFIS list's "Festivalul «George Enescu»"), a spelling for the experts to merge
 * rather than a second rank; a rejected or merged name is left alone. A report uploaded in several batches adds up: an
 * event an earlier batch ranked keeps its rank when the levels agree, and goes to the experts when they do not.
 */
@Service
@RequiredArgsConstructor
public class ArtisticEventFacultyRanking {

    private static final Logger log = LoggerFactory.getLogger(ArtisticEventFacultyRanking.class);

    private final ArtisticEventRepository eventRepository;
    private final ArtisticEventRankRegistrar registrar;

    /** What a batch did to the registry; {@code conflicts} names the events left to the experts. */
    public record Outcome(int ranked, int conflicting, int alreadyRanked, int leftAlone, List<String> conflicts) {
        public static final Outcome NONE = new Outcome(0, 0, 0, 0, List.of());
    }

    public Outcome rank(List<ActivityFileImportService.ReportedLevel> reported, String domain, String actor, String source) {
        Map<String, String> nameByKey = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> levelsByKey = new LinkedHashMap<>();
        for (ActivityFileImportService.ReportedLevel r : reported == null ? List.<ActivityFileImportService.ReportedLevel>of() : reported) {
            String key = ArtisticEventRankSupport.normalize(r.eventName());
            if (key.isEmpty() || rankOf(r.level()) == null) continue;
            nameByKey.putIfAbsent(key, r.eventName().trim());
            levelsByKey.computeIfAbsent(key, k -> new TreeMap<>()).merge(r.level(), 1, Integer::sum);
        }
        Set<String> prizeKeys = new java.util.LinkedHashSet<>();
        for (ActivityFileImportService.ReportedLevel r : reported == null ? List.<ActivityFileImportService.ReportedLevel>of() : reported) {
            String key = ArtisticEventRankSupport.normalize(r.eventName());
            if (r.prize() && !key.isEmpty()) prizeKeys.add(key);
        }
        if (levelsByKey.isEmpty() && prizeKeys.isEmpty()) {
            return Outcome.NONE;
        }
        Map<String, ArtisticEvent> byKey = new HashMap<>();
        for (ArtisticEvent e : eventRepository.findAll()) {
            for (String k : RegistryReviewService.keysOf(e)) byKey.putIfAbsent(k, e);
        }
        List<Distinctive> rankedNames = new ArrayList<>();
        for (ArtisticEvent e : byKey.values()) {
            if (e.isConfirmed() && e.getRank() != null) {
                Set<String> words = distinctive(e.getName());
                if (!words.isEmpty()) rankedNames.add(new Distinctive(e.getName(), words));
            }
        }
        Instant now = Instant.now();
        int ranked = 0, conflicting = 0, already = 0, leftAlone = 0;
        List<String> conflicts = new ArrayList<>();
        for (Map.Entry<String, Map<String, Integer>> entry : levelsByKey.entrySet()) {
            Map<String, Integer> levels = entry.getValue();
            String note = source + ": " + levels.entrySet().stream()
                    .map(l -> l.getValue() + " × " + label(l.getKey())).collect(Collectors.joining(", "));
            ArtisticEvent event = byKey.get(entry.getKey());
            if (event != null && (event.getStatus() == RegistryStatus.REJECTED || event.getStatus() == RegistryStatus.MERGED)) {
                leftAlone++;
                continue;
            }
            boolean earlierBatch = event != null && event.getNote() != null && event.getNote().startsWith(source);
            String counts = levels.entrySet().stream().map(l -> l.getValue() + " × " + label(l.getKey()))
                    .collect(Collectors.joining(", "));
            if (event != null && event.isConfirmed() && event.getRank() != null) {
                if (earlierBatch && ArtisticEvent.Basis.FACULTY_CNFIS_REPORT.name().equals(event.getBasis())) {
                    // ranked by an earlier batch of the same report: the levels add up
                    String later = event.getNote() + " + " + counts;
                    if (levels.keySet().stream().map(ArtisticEventFacultyRanking::rankOf).allMatch(event.getRank()::equals)) {
                        event.setNote(later);
                        eventRepository.save(event);
                        already++;
                    } else {
                        String from = event.getRank().name();
                        event.setStatus(RegistryStatus.PROPOSED);
                        event.setRank(null);
                        event.setBasis(null);
                        event.setNote(later);
                        event.getHistory().add(RegistryReviewService.change(now, actor, "PROPOSED", from, null, later));
                        eventRepository.save(event);
                        conflicting++;
                        conflicts.add(event.getName() + " (" + later + ")");
                    }
                    continue;
                }
                already++;
                continue;
            }
            if (earlierBatch && event.getStatus() == RegistryStatus.PROPOSED) {
                // an earlier batch found different levels: still the experts'
                event.setNote(event.getNote() + " + " + counts);
                eventRepository.save(event);
                conflicting++;
                conflicts.add(event.getName() + " (" + event.getNote() + ")");
                continue;
            }
            if (event == null) {
                event = new ArtisticEvent();
                event.setName(nameByKey.get(entry.getKey()));
                event.setDomainId(domain);
                event.setSource(source);
                // no proposer: the uploader only carries the faculty's report, and the experts' page refuses a
                // proposer their own names (the first upload kept the uploader from deciding any of its 41)
                event.setProposedAt(now);
            } else if (event.getDomainId() == null) {
                event.setDomainId(domain);
            }
            String spelling = event.getId() != null ? null : likelySpellingOf(event.getName(), rankedNames);
            if (spelling != null) {
                note += "; poate fi «" + spelling + "» din registru";
            }
            if (levels.size() == 1 && spelling == null) {
                ArtisticEvent.Rank rank = rankOf(levels.keySet().iterator().next());
                String from = event.getRank() == null ? null : event.getRank().name();
                event.setRank(rank);
                event.setStatus(RegistryStatus.CONFIRMED);
                event.setBasis(ArtisticEvent.Basis.FACULTY_CNFIS_REPORT.name());
                event.setNote(note);
                event.setDecidedBy(actor);
                event.setDecidedAt(now);
                event.getHistory().add(RegistryReviewService.change(now, actor, "RANKED", from, rank.name(), note));
                ranked++;
            } else {
                event.setStatus(RegistryStatus.PROPOSED);
                event.setNote(note);
                event.getHistory().add(RegistryReviewService.change(now, actor, "PROPOSED", null, null, note));
                conflicting++;
                conflicts.add(event.getName() + " (" + note + ")");
            }
            eventRepository.save(event);
        }
        int competitions = markCompetitions(prizeKeys, now, actor, source);
        registrar.refresh();
        log.info("Faculty ranking from {} ({}): {} events ranked, {} reported at different levels, {} already ranked, {} left alone, "
                        + "{} marked competitions", source, domain, ranked, conflicting, already, leftAlone, competitions);
        return new Outcome(ranked, conflicting, already, leftAlone, List.copyOf(conflicts));
    }

    /**
     * A prize the faculty reported makes its event a competition (Adrian, 2026-10-03): RIA 2.3 counts a prize only at a
     * competition, and the faculty's report is the evidence. Only a ranked event without a kind; an expert's kind stays,
     * and a name still waiting gets its kind from the expert who ranks it. A merged name marks the entry it joined.
     */
    private int markCompetitions(Set<String> prizeKeys, Instant now, String actor, String source) {
        if (prizeKeys.isEmpty()) return 0;
        List<ArtisticEvent> all = eventRepository.findAll();
        Map<String, ArtisticEvent> byKey = new HashMap<>();
        Map<String, ArtisticEvent> byId = new HashMap<>();
        for (ArtisticEvent e : all) {
            if (e.getId() != null) byId.put(e.getId(), e);
            for (String k : RegistryReviewService.keysOf(e)) byKey.putIfAbsent(k, e);
        }
        int marked = 0;
        Set<String> done = new java.util.HashSet<>();
        for (String key : prizeKeys) {
            ArtisticEvent event = byKey.get(key);
            if (event != null && event.getStatus() == RegistryStatus.MERGED && event.getMergedInto() != null) {
                event = byId.get(event.getMergedInto());
            }
            if (event == null || !event.isConfirmed() || event.getRank() == null || event.getCategory() != null
                    || !done.add(String.valueOf(event.getId()))) {
                continue;
            }
            event.setCategory(ArtisticEvent.Kind.COMPETITION.name());
            if (event.getHistory() == null) event.setHistory(new ArrayList<>());
            event.getHistory().add(RegistryReviewService.change(now, actor, "EDITED", event.getLevel(), event.getLevel(),
                    source + ": concurs — fișa raportează un premiu la acest eveniment"));
            eventRepository.save(event);
            marked++;
        }
        return marked;
    }

    record Distinctive(String name, Set<String> words) {
        static Distinctive of(String name) {
            return new Distinctive(name, distinctive(name));
        }
    }

    /** Words that name no particular event: its kind, its reach, the art, the connectors. */
    private static final Set<String> GENERIC = Set.of("festivalul", "festivalului", "festival", "international", "internationala",
            "national", "nationala", "de", "si", "al", "a", "ale", "din", "la", "pentru", "muzica", "muzicii", "noua",
            "contemporana", "concursul", "concursului", "concurs", "stagiunea", "gala", "editia", "romania", "the", "of", "and",
            "music", "festivalul-concurs", "zilele", "saptamana");

    /**
     * The words of a name that tell its event apart: those outside the parentheses ("Festivalul «George Enescu»
     * (România)" → george, enescu), or all of them when fewer than two are outside ("Iaşi (Festivalul Internaţional de
     * Teatru pentru Publicul Tânăr FITPTI)" → iasi, teatru, publicul, tanar, fitpti — not iasi alone).
     */
    static Set<String> distinctive(String name) {
        String full = name == null ? "" : name;
        Set<String> outside = words(full.replaceAll("\\([^)]*\\)", " "));
        return outside.size() >= 2 ? outside : words(full.replace('(', ' ').replace(')', ' '));
    }

    private static Set<String> words(String text) {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String w : ArtisticEventRankSupport.normalize(text).split(" ")) {
            if (w.length() >= 3 && !GENERIC.contains(w)) out.add(w);
        }
        return out;
    }

    /**
     * The ranked event whose distinctive words the name holds, all of them and at least two — likely a spelling of
     * it; else null. One word is too little: «teatru», «iasi» or «noi» name no particular event (a first upload took
     * every concert of the «Facultatea de Muzică și Teatru» for the National Theatre Festival).
     */
    static String likelySpellingOf(String name, List<Distinctive> ranked) {
        Set<String> words = new java.util.HashSet<>(List.of(ArtisticEventRankSupport.normalize(name).split(" ")));
        for (Distinctive d : ranked) {
            if (d.words().size() >= 2 && words.containsAll(d.words())) return d.name();
        }
        return null;
    }

    /** The registry's rank for a CNFIS level: top international, international, national. */
    static ArtisticEvent.Rank rankOf(String cnfisLevel) {
        if (cnfisLevel == null) return null;
        return switch (cnfisLevel) {
            case "INTERNATIONAL_TOP" -> ArtisticEvent.Rank.INTERNATIONAL_TOP;
            case "INTERNATIONAL" -> ArtisticEvent.Rank.INTERNATIONAL;
            case "NATIONAL" -> ArtisticEvent.Rank.NATIONAL;
            default -> null;
        };
    }

    private static String label(String cnfisLevel) {
        return switch (cnfisLevel) {
            case "INTERNATIONAL_TOP" -> "internațional de vârf";
            case "INTERNATIONAL" -> "internațional";
            default -> "național";
        };
    }
}
