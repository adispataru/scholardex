package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.ArtisticEvent;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.repository.ArtisticEventRepository;
import ro.uvt.pokedex.core.service.reporting.ArtisticEventRankSupport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * H142 slice 3 — the researcher's side of the registry of artistic events: the names to pick from when declaring a
 * performance (ranked events and their spellings, and the names already waiting for the experts, so that a second
 * person joins the same proposal), and what the registry says about the event each record names.
 */
@Service
@RequiredArgsConstructor
public class ArtisticEventLookupFacade {

    private static final int MAX_SUGGESTIONS = 15;

    private final ArtisticEventRepository eventRepository;
    private final ActivityInstanceRepository activityInstanceRepository;

    /** One name to pick: the name as written, its rank (null while waiting), its domain, and the event it spells. */
    public record EventSuggestion(String name, String rank, String domain, String status, String spells) {
    }

    /** What the registry says about the event a record names. */
    public record EventLevel(String event, String status, String rank, String basis, String note) {
    }

    public List<EventSuggestion> search(String query) {
        String q = ArtisticEventRankSupport.normalize(query);
        if (q.length() < 2) {
            return List.of();
        }
        List<EventSuggestion> out = new ArrayList<>();
        for (ArtisticEvent e : eventRepository.findAll()) {
            boolean ranked = e.isConfirmed() && e.getRank() != null;
            boolean waiting = e.getStatus() == ArtisticEvent.Status.PROPOSED;
            if (!ranked && !waiting) {
                continue;
            }
            List<String> names = new ArrayList<>();
            names.add(e.getName());
            if (e.getAliases() != null) names.addAll(e.getAliases());
            for (String name : names) {
                if (name != null && ArtisticEventRankSupport.normalize(name).contains(q)) {
                    out.add(new EventSuggestion(name, ranked ? e.getRank().name() : null, e.getDomainId(),
                            ranked ? "RANKED" : "AWAITING_RANK", name.equals(e.getName()) ? null : e.getName()));
                }
            }
        }
        out.sort(Comparator.comparing((EventSuggestion s) -> !ArtisticEventRankSupport.normalize(s.name()).startsWith(q))
                .thenComparing(s -> s.rank() == null).thenComparing(EventSuggestion::name));
        return out.size() > MAX_SUGGESTIONS ? out.subList(0, MAX_SUGGESTIONS) : out;
    }

    /** Per record of the researcher that names an event: its rank, or that the experts have not ranked it. */
    public Map<String, EventLevel> levelsFor(String researcherEmail) {
        Map<String, EventLevel> out = new LinkedHashMap<>();
        if (researcherEmail == null || researcherEmail.isBlank()) {
            return out;
        }
        List<ArtisticEvent> all = null;
        for (ActivityInstance r : activityInstanceRepository.findAllByResearcherId(researcherEmail)) {
            String event = r.getReferenceFields() == null ? null : r.getReferenceFields().get(Activity.ReferenceField.EVENT_NAME);
            if (event == null || event.isBlank()) {
                continue;
            }
            if (all == null) {
                all = eventRepository.findAll();
            }
            String key = ArtisticEventRankSupport.normalize(event);
            ArtisticEvent decided = all.stream().filter(e -> ArtisticEventReviewService.keysOf(e).contains(key))
                    .filter(e -> e.isConfirmed() || e.getStatus() == ArtisticEvent.Status.REJECTED)
                    .min(Comparator.comparing((ArtisticEvent e) -> e.isConfirmed() ? 0 : 1)).orElse(null);
            if (decided != null && decided.isConfirmed() && decided.getRank() != null) {
                out.put(r.getId(), new EventLevel(decided.getName(), "RANKED", decided.getRank().name(),
                        decided.getBasis() != null ? decided.getBasis()
                                : decided.getStatus() == null ? ArtisticEvent.Basis.CNFIS_LIST.name() : null,
                        decided.getNote()));
            } else if (decided != null) {
                out.put(r.getId(), new EventLevel(event, "REJECTED", null, null, decided.getNote()));
            } else {
                out.put(r.getId(), new EventLevel(event, "AWAITING_RANK", null, null, null));
            }
        }
        return out;
    }
}
