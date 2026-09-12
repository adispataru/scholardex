package ro.uvt.pokedex.core.service.scopus;

import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.tasks.ScopusCitationsUpdate;
import ro.uvt.pokedex.core.service.importing.scopus.CitedWorkKey;
import ro.uvt.pokedex.core.service.scopus.dto.CitationsByTitleRequest;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * H106 S5 — plans the reverse-title pass of a citations sync: one Scopus {@code REFTITLE} search per work of
 * the author, narrowed by the author's surname variants and by the last citing date already known, with the
 * citing EIDs we already hold so the service never re-reports them. Runs for EVERY work, EID or not:
 * Scopus's linked-reference set (what {@code REF(eid)} and the cited-by count see) misses references it failed
 * to link, and those leave no count mismatch to trigger on.
 */
final class ScopusReferenceTitlePlanner {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    static final int DEFAULT_PAGE_SIZE_PER_ITEM = 25;
    /** Titles with fewer words are too generic for a reference-text search, even with the surname filter. */
    static final int MIN_TITLE_WORDS = 3;

    private final ScopusPublicationSyncPlanner publicationPlanner;

    ScopusReferenceTitlePlanner() {
        this(new ScopusPublicationSyncPlanner());
    }

    ScopusReferenceTitlePlanner(ScopusPublicationSyncPlanner publicationPlanner) {
        this.publicationPlanner = publicationPlanner;
    }

    /**
     * One item per work with a searchable title. {@code from_date} = the newest known citing cover date for
     * that work (like the EID pass), overridden by the task's sync mode exactly as the EID pass does.
     */
    Map<String, CitationsByTitleRequest.CitedWorkSpec> buildItems(
            List<ScholardexPublicationView> authorPublications,
            List<ScholardexCitationView> citations,
            List<ScholardexPublicationView> citingPublications,
            Collection<ScholardexAuthorView> authorViews,
            ScopusCitationsUpdate task) {
        if (authorPublications == null || authorPublications.isEmpty()) {
            return Map.of();
        }
        List<String> surnames = surnameVariants(authorViews);

        Map<String, ScholardexPublicationView> citingById = new HashMap<>();
        if (citingPublications != null) {
            for (ScholardexPublicationView p : citingPublications) {
                if (p.getId() != null) citingById.put(p.getId(), p);
            }
        }
        Map<String, List<ScholardexCitationView>> citationsByCitedId = new HashMap<>();
        if (citations != null) {
            for (ScholardexCitationView c : citations) {
                if (c.getCitedId() != null) {
                    citationsByCitedId.computeIfAbsent(c.getCitedId(), k -> new ArrayList<>()).add(c);
                }
            }
        }

        Map<String, CitationsByTitleRequest.CitedWorkSpec> items = new LinkedHashMap<>();
        for (ScholardexPublicationView publication : authorPublications) {
            String key = CitedWorkKey.forPublication(publication);
            String title = publication.getTitle();
            if (key == null || title == null || title.trim().split("\\s+").length < MIN_TITLE_WORDS) {
                continue;
            }
            CitationsByTitleRequest.CitedWorkSpec spec = new CitationsByTitleRequest.CitedWorkSpec();
            spec.setTitle(title.trim());
            spec.setSurnames(surnames);

            Set<String> knownEids = new LinkedHashSet<>();
            String lastDate = null;
            for (ScholardexCitationView c : citationsByCitedId.getOrDefault(publication.getId(), List.of())) {
                ScholardexPublicationView citing = citingById.get(c.getCitingId());
                if (citing == null) continue;
                if (citing.getEid() != null && !citing.getEid().isBlank()) {
                    knownEids.add(citing.getEid().trim());
                }
                Optional<LocalDate> date = publicationPlanner.parseCoverDate(citing.getCoverDate());
                if (date.isPresent()) {
                    Optional<LocalDate> existing = publicationPlanner.parseCoverDate(lastDate);
                    if (existing.isEmpty() || existing.get().isBefore(date.get())) {
                        lastDate = date.get().format(ISO);
                    }
                }
            }
            spec.setKnownCitingEids(new ArrayList<>(knownEids));
            spec.setFromDate(resolveFromDate(task, lastDate));
            items.put(key, spec);
        }
        return items;
    }

    /** Same sync-mode semantics as {@link ScopusCitationSyncPlanner#resolveEidLastDates}. */
    String resolveFromDate(ScopusCitationsUpdate task, String lastDate) {
        if (task != null && "FULL".equals(task.getSyncMode())) {
            return null;
        }
        if (task != null && "PERIOD".equals(task.getSyncMode()) && task.getStartYear() != null) {
            String periodStart = task.getStartYear() + "-01-01";
            return lastDate != null && lastDate.compareTo(periodStart) > 0 ? lastDate : periodStart;
        }
        return lastDate;
    }

    /**
     * Surnames as a reference may spell them: from "Fortiș, Teodor Florin" / "Teodor-Florin Fortiș" /
     * "Fortis T.-F." take the family name, then add its diacritics-stripped form ("Fortis"). Deduplicated,
     * order kept, one-letter tokens (initials) ignored.
     */
    static List<String> surnameVariants(Collection<ScholardexAuthorView> authorViews) {
        Set<String> out = new LinkedHashSet<>();
        if (authorViews == null) return List.of();
        for (ScholardexAuthorView view : authorViews) {
            if (view == null) continue;
            List<String> names = new ArrayList<>();
            if (view.getName() != null) names.add(view.getName());
            if (view.getAlternativeNames() != null) names.addAll(view.getAlternativeNames());
            for (String name : names) {
                String surname = surnameOf(name);
                if (surname == null) continue;
                out.add(surname);
                String ascii = stripDiacritics(surname);
                if (!ascii.equalsIgnoreCase(surname)) out.add(ascii);
            }
        }
        return new ArrayList<>(out);
    }

    /** "Last, First" → Last; "First Last" → Last; "Last F.-M." → Last. Null when nothing usable. */
    static String surnameOf(String name) {
        if (name == null) return null;
        String n = name.trim();
        if (n.isEmpty()) return null;
        String candidate;
        int comma = n.indexOf(',');
        if (comma > 0) {
            candidate = n.substring(0, comma);
        } else {
            String[] tokens = n.split("\\s+");
            // "Fortis T.-F." → tokens after the first are initials → surname is the first token.
            boolean restAreInitials = tokens.length > 1;
            for (int i = 1; i < tokens.length && restAreInitials; i++) {
                restAreInitials = isInitials(tokens[i]);
            }
            candidate = restAreInitials ? tokens[0] : tokens[tokens.length - 1];
        }
        candidate = candidate.replaceAll("[.]", "").trim();
        return candidate.length() >= 2 ? candidate : null;
    }

    private static boolean isInitials(String token) {
        String t = token.replaceAll("[.\\-]", "");
        return t.length() <= 2 && !t.isEmpty() && t.chars().allMatch(Character::isUpperCase);
    }

    static String stripDiacritics(String value) {
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFKD);
        return decomposed.replaceAll("\\p{M}+", "").replace("ß", "ss");
    }

    CitationsByTitleRequest buildRequest(Map<String, CitationsByTitleRequest.CitedWorkSpec> items) {
        CitationsByTitleRequest request = new CitationsByTitleRequest();
        request.setRequestId(UUID.randomUUID().toString());
        request.setItems(items);
        request.setPageSizePerItem(DEFAULT_PAGE_SIZE_PER_ITEM);
        request.setVerifyReferences(true);
        return request;
    }

    static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
