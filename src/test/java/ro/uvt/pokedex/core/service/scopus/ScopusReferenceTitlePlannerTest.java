package ro.uvt.pokedex.core.service.scopus;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.tasks.ScopusCitationsUpdate;
import ro.uvt.pokedex.core.service.scopus.dto.CitationsByTitleRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScopusReferenceTitlePlannerTest {

    private final ScopusReferenceTitlePlanner planner = new ScopusReferenceTitlePlanner();

    @Test
    void keysEidWorksByEidAndEidLessWorksByDoiOrCanonicalId() {
        ScholardexPublicationView scopusWork = pub("spub_1", "2-s2.0-1", "10.1007/978-3-031-87778-0_42",
                "A Hybrid Microservices Architecture for Smart Glasses");
        ScholardexPublicationView arxivWork = pub("spub_2", null, "https://doi.org/10.48550/arXiv.0905.4601",
                "Considerations on Construction Ontologies");
        ScholardexPublicationView userWork = pub("spub_3", null, null, "A user-defined technical report title");
        ScholardexPublicationView shortTitle = pub("spub_4", null, "10.1/x", "Editorial");

        Map<String, CitationsByTitleRequest.CitedWorkSpec> items = planner.buildItems(
                List.of(scopusWork, arxivWork, userWork, shortTitle), List.of(), List.of(), List.of(), new ScopusCitationsUpdate());

        assertThat(items.keySet()).containsExactly("2-s2.0-1", "doi:10.48550/arxiv.0905.4601", "spub_3");
        assertThat(items.get("doi:10.48550/arxiv.0905.4601").getTitle()).isEqualTo("Considerations on Construction Ontologies");
    }

    @Test
    void knownCitingEidsAndNewestCitingDateComeFromTheExistingGraph() {
        ScholardexPublicationView cited = pub("spub_1", "2-s2.0-1", null, "Considerations on Construction Ontologies");
        ScholardexPublicationView citingA = pub("spub_a", "2-s2.0-a", null, "Citing A");
        citingA.setCoverDate("2017-01-01");
        ScholardexPublicationView citingB = pub("spub_b", null, "10.1/b", "Citing B (OpenAlex only)");
        citingB.setCoverDate("2019-06-15");

        Map<String, CitationsByTitleRequest.CitedWorkSpec> items = planner.buildItems(
                List.of(cited), List.of(cite("spub_1", "spub_a"), cite("spub_1", "spub_b")),
                List.of(citingA, citingB), List.of(), new ScopusCitationsUpdate());

        CitationsByTitleRequest.CitedWorkSpec spec = items.get("2-s2.0-1");
        assertThat(spec.getKnownCitingEids()).containsExactly("2-s2.0-a");
        assertThat(spec.getFromDate()).isEqualTo("2019-06-15");
    }

    @Test
    void syncModeOverridesTheFromDateLikeTheEidPass() {
        ScopusCitationsUpdate full = new ScopusCitationsUpdate();
        full.setSyncMode("FULL");
        assertThat(planner.resolveFromDate(full, "2019-06-15")).isNull();

        ScopusCitationsUpdate period = new ScopusCitationsUpdate();
        period.setSyncMode("PERIOD");
        period.setStartYear(2020);
        assertThat(planner.resolveFromDate(period, "2019-06-15")).isEqualTo("2020-01-01");
        assertThat(planner.resolveFromDate(period, "2022-03-01")).isEqualTo("2022-03-01");

        assertThat(planner.resolveFromDate(new ScopusCitationsUpdate(), "2019-06-15")).isEqualTo("2019-06-15");
    }

    @Test
    void surnameVariantsCoverEveryNameShapeAndAddTheAsciiForm() {
        ScholardexAuthorView view = new ScholardexAuthorView();
        view.setName("Fortiș T.-F.");
        view.setAlternativeNames(List.of("Fortiş, Teodor Florin", "Teodor-Florin Fortiș", "Fortis F.", "X"));

        List<String> surnames = ScopusReferenceTitlePlanner.surnameVariants(List.of(view));

        assertThat(surnames).containsExactly("Fortiș", "Fortis", "Fortiş");
        assertThat(ScopusReferenceTitlePlanner.surnameOf("Alexandra-Emilia Fortiș")).isEqualTo("Fortiș");
        assertThat(ScopusReferenceTitlePlanner.surnameOf("Spataru A.")).isEqualTo("Spataru");
        assertThat(ScopusReferenceTitlePlanner.surnameOf("A.")).isNull();
    }

    private static ScholardexPublicationView pub(String id, String eid, String doi, String title) {
        ScholardexPublicationView p = new ScholardexPublicationView();
        p.setId(id);
        p.setEid(eid);
        p.setDoi(doi);
        p.setTitle(title);
        return p;
    }

    private static ScholardexCitationView cite(String citedId, String citingId) {
        ScholardexCitationView c = new ScholardexCitationView();
        c.setCitedId(citedId);
        c.setCitingId(citingId);
        return c;
    }
}
