package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.PrincipalAuthorDeclaration;
import ro.uvt.pokedex.core.model.scopus.canonical.PrincipalAuthorDeclaration.Action;
import ro.uvt.pokedex.core.model.scopus.canonical.PrincipalAuthorDeclaration.Event;
import ro.uvt.pokedex.core.model.scopus.canonical.PrincipalAuthorDeclaration.Kind;
import ro.uvt.pokedex.core.model.scopus.canonical.PrincipalAuthorDeclaration.Status;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.repository.scopus.canonical.PrincipalAuthorDeclarationRepository;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationService;
import ro.uvt.pokedex.core.service.security.PrincipalAuthorDeclarationAccessService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Declarations of principal authorship: made by the researcher, decided by a head, kept with their history.
 * What an approved one changes in the scores is in {@link PrincipalAuthorDeclarationReadService}.
 *
 * <p>Refusals carry a {@link Refusal} code, not a sentence: the pages say it in the reader's language.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrincipalAuthorDeclarationService {

    static final int EVIDENCE_MIN = 10;
    static final int EVIDENCE_MAX = 1000;
    static final int NOTE_MAX = 1000;

    public enum Refusal {
        NOT_YOUR_PUBLICATION, ALREADY_PRINCIPAL, ALREADY_PENDING, ALREADY_APPROVED, KIND_MISSING,
        EVIDENCE_TOO_SHORT, EVIDENCE_TOO_LONG, LINK_NOT_A_WEB_ADDRESS, NOTHING_TO_WITHDRAW,
        NOT_FOUND, NOT_ALLOWED, NOT_PENDING, NOT_APPROVED, NOTE_REQUIRED, NOTE_TOO_LONG
    }

    /** Thrown for everything a caller can do wrong; {@link #getMessage()} is the code's name. */
    public static class DeclarationException extends IllegalArgumentException {
        private final Refusal refusal;

        public DeclarationException(Refusal refusal) {
            super(refusal.name());
            this.refusal = refusal;
        }

        public Refusal refusal() {
            return refusal;
        }
    }

    /** How the data sees the researcher on a publication, before any declaration. */
    public enum Role { FIRST_AUTHOR, CORRESPONDING_AUTHOR, CO_AUTHOR }

    public record PublicationState(Role role, Status declaration, Kind kind, String decisionNote) {}

    public record WorkspaceState(Map<String, PublicationState> byPublicationId) {}

    private final PrincipalAuthorDeclarationRepository repository;
    private final EffectiveAuthorshipReadService effectiveAuthorshipReadService;
    private final PrincipalAuthorDeclarationAccessService access;

    // ------------------------------------------------------------------ the researcher

    public WorkspaceState stateFor(String userEmail) {
        Set<String> own = Set.copyOf(effectiveAuthorshipReadService.findCanonicalAuthorIdsForUser(userEmail));
        List<PrincipalAuthorDeclaration> declarations = repository.findByUserEmail(userEmail);
        Map<String, PublicationState> states = new LinkedHashMap<>();
        for (ScholardexPublicationView publication : effectiveAuthorshipReadService.findEffectivePublicationsForUser(userEmail)) {
            if (publication == null || publication.getId() == null) {
                continue;
            }
            PrincipalAuthorDeclaration declaration = declarations.stream()
                    .filter(d -> PrincipalAuthorDeclarationReadService.isAbout(d, publication))
                    .findFirst().orElse(null);
            states.put(publication.getId(), new PublicationState(
                    roleOf(publication, own),
                    declaration == null ? null : declaration.getStatus(),
                    declaration == null ? null : declaration.getKind(),
                    declaration == null ? null : declaration.getDecisionNote()));
        }
        return new WorkspaceState(states);
    }

    static Role roleOf(ScholardexPublicationView publication, Set<String> own) {
        List<String> authors = publication.getAuthors();
        if (authors != null && !authors.isEmpty() && own.contains(authors.getFirst())) {
            return Role.FIRST_AUTHOR;
        }
        if (publication.getCorrespondingAuthorIds() != null
                && publication.getCorrespondingAuthorIds().stream().anyMatch(own::contains)) {
            return Role.CORRESPONDING_AUTHOR;
        }
        return Role.CO_AUTHOR;
    }

    public PrincipalAuthorDeclaration declare(String userEmail, String publicationId, Kind kind,
                                              String evidence, String evidenceUrl) {
        if (kind == null) {
            throw new DeclarationException(Refusal.KIND_MISSING);
        }
        String text = evidence == null ? "" : evidence.trim();
        if (text.length() < EVIDENCE_MIN) {
            throw new DeclarationException(Refusal.EVIDENCE_TOO_SHORT);
        }
        if (text.length() > EVIDENCE_MAX) {
            throw new DeclarationException(Refusal.EVIDENCE_TOO_LONG);
        }
        String link = evidenceUrl == null || evidenceUrl.isBlank() ? null : evidenceUrl.trim();
        if (link != null && !(link.startsWith("https://") || link.startsWith("http://"))) {
            throw new DeclarationException(Refusal.LINK_NOT_A_WEB_ADDRESS);
        }
        ScholardexPublicationView publication = effectiveAuthorshipReadService
                .findEffectivePublicationsForUser(userEmail).stream()
                .filter(p -> p != null && publicationId != null && publicationId.equals(p.getId()))
                .findFirst()
                .orElseThrow(() -> new DeclarationException(Refusal.NOT_YOUR_PUBLICATION));
        Set<String> own = Set.copyOf(effectiveAuthorshipReadService.findCanonicalAuthorIdsForUser(userEmail));
        if (roleOf(publication, own) != Role.CO_AUTHOR) {
            throw new DeclarationException(Refusal.ALREADY_PRINCIPAL);
        }

        PrincipalAuthorDeclaration declaration = repository.findByUserEmail(userEmail).stream()
                .filter(d -> PrincipalAuthorDeclarationReadService.isAbout(d, publication))
                .findFirst().orElse(null);
        if (declaration != null && declaration.getStatus() == Status.PENDING) {
            throw new DeclarationException(Refusal.ALREADY_PENDING);
        }
        if (declaration != null && declaration.getStatus() == Status.APPROVED) {
            throw new DeclarationException(Refusal.ALREADY_APPROVED);
        }
        Instant now = Instant.now();
        if (declaration == null) {
            declaration = new PrincipalAuthorDeclaration();
            declaration.setUserEmail(userEmail);
            declaration.setCreatedAt(now);
        }
        // A rejected or withdrawn declaration is made again on the same record, so its history stays in one place.
        declaration.setPublicationId(publication.getId());
        declaration.setDoiNormalized(firstNonBlank(publication.getDoiNormalized(),
                ScholardexPublicationCanonicalizationService.normalizeDoi(publication.getDoi())));
        declaration.setTitleNormalized(ScholardexPublicationCanonicalizationService.normalizeTitle(publication.getTitle()));
        declaration.setYear(PrincipalAuthorDeclarationReadService.yearOf(publication).orElse(null));
        declaration.setPublicationTitle(publication.getTitle());
        declaration.setKind(kind);
        declaration.setEvidence(text);
        declaration.setEvidenceUrl(link);
        declaration.setStatus(Status.PENDING);
        declaration.setDecidedBy(null);
        declaration.setDecidedAt(null);
        declaration.setDecisionNote(null);
        declaration.setUpdatedAt(now);
        history(declaration).add(Event.of(Action.DECLARED, userEmail, kind.name() + ": " + text));
        log.info("Principal authorship declared: user={} publication={} kind={}", userEmail, publication.getId(), kind);
        return repository.save(declaration);
    }

    /** The researcher takes the declaration back, whether it was decided or not. */
    public PrincipalAuthorDeclaration withdraw(String userEmail, String publicationId) {
        // Found the way the scores find it: by id, or — after a rebuild gave the publication a new id — by
        // what the declaration remembers of it.
        Optional<ScholardexPublicationView> publication = effectiveAuthorshipReadService
                .findEffectivePublicationsForUser(userEmail).stream()
                .filter(p -> p != null && publicationId != null && publicationId.equals(p.getId()))
                .findFirst();
        PrincipalAuthorDeclaration declaration = repository.findByUserEmail(userEmail).stream()
                .filter(d -> publication.map(p -> PrincipalAuthorDeclarationReadService.isAbout(d, p))
                        .orElseGet(() -> publicationId != null && publicationId.equals(d.getPublicationId())))
                .filter(d -> d.getStatus() == Status.PENDING || d.getStatus() == Status.APPROVED)
                .findFirst()
                .orElseThrow(() -> new DeclarationException(Refusal.NOTHING_TO_WITHDRAW));
        declaration.setStatus(Status.WITHDRAWN);
        declaration.setUpdatedAt(Instant.now());
        history(declaration).add(Event.of(Action.WITHDRAWN, userEmail, null));
        return repository.save(declaration);
    }

    // ------------------------------------------------------------------ the head

    public PrincipalAuthorDeclaration approve(String id, Authentication authentication, String note) {
        PrincipalAuthorDeclaration declaration = decidable(id, authentication, Status.PENDING, Refusal.NOT_PENDING);
        return decided(declaration, Status.APPROVED, Action.APPROVED, authentication, clean(note, false));
    }

    public PrincipalAuthorDeclaration reject(String id, Authentication authentication, String note) {
        PrincipalAuthorDeclaration declaration = decidable(id, authentication, Status.PENDING, Refusal.NOT_PENDING);
        return decided(declaration, Status.REJECTED, Action.REJECTED, authentication, clean(note, true));
    }

    /** An approval taken back: the publication stops counting as a principal-author one. */
    public PrincipalAuthorDeclaration revoke(String id, Authentication authentication, String note) {
        PrincipalAuthorDeclaration declaration = decidable(id, authentication, Status.APPROVED, Refusal.NOT_APPROVED);
        return decided(declaration, Status.REJECTED, Action.REVOKED, authentication, clean(note, true));
    }

    /** Waiting declarations the principal may decide, oldest first. */
    public List<PrincipalAuthorDeclaration> pendingFor(Authentication authentication) {
        return visibleTo(authentication, repository.findByStatusOrderByCreatedAtAsc(Status.PENDING));
    }

    /** Decided declarations of the researchers the principal answers for, latest first. */
    public List<PrincipalAuthorDeclaration> decidedFor(Authentication authentication, int limit) {
        List<PrincipalAuthorDeclaration> decided = visibleTo(authentication,
                repository.findByStatusInOrderByDecidedAtDesc(List.of(Status.APPROVED, Status.REJECTED)));
        return decided.size() > limit ? new ArrayList<>(decided.subList(0, Math.max(0, limit))) : decided;
    }

    public int pendingCountFor(Authentication authentication) {
        return pendingFor(authentication).size();
    }

    private List<PrincipalAuthorDeclaration> visibleTo(Authentication authentication,
                                                       List<PrincipalAuthorDeclaration> declarations) {
        Map<String, Boolean> allowedByResearcher = new HashMap<>();
        List<PrincipalAuthorDeclaration> visible = new ArrayList<>();
        for (PrincipalAuthorDeclaration declaration : declarations) {
            if (declaration.getUserEmail() != null && allowedByResearcher.computeIfAbsent(
                    declaration.getUserEmail(), email -> access.canDecide(email, authentication))) {
                visible.add(declaration);
            }
        }
        return visible;
    }

    private PrincipalAuthorDeclaration decidable(String id, Authentication authentication,
                                                 Status expected, Refusal otherwise) {
        PrincipalAuthorDeclaration declaration = Optional.ofNullable(id).flatMap(repository::findById)
                .orElseThrow(() -> new DeclarationException(Refusal.NOT_FOUND));
        if (!access.canDecide(declaration.getUserEmail(), authentication)) {
            throw new DeclarationException(Refusal.NOT_ALLOWED);
        }
        if (declaration.getStatus() != expected) {
            throw new DeclarationException(otherwise);
        }
        return declaration;
    }

    private PrincipalAuthorDeclaration decided(PrincipalAuthorDeclaration declaration, Status status, Action action,
                                               Authentication authentication, String note) {
        Instant now = Instant.now();
        declaration.setStatus(status);
        declaration.setDecidedBy(authentication.getName());
        declaration.setDecidedAt(now);
        declaration.setDecisionNote(note);
        declaration.setUpdatedAt(now);
        history(declaration).add(Event.of(action, authentication.getName(), note));
        log.info("Principal authorship {}: declaration={} researcher={} by={}",
                action, declaration.getId(), declaration.getUserEmail(), authentication.getName());
        return repository.save(declaration);
    }

    private static String clean(String note, boolean required) {
        String text = note == null ? "" : note.trim();
        if (text.isEmpty()) {
            if (required) {
                throw new DeclarationException(Refusal.NOTE_REQUIRED);
            }
            return null;
        }
        if (text.length() > NOTE_MAX) {
            throw new DeclarationException(Refusal.NOTE_TOO_LONG);
        }
        return text;
    }

    private static List<Event> history(PrincipalAuthorDeclaration declaration) {
        if (declaration.getHistory() == null) {
            declaration.setHistory(new ArrayList<>());
        }
        return declaration.getHistory();
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
