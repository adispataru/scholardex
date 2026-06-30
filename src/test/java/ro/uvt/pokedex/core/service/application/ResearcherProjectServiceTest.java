package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import ro.uvt.pokedex.core.controller.dto.ScholardexProjectListItemResponse;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.repository.ActivityRepository;
import ro.uvt.pokedex.core.service.brainmap.ProjectCanonicalizationService;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearcherProjectServiceTest {

    private final ScholardexProjectReadPort readPort = mock(ScholardexProjectReadPort.class);
    private final ActivityRepository activityRepository = mock(ActivityRepository.class);
    private final ActivityInstanceRepository instanceRepository = mock(ActivityInstanceRepository.class);
    private final ResearcherProjectService service =
            new ResearcherProjectService(readPort, activityRepository, instanceRepository);

    ResearcherProjectServiceTest() {
        ReflectionTestUtils.setField(service, "importActivityName", "Grant Cercetare");
    }

    private static User.ResearcherProfile profile(String first, String last) {
        User.ResearcherProfile p = new User.ResearcherProfile();
        p.setFirstName(first);
        p.setLastName(last);
        return p;
    }

    private static ScholardexProjectListItemResponse project(String id, String title, String funder, Long budget,
                                                             Integer startYear) {
        return new ScholardexProjectListItemResponse(id, "PN-III", null, title, funder, "Marius Paulescu",
                startYear, 2024, "UVT", budget);
    }

    private static Activity grantCercetare() {
        Activity a = new Activity();
        a.setName("Grant Cercetare");
        Activity.Field rol = new Activity.Field();
        rol.name = "Rol";
        rol.allowedValues = List.of("Director (proiect internațional)", "Coordonator local (proiect internațional)",
                "Director (proiect național)", "Membru");
        Activity.Field buget = new Activity.Field();
        buget.name = "Buget";
        buget.number = true;
        Activity.Field nume = new Activity.Field();
        nume.name = "Nume Proiect";
        a.setFields(List.of(rol, buget, nume));
        a.setReferenceFields(List.of(Activity.ReferenceField.PROJECT_GRANT_ID));
        return a;
    }

    // ── myProjects (slice 1) ──────────────────────────────────────────────────

    @Test
    void queriesReadPortWithTheResearcherNameSignature() {
        String expected = ProjectCanonicalizationService.signature("Marius Paulescu");
        when(readPort.findByDirectorSignature(expected))
                .thenReturn(List.of(project("sproj_a", "Photovoltaic toolkit", "UEFISCDI", null, 2017)));
        assertThat(service.myProjects(profile("Marius", "Paulescu")))
                .extracting(ScholardexProjectListItemResponse::id).containsExactly("sproj_a");
        verify(readPort).findByDirectorSignature(expected);
    }

    @Test
    void signatureIsWordOrderInsensitive() {
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
        verify(readPort, never()).findByDirectorSignature(any());
    }

    // ── importProject (slice 2) ───────────────────────────────────────────────

    @Test
    void importPreFillsFieldsReferenceAndInfersNationalDirectorRole() {
        when(readPort.findById("sproj_a"))
                .thenReturn(project("sproj_a", "Photovoltaic toolkit", "UEFISCDI", 250000L, 2017));
        when(instanceRepository.findAllByResearcherId("a@uvt.ro")).thenReturn(List.of());
        when(activityRepository.findByName("Grant Cercetare")).thenReturn(List.of(grantCercetare()));
        when(instanceRepository.save(any())).thenAnswer(inv -> {
            ActivityInstance i = inv.getArgument(0);
            i.setId("inst_1");
            return i;
        });

        ResearcherProjectService.ImportResult r = service.importProject("a@uvt.ro", "sproj_a");

        assertThat(r.status()).isEqualTo("CREATED");
        assertThat(r.instanceId()).isEqualTo("inst_1");

        org.mockito.ArgumentCaptor<ActivityInstance> cap = org.mockito.ArgumentCaptor.forClass(ActivityInstance.class);
        verify(instanceRepository).save(cap.capture());
        ActivityInstance saved = cap.getValue();
        assertThat(saved.getResearcherId()).isEqualTo("a@uvt.ro");
        assertThat(saved.getName()).isEqualTo("Photovoltaic toolkit");
        assertThat(saved.getFields()).containsEntry("Nume Proiect", "Photovoltaic toolkit")
                .containsEntry("Buget", "250000")
                .containsEntry("Rol", "Director (proiect național)"); // UEFISCDI → national
        assertThat(saved.getReferenceFields())
                .containsEntry(Activity.ReferenceField.PROJECT_GRANT_ID, "sproj_a");
    }

    @Test
    void importInfersInternationalDirectorRoleForEcFunder() {
        when(readPort.findById("sproj_b")).thenReturn(project("sproj_b", "U Night", "EC", null, 2022));
        when(instanceRepository.findAllByResearcherId("a@uvt.ro")).thenReturn(List.of());
        when(activityRepository.findByName("Grant Cercetare")).thenReturn(List.of(grantCercetare()));
        when(instanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.importProject("a@uvt.ro", "sproj_b");

        org.mockito.ArgumentCaptor<ActivityInstance> cap = org.mockito.ArgumentCaptor.forClass(ActivityInstance.class);
        verify(instanceRepository).save(cap.capture());
        // EC → international; no budget → no Buget field
        assertThat(cap.getValue().getFields()).containsEntry("Rol", "Director (proiect internațional)")
                .doesNotContainKey("Buget");
    }

    @Test
    void importAsParticipantSetsMembruRole() {
        when(readPort.findById("sproj_b")).thenReturn(project("sproj_b", "U Night", "EC", null, 2022));
        when(instanceRepository.findAllByResearcherId("a@uvt.ro")).thenReturn(List.of());
        when(activityRepository.findByName("Grant Cercetare")).thenReturn(List.of(grantCercetare()));
        when(instanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.importProject("a@uvt.ro", "sproj_b", true); // asParticipant

        org.mockito.ArgumentCaptor<ActivityInstance> cap = org.mockito.ArgumentCaptor.forClass(ActivityInstance.class);
        verify(instanceRepository).save(cap.capture());
        // participant → Membru, NOT a director role (despite the EC funder)
        assertThat(cap.getValue().getFields()).containsEntry("Rol", "Membru");
    }

    @Test
    void importIsIdempotentOnProjectGrantId() {
        ActivityInstance existing = new ActivityInstance();
        existing.setId("inst_existing");
        existing.setReferenceFields(java.util.Map.of(Activity.ReferenceField.PROJECT_GRANT_ID, "sproj_a"));
        when(readPort.findById("sproj_a")).thenReturn(project("sproj_a", "Photovoltaic toolkit", "UEFISCDI", 1L, 2017));
        when(instanceRepository.findAllByResearcherId("a@uvt.ro")).thenReturn(List.of(existing));

        ResearcherProjectService.ImportResult r = service.importProject("a@uvt.ro", "sproj_a");

        assertThat(r.status()).isEqualTo("EXISTS");
        assertThat(r.instanceId()).isEqualTo("inst_existing");
        verify(instanceRepository, never()).save(any());
    }

    @Test
    void importReportsMissingProjectAndUnconfiguredActivity() {
        when(readPort.findById("nope")).thenReturn(null);
        assertThat(service.importProject("a@uvt.ro", "nope").status()).isEqualTo("PROJECT_NOT_FOUND");

        when(readPort.findById("sproj_a")).thenReturn(project("sproj_a", "T", "UEFISCDI", null, 2017));
        when(instanceRepository.findAllByResearcherId("a@uvt.ro")).thenReturn(List.of());
        when(activityRepository.findByName("Grant Cercetare")).thenReturn(List.of());
        assertThat(service.importProject("a@uvt.ro", "sproj_a").status()).isEqualTo("ACTIVITY_NOT_CONFIGURED");
        verify(instanceRepository, never()).save(any());
    }

    // ── linkProject (slice 3) ─────────────────────────────────────────────────

    private ActivityInstance freeText(String id, String owner, Map<String, String> fields) {
        ActivityInstance i = new ActivityInstance();
        i.setId(id);
        i.setResearcherId(owner);
        i.setActivity(grantCercetare());
        i.setFields(new java.util.LinkedHashMap<>(fields));
        return i;
    }

    @Test
    void linkSetsReferenceAndBackfillsOnlyBlankFields() {
        // existing instance already has a user-entered Nume Proiect (must be preserved); Buget is blank (back-filled).
        ActivityInstance inst = freeText("inst_1", "a@uvt.ro", Map.of("Nume Proiect", "My own title"));
        when(readPort.findById("sproj_a")).thenReturn(project("sproj_a", "Canonical title", "UEFISCDI", 250000L, 2017));
        when(instanceRepository.findById("inst_1")).thenReturn(java.util.Optional.of(inst));
        when(instanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ResearcherProjectService.ImportResult r = service.linkProject("a@uvt.ro", "inst_1", "sproj_a");

        assertThat(r.status()).isEqualTo("LINKED");
        assertThat(inst.getReferenceFields()).containsEntry(Activity.ReferenceField.PROJECT_GRANT_ID, "sproj_a");
        assertThat(inst.getFields()).containsEntry("Nume Proiect", "My own title") // preserved, NOT overwritten
                .containsEntry("Buget", "250000");                                 // blank → back-filled
    }

    @Test
    void linkRejectsInstanceNotOwnedOrMissingOrMissingProject() {
        when(readPort.findById("sproj_a")).thenReturn(project("sproj_a", "T", "UEFISCDI", null, 2017));
        // owned by someone else
        when(instanceRepository.findById("inst_other"))
                .thenReturn(java.util.Optional.of(freeText("inst_other", "other@uvt.ro", Map.of())));
        assertThat(service.linkProject("a@uvt.ro", "inst_other", "sproj_a").status()).isEqualTo("INSTANCE_NOT_FOUND");
        // missing instance
        when(instanceRepository.findById("gone")).thenReturn(java.util.Optional.empty());
        assertThat(service.linkProject("a@uvt.ro", "gone", "sproj_a").status()).isEqualTo("INSTANCE_NOT_FOUND");
        // missing project
        when(readPort.findById("nope")).thenReturn(null);
        assertThat(service.linkProject("a@uvt.ro", "inst_1", "nope").status()).isEqualTo("PROJECT_NOT_FOUND");
        verify(instanceRepository, never()).save(any());
    }
}
