package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.controller.dto.ScholardexProjectListItemResponse;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.brainmap.ProjectCanonicalizationService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ResearcherProjectServiceTest {

    private final ScholardexProjectReadPort readPort = mock(ScholardexProjectReadPort.class);
    private final ResearcherProjectService service = new ResearcherProjectService(readPort);

    private static User.ResearcherProfile profile(String first, String last) {
        User.ResearcherProfile p = new User.ResearcherProfile();
        p.setFirstName(first);
        p.setLastName(last);
        return p;
    }

    @Test
    void queriesReadPortWithTheResearcherNameSignature() {
        ScholardexProjectListItemResponse hit = new ScholardexProjectListItemResponse(
                "sproj_a", "PN-III", null, "Photovoltaic toolkit", "UEFISCDI", "Marius Paulescu",
                2017, 2018, "UVT", null);
        String expected = ProjectCanonicalizationService.signature("Marius Paulescu");
        when(readPort.findByDirectorSignature(expected)).thenReturn(List.of(hit));

        assertThat(service.myProjects(profile("Marius", "Paulescu")))
                .extracting(ScholardexProjectListItemResponse::id).containsExactly("sproj_a");
        verify(readPort).findByDirectorSignature(expected);
    }

    @Test
    void signatureIsWordOrderInsensitive() {
        // The researcher's stored order ("Paulescu", "Marius") must produce the same signature the projection wrote.
        assertThat(ProjectCanonicalizationService.signature("Paulescu Marius"))
                .isEqualTo(ProjectCanonicalizationService.signature("Marius Paulescu"));
        service.myProjects(profile("Paulescu", "Marius"));
        verify(readPort).findByDirectorSignature(ProjectCanonicalizationService.signature("Marius Paulescu"));
    }

    @Test
    void noProfileOrBlankNameYieldsEmptyWithoutQuerying() {
        assertThat(service.myProjects(null)).isEmpty();
        assertThat(service.myProjects(profile(null, null))).isEmpty();
        assertThat(service.myProjects(profile("  ", "  "))).isEmpty();
        verifyNoInteractions(readPort);
    }

    @Test
    void blankSignatureNeverHitsTheReadPort() {
        service.myProjects(profile("", ""));
        verify(readPort, never()).findByDirectorSignature(any());
    }
}
