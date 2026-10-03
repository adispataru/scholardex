package ro.uvt.pokedex.core.service.application;

import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationService;
import ro.uvt.pokedex.core.service.reporting.RegistryScoringSupport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * H145 — one publication counts once. A record that declares a publication (an activity type marked
 * {@code publicationRecord}: an article, a book, a chapter, a coordinated volume, a course) and a publication of the
 * researcher's list that is the same work — the same DOI, or the same title within a year — used to count twice (a grid
 * import declares every article of the sheet, the ones Scopus already holds too). Now the record does not count; when
 * a head approved the record's category, the record counts and the list's copy does not (the head decided about the
 * record, e.g. a coordinated volume the list holds as a book).
 * <p>
 * Matched against every publication the researcher confirmed, before a report's affiliation filter: a declared copy
 * must not bring back a publication the filter leaves out.
 */
public final class DeclaredPublicationCopies {

    static final List<String> TITLE_FIELDS = List.of("Titlu", "Titlu_articol", "Nume");
    static final String DOI_FIELD = "DOI";
    /** Shorter titles ("Introduction", "Editorial") name too many works to match on. */
    static final int MIN_TITLE_WORDS = 3;
    /** A title that continues another ("Title" / "Title: a subtitle") matches when the shorter has this many words. */
    static final int MIN_PREFIX_WORDS = 5;

    private DeclaredPublicationCopies() {
    }

    /** The records that do not count, and the list's publications a head-approved record replaces. */
    public record Resolution(Set<String> declaredCopies, Set<String> supersededPublications) {
        public static final Resolution NONE = new Resolution(Set.of(), Set.of());

        public List<ScholardexPublicationView> publications(List<ScholardexPublicationView> publications) {
            if (supersededPublications.isEmpty() || publications == null) {
                return publications;
            }
            return publications.stream().filter(p -> p == null || !supersededPublications.contains(p.getId())).toList();
        }
    }

    public static Resolution resolve(List<ActivityInstance> activities, List<ScholardexPublicationView> publications) {
        if (activities == null || activities.isEmpty() || publications == null || publications.isEmpty()
                || activities.stream().noneMatch(a -> a != null && a.getActivity() != null && a.getActivity().isPublication())) {
            return Resolution.NONE;
        }
        Map<String, ScholardexPublicationView> byDoi = new HashMap<>();
        List<Titled> titled = new ArrayList<>();
        for (ScholardexPublicationView p : publications) {
            if (p == null || p.getId() == null) {
                continue;
            }
            String doi = doi(p.getDoi());
            if (doi != null) {
                byDoi.putIfAbsent(doi, p);
            }
            String title = ScholardexPublicationCanonicalizationService.normalizeTitle(p.getTitle());
            if (title != null) {
                titled.add(new Titled(p, title, year(p.getCoverDate())));
            }
        }
        Set<String> copies = new HashSet<>();
        Set<String> superseded = new HashSet<>();
        for (ActivityInstance activity : activities) {
            if (activity == null || activity.getId() == null || activity.getActivity() == null
                    || !activity.getActivity().isPublication()) {
                continue;
            }
            Map<String, String> fields = activity.getFields() == null ? Map.of() : activity.getFields();
            ScholardexPublicationView match = null;
            String doi = doi(fields.get(DOI_FIELD));
            if (doi != null) {
                match = byDoi.get(doi);
            }
            if (match == null) {
                match = byTitle(declaredTitle(fields), activity.getYear(), titled);
            }
            if (match == null) {
                continue;
            }
            if (RegistryScoringSupport.approvedRequest(activity, fields) != null) {
                superseded.add(match.getId());
            } else {
                copies.add(activity.getId());
            }
        }
        return copies.isEmpty() && superseded.isEmpty() ? Resolution.NONE : new Resolution(copies, superseded);
    }

    private record Titled(ScholardexPublicationView publication, String title, Integer year) {
    }

    /** A DOI, normalised; null for anything else (a source's "null" placeholder, a typed "-"). */
    private static String doi(String value) {
        String doi = ScholardexPublicationCanonicalizationService.normalizeDoi(value);
        return doi != null && doi.startsWith("10.") ? doi : null;
    }

    private static String declaredTitle(Map<String, String> fields) {
        for (String field : TITLE_FIELDS) {
            String value = fields.get(field);
            if (value != null && !value.isBlank()) {
                return ScholardexPublicationCanonicalizationService.normalizeTitle(value);
            }
        }
        return null;
    }

    private static ScholardexPublicationView byTitle(String title, int year, List<Titled> titled) {
        if (title == null || title.split(" ").length < MIN_TITLE_WORDS) {
            return null;
        }
        for (Titled candidate : titled) {
            if (year > 0 && candidate.year() != null && Math.abs(candidate.year() - year) > 1) {
                continue;
            }
            if (sameWork(title, candidate.title())) {
                return candidate.publication();
            }
        }
        return null;
    }

    static boolean sameWork(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        String shorter = a.length() <= b.length() ? a : b;
        String longer = shorter == a ? b : a;
        return shorter.split(" ").length >= MIN_PREFIX_WORDS && longer.startsWith(shorter + " ");
    }

    private static Integer year(String coverDate) {
        if (coverDate == null || coverDate.length() < 4) {
            return null;
        }
        try {
            return Integer.parseInt(coverDate.substring(0, 4));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
