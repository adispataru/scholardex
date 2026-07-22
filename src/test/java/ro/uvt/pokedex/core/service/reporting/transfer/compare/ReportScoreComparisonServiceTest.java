package ro.uvt.pokedex.core.service.reporting.transfer.compare;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.transfer.ActivitySnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.CitationSnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.PublicationSnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.SnapshotItem;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReportScoreComparisonServiceTest {

    private final ReportScoreComparisonService service = new ReportScoreComparisonService();

    @Test
    void mergesPublicationAndCitationDimensionsIntoOneRowPerTitle() {
        List<SnapshotItem> platform = List.of(
                pub("journal-publications", "Paper A", 8.0),
                citationTile("Paper A", 5.0)
        );
        List<SnapshotItem> file = List.of(
                pub("journal-publications", "Paper A", 8.0),     // publication matches
                citationTile("Paper A", 3.0)                     // citation differs
        );

        ReportScoreComparison cmp = service.compare(platform, file);

        assertThat(cmp.rows()).hasSize(1);
        ReportScoreComparison.PublicationRow row = cmp.rows().get(0);
        assertThat(row.title()).isEqualTo("Paper A");
        assertThat(row.publication().status()).isEqualTo(ReportScoreComparison.Status.MATCH);
        assertThat(row.publication().platform()).isEqualTo(8.0);
        assertThat(row.citation().status()).isEqualTo(ReportScoreComparison.Status.DIFFERS);
        assertThat(row.citation().platform()).isEqualTo(5.0);
        assertThat(row.citation().file()).isEqualTo(3.0);
        assertThat(row.rowMatches()).isFalse();

        // Correct points = publication (8, matches) only; citation differs so not counted.
        assertThat(cmp.correctPoints()).isEqualTo(8.0);
        assertThat(cmp.platformTotal()).isEqualTo(13.0); // 8 + 5
        assertThat(cmp.fileTotal()).isEqualTo(11.0);     // 8 + 3
        assertThat(cmp.matchingCount()).isZero();
        assertThat(cmp.differingCount()).isEqualTo(1);
    }

    @Test
    void publicationOnlyAndCitationOnlyTitlesEachGetTheirOwnRow() {
        List<SnapshotItem> platform = List.of(
                pub("conference-publications", "Conf only", 4.0),
                citationTile("Cited only", 2.0)
        );
        List<SnapshotItem> file = List.of(
                pub("conference-publications", "Conf only", 4.0),
                citationTile("Cited only", 2.0)
        );

        ReportScoreComparison cmp = service.compare(platform, file);

        assertThat(cmp.rows()).hasSize(2);
        ReportScoreComparison.PublicationRow confOnly = cmp.rows().stream()
                .filter(r -> r.title().equals("Conf only")).findFirst().orElseThrow();
        assertThat(confOnly.publication()).isNotNull();
        assertThat(confOnly.citation()).isNull();

        ReportScoreComparison.PublicationRow citedOnly = cmp.rows().stream()
                .filter(r -> r.title().equals("Cited only")).findFirst().orElseThrow();
        assertThat(citedOnly.publication()).isNull();
        assertThat(citedOnly.citation()).isNotNull();

        assertThat(cmp.matchingCount()).isEqualTo(2);
        assertThat(cmp.correctPoints()).isEqualTo(6.0); // 4 + 2
    }

    @Test
    void titleMatchingIsCaseAndWhitespaceInsensitive() {
        List<SnapshotItem> platform = List.of(pub("journal-publications", "Self-Supervised  Graph Learning", 8.0));
        List<SnapshotItem> file = List.of(pub("journal-publications", "self-supervised graph learning", 8.0));

        ReportScoreComparison cmp = service.compare(platform, file);
        assertThat(cmp.rows()).hasSize(1);
        assertThat(cmp.matchingCount()).isEqualTo(1);
        assertThat(cmp.correctPoints()).isEqualTo(8.0);
    }

    @Test
    void titleMatchingToleratesTrailingPunctuationAndUnicodeHyphens() {
        // Real-world drift: trailing period, unicode hyphen, casing.
        List<SnapshotItem> platform = List.of(
                pub("conference-publications", "A generic framework supporting self-organisation", 0.40));
        List<SnapshotItem> file = List.of(
                pub("conference-publications", "A generic framework supporting self‐organisation.", 0.40));

        ReportScoreComparison cmp = service.compare(platform, file);
        assertThat(cmp.rows()).hasSize(1);
        assertThat(cmp.rows().get(0).publication().status()).isEqualTo(ReportScoreComparison.Status.MATCH);
        assertThat(cmp.correctPoints()).isEqualTo(0.40);
    }

    private PublicationSnapshotItem pub(String roleKey, String title, double score) {
        PublicationSnapshotItem p = new PublicationSnapshotItem();
        p.setRoleKey(roleKey);
        p.setTitle(title);
        p.setItemKey(title);
        p.setScore(score);
        return p;
    }

    @Test
    void buildsPerCitationBreakdownMatchedByCitingTitle() {
        CitationSnapshotItem platformTile = citationTile("Paper A", 12.0);
        platformTile.getCitingPublications().add(citing("Citing one", 8.0));
        platformTile.getCitingPublications().add(citing("Citing two", 4.0));

        CitationSnapshotItem fileTile = citationTile("Paper A", 10.0);
        fileTile.getCitingPublications().add(citing("Citing one", 8.0));   // match
        fileTile.getCitingPublications().add(citing("Citing two", 2.0));   // differs

        ReportScoreComparison cmp = service.compare(List.of(platformTile), List.of(fileTile));
        ReportScoreComparison.PublicationRow row = cmp.rows().get(0);

        assertThat(row.citationBreakdown()).hasSize(2);
        ReportScoreComparison.CitationDetail one = row.citationBreakdown().stream()
                .filter(d -> d.citingTitle().equals("Citing one")).findFirst().orElseThrow();
        ReportScoreComparison.CitationDetail two = row.citationBreakdown().stream()
                .filter(d -> d.citingTitle().equals("Citing two")).findFirst().orElseThrow();
        assertThat(one.status()).isEqualTo(ReportScoreComparison.Status.MATCH);
        assertThat(one.platform()).isEqualTo(8.0);
        assertThat(two.status()).isEqualTo(ReportScoreComparison.Status.DIFFERS);
        assertThat(two.platform()).isEqualTo(4.0);
        assertThat(two.file()).isEqualTo(2.0);
    }

    @Test
    void publicationDiffersExposesCategoryAndAuthorCountFieldDiff() {
        PublicationSnapshotItem platform = pub("journal-publications", "Paper A", 8.0);
        platform.setForumCategoryLetter("A");
        platform.setAuthorCount(7);
        PublicationSnapshotItem file = pub("journal-publications", "Paper A", 2.0);
        file.setForumCategoryLetter("C");
        file.setAuthorCount(3);

        ReportScoreComparison cmp = service.compare(List.of(platform), List.of(file));
        ReportScoreComparison.PublicationRow row = cmp.rows().get(0);

        assertThat(row.publication().status()).isEqualTo(ReportScoreComparison.Status.DIFFERS);
        assertThat(row.categoryDiffers()).isTrue();
        assertThat(row.platformCategory()).isEqualTo("A");
        assertThat(row.fileCategory()).isEqualTo("C");
        assertThat(row.authorCountDiffers()).isTrue();
        assertThat(row.platformAuthorCount()).isEqualTo(7);
        assertThat(row.fileAuthorCount()).isEqualTo(3);
    }

    @Test
    void duplicateCitationSheetIsKeptSeparateAndSuggestsTheActualPaper() {
        // Platform knows the real citation graph: "Paper A" cited by X+Y, "Other paper" cited by Z+W.
        CitationSnapshotItem platformA = citationTile("Paper A", 8.0);
        platformA.getCitingPublications().add(citing("Citing X", 4.0));
        platformA.getCitingPublications().add(citing("Citing Y", 4.0));
        CitationSnapshotItem platformOther = citationTile("Other paper", 6.0);
        platformOther.getCitingPublications().add(citing("Citing Z", 4.0));
        platformOther.getCitingPublications().add(citing("Citing W", 2.0));

        // File has two sheets both titled "Paper A": the real one, plus a duplicate that actually
        // holds "Other paper"'s citations (the user forgot to update the cited title).
        CitationSnapshotItem fileA = citationTile("Paper A", 8.0);
        fileA.getCitingPublications().add(citing("Citing X", 4.0));
        fileA.getCitingPublications().add(citing("Citing Y", 4.0));
        CitationSnapshotItem fileDup = citationTile("Paper A", 6.0);
        fileDup.getCitingPublications().add(citing("Citing Z", 4.0));
        fileDup.getCitingPublications().add(citing("Citing W", 2.0));

        ReportScoreComparison cmp = service.compare(
                List.of(platformA, platformOther),
                List.of(fileA, fileDup));

        ReportScoreComparison.PublicationRow row = cmp.rows().stream()
                .filter(r -> r.title().equals("Paper A")).findFirst().orElseThrow();

        // The paper's own citation score is the primary tile only (8), not 14 — duplicate not summed.
        assertThat(row.citation().platform()).isEqualTo(8.0);
        assertThat(row.citation().file()).isEqualTo(8.0);
        assertThat(row.citation().status()).isEqualTo(ReportScoreComparison.Status.MATCH);

        // The duplicate sheet is surfaced separately, with the actual paper suggested.
        assertThat(row.duplicateCitationGroups()).hasSize(1);
        ReportScoreComparison.DuplicateCitationGroup g = row.duplicateCitationGroups().get(0);
        assertThat(g.totalFileScore()).isEqualTo(6.0);
        assertThat(g.suggestedActualTitle()).isEqualTo("Other paper");
        assertThat(g.suggestionOverlap()).isEqualTo(2);
        assertThat(g.citations()).extracting(ReportScoreComparison.CitationDetail::citingTitle)
                .containsExactlyInAnyOrder("Citing Z", "Citing W");
    }

    @Test
    void activityBlockFuzzyMatchesAndFlagsImportable() {
        // Platform "Granturi": two grants. File: one matching (fuzzy text), one new (importable).
        ActivitySnapshotItem pGrant1 = activity("Granturi", "a-grant", "PI on Horizon Europe project CloudLightning", "A", 6.0);
        ActivitySnapshotItem pGrant2 = activity("Granturi", "a-grant", "Member of national research grant PED", "B", 2.0);
        ActivitySnapshotItem fGrant1 = activity("Granturi", null, "Horizon Europe CloudLightning project (director)", "A", 6.0);
        ActivitySnapshotItem fNew = activity("Granturi", null, "Brand new bilateral mobility grant 2024", "C", 4.0);

        ReportScoreComparison cmp = service.compare(
                List.of(pGrant1, pGrant2), List.of(fGrant1, fNew));

        assertThat(cmp.activityBlocks()).hasSize(1);
        ReportScoreComparison.ActivityBlockComparison block = cmp.activityBlocks().get(0);
        assertThat(block.blockName()).isEqualTo("Granturi");
        assertThat(block.platformTotal()).isEqualTo(8.0);
        assertThat(block.fileTotal()).isEqualTo(10.0);

        // CloudLightning grant fuzzy-matched (shared tokens "horizon","europe","cloudlightning","project").
        assertThat(block.matched()).hasSize(1);
        assertThat(block.matched().get(0).status()).isEqualTo(ReportScoreComparison.Status.MATCH);

        // The brand-new grant has no platform counterpart → importable.
        assertThat(block.importable()).hasSize(1);
        assertThat(block.importable().get(0).description()).isEqualTo("Brand new bilateral mobility grant 2024");

        // The unmatched platform grant (PED) is only-in-platform.
        assertThat(block.onlyInPlatform()).hasSize(1);

        // Activity option resolved from the platform side for the add-form.
        assertThat(block.activityOptions()).extracting(ReportScoreComparison.ActivityOption::activityId)
                .containsExactly("a-grant");
    }

    @Test
    void activityOptionsAreEmptyForABlockTheResearcherHasNeverUsedWithoutBoundOptions() {
        // A researcher with ZERO existing "Granturi" entries gets no activityId from platform items —
        // this is the gap the report-bound-options parameter exists to close.
        ActivitySnapshotItem fNew = activity("Granturi", null, "First grant ever, e.g. SCAPE FP7", "C", 2.0);

        ReportScoreComparison cmp = service.compare(List.of(), List.of(fNew));

        ReportScoreComparison.ActivityBlockComparison block = cmp.activityBlocks().get(0);
        assertThat(block.hasImportable()).isTrue();
        assertThat(block.activityOptions()).isEmpty();
    }

    @Test
    void activityOptionsFallBackToReportBoundActivitiesWhenPlatformHasNone() {
        // Same zero-existing-entries scenario, but the report definition's own block binding is passed
        // through — the researcher now gets a real candidate Activity type for the "Add to platform" form.
        ActivitySnapshotItem fNew = activity("Granturi", null, "First grant ever, e.g. SCAPE FP7", "C", 2.0);
        ReportScoreComparison.ActivityOption grantCercetare =
                new ReportScoreComparison.ActivityOption("act-grant-cercetare", "Grant Cercetare");

        ReportScoreComparison cmp = service.compare(
                List.of(), List.of(fNew), Map.of("Granturi", List.of(grantCercetare)));

        ReportScoreComparison.ActivityBlockComparison block = cmp.activityBlocks().get(0);
        assertThat(block.activityOptions()).containsExactly(grantCercetare);
    }

    @Test
    void activityOptionsUnionPlatformAndReportBoundWithoutDuplicates() {
        // The platform already has one grant activityId; the report also binds that SAME activityId
        // plus a second one the researcher hasn't used yet — union, deduped by activityId.
        ActivitySnapshotItem pGrant = activity("Granturi", "act-grant-cercetare", "Existing grant", "A", 4.0);
        ActivitySnapshotItem fNew = activity("Granturi", null, "Brand new second grant", "C", 1.0);
        ReportScoreComparison.ActivityOption grantCercetare =
                new ReportScoreComparison.ActivityOption("act-grant-cercetare", "Grant Cercetare");
        ReportScoreComparison.ActivityOption grantMobilitate =
                new ReportScoreComparison.ActivityOption("act-grant-mobilitate", "Grant Mobilitate");

        ReportScoreComparison cmp = service.compare(
                List.of(pGrant), List.of(fNew),
                Map.of("Granturi", List.of(grantCercetare, grantMobilitate)));

        ReportScoreComparison.ActivityBlockComparison block = cmp.activityBlocks().get(0);
        assertThat(block.activityOptions()).containsExactlyInAnyOrder(grantCercetare, grantMobilitate);
    }

    @Test
    void bookChapterMatchesDespiteDifferentSurroundingText() {
        ActivitySnapshotItem platform = activity("Carti/Capitole", "a-book",
                "Application Blueprints and Service Description — Dragan, Ioan, Fortis T.-F., Neagul, "
                        + "Marian, Petcu, D., Selea T., Spataru A. — Palgrave Studies in Digital Business "
                        + "and Enabling Technologies (2018)", "A", 8.0);
        ActivitySnapshotItem file = activity("Carti/Capitole", null,
                "Dragan, Ioan, et al. \"Application Blueprints and Service Description.\" Heterogeneity, "
                        + "High Performance Computing, Self-Organization and the Cloud. Palgrave Macmillan, "
                        + "Cham, 2018. 89-117.", "A", 8.0);

        ReportScoreComparison cmp = service.compare(List.of(platform), List.of(file));
        ReportScoreComparison.ActivityBlockComparison block = cmp.activityBlocks().get(0);

        assertThat(block.matched()).hasSize(1);
        assertThat(block.matched().get(0).status()).isEqualTo(ReportScoreComparison.Status.MATCH);
        assertThat(block.importable()).isEmpty();
    }

    @Test
    void globalBestPairingMatchesTheRightLabRowNotTheFirstAboveThreshold() {
        // Platform has one course (143188). File has three similar "Laborator … stepik …" rows that
        // share URL tokens; only the 143188 one should match — the others are importable.
        ActivitySnapshotItem platform = activity("Curs in format electronic", "a-curs",
                "Algorithms and Data Structures — Dovezi (link, ISBN): https://stepik.org/course/143188/syllabus "
                        + "— Nume: Curs interactiv în format digital — (2022)", "A", 2.0);
        ActivitySnapshotItem f1 = activity("Curs in format electronic", null,
                "Laborator Programming II, Stepik (https://stepik.org/course/52108) Îndrumător de laborator", "A", 2.0);
        ActivitySnapshotItem f2 = activity("Curs in format electronic", null,
                "Laborator Programmin I, Stepik (https://stepik.org/course/102668/syllabus) Îndrumător de laborator", "A", 2.0);
        ActivitySnapshotItem f3 = activity("Curs in format electronic", null,
                "Laborator Algorithms and Data Structures II (https://stepik.org/course/143188/syllabus) Îndrumător de laborator", "A", 2.0);

        ReportScoreComparison cmp = service.compare(List.of(platform), List.of(f1, f2, f3));
        ReportScoreComparison.ActivityBlockComparison block = cmp.activityBlocks().get(0);

        assertThat(block.matched()).hasSize(1);
        // The matched row's file side is the Algorithms/143188 one — verified via importables excluding it.
        assertThat(block.importable()).hasSize(2);
        assertThat(block.importable()).extracting(ReportScoreComparison.ActivityItem::description)
                .anyMatch(d -> d.contains("Programming II"))
                .anyMatch(d -> d.contains("Programmin I"));
        assertThat(block.importable()).noneMatch(d -> d.description().contains("Algorithms and Data Structures II"));
    }

    private ActivitySnapshotItem activity(String block, String activityId, String description, String category, double score) {
        ActivitySnapshotItem a = new ActivitySnapshotItem();
        a.setActivityName(block);
        a.setActivityId(activityId);
        a.setDescription(description);
        a.setCategory(category);
        a.setScore(score);
        return a;
    }

    private CitationSnapshotItem citationTile(String publicationTitle, double score) {
        CitationSnapshotItem c = new CitationSnapshotItem();
        c.setRoleKey("citations-per-publication");
        c.setPublicationTitle(publicationTitle);
        c.setItemKey(publicationTitle);
        c.setScore(score);
        return c;
    }

    private CitationSnapshotItem.CitingPublication citing(String title, double score) {
        CitationSnapshotItem.CitingPublication c = new CitationSnapshotItem.CitingPublication();
        c.setTitle(title);
        c.setScore(score);
        return c;
    }
}
