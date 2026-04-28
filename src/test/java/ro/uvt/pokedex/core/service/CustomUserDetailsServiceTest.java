package ro.uvt.pokedex.core.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.UserRepository;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    @Test
    void loadUserByUsernameReturnsRepositoryUser() {
        User user = new User();
        user.setEmail("researcher@uvt.ro");
        CustomUserDetailsService service = new CustomUserDetailsService(userRepository);
        when(userRepository.findById("researcher@uvt.ro")).thenReturn(Optional.of(user));

        assertSame(user, service.loadUserByUsername("researcher@uvt.ro"));
    }

    @Test
    void loadUserByUsernameThrowsWhenUserIsMissing() {
        CustomUserDetailsService service = new CustomUserDetailsService(userRepository);
        when(userRepository.findById("missing@uvt.ro")).thenReturn(Optional.empty());

        UsernameNotFoundException exception = assertThrows(
                UsernameNotFoundException.class,
                () -> service.loadUserByUsername("missing@uvt.ro")
        );

        assertTrue(exception.getMessage().contains("missing@uvt.ro"));
    }
}
