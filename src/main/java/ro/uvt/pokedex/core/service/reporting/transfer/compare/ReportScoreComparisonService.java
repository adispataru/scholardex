package ro.uvt.pokedex.core.service.reporting.transfer.compare;

import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.reporting.transfer.ActivitySnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.CitationSnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.PublicationSnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.SnapshotItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compares the platform's per-publication scores against an uploaded file's scores. Publications are
 * matched by normalized title; each row carries up to two dimensions — publication points (from the
 * journal/conference roles) and citation points (from the citations-per-publication role). The
 * "correct points" tally sums the platform points of every dimension whose file value agrees.
 */
@Component
public class ReportScoreComparisonService {

    private static final double EPSILON = 0.01;
    private static final String CITATION_ROLE = "citations-per-publication";

    public ReportScoreComparison compare(List<SnapshotItem> platformItems, List<SnapshotItem> fileItems) {
        return compare(platformItems, fileItems, Map.of());
    }

    /**
     * @param boundOptionsByBlock block name → every Activity type the report definition binds to that
     *   block (via {@code IndividualReport.blockByIndicatorId}), regardless of whether the researcher
     *   has any scored instances yet. Widens the "Add to platform" inline form's candidate list beyond
     *   only-what-the-researcher-already-has — without it, a researcher with zero existing entries for
     *   a block (e.g. their first grant) sees no candidate Activity type at all and the importable rows
     *   fall back to a dead-end "add via the workspace" message.
     */
    public ReportScoreComparison compare(List<SnapshotItem> platformItems, List<SnapshotItem> fileItems,
                                         Map<String, List<ReportScoreComparison.ActivityOption>> boundOptionsByBlock) {
        Map<String, Dim> platformPub = new LinkedHashMap<>();
        Map<String, Dim> platformCit = new LinkedHashMap<>();
        Map<String, Dim> filePub = new LinkedHashMap<>();
        Map<String, Dim> fileCit = new LinkedHashMap<>();
        index(platformItems, platformPub, platformCit);
        index(fileItems, filePub, fileCit);

        Set<String> titles = new TreeSet<>();
        titles.addAll(platformPub.keySet());
        titles.addAll(platformCit.keySet());
        titles.addAll(filePub.keySet());
        titles.addAll(fileCit.keySet());

        // Platform citation graph: cited-paper title → set of (normalized) citing titles. Used to
        // guess which paper a duplicate sheet's citations actually belong to.
        Map<String, Set<String>> platformCitingSets = new LinkedHashMap<>();
        Map<String, String> platformCitedDisplay = new LinkedHashMap<>();
        for (Dim d : platformCit.values()) {
            if (d.citationSource == null || d.citationSource.getCitingPublications() == null) continue;
            Set<String> set = new java.util.HashSet<>();
            for (CitationSnapshotItem.CitingPublication c : d.citationSource.getCitingPublications()) {
                if (c.getTitle() != null) set.add(normalize(c.getTitle()));
            }
            platformCitingSets.put(normalize(d.title), set);
            platformCitedDisplay.put(normalize(d.title), d.title);
        }

        List<ReportScoreComparison.PublicationRow> rows = new ArrayList<>();
        double platformTotal = 0, fileTotal = 0, correctPoints = 0;
        int matching = 0, differing = 0;

        for (String title : titles) {
            Dim pp = platformPub.get(title);
            Dim fp = filePub.get(title);
            Dim pc = platformCit.get(title);
            Dim fc = fileCit.get(title);

            ReportScoreComparison.ScorePair publication = pair(pp, fp);
            ReportScoreComparison.ScorePair citation = pair(pc, fc);

            platformTotal += (pp != null ? pp.score : 0) + (pc != null ? pc.score : 0);
            fileTotal += (fp != null ? fp.score : 0) + (fc != null ? fc.score : 0);
            correctPoints += correctOf(publication) + correctOf(citation);

            Dim displaySource = pp != null ? pp : (fp != null ? fp : (pc != null ? pc : fc));
            String roleKey = displaySource != null ? displaySource.roleKey : null;
            String displayTitle = displaySource != null ? displaySource.title : title;
            PublicationSnapshotItem detail = pp != null ? pp.source : null;

            List<ReportScoreComparison.CitationDetail> breakdown = citationBreakdown(
                    pc != null ? pc.citationSource : null,
                    fc != null ? fc.citationSource : null);

            List<ReportScoreComparison.DuplicateCitationGroup> duplicates = buildDuplicateGroups(
                    fc != null ? fc.duplicateCitationSources : List.of(),
                    title, platformCitingSets, platformCitedDisplay);

            PublicationSnapshotItem fileDetail = fp != null ? fp.source : null;
            ReportScoreComparison.PublicationRow row = new ReportScoreComparison.PublicationRow(
                    displayTitle, roleKey, publication, citation,
                    detail != null ? detail.getForumCategoryLetter() : null,
                    detail != null ? detail.getAuthorCount() : null,
                    detail != null ? detail.getForumName() : null,
                    detail != null ? detail.getYear() : null,
                    fileDetail != null ? fileDetail.getForumCategoryLetter() : null,
                    fileDetail != null ? fileDetail.getAuthorCount() : null,
                    breakdown, duplicates);
            rows.add(row);
            if (row.rowMatches()) matching++; else differing++;
        }

        // ── Activities (Perspectiva D blocks) ────────────────────────────────
        ActivityTally activities = compareActivities(platformItems, fileItems, boundOptionsByBlock);
        platformTotal += activities.platformTotal;
        fileTotal += activities.fileTotal;
        correctPoints += activities.correctPoints;
        matching += activities.matching;
        differing += activities.differing;

        return new ReportScoreComparison(rows, activities.blocks, round(platformTotal), round(fileTotal),
                round(correctPoints), matching, differing);
    }

