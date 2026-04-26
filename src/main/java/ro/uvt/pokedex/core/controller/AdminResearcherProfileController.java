package ro.uvt.pokedex.core.controller;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ro.uvt.pokedex.core.controller.dto.ResearcherProfileRequest;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.UserService;

import java.util.List;

@RestController
@RequestMapping("/api/admin/researcher-profiles")
public class AdminResearcherProfileController {

    private final UserService userService;

    @Autowired
    public AdminResearcherProfileController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    public ResponseEntity<User> createProfile(@Valid @RequestBody ResearcherProfileRequest request) {
        if (request.email() == null || request.email().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        User.ResearcherProfile profile = toProfile(request);
        User savedUser = userService.saveResearcherProfile(request.email(), profile);
        return ResponseEntity.ok(savedUser);
    }

    @GetMapping
    public ResponseEntity<List<User>> getAllProfiles() {
        List<User> users = userService.findUsersWithResearcherProfile();
        return ResponseEntity.ok(users);
    }

    @GetMapping("/{email}")
    public ResponseEntity<User> getProfileByEmail(@PathVariable String email) {
        return userService.getUserByEmail(email)
                .filter(u -> u.getResearcherProfile() != null)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{email}")
    public ResponseEntity<User> updateProfile(@PathVariable String email, @Valid @RequestBody ResearcherProfileRequest request) {
        User.ResearcherProfile profile = toProfile(request);
        User updatedUser = userService.saveResearcherProfile(email, profile);
        return ResponseEntity.ok(updatedUser);
    }

    @DeleteMapping("/{email}")
    public ResponseEntity<Void> deleteProfile(@PathVariable String email) {
        userService.deleteResearcherProfile(email);
        return ResponseEntity.ok().build();
    }

    private User.ResearcherProfile toProfile(ResearcherProfileRequest request) {
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setFirstName(request.firstName());
        profile.setLastName(request.lastName());
        profile.setScholarId(request.scholarId());
        profile.setScopusId(request.normalizedScopusId());
        profile.setWosId(request.normalizedWosId());
        profile.setPrimaryScholardexAuthorId(request.primaryScholardexAuthorId());
        profile.setPosition(request.position());
        return profile;
    }
}
