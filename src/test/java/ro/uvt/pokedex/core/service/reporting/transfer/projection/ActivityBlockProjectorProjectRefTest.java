package ro.uvt.pokedex.core.service.reporting.transfer.projection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.controller.dto.ScholardexProjectListItemResponse;
import ro.uvt.pokedex.core.service.application.ScholardexProjectReadPort;
import ro.uvt.pokedex.core.service.application.UserIndicatorResultService;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * H64 slice 3a — the project-reference resolution in {@link ActivityBlockProjector#buildActivityInstanceDescription}
 * (exercised via reflection; it's the private seam where a PROJECT_GRANT_ID reference reaches report output).
 */
@ExtendWith(MockitoExtension.class)
class ActivityBlockProjectorProjectRefTest {

    @Mock
    private UserIndicatorResultService userIndicatorResultService;
    @Mock
    private PublicationRowProjector publicationRowProjector;
    @Mock
    private ScholardexProjectReadPort scholardexProjectReadPort;

    private String describe(List<Map<String, Object>> activities, String activityId) throws Exception {
        ActivityBlockProjector projector =
                new ActivityBlockProjector(userIndicatorResultService, publicationRowProjector, scholardexProjectReadPort);
        Method m = ActivityBlockProjector.class.getDeclaredMethod(
                "buildActivityInstanceDescription", Object.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(projector, activities, activityId);
    }

    private static List<Map<String, Object>> oneActivity(Map<String, String> refFields) {
        return List.of(Map.of(
                "id", "ai1",
                "name", "Grant Cercetare",
                "fields", Map.of("Rol", "Director", "Buget", "250000"),
                "referenceFields", refFields));
    }

    @Test
    void resolvesProjectGrantIdToTrustedLabelWithDirector() throws Exception {
        when(scholardexProjectReadPort.findById("sproj_x")).thenReturn(new ScholardexProjectListItemResponse(
                "sproj_x", "PN-III-P2-2.1-PED-2016-0592", null,
                "PV power forecasting toolkit", "UEFISCDI", "Marius Paulescu", 2017, 2018,
                "Universitatea de Vest din Timișoara", null));

        String desc = describe(oneActivity(Map.of("PROJECT_GRANT_ID", "sproj_x")), "ai1");

        assertThat(desc).contains("PN-III-P2-2.1-PED-2016-0592 — PV power forecasting toolkit (UEFISCDI)");
        assertThat(desc).contains("Director: Marius Paulescu");
        assertThat(desc).doesNotContain("sproj_x"); // raw id never surfaces when resolved
    }

    @Test
    void fallsBackToRawValueWhenUnresolved() throws Exception {
        when(scholardexProjectReadPort.findById("sproj_missing")).thenReturn(null);

        String desc = describe(oneActivity(Map.of("PROJECT_GRANT_ID", "sproj_missing")), "ai1");

        assertThat(desc).contains("sproj_missing");
    }

    @Test
    void nonProjectReferenceFieldsPassThroughUnchanged() throws Exception {
        // FORUM_NAME is not a project reference — must not hit the project read port, passes through verbatim.
        lenient().when(scholardexProjectReadPort.findById("anything")).thenReturn(null);

        String desc = describe(oneActivity(Map.of("FORUM_NAME", "Journal of Examples")), "ai1");

        assertThat(desc).contains("Journal of Examples");
    }
}
