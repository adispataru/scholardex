package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.controller.dto.ScholardexProjectListItemResponse;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.brainmap.ProjectCanonicalizationService;

import java.util.List;

/**
 * H78 slice 1 — surface the canonical projects that may belong to a researcher, by matching the researcher's name to a
 * project's <b>director</b>. Director-only: {@code ScholardexProjectFact} carries no participant names (only affiliation
 * ids), so participants aren't auto-surfaced — they self-serve via the project search when adding a {@code Grant
 * Cercetare} (slice 4). The match uses the same word-order/diacritic-insensitive name key the canonicalizer uses for
 * coordinators ({@link ProjectCanonicalizationService#signature}), compared by exact equality against the projected
 * {@code director_signature} column. Read-only surfacing (a candidate list) — never a silent link, so homonyms are
 * low-risk here; the actual import is an explicit per-project user action (slice 2).
 */
@Service
@RequiredArgsConstructor
public class ResearcherProjectService {

    private final ScholardexProjectReadPort projectReadPort;

    /** Canonical projects whose director name-signature equals the researcher's. Empty if the researcher has no name. */
    public List<ScholardexProjectListItemResponse> myProjects(User.ResearcherProfile profile) {
        if (profile == null) {
            return List.of();
        }
        String first = profile.getFirstName() == null ? "" : profile.getFirstName();
        String last = profile.getLastName() == null ? "" : profile.getLastName();
        String signature = ProjectCanonicalizationService.signature((first + " " + last).trim());
        if (signature == null || signature.isBlank()) {
            return List.of();
        }
        return projectReadPort.findByDirectorSignature(signature);
    }
}
