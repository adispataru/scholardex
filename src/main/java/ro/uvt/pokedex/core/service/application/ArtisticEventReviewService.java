package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.ArtisticEvent;
import ro.uvt.pokedex.core.model.ArtisticEventDomainExperts;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.repository.ArtisticEventDomainExpertsRepository;
import ro.uvt.pokedex.core.repository.ArtisticEventRepository;
import ro.uvt.pokedex.core.service.reporting.ArtisticEventRankRegistrar;
import ro.uvt.pokedex.core.service.reporting.ArtisticEventRankSupport;
import ro.uvt.pokedex.core.service.security.ArtisticEventAccessService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * H142 slice 3 — the experts' side of the registry of artistic events. A name the registry does not know is a
 * proposal — stored when an institutional table brought it, or derived from the records that name it — shared by
 * every record naming it. An expert of its domain ranks it once, for everyone (a confirmed event whose other spellings
 * become aliases), merges it into an event as a spelling, or rejects the name; every decision is kept in the event's
 * history and reloads the registry the scoring reads.
 *
 * <p><b>Never one's own:</b> nobody, an admin included, decides on an event their own records name or that they
 * proposed.</p>
 */
@Service
@RequiredArgsConstructor
public class ArtisticEventReviewService {

    private static final Logger log = LoggerFactory.getLogger(ArtisticEventReviewService.class);
    static final int NOTE_MAX = 1000;
    private static final int EVIDENCE_MAX = 5;

    private final ArtisticEventRepository eventRepository;
    private final ArtisticEventDomainExpertsRepository domainExpertsRepository;
    private final ActivityInstanceRepository activityInstanceRepository;
    private final ArtisticEventAccessService access;
    private final ArtisticEventRankRegistrar registrar;

    /** One name waiting for the experts, with what the records say about it. */
    public record QueueEntry(String key, String name, List<String> spellings, Set<String> domains, int records,
                             int researchers, List<Integer> years, List<String> evidence, Map<String, Integer> suggestions,
                             String source, boolean canDecide, String refusalKey) {
    }

    /** A confirmed event, as the experts edit it. */
    public record EventView(String id, String name, List<String> aliases, String domain, ArtisticEvent.Rank rank,
                            ArtisticEvent.Kind kind, String country, String basis, String note, String decidedBy,
                            Instant decidedAt, boolean canEdit) {
    }

    /** One decision of the history, for the page. */
    public record DecisionView(Instant at, String by, String action, String event, ArtisticEvent.Rank fromRank,
                               ArtisticEvent.Rank toRank, String note, String eventId, ArtisticEvent.Status status) {
    }

    public record ReviewPage(List<QueueEntry> queue, List<EventView> events, List<DecisionView> decisions,
                             List<String> domains, boolean admin, List<MergeTarget> mergeTargets) {
    }

    /** A ranked event a waiting name can be merged into, as the merge list shows it. */
    public record MergeTarget(String id, String name, String domain, ArtisticEvent.Rank rank) {
    }

    /** What an expert decides about a rank. */
    public record RankForm(ArtisticEvent.Rank rank, ArtisticEvent.Kind kind, String country, String basis, String note,
                           String domain) {
    }

    /** The outcome of a decision: done, or refused with a reason (message keys). */
    public record Outcome(boolean done, String messageKey) {
        static Outcome done(String key) { return new Outcome(true, key); }
        static Outcome refused(String key) { return new Outcome(false, key); }
    }

    // ── the page ──────────────────────────────────────────────────────────────