    private ReportScoreComparison.ScorePair pair(Dim platform, Dim file) {
        if (platform == null && file == null) return null;
        Double p = platform != null ? round(platform.score) : null;
        Double f = file != null ? round(file.score) : null;
        ReportScoreComparison.Status status;
        Double delta = null;
        if (platform != null && file != null) {
            delta = round(platform.score - file.score);
            status = Math.abs(platform.score - file.score) <= EPSILON
                    ? ReportScoreComparison.Status.MATCH : ReportScoreComparison.Status.DIFFERS;
        } else if (file != null) {
            status = ReportScoreComparison.Status.ONLY_IN_FILE;
        } else {
            status = ReportScoreComparison.Status.ONLY_IN_PLATFORM;
        }
        return new ReportScoreComparison.ScorePair(p, f, status, delta);
    }

    private List<ReportScoreComparison.CitationDetail> citationBreakdown(CitationSnapshotItem platform,
                                                                         CitationSnapshotItem file) {
        if (platform == null && file == null) return List.of();
        Map<String, double[]> byTitle = new LinkedHashMap<>(); // [platform, file]
        Map<String, String> display = new LinkedHashMap<>();
        addCiting(platform, byTitle, display, 0);
        addCiting(file, byTitle, display, 1);

        List<ReportScoreComparison.CitationDetail> out = new ArrayList<>();
        for (Map.Entry<String, double[]> e : byTitle.entrySet()) {
            double[] v = e.getValue();
            boolean hasP = !Double.isNaN(v[0]);
            boolean hasF = !Double.isNaN(v[1]);
            ReportScoreComparison.Status status;
            if (hasP && hasF) {
                status = Math.abs(v[0] - v[1]) <= EPSILON
                        ? ReportScoreComparison.Status.MATCH : ReportScoreComparison.Status.DIFFERS;
            } else if (hasF) {
                status = ReportScoreComparison.Status.ONLY_IN_FILE;
            } else {
                status = ReportScoreComparison.Status.ONLY_IN_PLATFORM;
            }
            Double delta = (hasP && hasF) ? round(v[0] - v[1]) : null;
            out.add(new ReportScoreComparison.CitationDetail(
                    display.get(e.getKey()),
                    hasP ? round(v[0]) : null,
                    hasF ? round(v[1]) : null,
                    status,
                    delta));
        }
        return out;
    }

    // ── Activities ───────────────────────────────────────────────────────────

    private static final double SIMILARITY_THRESHOLD = 0.4;
    private static final int MIN_SHARED_TOKENS = 2;

    private static final class ActivityTally {
        List<ReportScoreComparison.ActivityBlockComparison> blocks = new ArrayList<>();
        double platformTotal, fileTotal, correctPoints;
        int matching, differing;
    }

