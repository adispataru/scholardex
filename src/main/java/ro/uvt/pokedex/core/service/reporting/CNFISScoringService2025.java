package ro.uvt.pokedex.core.service.reporting;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.reporting.CNFISReport2025;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.service.application.PersistenceYearSupport;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CNFISScoringService2025 {
    private static final Logger log = LoggerFactory.getLogger(CNFISScoringService2025.class);
    private final ReportingLookupPort lookupPort;
    /** The sheet of edition 2025 — kept for the callers that report no window of their own. */
    public CNFISReport2025 getReport(ScoringPublicationReadModel publication, Domain domain) {
        return getReport(publication, domain, CnfisEdition.EDITION_2025);
    }

    /**
     * H129 — one row of Anexa 5, by the rules of the CNFIS guide (IC2.3):
     * <ul>
     * <li>reported are Article, Review and Proceedings Paper; a paper typed as a chapter counts as a
     * proceedings paper when its venue is a conference (proceedings published in a book series);</li>
     * <li>an article is classified by the list of {@link CnfisEdition#listYearFor its edition's year};</li>
     * <li>"cea mai bună clasare din anul în care a fost publicat articolul, indiferent de clasificare IF sau AIS
     * sau de categorie": the better of the AIS and the impact-factor quartile, over all SCIE/SSCI
     * categories; Arts &amp; Humanities and ESCI are columns of their own, without quartile;</li>
     * <li>ISI Proceedings is the conference index (CPCI) of the venue, not "the paper has a WoS code".</li>
     * </ul>
     * A publication the form has no place for comes back unclassified, with the reason.
     */
    public CNFISReport2025 getReport(ScoringPublicationReadModel publication, Domain domain, CnfisEdition edition) {
        ScholardexForumView forum = lookupPort.getForum(publication.getForumId());
        CNFISReport2025 report = new CNFISReport2025();
        report.setTitlu(publication.getTitle());
        report.setDoi(publication.getDoi());
        List<String> authors = publication.getAuthorIds() == null ? Collections.emptyList() : publication.getAuthorIds();
        report.setNumarAutori(authors.size());
        report.setNumarAutoriUniversitate((int) authors.stream().filter(a -> lookupPort.getUniversityAuthorIds().contains(a)).count());
        if (forum == null) {
            log.warn("Missing forum for publication {}", publication.getForumId());
            report.setLeftOutReason("the venue of the publication is not known to the platform");
            return report;
        }
        report.setDenumireJurnal(forum.getPublicationName());
        report.setIssnOnline(forum.getEIssn());
        report.setIssnPrint(forum.getIssn());

        String subtype = PublicationSubtypeSupport.resolveSubtype(publication);
        boolean conferenceVenue = forum.hasAggregationType("Conference Proceeding");
        if ("ar".equals(subtype) || "re".equals(subtype)) {
            classifyArticle(report, publication, forum, domain, edition);
        } else if ("cp".equals(subtype) || ("ch".equals(subtype) && conferenceVenue)) {
            classifyProceedingsPaper(report, forum);
        } else {
            report.setLeftOutReason("document type '" + (subtype == null || subtype.isBlank() ? "unknown" : subtype)
                    + "': reported are Article, Review and Proceedings Paper");
        }
        return report;
    }

    private void classifyArticle(CNFISReport2025 report, ScoringPublicationReadModel publication,
                                 ScholardexForumView forum, Domain domain, CnfisEdition edition) {
        // The list year is the edition's; no list can be newer than what is loaded.
        int year = PersistenceYearSupport.extractYear(publication.getCoverDate(), publication.getId(), log)
                .map(edition::listYearFor)
                .orElse(edition.lastListYear());
        year = Math.min(year, lookupPort.maxAvailableYear());
        report.setListYear(year);

        // H66 B3: resolve rankings by the canonical forum (stored-FK forum-keyed views first, with the
        // legacy fuzzy ISSN/name resolution as fallback) instead of fuzzy ISSN matching alone.
        WoSRanking.Quarter best = null;
        String bestBy = null;
        boolean arts = false;
        boolean emerging = false;
        for (WoSRanking ranking : lookupPort.getRankingsByForum(forum)) {
            for (Map.Entry<String, WoSRanking.Rank> entry : ranking.getWebOfScienceCategoryIndex().entrySet()) {
                String category = entry.getKey();
                if (!"ALL".equals(domain.getName()) && !domain.getWosCategories().contains(category)) {
                    continue;
                }
                WoSRanking.Rank rank = entry.getValue();
                WoSRanking.Quarter ais = quartile(rank.getQAis(), year);
                WoSRanking.Quarter impactFactor = quartile(rank.getQIF(), year);
                if (ais == null && impactFactor == null) {
                    continue;
                }
                switch (classifyCategoryIndex(extractCategoryIndex(category))) {
                    case SCIE_OR_SSCI -> {
                        if (ais != null && (best == null || ais.ordinal() < best.ordinal())) {
                            best = ais;
                            bestBy = "AIS " + ais + " · " + category;
                        }
                        if (impactFactor != null && (best == null || impactFactor.ordinal() < best.ordinal())) {
                            best = impactFactor;
                            bestBy = "IF " + impactFactor + " · " + category;
                        }
                    }
                    case AHCI -> arts = true;
                    case ESCI -> emerging = true;
                    default -> { }
                }
            }
        }
        if (best != null) {
            switch (best) {
                case Q1 -> report.setIsiQ1(true);
                case Q2 -> report.setIsiQ2(true);
                case Q3 -> report.setIsiQ3(true);
                default -> report.setIsiQ4(true);
            }
            report.setClassifiedBy(bestBy + " · list " + year);
            return;
        }
        // No quartile in a core edition. Arts & Humanities and ESCI carry none (or have no row for the year):
        // they come from the ranking rows above or, failing that, from the year-true edition membership, as the
        // CS scorer does. Prod 2026-09-24: an ESCI journal with a 2025 row but no quartile, and one with rows
        // only up to 2023, both exported unflagged.
        if (arts || (forum.getId() != null && lookupPort.isForumInAhci(forum.getId(), year))) {
            report.setIsiArtsHumanities(true);
            report.setClassifiedBy("Arts & Humanities Citation Index · " + year);
        } else if (emerging || (forum.getId() != null && lookupPort.isForumInEsci(forum.getId(), year))) {
            report.setIsiEmergingSourcesCitationIndex(true);
            report.setClassifiedBy("Emerging Sources Citation Index · " + year);
        } else if (forum.getId() != null && lookupPort.getForumIndexingDatabases(forum.getId()).stream()
                .anyMatch(db -> db != null && db.toUpperCase().startsWith("ERIH"))) {
            report.setErihPlus(true);
            report.setClassifiedBy("ERIH+");
        } else {
            report.setLeftOutReason("the journal is in none of the lists of " + year
                    + " (SCIE/SSCI quartiles, Arts & Humanities, ESCI, ERIH+)");
        }
    }

    private void classifyProceedingsPaper(CNFISReport2025 report, ScholardexForumView forum) {
        if (isIeee(forum)) {
            report.setIeeeProceedings(true);
            report.setClassifiedBy("IEEE Proceedings");
        } else if (forum.getId() != null && lookupPort.isForumCpciIndexed(forum.getId())) {
            report.setIsiProceedings(true);
            report.setClassifiedBy("ISI Proceedings (Conference Proceedings Citation Index)");
        } else {
            report.setLeftOutReason("the proceedings are neither IEEE nor in the Conference Proceedings Citation Index");
        }
    }

    /** IEEE by the venue the platform resolved: its publisher, or its name. */
    private static boolean isIeee(ScholardexForumView forum) {
        String publisher = forum.getPublisher() == null ? "" : forum.getPublisher();
        String name = forum.getPublicationName() == null ? "" : forum.getPublicationName();
        return publisher.contains("IEEE") || publisher.contains("Institute of Electrical and Electronics Engineers")
                || name.contains("IEEE");
    }

    /** Q1–Q4 of the year, or null: the quartile maps also hold markers that are no quartile (NOT_FOUND, …). */
    private static WoSRanking.Quarter quartile(Map<Integer, WoSRanking.Quarter> byYear, int year) {
        WoSRanking.Quarter q = byYear == null ? null : byYear.get(year);
        return q == WoSRanking.Quarter.Q1 || q == WoSRanking.Quarter.Q2
                || q == WoSRanking.Quarter.Q3 || q == WoSRanking.Quarter.Q4 ? q : null;
    }

    private String extractCategoryIndex(String category) {
        if (category == null) {
            log.warn("Encountered null WoS category while scoring CNFIS 2025.");
            return "";
        }
        String normalized = category.trim();
        if (normalized.isEmpty()) {
            log.warn("Encountered empty WoS category while scoring CNFIS 2025.");
            return "";
        }
        int delimiterPos = normalized.indexOf('-');
        if (delimiterPos < 0 || delimiterPos == normalized.length() - 1) {
            log.warn("Non-standard WoS category format '{}'; using full value as category index.", category);
            return normalized;
        }
        return normalized.substring(delimiterPos + 1).trim();
    }

    private CategoryIndexBucket classifyCategoryIndex(String categoryIndex) {
        if (categoryIndex == null) {
            return CategoryIndexBucket.OTHER;
        }
        if (categoryIndex.contains("SCIE") || categoryIndex.contains("SSCI")) {
            return CategoryIndexBucket.SCIE_OR_SSCI;
        }
        if (categoryIndex.contains("ESCI")) {
            return CategoryIndexBucket.ESCI;
        }
        if (categoryIndex.contains("AHCI")) {
            return CategoryIndexBucket.AHCI;
        }
        return CategoryIndexBucket.OTHER;
    }

    private enum CategoryIndexBucket {
        SCIE_OR_SSCI,
        ESCI,
        AHCI,
        OTHER
    }

}
