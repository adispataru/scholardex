package ro.uvt.pokedex.core.controller;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import ro.uvt.pokedex.core.controller.dto.AdminUserUpsertRequest;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.UserService;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/admin/users")
public class UserController {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public UserController(UserService userService, PasswordEncoder passwordEncoder) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping
    public ResponseEntity<User> createUser(@Valid @RequestBody AdminUserUpsertRequest request) {
        if (request.password() == null || request.password().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        User savedUser = userService.createUser(request.email(), request.password(), request.roles());
        return ResponseEntity.ok(savedUser);
    }

    @GetMapping
    public ResponseEntity<List<User>> getAllUsers() {
        List<User> users = userService.getAllUsers();
        return ResponseEntity.ok(users);
    }

    @GetMapping("/{email}")
    public ResponseEntity<User> getUserByEmail(@PathVariable String email) {
        return userService.getUserByEmail(email)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{email}")
    public ResponseEntity<User> updateUser(@PathVariable String email, @Valid @RequestBody AdminUserUpsertRequest request) {
        Optional<User> existingOpt = userService.getUserByEmail(email);
        if (existingOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        User existing = existingOpt.get();
        User updated = new User();
        updated.setEmail(email);
        updated.setPassword((request.password() == null || request.password().isBlank()) ? existing.getPassword() : request.password());
        updated.setResearcherId(existing.getResearcherId());
        updated.setLocked(request.locked() != null ? request.locked() : existing.isLocked());
        updated.setRoles(userService.parseRoles(request.roles()));

        return userService.updateUser(email, updated)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{email}")
    public ResponseEntity<Void> deleteUser(@PathVariable String email) {
        userService.deleteUser(email);
        return ResponseEntity.ok().build();
    }


}
