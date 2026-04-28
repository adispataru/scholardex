package ro.uvt.pokedex.core.service.importing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.repository.UserRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;

    private AdminUserService service(String email, String password) {
        return new AdminUserService(userRepository, passwordEncoder, email, password);
    }

    @Test
    void createDefaultAdminUser_repoNotEmpty_skipsCreation() {
        when(userRepository.count()).thenReturn(1L);
        service("admin@test.com", "secret").createDefaultAdminUser();
        verify(userRepository, never()).save(any());
    }

    @Test
    void createDefaultAdminUser_repoEmpty_savesAdminWithCorrectEmail() {
        when(userRepository.count()).thenReturn(0L);
        when(passwordEncoder.encode("secret")).thenReturn("hashed");

        service("admin@test.com", "secret").createDefaultAdminUser();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals("admin@test.com", captor.getValue().getEmail());
        assertEquals("hashed", captor.getValue().getPassword());
    }

    @Test
    void createDefaultAdminUser_repoEmpty_grantsPlatformAdminAndResearcherAndSupervisor() {
        when(userRepository.count()).thenReturn(0L);
        when(passwordEncoder.encode(any())).thenReturn("hashed");

        service("admin@test.com", "pw").createDefaultAdminUser();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertTrue(captor.getValue().getRoles().contains(UserRole.PLATFORM_ADMIN));
        assertTrue(captor.getValue().getRoles().contains(UserRole.RESEARCHER));
        assertTrue(captor.getValue().getRoles().contains(UserRole.SUPERVISOR));
    }
}
