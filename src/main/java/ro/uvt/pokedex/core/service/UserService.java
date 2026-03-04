package ro.uvt.pokedex.core.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.repository.UserRepository;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public Optional<User> createUser(User user) {
        if(!userExists(user.getEmail())) {
            return Optional.of(userRepository.save(user));
        }
        return Optional.empty();
    }

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    public Optional<User> getUserByEmail(String email) {
        return userRepository.findById(email);
    }

    public Optional<User> updateUser(String email, User updatedUser) {
        return userRepository.findById(email)
                .map(user -> userRepository.save(updatedUser));
    }

    public void deleteUser(String email) {
        userRepository.deleteById(email);
    }

    public void lockUser(String email) {
        userRepository.findById(email).ifPresent(user -> {
            user.setLocked(true);
            userRepository.save(user);
        });
    }

    public void updateUserRoles(String email, List<String> newRoles) {
        userRepository.findById(email).ifPresent(user -> {
            if(newRoles != null) {
                user.getRoles().removeIf(role -> !newRoles.contains(role.name()));
                user.getRoles().addAll(parseRoles(newRoles));
                userRepository.save(user);
            }
        });
    }

    public Optional<User> createUser(String email, String password, List<String> roles) {
        if(!userExists(email)) {
            User user = new User();
            user.setEmail(email);
            user.setPassword(passwordEncoder.encode(password));
            user.setRoles(parseRoles(roles));
            return Optional.of(userRepository.save(user));
        }
        return Optional.empty();
    }

    public boolean areValidRoleNames(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return false;
        }
        try {
            parseRoles(roles);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public Set<UserRole> parseRoles(List<String> roles) {
        Set<UserRole> resolvedRoles = new HashSet<>();
        for (String roleName : roles) {
            resolvedRoles.add(parseRoleOrThrow(roleName));
        }
        return resolvedRoles;
    }

    public UserRole parseRoleOrThrow(String roleName) {
        return UserRole.valueOf(roleName);
    }

    public boolean userExists(String email){
        return userRepository.findById(email).isPresent();
    }
}
