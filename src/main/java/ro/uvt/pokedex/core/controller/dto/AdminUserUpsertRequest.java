package ro.uvt.pokedex.core.controller.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

import java.util.List;

public record AdminUserUpsertRequest(
        @NotBlank
        @Email
        String email,
        String password,
        @NotEmpty
        List<@Pattern(regexp = "PLATFORM_ADMIN|RESEARCHER|SUPERVISOR") String> roles,
        Boolean locked
) {
}
