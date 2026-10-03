package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.registry.RegistryChange;
import ro.uvt.pokedex.core.model.registry.RegistryDomainExperts;
import ro.uvt.pokedex.core.model.registry.RegistryItem;
import ro.uvt.pokedex.core.model.registry.RegistryKind;
import ro.uvt.pokedex.core.model.registry.RegistryStatus;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.repository.RegistryDomainExpertsRepository;
import ro.uvt.pokedex.core.service.reporting.ArtisticEventRankSupport;
import ro.uvt.pokedex.core.service.security.RegistryAccessService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
 * H142 slice 3, H144 — the experts' side of the registries (artistic events, conferences, organisations, awards). A
 * name the registry does not know is a proposal — stored when an institutional table brought it, or derived from the
 * records that name it — shared by every record naming it. An expert of its domain ranks it once, for everyone (an
 * entry whose other spellings become aliases), merges it into an entry as a spelling, or rejects the name; every
 * decision is kept in the entry's history and reloads the registry the scoring reads.
 *
 * <p><b>Never one's own:</b> nobody, an admin included, decides on a name their own records give or that they
 * proposed.</p>
 */
@Service
@RequiredArgsConstructor
public class RegistryReviewService {

    private static final Logger log = LoggerFactory.getLogger(RegistryReviewService.class);
    static final int NOTE_MAX = 1000;
    private static final int EVIDENCE_MAX = 5;
    private static final int COUNTRY_MAX = 80;
    /** {@link #domainFor}: the entry is shared, of no domain. */
    private static final String SHARED = "";

    private final RegistryStores stores;
    private final RegistryDomainExpertsRepository domainExpertsRepository;
    private final ActivityInstanceRepository activityInstanceRepository;
    private final RegistryAccessService access;

    /** One name waiting for the experts, with what the records say about it. */
    public record QueueEntry(String key, String name, List<String> spellings, Set<String> domains, int records,
                             int researchers, List<Integer> years, List<String> evidence, Map<String, Integer> suggestions,
                             String source, boolean canDecide, String refusalKey) {
    }

    /** A confirmed entry, as the experts edit it. */
    public record ItemView(String id, String name, List<String> aliases, String domain, String level, String category,
                           List<String> criteria, String country, String basis, String note, String decidedBy,
                           Instant decidedAt, boolean canEdit) {
    }

    /** One decision of the history, for the page. */
    public record DecisionView(Instant at, String by, String action, String item, String fromLevel, String toLevel,
                               String note, String itemId, RegistryStatus status) {
    }

    /** A ranked entry a waiting name can be merged into, as the merge list shows it. */
    public record MergeTarget(String id, String name, String domain, String level) {
    }

    /** A registry and how many names wait in it for the viewer. */
    public record KindTab(RegistryKind kind, int waiting) {
    }

    public record ReviewPage(RegistryKind kind, List<KindTab> tabs, List<QueueEntry> queue, List<ItemView> items,
                             List<DecisionView> decisions, List<String> domains, boolean admin,
                             List<MergeTarget> mergeTargets) {
    }

    /** What an expert decides: the level, the category, the criteria ticked, the country, the basis and a note. */
    public record RankForm(String level, String category, List<String> criteria, String country, String basis,
                           String note, String domain) {
    }

    /** The outcome of a decision: done, or refused with a reason (message keys). */
    public record Outcome(boolean done, String messageKey) {
        static Outcome done(String key) { return new Outcome(true, key); }
        static Outcome refused(String key) { return new Outcome(false, key); }
    }

    // ── the page ──────────────────────────────────────────────────────────────