    private ActivityTally compareActivities(List<SnapshotItem> platformItems, List<SnapshotItem> fileItems,
                                            Map<String, List<ReportScoreComparison.ActivityOption>> boundOptionsByBlock) {
        Map<String, List<ActivitySnapshotItem>> platformByBlock = activitiesByBlock(platformItems);
        Map<String, List<ActivitySnapshotItem>> fileByBlock = activitiesByBlock(fileItems);

        Set<String> blocks = new java.util.LinkedHashSet<>();
        blocks.addAll(platformByBlock.keySet());
        blocks.addAll(fileByBlock.keySet());

        ActivityTally tally = new ActivityTally();
        for (String block : blocks) {
            List<ActivitySnapshotItem> platform = platformByBlock.getOrDefault(block, List.of());
            List<ActivitySnapshotItem> file = fileByBlock.getOrDefault(block, List.of());

            double pTotal = platform.stream().mapToDouble(a -> a.getScore() != null ? a.getScore() : 0).sum();
            double fTotal = file.stream().mapToDouble(a -> a.getScore() != null ? a.getScore() : 0).sum();

            // Global best-first fuzzy pairing: score every (file × platform) candidate above the
            // threshold, then assign the highest-similarity pairs first. Avoids an early, weaker
            // file row consuming a platform item that a later, stronger row should own.
            List<double[]> candidates = new ArrayList<>(); // [sim, fileIdx, platIdx]
            for (int fi = 0; fi < file.size(); fi++) {
                for (int pi = 0; pi < platform.size(); pi++) {
                    double sim = similarity(file.get(fi).getDescription(), platform.get(pi).getDescription());
                    if (sim >= SIMILARITY_THRESHOLD) candidates.add(new double[]{sim, fi, pi});
                }
            }
            candidates.sort((x, y) -> Double.compare(y[0], x[0]));

            boolean[] fileUsed = new boolean[file.size()];
            boolean[] platformUsed = new boolean[platform.size()];
            List<ReportScoreComparison.ActivityItem> matched = new ArrayList<>();
            List<ReportScoreComparison.ActivityItem> importable = new ArrayList<>();
            double blockCorrect = 0;

            for (double[] cand : candidates) {
                int fi = (int) cand[1], pi = (int) cand[2];
                if (fileUsed[fi] || platformUsed[pi]) continue;
                fileUsed[fi] = true;
                platformUsed[pi] = true;
                ActivitySnapshotItem f = file.get(fi);
                ActivitySnapshotItem p = platform.get(pi);
                double ps = p.getScore() != null ? p.getScore() : 0;
                double fs = f.getScore() != null ? f.getScore() : 0;
                ReportScoreComparison.Status st = Math.abs(ps - fs) <= EPSILON
                        ? ReportScoreComparison.Status.MATCH : ReportScoreComparison.Status.DIFFERS;
                if (st == ReportScoreComparison.Status.MATCH) blockCorrect += ps;
                matched.add(new ReportScoreComparison.ActivityItem(
                        p.getDescription() != null ? p.getDescription() : f.getDescription(),
                        p.getCategory(), round(ps), round(fs), st, round(ps - fs)));
            }

            for (int fi = 0; fi < file.size(); fi++) {
                if (fileUsed[fi]) continue;
                ActivitySnapshotItem f = file.get(fi);
                importable.add(new ReportScoreComparison.ActivityItem(
                        f.getDescription(), f.getCategory(), null,
                        round(f.getScore() != null ? f.getScore() : 0),
                        ReportScoreComparison.Status.ONLY_IN_FILE, null));
            }

            List<ReportScoreComparison.ActivityItem> onlyPlatform = new ArrayList<>();
            for (int i = 0; i < platform.size(); i++) {
                if (platformUsed[i]) continue;
                ActivitySnapshotItem p = platform.get(i);
                onlyPlatform.add(new ReportScoreComparison.ActivityItem(
                        p.getDescription(), p.getCategory(),
                        round(p.getScore() != null ? p.getScore() : 0), null,
                        ReportScoreComparison.Status.ONLY_IN_PLATFORM, null));
            }

            ReportScoreComparison.Status blockStatus = Math.abs(pTotal - fTotal) <= EPSILON
                    ? ReportScoreComparison.Status.MATCH : ReportScoreComparison.Status.DIFFERS;

            // Candidate Activity types for the inline add-form: every activityId the platform already
            // has for this block, plus every activityId the report definition binds to this block (so
            // a researcher with zero existing entries still gets a real candidate, not a dead end).
            List<ReportScoreComparison.ActivityOption> options =
                    activityOptions(platform, boundOptionsByBlock.getOrDefault(block, List.of()));

            tally.blocks.add(new ReportScoreComparison.ActivityBlockComparison(
                    block, round(pTotal), round(fTotal), round(pTotal - fTotal), blockStatus,
                    matched, importable, onlyPlatform, options));
            tally.platformTotal += pTotal;
            tally.fileTotal += fTotal;
            tally.correctPoints += blockCorrect;
            if (blockStatus == ReportScoreComparison.Status.MATCH) tally.matching++; else tally.differing++;
        }
        return tally;
    }

