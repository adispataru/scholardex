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
 * rather than a second rank; a rejected or merged name is left alone.
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
        if (levelsByKey.isEmpty()) {
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
            if (event != null && event.isConfirmed() && event.getRank() != null) {
                already++;
                continue;
            }
            if (event == null) {
                event = new ArtisticEvent();
                event.setName(nameByKey.get(entry.getKey()));
                event.setDomainId(domain);
                event.setSource(source);
                event.setProposedBy(actor);
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
        registrar.refresh();
        log.info("Faculty ranking from {} ({}): {} events ranked, {} reported at different levels, {} already ranked, {} left alone",
                source, domain, ranked, conflicting, already, leftAlone);
        return new Outcome(ranked, conflicting, already, leftAlone, List.copyOf(conflicts));
    }

    private record Distinctive(String name, Set<String> words) {
    }

    /** Words that name no particular event: its kind, its reach, the art, the connectors. */
    private static final Set<String> GENERIC = Set.of("festivalul", "festivalului", "festival", "international", "internationala",
            "national", "nationala", "de", "si", "al", "a", "ale", "din", "la", "pentru", "muzica", "muzicii", "noua",
            "contemporana", "concursul", "concursului", "concurs", "stagiunea", "gala", "editia", "romania", "the", "of", "and",
            "music", "festivalul-concurs", "zilele", "saptamana");

    /** The words of a name that tell its event apart (the parenthesised place dropped). */
    static Set<String> distinctive(String name) {
        String withoutPlace = name == null ? "" : name.replaceAll("\\([^)]*\\)", " ");
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String w : ArtisticEventRankSupport.normalize(withoutPlace).split(" ")) {
            if (w.length() >= 3 && !GENERIC.contains(w)) out.add(w);
        }
        return out;
    }

    /** The ranked event whose distinctive words the name holds, all of them — likely a spelling of it; else null. */
    private static String likelySpellingOf(String name, List<Distinctive> ranked) {
        Set<String> words = new java.util.HashSet<>(List.of(ArtisticEventRankSupport.normalize(name).split(" ")));
        for (Distinctive d : ranked) {
            if (words.containsAll(d.words())) return d.name();
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