    public ReviewPage page(Authentication auth, String query) {
        boolean admin = access.isPlatformAdmin(auth);
        Set<String> mine = access.domainsOf(auth);
        List<ArtisticEvent> all = eventRepository.findAll();
        Map<String, Set<String>> ownNames = ownEventKeys(auth);
        List<QueueEntry> queue = queue(all, auth).stream()
                .filter(e -> admin || e.domains().stream().anyMatch(mine::contains))
                .toList();
        String q = query == null ? "" : ArtisticEventRankSupport.normalize(query);
        List<EventView> events = all.stream()
                .filter(ArtisticEvent::isConfirmed)
                .filter(e -> admin || mine.contains(e.getDomainId()))
                .filter(e -> q.isEmpty() || keysOf(e).stream().anyMatch(k -> k.contains(q)))
                .sorted(Comparator.comparing((ArtisticEvent e) -> e.getDecidedAt() == null ? Instant.EPOCH : e.getDecidedAt())
                        .reversed().thenComparing(e -> e.getName() == null ? "" : e.getName()))
                .limit(q.isEmpty() ? 50 : 200)
                .map(e -> new EventView(e.getId(), e.getName(), aliases(e), e.getDomainId(), e.getRank(), e.getKind(),
                        e.getCountry(), basisOf(e), e.getNote(), e.getDecidedBy(), e.getDecidedAt(),
                        keysOf(e).stream().noneMatch(ownNames.getOrDefault("records", Set.of())::contains)))
                .toList();
        // every ranked event of the viewer's domains, not only the table's first rows: a spelling may belong to any
        List<MergeTarget> mergeTargets = all.stream()
                .filter(ArtisticEvent::isConfirmed)
                .filter(e -> e.getRank() != null && e.getId() != null)
                .filter(e -> admin || mine.contains(e.getDomainId()))
                .map(e -> new MergeTarget(e.getId(), e.getName(), e.getDomainId(), e.getRank()))
                .sorted(Comparator.comparing((MergeTarget t) -> t.domain() == null ? "" : t.domain())
                        .thenComparing(t -> t.name() == null ? "" : t.name(), String.CASE_INSENSITIVE_ORDER))
                .toList();
        // decisions only: a name proposed from a file is not one
        List<DecisionView> decisions = all.stream()
                .filter(e -> admin || mine.contains(e.getDomainId()))
                .flatMap(e -> (e.getHistory() == null ? List.<ArtisticEvent.Change>of() : e.getHistory()).stream()
                        .filter(c -> !"PROPOSED".equals(c.getAction()))
                        .map(c -> new DecisionView(c.getAt(), c.getBy(), c.getAction(), e.getName(), c.getFromRank(),
                                c.getToRank(), c.getNote(), e.getId(), e.getStatus())))
                .sorted(Comparator.comparing((DecisionView d) -> d.at() == null ? Instant.EPOCH : d.at()).reversed())
                .limit(50)
                .toList();
        List<String> domains = admin ? configuredDomains(all) : List.copyOf(mine);
        return new ReviewPage(queue, events, decisions, domains, admin, mergeTargets);
    }

