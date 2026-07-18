package ro.uvt.pokedex.core.service.importing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.repository.UserRepository;

import java.util.Arrays;

@Service
public class AdminUserService {

    private static final Logger logger = LoggerFactory.getLogger(AdminUserService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminEmail;

    public AdminUserService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${admin.email}") String adminEmail
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = adminEmail;
    }

    /**
     * Seeds the bootstrap admin IDENTITY (email + roles) into an empty user collection. OIDC-only
     * auth (H84): there is no local password login, so the password is a scrambled random — the
     * admin signs in through Keycloak with this email (or the realm-local break-glass user).
     */
    public void createDefaultAdminUser() {
        if (userRepository.count() == 0) {
            logger.info("No users found. Seeding bootstrap admin identity {} (OIDC-only, no local password).", adminEmail);
            User admin = new User();
            admin.setEmail(adminEmail);
            admin.setPassword(passwordEncoder.encode(java.util.UUID.randomUUID().toString()));
            admin.getRoles().addAll(Arrays.asList(UserRole.PLATFORM_ADMIN, UserRole.RESEARCHER, UserRole.SUPERVISOR));
            userRepository.save(admin);
        }
    }
}