    public ReviewPage page(RegistryKind kind, Authentication auth, String query) {
        boolean admin = access.isPlatformAdmin(auth);
        Set<String> mine = new HashSet<>(access.domainsOf(auth)); // a domain-less entry asks contains(null)
        List<KindTab> tabs = new ArrayList<>();
        List<QueueEntry> queue = List.of();
        List<RegistryItem> all = List.of();
        for (RegistryKind k : RegistryKind.values()) {
            List<RegistryItem> items = stores.all(k);
            List<QueueEntry> waiting = queue(k, items, auth).stream()
                    .filter(e -> admin || e.domains().stream().anyMatch(mine::contains))
                    .toList();
            tabs.add(new KindTab(k, waiting.size()));
            if (k == kind) {
                queue = waiting;
                all = items;
            }
        }
        Set<String> ownNames = ownKeys(kind, auth);
        String q = query == null ? "" : ArtisticEventRankSupport.normalize(query);
        // an entry of no domain (a body every standard knows, from the initial list) is everyone's to see, an admin's to edit
        List<ItemView> items = all.stream()
                .filter(RegistryItem::isConfirmed)
                .filter(e -> admin || e.getDomainId() == null || mine.contains(e.getDomainId()))
                .filter(e -> q.isEmpty() || keysOf(e).stream().anyMatch(k -> k.contains(q)))
                .sorted(Comparator.comparing((RegistryItem e) -> e.getDecidedAt() == null ? Instant.EPOCH : e.getDecidedAt())
                        .reversed().thenComparing(e -> e.getName() == null ? "" : e.getName()))
                .limit(q.isEmpty() ? 50 : 200)
                .map(e -> new ItemView(e.getId(), e.getName(), aliases(e), e.getDomainId(), e.getLevel(), e.getCategory(),
                        e.getCriteria(), e.getCountry(), e.effectiveBasis(), e.getNote(), e.getDecidedBy(), e.getDecidedAt(),
                        (admin || e.getDomainId() != null) && keysOf(e).stream().noneMatch(ownNames::contains)))
                .toList();
        // every ranked entry of the viewer's domains, not only the table's first rows: a spelling may belong to any
        List<MergeTarget> mergeTargets = all.stream()
                .filter(RegistryItem::isConfirmed)
                .filter(e -> e.getLevel() != null && e.getId() != null)
                .filter(e -> admin || e.getDomainId() == null || mine.contains(e.getDomainId()))
                .map(e -> new MergeTarget(e.getId(), e.getName(), e.getDomainId(), e.getLevel()))
                .sorted(Comparator.comparing((MergeTarget t) -> t.domain() == null ? "" : t.domain())
                        .thenComparing(t -> t.name() == null ? "" : t.name(), String.CASE_INSENSITIVE_ORDER))
                .toList();
        // decisions only: a name proposed from a file is not one
        List<DecisionView> decisions = all.stream()
                .filter(e -> admin || e.getDomainId() == null || mine.contains(e.getDomainId()))
                .flatMap(e -> (e.getHistory() == null ? List.<RegistryChange>of() : e.getHistory()).stream()
                        .filter(c -> !"PROPOSED".equals(c.getAction()))
                        .map(c -> new DecisionView(c.getAt(), c.getBy(), c.getAction(), e.getName(), c.getFromLevel(),
                                c.getToLevel(), c.getNote(), e.getId(), e.getStatus())))
                .sorted(Comparator.comparing((DecisionView d) -> d.at() == null ? Instant.EPOCH : d.at()).reversed())
                .limit(50)
                .toList();
        List<String> domains = admin ? configuredDomains() : List.copyOf(mine);
        return new ReviewPage(kind, tabs, queue, items, decisions, domains, admin, mergeTargets);
    }