    /** Every name waiting for the experts, whatever the viewer may decide (the viewer's refusals are filled in). */
    List<QueueEntry> queue(List<ArtisticEvent> all, Authentication auth) {
        Set<String> decided = new java.util.HashSet<>();
        Map<String, ArtisticEvent> stored = new LinkedHashMap<>();
        for (ArtisticEvent e : all) {
            if (e.isConfirmed() || e.getStatus() == ArtisticEvent.Status.REJECTED || e.getStatus() == ArtisticEvent.Status.MERGED) {
                decided.addAll(keysOf(e));
            } else if (e.getStatus() == ArtisticEvent.Status.PROPOSED) {
                for (String k : keysOf(e)) stored.putIfAbsent(k, e);
            }
        }
        // the records naming a name nobody decided, grouped by the stored proposal they spell or by their own name
        Map<String, List<ActivityInstance>> byEntry = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> spellings = new LinkedHashMap<>();
        for (ActivityInstance r : activityInstanceRepository.findAllNamingAnEvent()) {
            String name = eventName(r);
            String key = ArtisticEventRankSupport.normalize(name);
            if (key.isEmpty() || decided.contains(key)) continue;
            String entry = stored.containsKey(key) ? ArtisticEventRankSupport.normalize(stored.get(key).getName()) : key;
            byEntry.computeIfAbsent(entry, k -> new ArrayList<>()).add(r);
            spellings.computeIfAbsent(entry, k -> new LinkedHashMap<>()).merge(name.trim(), 1, Integer::sum);
        }
        for (ArtisticEvent p : new LinkedHashSet<>(stored.values())) {
            String entry = ArtisticEventRankSupport.normalize(p.getName());
            byEntry.putIfAbsent(entry, new ArrayList<>());
            Map<String, Integer> s = spellings.computeIfAbsent(entry, k -> new LinkedHashMap<>());
            s.putIfAbsent(p.getName(), 0);
            aliases(p).forEach(a -> s.putIfAbsent(a, 0));
        }
        List<ArtisticEventDomainExperts> settings = domainExpertsRepository.findAll();
        Map<String, Set<String>> domainsByResearcher = new HashMap<>();
        String me = me(auth);
        List<QueueEntry> entries = new ArrayList<>();
        for (Map.Entry<String, List<ActivityInstance>> group : byEntry.entrySet()) {
            String key = group.getKey();
            List<ActivityInstance> records = group.getValue();
            ArtisticEvent proposal = stored.get(key);
            Map<String, Integer> names = spellings.getOrDefault(key, Map.of());
            // the spelling most records use (the first written, among equals)
            String name = proposal != null ? proposal.getName() : names.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse(key);
            Set<String> researchers = records.stream().map(ActivityInstance::getResearcherId)
                    .filter(id -> id != null && !id.isBlank()).map(id -> id.trim().toLowerCase(Locale.ROOT))
                    .collect(Collectors.toCollection(TreeSet::new));
            Set<String> domains = new TreeSet<>();
            if (proposal != null && proposal.getDomainId() != null) domains.add(proposal.getDomainId());
            for (String researcher : researchers) {
                domains.addAll(domainsByResearcher.computeIfAbsent(researcher, r -> access.domainsOfResearcher(r, settings)));
            }
            List<Integer> years = records.stream().map(r -> year(r.getDate())).filter(y -> y != null).distinct().sorted().toList();
            List<String> evidence = records.stream().map(r -> r.getFields() == null ? null : r.getFields().get("Dovezi"))
                    .filter(v -> v != null && !v.isBlank()).distinct().limit(EVIDENCE_MAX).toList();
            Map<String, Integer> suggestions = new TreeMap<>();
            records.stream().map(ActivityInstance::getEventLevelSuggestion).filter(s -> s != null && !s.isBlank())
                    .forEach(s -> suggestions.merge(s, 1, Integer::sum));
            String refusal = null;
            if (me != null && (researchers.contains(me) || (proposal != null && me.equalsIgnoreCase(String.valueOf(proposal.getProposedBy()))))) {
                refusal = "artisticEvents.refused.own";
            } else if (!access.isPlatformAdmin(auth) && domains.stream().noneMatch(d -> access.canReview(d, auth))) {
                refusal = "artisticEvents.refused.domain";
            }
            entries.add(new QueueEntry(key, name, new ArrayList<>(names.keySet()), domains, records.size(),
                    researchers.size(), years, evidence, suggestions,
                    proposal != null && proposal.getSource() != null ? proposal.getSource() : "artisticEvents.source.records",
                    refusal == null, refusal));
        }
        entries.sort(Comparator.comparing(QueueEntry::records).reversed().thenComparing(QueueEntry::name));
        return entries;
    }

    // ── decisions ─────────────────────────────────────────────────────────────

