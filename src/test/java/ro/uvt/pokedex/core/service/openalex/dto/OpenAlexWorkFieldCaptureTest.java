package ro.uvt.pokedex.core.service.openalex.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Locks the Jackson binding of the OpenAlex work fields we harvest for the full re-ingest (a 90-min op):
 * venue source.type + publisher, is_retracted, biblio, fwci, citation_normalized_percentile, primary_topic.
 * The JSON mirrors the shape in data/openalex/uvt_works.jsonl.
 */
class OpenAlexWorkFieldCaptureTest {

    private static final String WORK_JSON = """
        {
          "id": "https://openalex.org/W3030759290",
          "doi": "https://doi.org/10.1007/978-3-319-99999-1_7",
          "title": "A Sample Work",
          "type": "article",
          "is_retracted": false,
          "fwci": 244.6159,
          "citation_normalized_percentile": {"value": 0.999, "is_in_top_1_percent": true},
          "biblio": {"volume": "114", "issue": "3", "first_page": "24", "last_page": "31"},
          "primary_topic": {"id": "https://openalex.org/T10921", "display_name": "Remote-Sensing Image Classification"},
          "primary_location": {
            "source": {
              "id": "https://openalex.org/S123",
              "display_name": "Lecture Notes in Computer Science",
              "issn_l": "0302-9743",
              "issn": ["0302-9743", "1611-3349"],
              "type": "book series",
              "host_organization_name": "Springer Nature"
            }
          }
        }
        """;

    @Test
    void capturesVenueAndWorkLevelFields() throws Exception {
        OpenAlexWorksResponse.OpenAlexWork work =
                new ObjectMapper().readValue(WORK_JSON, OpenAlexWorksResponse.OpenAlexWork.class);

        OpenAlexWorksResponse.OpenAlexSource src = work.getPrimary_location().getSource();
        assertEquals("book series", src.getType());
        assertEquals("Springer Nature", src.getHost_organization_name());

        assertFalse(work.getIs_retracted());
        assertEquals(244.6159, work.getFwci());
        assertNotNull(work.getCitation_normalized_percentile());
        assertEquals(0.999, work.getCitation_normalized_percentile().getValue());
        assertEquals("114", work.getBiblio().getVolume());
        assertEquals("24", work.getBiblio().getFirst_page());
        assertEquals("31", work.getBiblio().getLast_page());
        assertEquals("Remote-Sensing Image Classification", work.getPrimary_topic().getDisplay_name());
    }
}
