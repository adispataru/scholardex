package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.repository.ActivityIndicatorRepository;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.repository.ActivityRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserActivityInstanceFacadeTest {

    @Mock
    private ActivityInstanceRepository activityInstanceRepository;
    @Mock
    private ActivityRepository activityRepository;
    @Mock
    private ActivityIndicatorRepository activityIndicatorRepository;

    @InjectMocks
    private UserActivityInstanceFacade facade;

    @Test
    void buildViewPopulatesListsAndMetrics() {
        Activity activity = new Activity();
        activity.setId("a1");
        activity.setName("Teaching");
        ActivityInstance instance = new ActivityInstance();
        instance.setId("i1");
        instance.setActivity(activity);
        instance.setResearcherId("r1");

        when(activityRepository.findAll()).thenReturn(List.of(activity));
        when(activityInstanceRepository.findAllByResearcherId("r1")).thenReturn(List.of(instance));

        var vm = facade.buildActivityInstancesView("r1");
        assertEquals(1, vm.activities().size());
        assertEquals(1, vm.activityInstances().size());
        assertEquals(1, vm.activityLabels().size());
    }

    @Test
    void updateActivityInstanceUpdatesFieldsOnly() {
        ActivityInstance existing = new ActivityInstance();
        existing.setId("i1");
        ActivityInstance incoming = new ActivityInstance();
        incoming.setId("i1");
        incoming.setFields(Map.of());
        incoming.setReferenceFields(Map.of());
        when(activityInstanceRepository.findById("i1")).thenReturn(Optional.of(existing));

        facade.updateActivityInstance(incoming);

        verify(activityInstanceRepository).save(existing);
    }

    @Test
    void findAndDeleteDelegateToRepository() {
        when(activityInstanceRepository.findById("i1")).thenReturn(Optional.of(new ActivityInstance()));
        assertTrue(facade.findActivityInstance("i1").isPresent());
        facade.deleteActivityInstance("i1");
        verify(activityInstanceRepository).deleteById("i1");
    }
}
