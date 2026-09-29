package ro.uvt.pokedex.core.service.openalex;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.service.openalex.dto.OpenAlexWorksResponse.OpenAlexWork;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OpenAlexFundingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void grantsBecomeOneLineWithTheAwardIds() throws Exception {
        OpenAlexWork work = mapper.readValue("""
                {"id":"https://openalex.org/W1","grants":[
                  {"funder":"https://openalex.org/F1","funder_display_name":"UEFISCDI","award_id":"PN-III-P4-ID-PCE-2020-0407"},
                  {"funder":"https://openalex.org/F2","funder_display_name":"European Commission","award_id":null},
                  {"funder":"https://openalex.org/F1","funder_display_name":"UEFISCDI","award_id":"PN-III-P4-ID-PCE-2020-0407"}
                ]}""", OpenAlexWork.class);

        assertEquals("UEFISCDI (PN-III-P4-ID-PCE-2020-0407); European Commission", OpenAlexFunding.describe(work));
    }

    @Test
    void fundersAreReadWhenThereAreNoGrants() throws Exception {
        OpenAlexWork work = mapper.readValue("""
                {"id":"https://openalex.org/W1","funders":[{"id":"https://openalex.org/F1","display_name":"UEFISCDI"}]}""",
                OpenAlexWork.class);

        assertEquals("UEFISCDI", OpenAlexFunding.describe(work));
    }

    @Test
    void noFunderNoLine() throws Exception {
        assertNull(OpenAlexFunding.describe(mapper.readValue("{\"id\":\"W1\",\"grants\":[]}", OpenAlexWork.class)));
        assertNull(OpenAlexFunding.describe(null));
    }
}