    private Map<String, List<ActivitySnapshotItem>> activitiesByBlock(List<SnapshotItem> items) {
        Map<String, List<ActivitySnapshotItem>> out = new LinkedHashMap<>();
        if (items == null) return out;
        for (SnapshotItem i : items) {
            if (i instanceof ActivitySnapshotItem a && a.getActivityName() != null) {
                out.computeIfAbsent(a.getActivityName(), k -> new ArrayList<>()).add(a);
            }
        }
        return out;
    }

    private List<ReportScoreComparison.ActivityOption> activityOptions(
            List<ActivitySnapshotItem> platform, List<ReportScoreComparison.ActivityOption> bound) {
        Map<String, ReportScoreComparison.ActivityOption> byId = new LinkedHashMap<>();
        // Bound options carry the true Activity type name (e.g. "Grant Cercetare"); platform items'
        // activityName is actually the BLOCK name (e.g. "Granturi", see ActivityBlockProjector), so a
        // bound option — when one exists for this id — is always the more useful label. Bound first.
        for (ReportScoreComparison.ActivityOption option : bound) {
            if (option.activityId() != null) {
                byId.put(option.activityId(), option);
            }
        }
        for (ActivitySnapshotItem a : platform) {
            if (a.getActivityId() != null) {
                byId.putIfAbsent(a.getActivityId(), new ReportScoreComparison.ActivityOption(a.getActivityId(), a.getActivityName()));
            }
        }
        return new ArrayList<>(byId.values());
    }