    /** Ranks one or more waiting names, each as its own event, with the same rank, kind and basis. */
    public Outcome rank(List<String> keys, RankForm form, Authentication auth) {
        if (keys == null || keys.isEmpty()) return Outcome.refused("artisticEvents.refused.none");
        if (form == null || form.rank() == null || form.basis() == null || form.basis().isBlank()) {
            return Outcome.refused("artisticEvents.refused.rank");
        }
        String note = trim(form.note());
        if (note != null && note.length() > NOTE_MAX) return Outcome.refused("artisticEvents.refused.note");
        List<ArtisticEvent> all = eventRepository.findAll();
        Map<String, QueueEntry> waiting = queue(all, auth).stream().collect(Collectors.toMap(QueueEntry::key, e -> e, (a, b) -> a));
        List<QueueEntry> chosen = new ArrayList<>();
        for (String key : keys) {
            QueueEntry entry = waiting.get(key);
            if (entry == null) return Outcome.refused("artisticEvents.refused.gone");
            if (!entry.canDecide()) return Outcome.refused(entry.refusalKey());
            String domain = domainFor(entry, form.domain(), auth);
            if (domain == null) return Outcome.refused("artisticEvents.refused.domain");
            chosen.add(entry);
        }
        Instant now = Instant.now();
        for (QueueEntry entry : chosen) {
            ArtisticEvent event = storedFor(all, entry.key()).orElseGet(ArtisticEvent::new);
            boolean fresh = event.getId() == null;
            if (fresh) {
                event.setName(entry.name());
                event.setSource(entry.source());
            }
            event.setStatus(ArtisticEvent.Status.CONFIRMED);
            event.setRank(form.rank());
            event.setKind(form.kind());
            event.setCountry(trim(form.country()));
            event.setBasis(form.basis());
            event.setNote(note);
            event.setDomainId(domainFor(entry, form.domain(), auth));
            addAliases(event, entry.spellings());
            event.setDecidedBy(me(auth));
            event.setDecidedAt(now);
            history(event).add(change(now, me(auth), "RANKED", null, form.rank(), note));
            eventRepository.save(event);
        }
        registrar.refresh();
        log.info("Artistic events ranked by {}: {} ({})", me(auth), chosen.size(), form.rank());
        return Outcome.done(chosen.size() == 1 ? "artisticEvents.done.ranked" : "artisticEvents.done.rankedMany");
    }

    /** Makes a waiting name a spelling of a confirmed event: its records take that event's rank. */
    /**
     * Merges every ticked name into one ranked event, as its spellings; stops at the first name the viewer may not
     * decide (the names before it stay merged).
     */
    public Outcome mergeAll(List<String> keys, String targetId, Authentication auth) {
        if (keys == null || keys.isEmpty()) return Outcome.refused("artisticEvents.refused.none");
        if (targetId == null || targetId.isBlank()) return Outcome.refused("artisticEvents.refused.target");
        for (String key : keys) {
            Outcome outcome = merge(key, targetId, auth);
            if (!outcome.done()) return outcome;
        }
        return Outcome.done(keys.size() == 1 ? "artisticEvents.done.merged" : "artisticEvents.done.mergedMany");
    }

    public Outcome merge(String key, String targetId, Authentication auth) {
        List<ArtisticEvent> all = eventRepository.findAll();
        QueueEntry entry = queue(all, auth).stream().filter(e -> e.key().equals(key)).findFirst().orElse(null);
        if (entry == null) return Outcome.refused("artisticEvents.refused.gone");
        if (!entry.canDecide()) return Outcome.refused(entry.refusalKey());
        ArtisticEvent target = all.stream().filter(e -> e.getId() != null && e.getId().equals(targetId))
                .filter(ArtisticEvent::isConfirmed).findFirst().orElse(null);
        if (target == null) return Outcome.refused("artisticEvents.refused.target");
        if (!access.canReview(target.getDomainId(), auth)) return Outcome.refused("artisticEvents.refused.domain");
        if (ownEventKeys(auth).getOrDefault("records", Set.of()).stream().anyMatch(keysOf(target)::contains)) {
            return Outcome.refused("artisticEvents.refused.own");
        }
        Instant now = Instant.now();
        addAliases(target, entry.spellings());
        history(target).add(change(now, me(auth), "MERGED", target.getRank(), target.getRank(),
                String.join(" · ", entry.spellings())));
        eventRepository.save(target);
        storedFor(all, key).ifPresent(p -> {
            p.setStatus(ArtisticEvent.Status.MERGED);
            p.setMergedInto(target.getId());
            p.setDecidedBy(me(auth));
            p.setDecidedAt(now);
            history(p).add(change(now, me(auth), "MERGED", null, null, target.getName()));
            eventRepository.save(p);
        });
        registrar.refresh();
        return Outcome.done("artisticEvents.done.merged");
    }

