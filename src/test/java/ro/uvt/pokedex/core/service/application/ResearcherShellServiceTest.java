package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.service.UserService;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResearcherShellServiceTest {

    @Mock private UserService userService;
    @Mock private PasswordEncoder passwordEncoder;

    private ResearcherShellService service;

    @BeforeEach
    void setUp() {
        service = new ResearcherShellService(userService, passwordEncoder);
        ReflectionTestUtils.setField(service, "allowedDomainsCsv", "e-uvt.ro, uvt.ro");
        lenient().when(passwordEncoder.encode(any())).thenReturn("bcrypt-hash");
    }

    @Test
    void createsAPasswordlessResearcherShellForAnAllowedDomain() {
        when(userService.createUser(any(User.class))).thenAnswer(inv -> Optional.of(inv.getArgument(0)));

        assertEquals(ResearcherShellService.Result.CREATED, service.createShell("new.person@e-uvt.ro"));

        org.mockito.ArgumentCaptor<User> saved = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userService).createUser(saved.capture());
        User shell = saved.getValue();
        assertEquals("new.person@e-uvt.ro", shell.getEmail());
        assertTrue(shell.getRoles().contains(UserRole.RESEARCHER));
        assertEquals(1, shell.getRoles().size(), "shell gets RESEARCHER only, no elevated roles");
        assertEquals("bcrypt-hash", shell.getPassword(), "a random bcrypt blocks form login");
    }

    @Test
    void normalizesCaseAndTrimsBeforeChecks() {
        when(userService.createUser(any(User.class))).thenAnswer(inv -> Optional.of(inv.getArgument(0)));

        assertEquals(ResearcherShellService.Result.CREATED, service.createShell("  Ana.Pop@E-UVT.RO  "));

        org.mockito.ArgumentCaptor<User> saved = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userService).createUser(saved.capture());
        assertEquals("ana.pop@e-uvt.ro", saved.getValue().getEmail());
    }

    @Test
    void rejectsANonInstitutionalDomainWithoutCreatingAnything() {
        assertEquals(ResearcherShellService.Result.DOMAIN_NOT_ALLOWED, service.createShell("someone@gmail.com"));
        verify(userService, never()).createUser(any(User.class));
    }

    @Test
    void rejectsAMalformedEmail() {
        assertEquals(ResearcherShellService.Result.INVALID_EMAIL, service.createShell("not-an-email"));
        verify(userService, never()).createUser(any(User.class));
    }

    @Test
    void rejectsOverlongAndRedosProbeInputsQuickly() {
        // RFC 5321 cap: anything over 254 chars is invalid before the regex even runs.
        assertEquals(ResearcherShellService.Result.INVALID_EMAIL,
                service.createShell("a".repeat(250) + "@e-uvt.ro"));
        // The CodeQL polynomial-redos probe shape ('!@!.' + many '!.') — must return, and reject, fast.
        long start = System.nanoTime();
        assertEquals(ResearcherShellService.Result.INVALID_EMAIL,
                service.createShell("!@!." + "!.".repeat(120)));
        org.junit.jupiter.api.Assertions.assertTrue((System.nanoTime() - start) < 1_000_000_000L);
        // Consecutive-dot domains are malformed and now rejected by the unambiguous pattern.
        assertEquals(ResearcherShellService.Result.INVALID_EMAIL, service.createShell("x@a..b"));
        verify(userService, never()).createUser(any(User.class));
    }

    @Test
    void reportsAlreadyExistsWhenTheAccountIsPresent() {
        // createUser returns empty when the email already exists — never overwrites.
        when(userService.createUser(any(User.class))).thenReturn(Optional.empty());

        assertEquals(ResearcherShellService.Result.ALREADY_EXISTS, service.createShell("existing@e-uvt.ro"));
    }
}