    /**
     * Title-tolerant similarity: overlap coefficient (shared tokens ÷ the smaller token set) rather
     * than Jaccard, so a match isn't penalised when one side carries extra noise (full author lists,
     * book-series text, etc.). Requires a minimum number of shared tokens to avoid trivial matches —
     * EXCEPT when the smaller side is itself short (≤2 tokens): that's exactly the shape of a grant
     * row entered as "ACRONYM, https://project-url.eu" (tokens: the acronym + a URL-domain fragment).
     * The platform's own built description (Activity name + fields + resolved project label) has no
     * reason to also contain that URL fragment, so requiring 2 shared tokens there would reject a
     * correct acronym-only match; the ratio floor below already guards it (1 shared token out of a
     * ≤2-token side is ≥50% overlap, well past SIMILARITY_THRESHOLD). NOISE_TOKENS strips the generic
     * filler words ("proiect"/"project") that would otherwise turn this relaxation into false matches
     * across unrelated grants sharing only that word.
     */
    private double similarity(String a, String b) {
        Set<String> ta = tokens(a);
        Set<String> tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) return 0.0;
        Set<String> inter = new java.util.HashSet<>(ta);
        inter.retainAll(tb);
        int minSize = Math.min(ta.size(), tb.size());
        int requiredShared = minSize <= 2 ? 1 : MIN_SHARED_TOKENS;
        if (inter.size() < requiredShared) return 0.0;
        return (double) inter.size() / minSize;
    }

    private static final Set<String> NOISE_TOKENS =
            Set.of("https", "http", "www", "org", "com", "proiect", "project");

    private Set<String> tokens(String s) {
        if (s == null) return Set.of();
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        Set<String> out = new java.util.HashSet<>();
        for (String t : n.toLowerCase().split("[^a-z0-9]+")) {
            if (t.length() >= 3 && !NOISE_TOKENS.contains(t)) out.add(t);
        }
        return out;
    }

    private List<ReportScoreComparison.DuplicateCitationGroup> buildDuplicateGroups(
            List<CitationSnapshotItem> duplicates, String currentKey,
            Map<String, Set<String>> platformCitingSets, Map<String, String> platformCitedDisplay) {
        if (duplicates == null || duplicates.isEmpty()) return List.of();
        List<ReportScoreComparison.DuplicateCitationGroup> out = new ArrayList<>();
        for (CitationSnapshotItem dup : duplicates) {
            List<ReportScoreComparison.CitationDetail> citings = new ArrayList<>();
            Set<String> dupTitles = new java.util.HashSet<>();
            double total = 0.0;
            if (dup.getCitingPublications() != null) {
                for (CitationSnapshotItem.CitingPublication c : dup.getCitingPublications()) {
                    double s = c.getScore() != null ? c.getScore() : 0.0;
                    total += s;
                    if (c.getTitle() != null) dupTitles.add(normalize(c.getTitle()));
                    citings.add(new ReportScoreComparison.CitationDetail(
                            c.getTitle(), null, round(s), ReportScoreComparison.Status.ONLY_IN_FILE, null));
                }
            }
            // Suggest the platform-cited paper whose citing set overlaps the duplicate's the most
            // (excluding the current paper, which the sheet mistakenly re-titled).
            String suggested = null;
            int bestOverlap = 0;
            for (Map.Entry<String, Set<String>> e : platformCitingSets.entrySet()) {
                if (e.getKey().equals(currentKey)) continue;
                int overlap = 0;
                for (String t : dupTitles) if (e.getValue().contains(t)) overlap++;
                if (overlap > bestOverlap) {
                    bestOverlap = overlap;
                    suggested = platformCitedDisplay.get(e.getKey());
                }
            }
            out.add(new ReportScoreComparison.DuplicateCitationGroup(suggested, bestOverlap, round(total), citings));
        }
        return out;
    }

    private void addCiting(CitationSnapshotItem tile, Map<String, double[]> byTitle,
                           Map<String, String> display, int slot) {
        if (tile == null || tile.getCitingPublications() == null) return;
        for (CitationSnapshotItem.CitingPublication c : tile.getCitingPublications()) {
            if (c.getTitle() == null) continue;
            String key = normalize(c.getTitle());
            double[] v = byTitle.computeIfAbsent(key, k -> new double[]{Double.NaN, Double.NaN});
            double score = c.getScore() != null ? c.getScore() : 0.0;
            v[slot] = Double.isNaN(v[slot]) ? score : v[slot] + score;
            display.putIfAbsent(key, c.getTitle());
        }
    }

    private double correctOf(ReportScoreComparison.ScorePair pair) {
        return (pair != null && pair.status() == ReportScoreComparison.Status.MATCH && pair.platform() != null)
                ? pair.platform() : 0.0;
    }

    private void index(List<SnapshotItem> items, Map<String, Dim> pubOut, Map<String, Dim> citOut) {
        if (items == null) return;
        for (SnapshotItem item : items) {
            if (item instanceof PublicationSnapshotItem p && p.getTitle() != null) {
                double score = p.getScore() != null ? p.getScore() : 0.0;
                merge(pubOut, normalize(p.getTitle()), new Dim(p.getRoleKey(), p.getTitle(), score, p));
            } else if (item instanceof CitationSnapshotItem c && c.getPublicationTitle() != null) {
                double score = c.getScore() != null ? c.getScore() : 0.0;
                Dim dim = new Dim(c.getRoleKey() != null ? c.getRoleKey() : CITATION_ROLE,
                        c.getPublicationTitle(), score, null);
                dim.citationSource = c;
                merge(citOut, normalize(c.getPublicationTitle()), dim);
            }
        }
    }

    private void merge(Map<String, Dim> map, String key, Dim dim) {
        Dim existing = map.get(key);
        if (existing == null) {
            map.put(key, dim);
            return;
        }
        if (dim.citationSource != null) {
            // A second (or third...) citation sheet re-used an already-seen cited title. Do NOT add it
            // to the paper's score — keep it aside as a suspected misattribution to surface separately.
            existing.duplicateCitationSources.add(dim.citationSource);
        } else {
            existing.score += dim.score; // publications: legitimately sum same-title rows
        }
    }

    /**
     * Tolerant title key: strips diacritics, lowercases, and collapses every run of non-alphanumeric
     * characters (trailing periods, commas, ASCII vs unicode hyphens, double spaces) to a single
     * space. This lets hand-maintained files match the platform despite punctuation/casing drift
     * (e.g. "…systems" vs "…systems.", "self-organizing" vs "self‐organizing").
     */
    private String normalize(String s) {
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "");
        return n.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static final class Dim {
        final String roleKey;
        final String title;
        double score;
        final PublicationSnapshotItem source;
        CitationSnapshotItem citationSource;
        final List<CitationSnapshotItem> duplicateCitationSources = new ArrayList<>();

        Dim(String roleKey, String title, double score, PublicationSnapshotItem source) {
            this.roleKey = roleKey;
            this.title = title;
            this.score = score;
            this.source = source;
        }
    }
}
