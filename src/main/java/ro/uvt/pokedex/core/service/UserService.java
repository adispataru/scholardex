package ro.uvt.pokedex.core.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.repository.UserRepository;

import java.util.List;
import java.util.Optional;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public User createUser(User user) {
        if(!userExists(user.getEmail())) {
            return userRepository.save(user);
        }
        return null;
    }

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    public Optional<User> getUserByEmail(String email) {
        return userRepository.findById(email);
    }

    public User updateUser(String email, User updatedUser) {
        return userRepository.findById(email)
                .map(user -> {
                    return userRepository.save(updatedUser);
                }).orElseGet(null);
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
                for(String r : newRoles){
                    user.getRoles().add(UserRole.valueOf(r));
                }
                userRepository.save(user);
            }
        });
    }

    public User createUser(String email, String password, List<String> roles) {
        if(!userExists(email)) {
            User user = new User();
            user.setEmail(email);
            user.setPassword(passwordEncoder.encode(password));
            for(String r : roles){
                user.getRoles().add(UserRole.valueOf(r));
            }
            return userRepository.save(user);
        }
        return null;
    }

    public boolean userExists(String email){
        return userRepository.findById(email).isPresent();
    }
}

