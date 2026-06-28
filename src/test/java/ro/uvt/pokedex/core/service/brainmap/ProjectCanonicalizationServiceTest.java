package ro.uvt.pokedex.core.service.brainmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.BrainmapProjectFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexProjectFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.BrainmapProjectFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexProjectFactRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectCanonicalizationServiceTest {

    @Mock
    private BrainmapProjectFactRepository brainmapProjectFactRepository;
    @Mock
    private ScholardexAffiliationFactRepository affiliationFactRepository;
    @Mock
    private ScholardexProjectFactRepository projectFactRepository;
    @Mock
    private ro.uvt.pokedex.core.repository.scopus.canonical.UserDefinedProjectFactRepository userDefinedProjectFactRepository;

    @org.junit.jupiter.api.BeforeEach
    void noUserDefinedByDefault() {
        org.mockito.Mockito.lenient().when(userDefinedProjectFactRepository.findAll()).thenReturn(java.util.List.of());
    }

    private ProjectCanonicalizationService service() {
        return new ProjectCanonicalizationService(
                brainmapProjectFactRepository, affiliationFactRepository, projectFactRepository,
                userDefinedProjectFactRepository);
    }

    private static BrainmapProjectFact source(String id, String code, String funder, String coordinator) {
        BrainmapProjectFact f = new BrainmapProjectFact();
        f.setId(id);
        f.setCode(code);
        f.setFunder(funder);
        f.setCoordinator(coordinator);
        f.setTitle("Title " + id);
        f.setDirectorLast("Director" + id);
        f.setStartYear("2017");
        f.setEndYear("2018");
        return f;
    }

    private static ScholardexAffiliationFact uvt() {
        ScholardexAffiliationFact a = new ScholardexAffiliationFact();
        a.setId("saff_uvt");
        a.setName("West University of Timișoara");
        a.getAliases().add("Universitatea de Vest din Timișoara");
        a.getAliases().add("UVT");
        a.setCountry("Romania");
        a.setCity("Timișoara");
        return a;
    }

    // ── static helpers ──────────────────────────────────────────────────────

    @Test
    void deriveEuGrantIdOnlyForEcFunderTrailingSegment() {
        assertThat(ProjectCanonicalizationService.deriveEuGrantId("Horizon-239038-101061610", "EC"))
                .isEqualTo("101061610");
        assertThat(ProjectCanonicalizationService.deriveEuGrantId("FP7-86416-211338", "EC")).isEqualTo("211338");
        // RO codes also end in digits but must NOT be treated as EU grant ids
        assertThat(ProjectCanonicalizationService.deriveEuGrantId("PN-III-P2-2.1-PED-2016-0592", "UEFISCDI")).isNull();
    }

    @Test
    void signatureIsArticleAndWordOrderInsensitive() {
        // brainmap "Universitatea de Vest Timisoara" and OpenAlex "Universitatea de Vest din Timișoara" → same key
        String fromBrainmap = ProjectCanonicalizationService.signature(
                ProjectCanonicalizationService.stripLocationSuffix(
                        "UNIVERSITATEA DE VEST TIMISOARA (JUDEŢUL TIMIŞ  - TIMISOARA)"));
        String fromAlias = ProjectCanonicalizationService.signature("Universitatea de Vest din Timișoara");
        assertThat(fromBrainmap).isEqualTo(fromAlias);
    }

    // ── rebuild ──────────────────────────────────────────────────────────────

    @Test
    void rebuildBuildsCanonicalProjectsResolvesCoordinatorAndDerivesEuGrantId() {
        when(brainmapProjectFactRepository.findAll()).thenReturn(List.of(
                source("8", "Horizon-239038-101061610", "EC",
                        "UNIVERSITATEA DE VEST TIMISOARA (JUDEŢUL TIMIŞ  - TIMISOARA)"),
                source("9", "PN-III-P2-2.1-PED-2016-0592", "UEFISCDI",
                        "UNIVERSITATEA DE VEST TIMISOARA (JUDEŢUL TIMIŞ  - TIMISOARA)")));
        when(affiliationFactRepository.findAll()).thenReturn(List.of(uvt()));

        ProjectCanonicalizationService.ProjectCanonResult result = service().rebuild("batch", "corr");

        verify(projectFactRepository).deleteAll();
        ArgumentCaptor<List<ScholardexProjectFact>> captor = ArgumentCaptor.captor();
        verify(projectFactRepository).saveAll(captor.capture());
        List<ScholardexProjectFact> saved = captor.getValue();

        assertThat(result.sourceFacts()).isEqualTo(2);
        assertThat(result.canonicalProjects()).isEqualTo(2);
        assertThat(result.coordinatorsResolved()).isEqualTo(2);

        ScholardexProjectFact ec = saved.stream().filter(p -> "EC".equals(p.getFunder())).findFirst().orElseThrow();
        assertThat(ec.getEuGrantId()).isEqualTo("101061610");
        assertThat(ec.getId()).isEqualTo("sproj_"
                + ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport.shortHash("eu:101061610"));
        assertThat(ec.getCoordinatorAffiliationId()).isEqualTo("saff_uvt");
        assertThat(ec.getBudget()).isNull();
        assertThat(ec.getStartYear()).isEqualTo(2017);
        assertThat(ec.getBrainmapProjectIds()).containsExactly("8");

        ScholardexProjectFact roProj = saved.stream().filter(p -> "UEFISCDI".equals(p.getFunder())).findFirst().orElseThrow();
        assertThat(roProj.getEuGrantId()).isNull();
        assertThat(roProj.getId()).isEqualTo("sproj_"
                + ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport.shortHash(
                        "code:PN-III-P2-2.1-PED-2016-0592"));
        assertThat(roProj.getCoordinatorAffiliationId()).isEqualTo("saff_uvt");
    }

    @Test
    void rebuildLeavesCoordinatorUnresolvedWhenNoAffiliationMatches() {
        when(brainmapProjectFactRepository.findAll()).thenReturn(List.of(
                source("1", "PN-X", "UEFISCDI", "SOME OTHER INSTITUTE (JUDEŢUL CLUJ - CLUJ-NAPOCA)")));
        when(affiliationFactRepository.findAll()).thenReturn(List.of(uvt()));

        service().rebuild("b", "c");

        ArgumentCaptor<List<ScholardexProjectFact>> captor = ArgumentCaptor.captor();
        verify(projectFactRepository).saveAll(captor.capture());
        assertThat(captor.getValue().get(0).getCoordinatorAffiliationId()).isNull();
    }

    @Test
    void rebuildWithNoSourcesWipesAndReturnsZero() {
        when(brainmapProjectFactRepository.findAll()).thenReturn(List.of());
        ProjectCanonicalizationService.ProjectCanonResult result = service().rebuild("b", "c");
        verify(projectFactRepository).deleteAll();
        assertThat(result.canonicalProjects()).isZero();
    }

    // ── H64 slice 4b: user-defined (admin/CORDIS) merge ──────────────────────────

    private static ro.uvt.pokedex.core.model.scopus.canonical.UserDefinedProjectFact userDefined(
            String id, String euGrantId, String code, Long budget, String origin) {
        var u = new ro.uvt.pokedex.core.model.scopus.canonical.UserDefinedProjectFact();
        u.setId(id);
        u.setEuGrantId(euGrantId);
        u.setCode(code);
        u.setBudget(budget);
        u.setOrigin(origin);
        return u;
    }

    @Test
    void userDefinedBudgetOverlaysMatchingBrainmapProjectByEuGrantId() {
        // brainmap EC project (euGrantId derived from code) + a CORDIS user-defined fact with the same grant id
        when(brainmapProjectFactRepository.findAll()).thenReturn(List.of(
                source("8", "Horizon-239038-101061610", "EC",
                        "UNIVERSITATEA DE VEST TIMISOARA (JUDEŢUL TIMIŞ  - TIMISOARA)")));
        when(affiliationFactRepository.findAll()).thenReturn(List.of(uvt()));
        when(userDefinedProjectFactRepository.findAll()).thenReturn(List.of(
                userDefined("ud1", "101061610", null, 270000L, "CORDIS")));

        ProjectCanonicalizationService.ProjectCanonResult result = service().rebuild("b", "c");

        ArgumentCaptor<List<ScholardexProjectFact>> captor = ArgumentCaptor.captor();
        verify(projectFactRepository).saveAll(captor.capture());
        // one canonical project — the user-defined budget overlays the brainmap record (same euGrantId)
        assertThat(captor.getValue()).hasSize(1);
        ScholardexProjectFact p = captor.getValue().get(0);
        assertThat(p.getBudget()).isEqualTo(270000L);
        assertThat(p.getBudgetSource()).isEqualTo("CORDIS");
        assertThat(p.getCoordinatorAffiliationId()).isEqualTo("saff_uvt"); // brainmap identity preserved
        assertThat(p.getBrainmapProjectIds()).containsExactly("8");
        assertThat(p.getUserDefinedProjectIds()).containsExactly("ud1");
        assertThat(result.canonicalProjects()).isEqualTo(1);
    }

    @Test
    void userDefinedOnlyProjectIsAddedWhenNoBrainmapMatch() {
        when(brainmapProjectFactRepository.findAll()).thenReturn(List.of());
        when(affiliationFactRepository.findAll()).thenReturn(List.of());
        when(userDefinedProjectFactRepository.findAll()).thenReturn(List.of(
                userDefined("ud2", "101017168", null, 4343180L, "CORDIS")));

        service().rebuild("b", "c");

        ArgumentCaptor<List<ScholardexProjectFact>> captor = ArgumentCaptor.captor();
        verify(projectFactRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        ScholardexProjectFact p = captor.getValue().get(0);
        assertThat(p.getEuGrantId()).isEqualTo("101017168");
        assertThat(p.getBudget()).isEqualTo(4343180L);
        assertThat(p.getBrainmapProjectIds()).isEmpty();
        assertThat(p.getUserDefinedProjectIds()).containsExactly("ud2");
    }
}
