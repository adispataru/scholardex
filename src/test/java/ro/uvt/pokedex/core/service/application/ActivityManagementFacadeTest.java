package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.repository.ActivityRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ActivityManagementFacadeTest {

    @Mock
    private ActivityRepository activityRepository;

    @InjectMocks
    private ActivityManagementFacade facade;

    @Test
    void listActivitiesReturnsRepositoryValues() {
        when(activityRepository.findAll()).thenReturn(List.of(new Activity()));
        assertEquals(1, facade.listActivities().size());
    }

    @Test
    void duplicateActivityCreatesCopy() {
        Activity activity = new Activity();
        activity.setId("a1");
        activity.setName("Act");
        Activity saved = new Activity();
        saved.setId("a2");
        when(activityRepository.findById("a1")).thenReturn(Optional.of(activity));
        when(activityRepository.save(any(Activity.class))).thenReturn(saved);

        Optional<Activity> result = facade.duplicateActivity("a1");
        assertTrue(result.isPresent());
        assertEquals("a2", result.get().getId());
    }

    @Test
    void saveAndDeleteActivityDelegateToRepository() {
        Activity activity = new Activity();
        facade.saveActivity(activity);
        facade.deleteActivity("x");
        verify(activityRepository).save(activity);
        verify(activityRepository).deleteById("x");
    }
}
