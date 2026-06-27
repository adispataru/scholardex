package ro.uvt.pokedex.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.repository.ActivityRepository;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectReferenceFieldSeedRunnerTest {

    @Mock
    private ActivityRepository activityRepository;

    private ProjectReferenceFieldSeedRunner runner(String names) {
        ProjectReferenceFieldSeedRunner r = new ProjectReferenceFieldSeedRunner(activityRepository);
        ReflectionTestUtils.setField(r, "referenceActivityNames", names);
        return r;
    }

    private static Activity activity(String id, String name, List<Activity.ReferenceField> refs) {
        Activity a = new Activity();
        a.setId(id);
        a.setName(name);
        a.setReferenceFields(refs);
        return a;
    }

    @Test
    void blankConfigIsNoOp() {
        runner("").run();
        runner(null).run();
        verifyNoInteractions(activityRepository);
    }

    @Test
    void addsProjectGrantIdWhenAbsent() {
        when(activityRepository.findByName("Grant Cercetare"))
                .thenReturn(List.of(activity("a1", "Grant Cercetare", null)));

        runner("Grant Cercetare").run();

        ArgumentCaptor<Activity> captor = ArgumentCaptor.forClass(Activity.class);
        verify(activityRepository).save(captor.capture());
        assertThat(captor.getValue().getReferenceFields()).contains(Activity.ReferenceField.PROJECT_GRANT_ID);
    }

    @Test
    void idempotentWhenAlreadyPresent() {
        when(activityRepository.findByName("Granturi"))
                .thenReturn(List.of(activity("a2", "Granturi",
                        new ArrayList<>(List.of(Activity.ReferenceField.PROJECT_GRANT_ID)))));

        runner("Granturi").run();

        verify(activityRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void preservesExistingReferenceFields() {
        when(activityRepository.findByName("Grant Cercetare"))
                .thenReturn(List.of(activity("a1", "Grant Cercetare",
                        new ArrayList<>(List.of(Activity.ReferenceField.UNIVERSITY_NAME)))));

        runner(" Grant Cercetare , , ").run();

        ArgumentCaptor<Activity> captor = ArgumentCaptor.forClass(Activity.class);
        verify(activityRepository).save(captor.capture());
        assertThat(captor.getValue().getReferenceFields())
                .containsExactly(Activity.ReferenceField.UNIVERSITY_NAME, Activity.ReferenceField.PROJECT_GRANT_ID);
    }

    @Test
    void missingActivityNameIsSkippedNotFailed() {
        when(activityRepository.findByName("Nonexistent")).thenReturn(List.of());
        runner("Nonexistent").run();
        verify(activityRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
