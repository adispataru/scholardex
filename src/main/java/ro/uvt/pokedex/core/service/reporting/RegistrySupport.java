package ro.uvt.pokedex.core.service.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ro.uvt.pokedex.core.model.registry.RegistryEntry;
import ro.uvt.pokedex.core.model.registry.RegistryKind;
import ro.uvt.pokedex.core.model.registry.RegistryStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * H144 — what the registries experts rank say about a name, for the scoring: the level of a confirmed entry (by its
 * name or a spelling, normalised like the artistic events), whether experts rejected the name, or that it waits for
 * them. Artistic events answer from {@link ArtisticEventRankSupport}, the other kinds from {@link RegistryEntry}.
 *
 * <p>Static registry like the others: {@link RegistryRegistrar} hands it a loader, the registry loads on first use,
 * and unregistered (unit tests) nothing is ranked.</p>
 */
public final class RegistrySupport {

    private static final Logger log = LoggerFactory.getLogger(RegistrySupport.class);

    /** What the registry says about a name: ranked, rejected by experts, or waiting for them (unknown included). */
    public enum EntryStatus { RANKED, REJECTED, AWAITING_RANK }

    /** A ranked entry, as the scoring reads it. */
    public record Ranked(String name, String level, String category, String country, List<String> criteria, String basis) {
    }

    private static volatile Map<RegistryKind, Map<String, Ranked>> confirmed = Map.of();
    private static volatile Map<RegistryKind, Set<String>> rejected = Map.of();
    private static volatile Map<RegistryKind, Set<String>> proposed = Map.of();
    private static volatile Supplier<? extends Collection<RegistryEntry>> loader;
    private static volatile boolean loaded;

    private RegistrySupport() {
    }

    /** Replaces the registries (conferences, organisations, awards) with these entries. */
    public static synchronized void register(Collection<RegistryEntry> entries) {
        Map<RegistryKind, Map<String, Ranked>> index = new EnumMap<>(RegistryKind.class);
        Map<RegistryKind, Set<String>> rejectedNames = new EnumMap<>(RegistryKind.class);
        Map<RegistryKind, Set<String>> proposedNames = new EnumMap<>(RegistryKind.class);
        for (RegistryEntry entry : entries) {
            if (entry.getKind() == null || entry.getKind() == RegistryKind.ARTISTIC_EVENT) {
                continue;
            }
            for (String name : namesOf(entry)) {
                String key = ArtisticEventRankSupport.normalize(name);
                if (key.isEmpty()) {
                    continue;
                }
                if (entry.isConfirmed() && entry.getLevel() != null) {
                    index.computeIfAbsent(entry.getKind(), k -> new HashMap<>()).merge(key,
                            new Ranked(entry.getName(), entry.getLevel(), entry.getCategory(), entry.getCountry(),
                                    entry.getCriteria() == null ? List.of() : List.copyOf(entry.getCriteria()), entry.getBasis()),
                            (a, b) -> better(entry.getKind(), a, b));
                } else if (entry.getStatus() == RegistryStatus.REJECTED) {
                    rejectedNames.computeIfAbsent(entry.getKind(), k -> new HashSet<>()).add(key);
                } else if (entry.getStatus() == RegistryStatus.PROPOSED) {
                    proposedNames.computeIfAbsent(entry.getKind(), k -> new HashSet<>()).add(key);
                }
            }
        }
        Map<RegistryKind, Map<String, Ranked>> frozen = new EnumMap<>(RegistryKind.class);
        index.forEach((k, v) -> frozen.put(k, Map.copyOf(v)));
        Map<RegistryKind, Set<String>> frozenRejected = new EnumMap<>(RegistryKind.class);
        rejectedNames.forEach((k, v) -> {
            Set<String> s = new HashSet<>(v);
            s.removeAll(frozen.getOrDefault(k, Map.of()).keySet());
            frozenRejected.put(k, Set.copyOf(s));
        });
        Map<RegistryKind, Set<String>> frozenProposed = new EnumMap<>(RegistryKind.class);
        proposedNames.forEach((k, v) -> frozenProposed.put(k, Set.copyOf(v)));
        confirmed = frozen;
        rejected = frozenRejected;
        proposed = frozenProposed;
        loaded = true;
    }

