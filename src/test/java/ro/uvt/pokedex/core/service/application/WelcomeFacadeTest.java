package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.workspace.WorkspacePreferences;
import ro.uvt.pokedex.core.repository.WorkspacePreferencesRepository;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WelcomeFacadeTest {

    @Mock private WorkspacePreferencesRepository workspacePreferencesRepository;
    @Mock private ChangelogService changelogService;

    @InjectMocks private WelcomeFacade facade;

    private static User user(String email, String firstName, String lastName) {
        User user = new User();
        user.setEmail(email);
        if (firstName != null || lastName != null) {
            User.ResearcherProfile profile = new User.ResearcherProfile();
            profile.setFirstName(firstName);
            profile.setLastName(lastName);
            user.setResearcherProfile(profile);
        }
        return user;
    }

    @Test
    void greetsByFirstNameAndReportsUpdatesSinceTheLastWorkspaceVisit() {
        Instant lastVisit = Instant.parse("2026-07-20T10:00:00Z");
        WorkspacePreferences prefs = new WorkspacePreferences();
        prefs.setUserEmail("florin.fortis@e-uvt.ro");
        prefs.setLastVisitAt(lastVisit);
        when(workspacePreferencesRepository.findById("florin.fortis@e-uvt.ro")).thenReturn(Optional.of(prefs));
        when(changelogService.newSince(eq(lastVisit), eq(false))).thenReturn(4L);

        WelcomeFacade.Welcome welcome = facade.forUser(user("florin.fortis@e-uvt.ro", "Florin", "Fortis"), false);

        assertThat(welcome.displayName()).isEqualTo("Florin");
        assertThat(welcome.email()).isEqualTo("florin.fortis@e-uvt.ro");
        assertThat(welcome.newUpdates()).isEqualTo(4L);
        assertThat(welcome.returning()).isTrue();
    }

    @Test
    void aFirstTimeVisitorHasNoLastVisitAndNoUpdateCount() {
        when(workspacePreferencesRepository.findById(any())).thenReturn(Optional.empty());
        when(changelogService.newSince(eq(null), anyBoolean())).thenReturn(0L);

        WelcomeFacade.Welcome welcome = facade.forUser(user("nou@e-uvt.ro", null, null), false);

        assertThat(welcome.returning()).isFalse();
        assertThat(welcome.newUpdates()).isZero();
    }

    @Test
    void fallsBackToLastNameThenEmailLocalPartSoTheGreetingIsNeverEmpty() {
        lenient().when(workspacePreferencesRepository.findById(any())).thenReturn(Optional.empty());
        lenient().when(changelogService.newSince(any(), anyBoolean())).thenReturn(0L);

        assertThat(facade.forUser(user("a@e-uvt.ro", "  ", "Fortis"), false).displayName()).isEqualTo("Fortis");
        assertThat(facade.forUser(user("adrian.spataru@e-uvt.ro", null, null), false).displayName())
                .isEqualTo("adrian.spataru");
        assertThat(facade.forUser(user("", null, null), false).displayName()).isEqualTo("cercetător");
    }

    @Test
    void adminsGetTheAdminVisibleUpdateCount() {
        when(workspacePreferencesRepository.findById(any())).thenReturn(Optional.empty());
        when(changelogService.newSince(any(), eq(true))).thenReturn(9L);

        assertThat(facade.forUser(user("admin@e-uvt.ro", "Adi", null), true).newUpdates()).isEqualTo(9L);
    }

    @Test
    void anonymousVisitorGetsNoWelcome() {
        assertThat(facade.forUser(null, false)).isNull();
    }
}