    /** Rejects a waiting name (not an event, or not one to rank): it leaves the queue; its records keep the floor. */
    public Outcome reject(String key, String note, Authentication auth) {
        String reason = trim(note);
        if (reason == null) return Outcome.refused("artisticEvents.refused.noteRequired");
        if (reason.length() > NOTE_MAX) return Outcome.refused("artisticEvents.refused.note");
        List<ArtisticEvent> all = eventRepository.findAll();
        QueueEntry entry = queue(all, auth).stream().filter(e -> e.key().equals(key)).findFirst().orElse(null);
        if (entry == null) return Outcome.refused("artisticEvents.refused.gone");
        if (!entry.canDecide()) return Outcome.refused(entry.refusalKey());
        Instant now = Instant.now();
        ArtisticEvent event = storedFor(all, key).orElseGet(ArtisticEvent::new);
        if (event.getId() == null) {
            event.setName(entry.name());
            event.setSource(entry.source());
            event.setDomainId(entry.domains().stream().findFirst().orElse(null));
        }
        event.setStatus(ArtisticEvent.Status.REJECTED);
        event.setRank(null);
        event.setNote(reason);
        addAliases(event, entry.spellings());
        event.setDecidedBy(me(auth));
        event.setDecidedAt(now);
        history(event).add(change(now, me(auth), "REJECTED", null, null, reason));
        eventRepository.save(event);
        registrar.refresh();
        return Outcome.done("artisticEvents.done.rejected");
    }

    /** Sends a rejected name back to the queue. */
    public Outcome reopen(String eventId, Authentication auth) {
        ArtisticEvent event = eventRepository.findById(eventId).orElse(null);
        if (event == null || event.getStatus() != ArtisticEvent.Status.REJECTED) return Outcome.refused("artisticEvents.refused.gone");
        if (!access.canReview(event.getDomainId(), auth)) return Outcome.refused("artisticEvents.refused.domain");
        Instant now = Instant.now();
        event.setStatus(ArtisticEvent.Status.PROPOSED);
        history(event).add(change(now, me(auth), "REOPENED", null, null, null));
        eventRepository.save(event);
        registrar.refresh();
        return Outcome.done("artisticEvents.done.reopened");
    }

