package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.CanonicalPublicationConstants;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexBookFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.scopus.canonical.UserDefinedPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.WizardPublicationReview;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexBookFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.UserDefinedPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.WizardPublicationReviewRepository;
import ro.uvt.pokedex.core.service.crossref.CrossrefClient;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationService;
import ro.uvt.pokedex.core.service.security.PrincipalAuthorDeclarationAccessService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * H145 — a publication a researcher adds through the wizard does not count on their word. It counts once its DOI
 * resolves at Crossref to the same work (title, year, the researcher among the authors or editors, the venue's ISSN or
 * ISBN), or once a head of the researcher's department or faculty (or a platform admin, never the researcher) approved
 * it, and only while the entry still states what was verified. A publication another source holds as well (Scopus, Web
 * of Science, OpenAlex) counts as that source's. Before H145 the entry counted at once: type, year, venue, authors and
 * their order were all typed, and a real journal's name with a made-up ISSN took that journal's quartile.
 */
@Service
@RequiredArgsConstructor
public class WizardPublicationReviewService {

    private static final Logger log = LoggerFactory.getLogger(WizardPublicationReviewService.class);

    public static final String CROSSREF = "crossref";
    static final String WIZARD_EID_PREFIX = UserDefinedWizardOnboardingContract.SOURCE + ":";
    static final int NOTE_MAX = 1000;
    /** Two titles are the same work when their words overlap this much (after normalisation). */
    static final double TITLE_OVERLAP = 0.85;

    public enum Refusal { NOT_FOUND, NOT_PENDING, NOT_DECIDED, NOT_ALLOWED, NOTE_REQUIRED, NOTE_TOO_LONG, CHANGED }

    public static class ReviewRefused extends RuntimeException {
        private final Refusal refusal;

        public ReviewRefused(Refusal refusal) {
            super(refusal.name());
            this.refusal = refusal;
        }

        public Refusal refusal() {
            return refusal;
        }
    }

    /** A wizard publication as a head sees it. */
    public record ReviewItem(String id, String publicationId, String submitterEmail, String title, String type,
                             String year, String venue, String venueIdentifiers, String publisher, String doi,
                             List<String> authors, String status, String verificationNote, String decidedBy,
                             Instant decidedAt, String decisionNote, String facts) {
    }

    private final WizardPublicationReviewRepository reviewRepository;
    private final UserDefinedPublicationFactRepository entryRepository;
    private final ScholardexPublicationFactRepository publicationFactRepository;
    private final ScholardexBookFactRepository bookFactRepository;
    private final ScholardexSourceLinkService sourceLinkService;
    private final ScholardexProjectionReadService projectionReadService;
    private final CrossrefClient crossrefClient;
    private final UserRepository userRepository;
    private final PrincipalAuthorDeclarationAccessService access;

    // ── scoring ─────────────────────────────────────────────────────────────

