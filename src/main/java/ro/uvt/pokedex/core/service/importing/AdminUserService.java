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
    private final String adminPassword;

    public AdminUserService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${admin.email}") String adminEmail,
            @Value("${admin.password}") String adminPassword
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    public void createDefaultAdminUser() {
        if (userRepository.count() == 0) {
            logger.info("No users found. Creating default admin user.");
            User admin = new User();
            admin.setEmail(adminEmail);
            admin.setPassword(passwordEncoder.encode(adminPassword));
            admin.getRoles().addAll(Arrays.asList(UserRole.PLATFORM_ADMIN, UserRole.RESEARCHER, UserRole.SUPERVISOR));
            userRepository.save(admin);
        }
    }
}
