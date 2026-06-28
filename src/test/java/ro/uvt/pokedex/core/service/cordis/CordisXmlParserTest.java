package ro.uvt.pokedex.core.service.cordis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CordisXmlParserTest {

    // Mirrors the real cordis.europa.eu/project/id/{ID}?format=xml structure: default namespace, project-level
    // child elements, and <organization type= ecContribution=> with legalName/shortName/address>country.
    private static final String XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://cordis.europa.eu">
              <rcn>232756</rcn>
              <id>101017168</id>
              <acronym>SERRANO</acronym>
              <title>TRANSPARENT APPLICATION DEPLOYMENT</title>
              <startDate>2021-01-01</startDate>
              <endDate>2023-12-31</endDate>
              <totalCost>4343180</totalCost>
              <ecMaxContribution>4343180</ecMaxContribution>
              <frameworkProgramme>H2020</frameworkProgramme>
              <relations><associations>
                <organization type="coordinator" ecContribution="550000" totalCost="550000">
                  <legalName>EREVNITIKO PANEPISTIMIAKO</legalName>
                  <shortName>EPI</shortName>
                  <address><city>ATHENS</city><country>EL</country></address>
                </organization>
                <organization type="participant" ecContribution="216250" totalCost="216250">
                  <legalName>UNIVERSITATEA DE VEST DIN TIMISOARA</legalName>
                  <shortName>WEST UNIVERSITY OF TIMISOARA</shortName>
                  <address><city>TIMISOARA</city><country>RO</country></address>
                </organization>
              </associations></relations>
            </project>
            """;

    @Test
    void parsesProjectMetadataAndOrganizations() {
        CordisProject p = CordisXmlParser.parse("101017168", XML);

        assertThat(p.grantId()).isEqualTo("101017168");
        assertThat(p.acronym()).isEqualTo("SERRANO");
        assertThat(p.title()).isEqualTo("TRANSPARENT APPLICATION DEPLOYMENT");
        assertThat(p.frameworkProgramme()).isEqualTo("H2020");
        assertThat(p.totalCost()).isEqualTo(4343180L);
        assertThat(p.ecMaxContribution()).isEqualTo(4343180L);
        assertThat(p.startYear()).isEqualTo(2021);
        assertThat(p.endYear()).isEqualTo(2023);
        assertThat(p.organizations()).hasSize(2);

        CordisProject.CordisOrg coord = p.organizations().get(0);
        assertThat(coord.role()).isEqualTo("coordinator");
        assertThat(coord.ecContribution()).isEqualTo(550000L);
        assertThat(coord.country()).isEqualTo("EL");
    }

    @Test
    void suggestsUvtContributionByNameFragment() {
        CordisProject p = CordisXmlParser.parse("101017168", XML);

        assertThat(p.contributionFor("Universitatea de Vest")).isEqualTo(216250L);
        CordisProject.CordisOrg uvt = p.organizationFor("VEST DIN TIMISOARA");
        assertThat(uvt).isNotNull();
        assertThat(uvt.legalName()).isEqualTo("UNIVERSITATEA DE VEST DIN TIMISOARA");
        assertThat(uvt.country()).isEqualTo("RO");
        assertThat(p.contributionFor("Nonexistent Org")).isNull();
    }
}