    /** Changes the rank or the details of a confirmed event; every record naming it follows. */
    public Outcome edit(String eventId, RankForm form, Authentication auth) {
        ArtisticEvent event = eventRepository.findById(eventId).filter(ArtisticEvent::isConfirmed).orElse(null);
        if (event == null) return Outcome.refused("artisticEvents.refused.gone");
        if (form == null || form.rank() == null || form.basis() == null || form.basis().isBlank()) {
            return Outcome.refused("artisticEvents.refused.rank");
        }
        String note = trim(form.note());
        if (note != null && note.length() > NOTE_MAX) return Outcome.refused("artisticEvents.refused.note");
        if (!access.canReview(event.getDomainId(), auth)) return Outcome.refused("artisticEvents.refused.domain");
        if (ownEventKeys(auth).getOrDefault("records", Set.of()).stream().anyMatch(keysOf(event)::contains)) {
            return Outcome.refused("artisticEvents.refused.own");
        }
        Instant now = Instant.now();
        ArtisticEvent.Rank before = event.getRank();
        event.setRank(form.rank());
        event.setKind(form.kind());
        event.setCountry(trim(form.country()));
        event.setBasis(form.basis());
        event.setNote(note);
        if (event.getStatus() == null) event.setStatus(ArtisticEvent.Status.CONFIRMED);
        event.setDecidedBy(me(auth));
        event.setDecidedAt(now);
        history(event).add(change(now, me(auth), "EDITED", before, form.rank(), note));
        eventRepository.save(event);
        registrar.refresh();
        return Outcome.done("artisticEvents.done.edited");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String domainFor(QueueEntry entry, String requested, Authentication auth) {
        String wanted = trim(requested);
        if (wanted != null) {
            return access.canReview(wanted, auth) ? wanted : null;
        }
        return entry.domains().stream().filter(d -> access.canReview(d, auth)).findFirst()
                .orElse(access.isPlatformAdmin(auth) ? entry.domains().stream().findFirst().orElse(null) : null);
    }

    /** The normalised event names the viewer's own records give ("records"). */
    private Map<String, Set<String>> ownEventKeys(Authentication auth) {
        String me = me(auth);
        if (me == null) return Map.of();
        Set<String> keys = activityInstanceRepository.findAllByResearcherId(me).stream()
                .map(this::eventName).map(ArtisticEventRankSupport::normalize).filter(k -> !k.isEmpty())
                .collect(Collectors.toSet());
        return Map.of("records", keys);
    }

    private static Optional<ArtisticEvent> storedFor(List<ArtisticEvent> all, String key) {
        return all.stream().filter(e -> e.getStatus() == ArtisticEvent.Status.PROPOSED
                        || e.getStatus() == ArtisticEvent.Status.REJECTED)
                .filter(e -> keysOf(e).contains(key)).findFirst();
    }

    static Set<String> keysOf(ArtisticEvent e) {
        Set<String> keys = new LinkedHashSet<>();
        String k = ArtisticEventRankSupport.normalize(e.getName());
        if (!k.isEmpty()) keys.add(k);
        for (String a : aliases(e)) {
            String ak = ArtisticEventRankSupport.normalize(a);
            if (!ak.isEmpty()) keys.add(ak);
        }
        return keys;
    }

    private static List<String> aliases(ArtisticEvent e) {
        return e.getAliases() == null ? List.of() : e.getAliases();
    }

    private static void addAliases(ArtisticEvent event, List<String> spellings) {
        if (event.getAliases() == null) event.setAliases(new ArrayList<>());
        Set<String> known = keysOf(event);
        for (String s : spellings) {
            String k = ArtisticEventRankSupport.normalize(s);
            if (!k.isEmpty() && known.add(k)) {
                event.getAliases().add(s.trim());
            }
        }
    }

    private static List<ArtisticEvent.Change> history(ArtisticEvent event) {
        if (event.getHistory() == null) event.setHistory(new ArrayList<>());
        return event.getHistory();
    }

    private static ArtisticEvent.Change change(Instant at, String by, String action, ArtisticEvent.Rank from,
                                               ArtisticEvent.Rank to, String note) {
        ArtisticEvent.Change c = new ArtisticEvent.Change();
        c.setAt(at);
        c.setBy(by);
        c.setAction(action);
        c.setFromRank(from);
        c.setToRank(to);
        c.setNote(note);
        return c;
    }

    private static String basisOf(ArtisticEvent e) {
        if (e.getBasis() != null) return e.getBasis();
        return e.getStatus() == null ? ArtisticEvent.Basis.CNFIS_LIST.name() : null;
    }

    private List<String> configuredDomains(List<ArtisticEvent> all) {
        Set<String> domains = new TreeSet<>();
        all.stream().map(ArtisticEvent::getDomainId).filter(d -> d != null && !d.isBlank()).forEach(domains::add);
        domainExpertsRepository.findAll().forEach(s -> domains.add(s.getDomain()));
        return List.copyOf(domains);
    }

    private String eventName(ActivityInstance r) {
        return r.getReferenceFields() == null ? "" : String.valueOf(
                r.getReferenceFields().getOrDefault(Activity.ReferenceField.EVENT_NAME, ""));
    }

    private static Integer year(String date) {
        if (date == null || date.length() < 4) return null;
        try {
            return Integer.parseInt(date.substring(0, 4));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String me(Authentication auth) {
        return auth == null || auth.getName() == null ? null : auth.getName().trim().toLowerCase(Locale.ROOT);
    }

    private static String trim(String value) {
        if (value == null) return null;
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }
}