    /** Every name waiting for the experts, whatever the viewer may decide (the viewer's refusals are filled in). */
    List<QueueEntry> queue(RegistryKind kind, List<RegistryItem> all, Authentication auth) {
        Set<String> decided = new HashSet<>();
        Map<String, RegistryItem> stored = new LinkedHashMap<>();
        for (RegistryItem e : all) {
            if (e.isConfirmed() || e.getStatus() == RegistryStatus.REJECTED || e.getStatus() == RegistryStatus.MERGED) {
                decided.addAll(keysOf(e));
            } else if (e.getStatus() == RegistryStatus.PROPOSED) {
                for (String k : keysOf(e)) stored.putIfAbsent(k, e);
            }
        }
        // the records naming a name nobody decided, grouped by the stored proposal they spell or by their own name
        Map<String, List<ActivityInstance>> byEntry = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> spellings = new LinkedHashMap<>();
        for (ActivityInstance r : stores.recordsNaming(kind)) {
            String name = RegistryStores.nameIn(r, kind);
            String key = ArtisticEventRankSupport.normalize(name);
            if (key.isEmpty() || decided.contains(key)) continue;
            String entry = stored.containsKey(key) ? ArtisticEventRankSupport.normalize(stored.get(key).getName()) : key;
            byEntry.computeIfAbsent(entry, k -> new ArrayList<>()).add(r);
            spellings.computeIfAbsent(entry, k -> new LinkedHashMap<>()).merge(name.trim(), 1, Integer::sum);
        }
        for (RegistryItem p : new LinkedHashSet<>(stored.values())) {
            String entry = ArtisticEventRankSupport.normalize(p.getName());
            byEntry.putIfAbsent(entry, new ArrayList<>());
            Map<String, Integer> s = spellings.computeIfAbsent(entry, k -> new LinkedHashMap<>());
            s.putIfAbsent(p.getName(), 0);
            aliases(p).forEach(a -> s.putIfAbsent(a, 0));
        }
        List<RegistryDomainExperts> settings = domainExpertsRepository.findAll();
        Map<String, Set<String>> domainsByResearcher = new HashMap<>();
        String me = me(auth);
        boolean admin = access.isPlatformAdmin(auth);
        Set<String> reviewable = admin ? Set.of() : access.domainsOf(auth);
        List<QueueEntry> entries = new ArrayList<>();
        for (Map.Entry<String, List<ActivityInstance>> group : byEntry.entrySet()) {
            String key = group.getKey();
            List<ActivityInstance> records = group.getValue();
            RegistryItem proposal = stored.get(key);
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
                refusal = "registry.refused.own";
            } else if (!admin && domains.stream().noneMatch(reviewable::contains)) {
                refusal = "registry.refused.domain";
            }
            entries.add(new QueueEntry(key, name, new ArrayList<>(names.keySet()), domains, records.size(),
                    researchers.size(), years, evidence, suggestions,
                    proposal != null && proposal.getSource() != null ? proposal.getSource() : "registry.source.records",
                    refusal == null, refusal));
        }
        entries.sort(Comparator.comparing(QueueEntry::records).reversed().thenComparing(QueueEntry::name));
        return entries;
    }

    // ── decisions ─────────────────────────────────────────────────────────────

    /** Ranks one or more waiting names, each as its own entry, with the same level, category, criteria and basis. */
    public Outcome rank(RegistryKind kind, List<String> keys, RankForm form, Authentication auth) {
        if (keys == null || keys.isEmpty()) return Outcome.refused("registry.refused.none");
        Optional<String> invalid = invalid(kind, form);
        if (invalid.isPresent()) return Outcome.refused(invalid.get());
        List<RegistryItem> all = stores.all(kind);
        Map<String, QueueEntry> waiting = queue(kind, all, auth).stream()
                .collect(Collectors.toMap(QueueEntry::key, e -> e, (a, b) -> a));
        List<QueueEntry> chosen = new ArrayList<>();
        for (String key : keys) {
            QueueEntry entry = waiting.get(key);
            if (entry == null) return Outcome.refused("registry.refused.gone");
            if (!entry.canDecide()) return Outcome.refused(entry.refusalKey());
            if (domainFor(entry, form.domain(), auth) == null) return Outcome.refused("registry.refused.domain");
            chosen.add(entry);
        }
        Instant now = Instant.now();
        String note = trim(form.note());
        for (QueueEntry entry : chosen) {
            RegistryItem item = storedFor(all, entry.key()).orElseGet(() -> stores.create(kind));
            if (item.getId() == null) {
                item.setName(entry.name());
                item.setSource(entry.source());
            }
            item.setStatus(RegistryStatus.CONFIRMED);
            apply(item, form, note);
            String domain = domainFor(entry, form.domain(), auth);
            item.setDomainId(SHARED.equals(domain) ? null : domain);
            addAliases(item, entry.spellings());
            item.setDecidedBy(me(auth));
            item.setDecidedAt(now);
            history(item).add(change(now, me(auth), "RANKED", null, form.level(), note));
            stores.save(item);
        }
        stores.refresh(kind);
        log.info("Registry {}: {} ranked by {} ({})", kind, chosen.size(), me(auth), form.level());
        return Outcome.done(chosen.size() == 1 ? "registry.done.ranked" : "registry.done.rankedMany");
    }

    /**
     * Merges every ticked name into one ranked entry, as its spellings; stops at the first name the viewer may not
     * decide (the names before it stay merged).
     */
    public Outcome mergeAll(RegistryKind kind, List<String> keys, String targetId, Authentication auth) {
        if (keys == null || keys.isEmpty()) return Outcome.refused("registry.refused.none");
        if (targetId == null || targetId.isBlank()) return Outcome.refused("registry.refused.target");
        for (String key : keys) {
            Outcome outcome = merge(kind, key, targetId, auth);
            if (!outcome.done()) return outcome;
        }
        return Outcome.done(keys.size() == 1 ? "registry.done.merged" : "registry.done.mergedMany");
    }

    /** Makes a waiting name a spelling of a confirmed entry: its records take that entry's level. */
    public Outcome merge(RegistryKind kind, String key, String targetId, Authentication auth) {
        List<RegistryItem> all = stores.all(kind);
        QueueEntry entry = queue(kind, all, auth).stream().filter(e -> e.key().equals(key)).findFirst().orElse(null);
        if (entry == null) return Outcome.refused("registry.refused.gone");
        if (!entry.canDecide()) return Outcome.refused(entry.refusalKey());
        RegistryItem target = all.stream().filter(e -> e.getId() != null && e.getId().equals(targetId))
                .filter(RegistryItem::isConfirmed).findFirst().orElse(null);
        if (target == null) return Outcome.refused("registry.refused.target");
        // a shared entry (no domain) takes spellings from any expert who may decide the waiting name
        if (target.getDomainId() != null && !access.canReview(target.getDomainId(), auth)) {
            return Outcome.refused("registry.refused.domain");
        }
        if (ownKeys(kind, auth).stream().anyMatch(keysOf(target)::contains)) {
            return Outcome.refused("registry.refused.own");
        }
        Instant now = Instant.now();
        addAliases(target, entry.spellings());
        history(target).add(change(now, me(auth), "MERGED", target.getLevel(), target.getLevel(),
                String.join(" · ", entry.spellings())));
        stores.save(target);
        storedFor(all, key).ifPresent(p -> {
            p.setStatus(RegistryStatus.MERGED);
            p.setMergedInto(target.getId());
            p.setDecidedBy(me(auth));
            p.setDecidedAt(now);
            history(p).add(change(now, me(auth), "MERGED", null, null, target.getName()));
            stores.save(p);
        });
        stores.refresh(kind);
        return Outcome.done("registry.done.merged");
    }

    /** Rejects a waiting name (not an entity to rank): it leaves the queue; its records keep the floor, or none. */
    public Outcome reject(RegistryKind kind, String key, String note, Authentication auth) {
        String reason = trim(note);
        if (reason == null) return Outcome.refused("registry.refused.noteRequired");
        if (reason.length() > NOTE_MAX) return Outcome.refused("registry.refused.note");
        List<RegistryItem> all = stores.all(kind);
        QueueEntry entry = queue(kind, all, auth).stream().filter(e -> e.key().equals(key)).findFirst().orElse(null);
        if (entry == null) return Outcome.refused("registry.refused.gone");
        if (!entry.canDecide()) return Outcome.refused(entry.refusalKey());
        Instant now = Instant.now();
        RegistryItem item = storedFor(all, key).orElseGet(() -> stores.create(kind));
        if (item.getId() == null) {
            item.setName(entry.name());
            item.setSource(entry.source());
            item.setDomainId(entry.domains().stream().findFirst().orElse(null));
        }
        item.setStatus(RegistryStatus.REJECTED);
        item.setLevel(null);
        item.setNote(reason);
        addAliases(item, entry.spellings());
        item.setDecidedBy(me(auth));
        item.setDecidedAt(now);
        history(item).add(change(now, me(auth), "REJECTED", null, null, reason));
        stores.save(item);
        stores.refresh(kind);
        return Outcome.done("registry.done.rejected");
    }

    /** Sends a rejected name back to the queue. */
    public Outcome reopen(RegistryKind kind, String itemId, Authentication auth) {
        RegistryItem item = stores.byId(kind, itemId).orElse(null);
        if (item == null || item.getStatus() != RegistryStatus.REJECTED) return Outcome.refused("registry.refused.gone");
        if (!access.canReview(item.getDomainId(), auth)) return Outcome.refused("registry.refused.domain");
        item.setStatus(RegistryStatus.PROPOSED);
        history(item).add(change(Instant.now(), me(auth), "REOPENED", null, null, null));
        stores.save(item);
        stores.refresh(kind);
        return Outcome.done("registry.done.reopened");
    }

    /** Changes the level or the details of a confirmed entry; every record naming it follows. */
    public Outcome edit(RegistryKind kind, String itemId, RankForm form, Authentication auth) {
        RegistryItem item = stores.byId(kind, itemId).filter(RegistryItem::isConfirmed).orElse(null);
        if (item == null) return Outcome.refused("registry.refused.gone");
        Optional<String> invalid = invalid(kind, form);
        if (invalid.isPresent()) return Outcome.refused(invalid.get());
        if (!access.canReview(item.getDomainId(), auth)) return Outcome.refused("registry.refused.domain");
        if (ownKeys(kind, auth).stream().anyMatch(keysOf(item)::contains)) {
            return Outcome.refused("registry.refused.own");
        }
        Instant now = Instant.now();
        String before = item.getLevel();
        String note = trim(form.note());
        apply(item, form, note);
        if (item.getStatus() == null) item.setStatus(RegistryStatus.CONFIRMED);
        item.setDecidedBy(me(auth));
        item.setDecidedAt(now);
        history(item).add(change(now, me(auth), "EDITED", before, form.level(), note));
        stores.save(item);
        stores.refresh(kind);
        return Outcome.done("registry.done.edited");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Why a decision cannot be taken as filled in, by the kind's own rules: a level and a basis of the kind; a category
     * of the kind (an award's is required: its nature counts); criteria of the kind — and, on Comisia 28's basis, a
     * conference is international exactly when two of its criteria hold.
     */
    static Optional<String> invalid(RegistryKind kind, RankForm form) {
        if (form == null || form.level() == null || !kind.levels().contains(form.level())
                || form.basis() == null || !kind.bases().contains(form.basis())) {
            return Optional.of("registry.refused.rank");
        }
        if (form.category() != null && !kind.categories().contains(form.category())) {
            return Optional.of("registry.refused.rank");
        }
        if (kind == RegistryKind.AWARD && form.category() == null) {
            return Optional.of("registry.refused.category");
        }
        List<String> criteria = form.criteria() == null ? List.of() : form.criteria();
        if (!kind.criteria().containsAll(criteria)) {
            return Optional.of("registry.refused.rank");
        }
        if (kind == RegistryKind.SCIENTIFIC_EVENT && "COMISIA_28_CRITERIA".equals(form.basis())
                && ("INTERNATIONAL".equals(form.level()) != (new HashSet<>(criteria).size() >= RegistryKind.COMISIA_28_CRITERIA_NEEDED))) {
            return Optional.of("registry.refused.criteria");
        }
        String note = trim(form.note());
        if (note != null && note.length() > NOTE_MAX) return Optional.of("registry.refused.note");
        String country = trim(form.country());
        if (country != null && country.length() > COUNTRY_MAX) return Optional.of("registry.refused.rank");
        return Optional.empty();
    }

    private static void apply(RegistryItem item, RankForm form, String note) {
        item.setLevel(form.level());
        item.setCategory(form.category());
        item.setCriteria(form.criteria() == null ? new ArrayList<>() : new ArrayList<>(new LinkedHashSet<>(form.criteria())));
        item.setCountry(trim(form.country()));
        item.setBasis(form.basis());
        item.setNote(note);
    }

    /**
     * The domain a decided name's entry takes: the one asked for, else the viewer's among the name's; null when the
     * viewer may not decide it there. An admin decides a name of no domain (its researchers' departments map to none)
     * as a shared entry, {@link #SHARED}, like the bodies of the initial list.
     */
    private String domainFor(QueueEntry entry, String requested, Authentication auth) {
        String wanted = trim(requested);
        if (wanted != null) {
            return access.canReview(wanted, auth) ? wanted : null;
        }
        return entry.domains().stream().filter(d -> access.canReview(d, auth)).findFirst()
                .orElse(access.isPlatformAdmin(auth) ? entry.domains().stream().findFirst().orElse(SHARED) : null);
    }

    /** The normalised names the viewer's own records give in this kind's reference field. */
    private Set<String> ownKeys(RegistryKind kind, Authentication auth) {
        String me = me(auth);
        if (me == null) return Set.of();
        return activityInstanceRepository.findAllByResearcherId(me).stream()
                .map(r -> RegistryStores.nameIn(r, kind)).map(ArtisticEventRankSupport::normalize).filter(k -> !k.isEmpty())
                .collect(Collectors.toSet());
    }

    private static Optional<RegistryItem> storedFor(List<RegistryItem> all, String key) {
        return all.stream().filter(e -> e.getStatus() == RegistryStatus.PROPOSED || e.getStatus() == RegistryStatus.REJECTED)
                .filter(e -> keysOf(e).contains(key)).findFirst();
    }

    /** The normalised name and spellings of an entry. */
    public static Set<String> keysOf(RegistryItem e) {
        Set<String> keys = new LinkedHashSet<>();
        String k = ArtisticEventRankSupport.normalize(e.getName());
        if (!k.isEmpty()) keys.add(k);
        for (String a : aliases(e)) {
            String ak = ArtisticEventRankSupport.normalize(a);
            if (!ak.isEmpty()) keys.add(ak);
        }
        return keys;
    }

    private static List<String> aliases(RegistryItem e) {
        return e.getAliases() == null ? List.of() : e.getAliases();
    }

    private static void addAliases(RegistryItem item, List<String> spellings) {
        if (item.getAliases() == null) item.setAliases(new ArrayList<>());
        Set<String> known = keysOf(item);
        for (String s : spellings) {
            String k = ArtisticEventRankSupport.normalize(s);
            if (!k.isEmpty() && known.add(k)) {
                item.getAliases().add(s.trim());
            }
        }
    }

    private static List<RegistryChange> history(RegistryItem item) {
        if (item.getHistory() == null) item.setHistory(new ArrayList<>());
        return item.getHistory();
    }

    static RegistryChange change(Instant at, String by, String action, String from, String to, String note) {
        RegistryChange c = new RegistryChange();
        c.setAt(at);
        c.setBy(by);
        c.setAction(action);
        c.setFromLevel(from);
        c.setToLevel(to);
        c.setNote(note);
        return c;
    }

    /** Every domain a registry entry names or an admin configured. */
    private List<String> configuredDomains() {
        Set<String> domains = new TreeSet<>();
        for (RegistryKind k : RegistryKind.values()) {
            stores.all(k).stream().map(RegistryItem::getDomainId).filter(d -> d != null && !d.isBlank()).forEach(domains::add);
        }
        domainExpertsRepository.findAll().forEach(s -> domains.add(s.getDomain()));
        return List.copyOf(domains);
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