    /**
     * The publications of the list that count: all of them but the wizard's own entries that neither another source
     * holds nor Crossref or a head verified for what they state now. One lookup, and only when the list has wizard
     * entries at all.
     */
    public List<ScholardexPublicationView> countable(List<ScholardexPublicationView> publications) {
        if (publications == null || publications.isEmpty()) {
            return publications;
        }
        Set<String> wizardIds = publications.stream()
                .filter(p -> p != null && p.getId() != null && isWizardMinted(p.getEid()))
                .map(ScholardexPublicationView::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (wizardIds.isEmpty()) {
            return publications;
        }
        Set<String> uncounted = new HashSet<>(wizardIds);
        for (ScholardexPublicationFact fact : publicationFactRepository.findAllById(wizardIds)) {
            if (counts(fact)) {
                uncounted.remove(fact.getId());
            }
        }
        if (uncounted.isEmpty()) {
            return publications;
        }
        return publications.stream().filter(p -> p == null || !uncounted.contains(p.getId())).toList();
    }

    boolean counts(ScholardexPublicationFact fact) {
        if (heldByOtherSources(fact)) {
            return true;
        }
        if (isBlank(fact.getUserSourceId())) {
            return false;
        }
        Optional<WizardPublicationReview> review = reviewRepository.findById(fact.getUserSourceId());
        if (review.isEmpty() || !counting(review.get().getStatus())) {
            return false;
        }
        return entryRepository.findBySourceRecordId(fact.getUserSourceId())
                .map(entry -> factsOf(entry).equals(review.get().getFacts()))
                .orElse(false);
    }

    private static boolean counting(WizardPublicationReview.Status status) {
        return status == WizardPublicationReview.Status.VERIFIED || status == WizardPublicationReview.Status.APPROVED;
    }

    static boolean isWizardMinted(String eid) {
        return eid != null && eid.startsWith(WIZARD_EID_PREFIX);
    }

    /** Scopus, Web of Science or OpenAlex hold the publication as well: their record vouches for it. */
    boolean heldByOtherSources(ScholardexPublicationFact fact) {
        String wosId = fact.getWosId();
        if (!isBlank(wosId) && !CanonicalPublicationConstants.NON_WOS_ID.equals(wosId)) {
            return true;
        }
        if (!isBlank(fact.getEid()) && !isWizardMinted(fact.getEid())) {
            return true;
        }
        return sourceLinkService.findByCanonical(ScholardexEntityType.PUBLICATION, fact.getId()).stream()
                .anyMatch(link -> !UserDefinedWizardOnboardingContract.SOURCE.equals(link.getSource())
                        && ScholardexSourceLinkService.STATE_LINKED.equals(link.getLinkState()));
    }

    /**
     * The fingerprint of an entry as submitted — title, type, date, venue, authors in order, DOI, volume, issue, pages.
     * Taken from the wizard's own record (rebuilt from the submission), so a rebuild that re-mints canonical ids leaves
     * it as it is; a resubmission that changes any of them changes it.
     */
    static String factsOf(UserDefinedPublicationFact entry) {
        String text = String.join("|",
                nz(entry.getTitle()).trim(), nz(entry.getSubtype()), nz(entry.getCoverDate()),
                nz(entry.getForumSourceRecordId()), nz(entry.getBookId()),
                nz(ScholardexPublicationCanonicalizationService.normalizeDoi(entry.getDoi())),
                entry.getAuthorIds() == null ? "" : String.join(",", entry.getAuthorIds()),
                String.valueOf(entry.getAuthorCount()), nz(entry.getVolume()), nz(entry.getIssueIdentifier()),
                nz(entry.getPageRange()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── submission ──────────────────────────────────────────────────────────

    /**
     * After the wizard materialised an entry: a new or changed entry waits for verification (a decision on what it
     * stated before no longer holds); when it gives a DOI, Crossref is asked at once. Empty when another source holds
     * the publication (it counts as theirs) or nothing was materialised.
     */
    public Optional<WizardPublicationReview.Status> afterSubmit(String sourceRecordId, String submitterEmail) {
        Optional<UserDefinedPublicationFact> entry = entryRepository.findBySourceRecordId(sourceRecordId);
        Optional<ScholardexPublicationFact> fact = publicationFactRepository.findByUserSourceId(sourceRecordId);
        if (entry.isEmpty() || fact.isEmpty() || heldByOtherSources(fact.get())) {
            return Optional.empty();
        }
        String facts = factsOf(entry.get());
        WizardPublicationReview review = reviewRepository.findById(sourceRecordId).orElseGet(() -> {
            WizardPublicationReview created = new WizardPublicationReview();
            created.setId(sourceRecordId);
            created.setCreatedAt(Instant.now());
            return created;
        });
        review.setPublicationId(fact.get().getId());
        review.setSubmitterEmail(submitterEmail);
        if (facts.equals(review.getFacts()) && review.getStatus() != null
                && review.getStatus() != WizardPublicationReview.Status.PENDING) {
            reviewRepository.save(review); // the same entry again: its decision stands, a rejection too
            return Optional.of(review.getStatus());
        }
        review.setFacts(facts);
        review.setStatus(WizardPublicationReview.Status.PENDING);
        review.setDecidedBy(null);
        review.setDecidedAt(null);
        review.setDecisionNote(null);
        review.setVerificationNote(null);
        review.getHistory().add(WizardPublicationReview.Event.of(WizardPublicationReview.Status.PENDING, submitterEmail, null));
        if (!isBlank(entry.get().getDoi())) {
            List<String> mismatches = crossrefClient.work(entry.get().getDoi())
                    .map(work -> mismatches(work, entry.get(), fact.get(), submitterEmail))
                    .orElse(List.of("Crossref has no record of the DOI (or did not answer)"));
            if (mismatches.isEmpty()) {
                review.setStatus(WizardPublicationReview.Status.VERIFIED);
                review.setDecidedBy(CROSSREF);
                review.setDecidedAt(Instant.now());
                review.getHistory().add(WizardPublicationReview.Event.of(WizardPublicationReview.Status.VERIFIED, CROSSREF, null));
            } else {
                review.setVerificationNote(String.join("; ", mismatches));
            }
        }
        reviewRepository.save(review);
        log.info("Wizard publication {}: {}{}", sourceRecordId, review.getStatus(),
                review.getVerificationNote() == null ? "" : " (" + review.getVerificationNote() + ")");
        return Optional.of(review.getStatus());
    }

    /**
     * What Crossref says differently from the entry; empty when it is the same work: the title (word overlap), a year
     * within one of the entry's (print and online years differ), the researcher's name among the authors or editors,
     * and the venue (an ISSN of the journal, or an ISBN of the book, among Crossref's; by name when the venue gives none).
     */
    List<String> mismatches(CrossrefClient.Work work, UserDefinedPublicationFact entry, ScholardexPublicationFact fact,
                            String submitterEmail) {
        List<String> out = new ArrayList<>();
        if (!sameTitle(work.title(), entry.getTitle())) {
            out.add("the title differs from Crossref's (" + nz(work.title()) + ")");
        }
        Integer year = year(entry.getCoverDate());
        if (year == null || work.years().stream().noneMatch(y -> Math.abs(y - year) <= 1)) {
            out.add("the year differs from Crossref's " + work.years());
        }
        if (!namedAmong(submitterEmail, work.familyNames())) {
            out.add("the researcher is not among Crossref's authors or editors");
        }
        String venue = venueMismatch(work, entry, fact);
        if (venue != null) {
            out.add(venue);
        }
        return out;
    }

    private String venueMismatch(CrossrefClient.Work work, UserDefinedPublicationFact entry, ScholardexPublicationFact fact) {
        if (!isBlank(fact.getBookId())) {
            Optional<ScholardexBookFact> book = bookFactRepository.findById(fact.getBookId());
            Set<String> isbns = book.map(b -> identifiers(b.getPrintIsbn(), b.getElectronicIsbn())).orElse(Set.of());
            if (!isbns.isEmpty()) {
                return work.isbns().stream().map(WizardPublicationReviewService::identifier).anyMatch(isbns::contains)
                        ? null : "the book's ISBN is not Crossref's " + work.isbns();
            }
            String bookTitle = book.map(ScholardexBookFact::getTitle).orElse(null);
            boolean named = sameTitle(work.title(), bookTitle)
                    || work.containerTitles().stream().anyMatch(c -> sameTitle(c, bookTitle));
            return named ? null : "the book is not the one Crossref names";
        }
        Optional<ScholardexForumView> forum = isBlank(fact.getForumId()) ? Optional.empty()
                : projectionReadService.findForumById(fact.getForumId());
        if (forum.isEmpty()) {
            return "the venue is unknown";
        }
        Set<String> issns = identifiers(forum.get().getIssn(), forum.get().getEIssn());
        if (!issns.isEmpty()) {
            return work.issns().stream().map(WizardPublicationReviewService::identifier).anyMatch(issns::contains)
                    ? null : "the venue's ISSN is not Crossref's " + work.issns();
        }
        String name = forum.get().getPublicationName();
        return work.containerTitles().stream().anyMatch(c -> sameTitle(c, name))
                ? null : "the venue is not the one Crossref names " + work.containerTitles();
    }

    private boolean namedAmong(String submitterEmail, List<String> familyNames) {
        Set<String> names = new HashSet<>();
        userRepository.findById(submitterEmail).map(User::getResearcherProfile).ifPresent(p -> {
            names.addAll(words(p.getLastName()));
            names.addAll(words(p.getFirstName()));
        });
        names.removeIf(n -> n.length() < 3);
        if (names.isEmpty()) {
            return false;
        }
        return familyNames.stream().flatMap(f -> words(f).stream()).anyMatch(names::contains);
    }

    static boolean sameTitle(String a, String b) {
        String x = ScholardexPublicationCanonicalizationService.normalizeTitle(a);
        String y = ScholardexPublicationCanonicalizationService.normalizeTitle(b);
        if (x == null || y == null) {
            return false;
        }
        if (x.equals(y)) {
            return true;
        }
        Set<String> wx = new HashSet<>(List.of(x.split(" ")));
        Set<String> wy = new HashSet<>(List.of(y.split(" ")));
        Set<String> both = new HashSet<>(wx);
        both.retainAll(wy);
        Set<String> either = new HashSet<>(wx);
        either.addAll(wy);
        return !either.isEmpty() && (double) both.size() / either.size() >= TITLE_OVERLAP;
    }

    private static List<String> words(String text) {
        String normalized = ScholardexPublicationCanonicalizationService.normalizeTitle(text);
        return normalized == null ? List.of() : List.of(normalized.split(" "));
    }

    private static Set<String> identifiers(String... values) {
        Set<String> out = new HashSet<>();
        for (String value : values) {
            String id = identifier(value);
            if (!id.isEmpty()) {
                out.add(id);
            }
        }
        return out;
    }

    /** An ISSN or ISBN as its digits (and check letter), whatever the hyphens. */
    static String identifier(String value) {
        return value == null ? "" : value.replaceAll("[^0-9Xx]", "").toUpperCase(java.util.Locale.ROOT);
    }

    private static Integer year(String coverDate) {
        if (coverDate == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(1[89]|20)\\d{2}").matcher(coverDate);
        return m.find() ? Integer.parseInt(m.group()) : null;
    }

    // ── heads ───────────────────────────────────────────────────────────────

    /** The entries waiting for a head that the principal may decide, oldest first. */
    public List<ReviewItem> pendingFor(Authentication authentication) {
        return visible(authentication, Set.of(WizardPublicationReview.Status.PENDING),
                Comparator.comparing(WizardPublicationReview::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())),
                Integer.MAX_VALUE);
    }

    /** The latest decisions (a head's or Crossref's) on the entries of the researchers the principal answers for. */
    public List<ReviewItem> decidedFor(Authentication authentication, int limit) {
        return visible(authentication, Set.of(WizardPublicationReview.Status.APPROVED, WizardPublicationReview.Status.VERIFIED,
                        WizardPublicationReview.Status.REJECTED),
                Comparator.comparing(WizardPublicationReview::getDecidedAt, Comparator.nullsLast(Comparator.reverseOrder())),
                limit);
    }

    /** A head approves the entry as the page showed it ({@code factsSeen}); a changed entry is refused. */
    public WizardPublicationReview approve(String id, Authentication authentication, String note, String factsSeen) {
        return decide(id, authentication, note, factsSeen, Set.of(WizardPublicationReview.Status.PENDING),
                Refusal.NOT_PENDING, false, WizardPublicationReview.Status.APPROVED);
    }

    public WizardPublicationReview reject(String id, Authentication authentication, String note, String factsSeen) {
        return decide(id, authentication, note, factsSeen, Set.of(WizardPublicationReview.Status.PENDING),
                Refusal.NOT_PENDING, true, WizardPublicationReview.Status.REJECTED);
    }

    /** Withdraws an approval or a Crossref verification that turned out wrong, saying why. */
    public WizardPublicationReview revoke(String id, Authentication authentication, String note, String factsSeen) {
        return decide(id, authentication, note, factsSeen,
                Set.of(WizardPublicationReview.Status.APPROVED, WizardPublicationReview.Status.VERIFIED),
                Refusal.NOT_DECIDED, true, WizardPublicationReview.Status.REJECTED);
    }

    private WizardPublicationReview decide(String id, Authentication authentication, String note, String factsSeen,
                                           Set<WizardPublicationReview.Status> from, Refusal notFrom, boolean noteRequired,
                                           WizardPublicationReview.Status to) {
        WizardPublicationReview review = reviewRepository.findById(id).orElseThrow(() -> new ReviewRefused(Refusal.NOT_FOUND));
        if (!from.contains(review.getStatus())) {
            throw new ReviewRefused(notFrom);
        }
        if (!access.canDecide(review.getSubmitterEmail(), authentication)) {
            throw new ReviewRefused(Refusal.NOT_ALLOWED);
        }
        UserDefinedPublicationFact entry = entryRepository.findBySourceRecordId(id)
                .orElseThrow(() -> new ReviewRefused(Refusal.NOT_FOUND));
        String facts = factsOf(entry);
        if (!facts.equals(factsSeen) || !facts.equals(review.getFacts())) {
            throw new ReviewRefused(Refusal.CHANGED);
        }
        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        if (noteRequired && cleanNote == null) {
            throw new ReviewRefused(Refusal.NOTE_REQUIRED);
        }
        if (cleanNote != null && cleanNote.length() > NOTE_MAX) {
            throw new ReviewRefused(Refusal.NOTE_TOO_LONG);
        }
        review.setStatus(to);
        review.setDecidedBy(authentication.getName());
        review.setDecidedAt(Instant.now());
        review.setDecisionNote(cleanNote);
        review.getHistory().add(WizardPublicationReview.Event.of(to, authentication.getName(), cleanNote));
        return reviewRepository.save(review);
    }

    private List<ReviewItem> visible(Authentication authentication, Set<WizardPublicationReview.Status> statuses,
                                     Comparator<WizardPublicationReview> order, int limit) {
        List<WizardPublicationReview> reviews = reviewRepository.findByStatusIn(statuses).stream()
                .filter(r -> access.canDecide(r.getSubmitterEmail(), authentication))
                .sorted(order)
                .limit(Math.max(0, limit))
                .toList();
        if (reviews.isEmpty()) {
            return List.of();
        }
        Map<String, UserDefinedPublicationFact> entries = entryRepository
                .findBySourceRecordIdIn(reviews.stream().map(WizardPublicationReview::getId).toList()).stream()
                .collect(Collectors.toMap(UserDefinedPublicationFact::getSourceRecordId, Function.identity(), (a, b) -> a));
        List<ReviewItem> out = new ArrayList<>();
        for (WizardPublicationReview review : reviews) {
            UserDefinedPublicationFact entry = entries.get(review.getId());
            if (entry == null) {
                continue;
            }
            Optional<ScholardexPublicationFact> fact = publicationFactRepository.findByUserSourceId(review.getId());
            out.add(item(review, entry, fact.orElse(null)));
        }
        return out;
    }

    private ReviewItem item(WizardPublicationReview review, UserDefinedPublicationFact entry, ScholardexPublicationFact fact) {
        String venue = "";
        String identifiers = "";
        String publisher = "";
        if (fact != null && !isBlank(fact.getBookId())) {
            Optional<ScholardexBookFact> book = bookFactRepository.findById(fact.getBookId());
            venue = book.map(ScholardexBookFact::getTitle).map(WizardPublicationReviewService::nz).orElse("");
            publisher = book.map(ScholardexBookFact::getPublisher).map(WizardPublicationReviewService::nz).orElse("");
            identifiers = book.map(b -> joinNonBlank("ISBN ", b.getPrintIsbn(), b.getElectronicIsbn())).orElse("");
        } else if (fact != null && !isBlank(fact.getForumId())) {
            Optional<ScholardexForumView> forum = projectionReadService.findForumById(fact.getForumId());
            venue = forum.map(ScholardexForumView::getPublicationName).map(WizardPublicationReviewService::nz).orElse("");
            publisher = forum.map(ScholardexForumView::getPublisher).map(WizardPublicationReviewService::nz).orElse("");
            identifiers = forum.map(f -> joinNonBlank("ISSN ", f.getIssn(), f.getEIssn())).orElse("");
        }
        List<String> authorIds = fact != null && fact.getAuthorIds() != null ? fact.getAuthorIds() : List.of();
        Map<String, String> names = projectionReadService.findAuthorsByIdIn(authorIds).stream()
                .filter(a -> a.getId() != null)
                .collect(Collectors.toMap(ScholardexAuthorView::getId, a -> nz(a.getName()), (a, b) -> a));
        List<String> authors = authorIds.stream().map(id -> names.getOrDefault(id, "?")).toList();
        String year = Optional.ofNullable(year(entry.getCoverDate())).map(String::valueOf).orElse("");
        return new ReviewItem(review.getId(), review.getPublicationId(), review.getSubmitterEmail(), entry.getTitle(),
                isBlank(entry.getSubtypeDescription()) ? entry.getSubtype() : entry.getSubtypeDescription(), year,
                venue, identifiers, publisher, entry.getDoi(), authors, review.getStatus().name(),
                review.getVerificationNote(), review.getDecidedBy(), review.getDecidedAt(), review.getDecisionNote(),
                factsOf(entry));
    }

    private static String joinNonBlank(String prefix, String... values) {
        List<String> present = java.util.Arrays.stream(values).filter(v -> !isBlank(v)).map(String::trim).distinct().toList();
        return present.isEmpty() ? "" : prefix + String.join(" / ", present);
    }

    // ── existing entries ────────────────────────────────────────────────────

    /**
     * Entries made before H145 have no review: each one another source does not hold gets a pending one, so a head
     * sees it and it stops counting until decided. Returns how many were opened.
     */
    public int openMissingReviews() {
        int opened = 0;
        for (UserDefinedPublicationFact entry : entryRepository.findAll()) {
            if (entry.getSourceRecordId() == null || reviewRepository.existsById(entry.getSourceRecordId())) {
                continue;
            }
            Optional<ScholardexPublicationFact> fact = publicationFactRepository.findByUserSourceId(entry.getSourceRecordId());
            if (fact.isEmpty() || heldByOtherSources(fact.get())) {
                continue;
            }
            WizardPublicationReview review = new WizardPublicationReview();
            review.setId(entry.getSourceRecordId());
            review.setPublicationId(fact.get().getId());
            review.setSubmitterEmail(entry.getWizardSubmitterEmail());
            review.setStatus(WizardPublicationReview.Status.PENDING);
            review.setFacts(factsOf(entry));
            review.setCreatedAt(Objects.requireNonNullElse(entry.getWizardSubmittedAt(), Instant.now()));
            review.getHistory().add(WizardPublicationReview.Event.of(WizardPublicationReview.Status.PENDING, "H145",
                    "added before verification existed"));
            reviewRepository.save(review);
            opened++;
        }
        return opened;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