    /** The source the registries are (re)loaded from on first use. */
    public static synchronized void registerLoader(Supplier<? extends Collection<RegistryEntry>> source) {
        loader = source;
        loaded = false;
    }

    /** Back to the unregistered state (tests). */
    public static synchronized void reset() {
        confirmed = Map.of();
        rejected = Map.of();
        proposed = Map.of();
        loader = null;
        loaded = false;
    }

    /** The ranked entry a name spells, if any. */
    public static Optional<Ranked> ranked(RegistryKind kind, String name) {
        if (kind == null) {
            return Optional.empty();
        }
        if (kind == RegistryKind.ARTISTIC_EVENT) {
            return ArtisticEventRankSupport.rankOf(name)
                    .map(rank -> new Ranked(name, rank.name(), null, null, List.of(), null));
        }
        String key = ArtisticEventRankSupport.normalize(name);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        ensureLoaded();
        return Optional.ofNullable(confirmed.getOrDefault(kind, Map.of()).get(key));
    }

    /** RANKED, REJECTED, or AWAITING_RANK; empty for a blank name. */
    public static Optional<EntryStatus> statusOf(RegistryKind kind, String name) {
        if (kind == null) {
            return Optional.empty();
        }
        if (kind == RegistryKind.ARTISTIC_EVENT) {
            return ArtisticEventRankSupport.statusOf(name).map(s -> EntryStatus.valueOf(s.name()));
        }
        String key = ArtisticEventRankSupport.normalize(name);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        ensureLoaded();
        if (confirmed.getOrDefault(kind, Map.of()).containsKey(key)) {
            return Optional.of(EntryStatus.RANKED);
        }
        return Optional.of(rejected.getOrDefault(kind, Set.of()).contains(key) ? EntryStatus.REJECTED : EntryStatus.AWAITING_RANK);
    }

    /**
     * The level the scoring reads for a named entity — INTERNATIONAL, NATIONAL or LOCAL ({@link RegistryKind#scoringLevel}):
     * its rank when ranked; while it waits, the kind's floor (a conference counts national); null when the experts
     * rejected the name, or when the kind has no floor (a gate needs their decision).
     */
    public static String scoringLevel(RegistryKind kind, String name) {
        Optional<Ranked> ranked = ranked(kind, name);
        if (ranked.isPresent()) {
            return RegistryKind.scoringLevel(ranked.get().level());
        }
        Optional<EntryStatus> status = statusOf(kind, name);
        if (status.isEmpty() || status.get() == EntryStatus.REJECTED) {
            return null;
        }
        return RegistryKind.scoringLevel(kind.floor());
    }

    private static List<String> namesOf(RegistryEntry entry) {
        List<String> names = new ArrayList<>();
        if (entry.getName() != null) names.add(entry.getName());
        if (entry.getAliases() != null) names.addAll(entry.getAliases());
        return names;
    }

    /** Two entries spelled alike: the better level wins (best first in the kind's levels). */
    private static Ranked better(RegistryKind kind, Ranked a, Ranked b) {
        int ia = kind.levels().indexOf(a.level());
        int ib = kind.levels().indexOf(b.level());
        return ib >= 0 && (ia < 0 || ib < ia) ? b : a;
    }

    private static void ensureLoaded() {
        if (loaded || loader == null) {
            return;
        }
        synchronized (RegistrySupport.class) {
            if (loaded || loader == null) {
                return;
            }
            try {
                register(loader.get());
            } catch (RuntimeException e) {
                log.warn("Registries not loadable: {} — entries stay unranked until the next try", e.getMessage());
            }
        }
    }
}
