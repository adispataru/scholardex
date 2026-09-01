package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.service.application.model.WizardPublicationCommand;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusCanonicalMaterializationService;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusImportEventIngestionService;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PublicationWizardFacade {

    static final String PAYLOAD_FORMAT_JSON_OBJECT = "json-object";

    private final ScholardexProjectionReadService scholardexProjectionReadService;
    private final ScopusImportEventIngestionService importEventIngestionService;
    private final ScopusCanonicalMaterializationService canonicalMaterializationService;
    // H99 item 7 — the book/chapter path (books are entities, not forums) + submit-time self-confirmation.
    private final ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexBookFactRepository bookFactRepository;
    private final ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository scholardexPublicationFactRepository;
    private final PublicationAuthorshipDecisionService publicationAuthorshipDecisionService;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PublicationWizardFacade.class);

    /** Subtypes whose venue is a book entity ({@code bookId}) rather than a forum. */
    static boolean isBookSubtype(String subtype) {
        String normalized = subtype == null ? "" : subtype.trim().toLowerCase(Locale.ROOT);
        return "bk".equals(normalized) || "ch".equals(normalized);
    }

    /** One row of the wizard's book search — enough to pick a volume and see its publisher. */
    public record BookOption(String id, String title, String publisher, String isbn, Integer year) {}

    /**
     * H99 item 7: search the book list (Scopus snapshot + wizard-minted entries) by title substring or
     * exact ISBN. A Springer/IGI/Elsevier volume is usually already listed with its publisher — attaching
     * to the real entity beats minting a duplicate.
     */
    public List<BookOption> searchBooks(String query) {
        String q = trim(query);
        if (q.length() < 3) {
            return List.of();
        }
        LinkedHashMap<String, BookOption> byId = new LinkedHashMap<>();
        String isbnKey = q.replaceAll("[^0-9Xx]", "");
        if (isbnKey.length() >= 10) {
            for (var book : bookFactRepository.findByPrintIsbnOrElectronicIsbn(isbnKey, isbnKey)) {
                byId.putIfAbsent(book.getId(), toBookOption(book));
            }
        }
        // $text first (indexed, word-based — right for "cloud computing"-style queries); the regex
        // contains-scan is the fallback for substring-of-word matches and for the window before the
        // text index exists (it is created by /scopus/ensureIndexes, not annotations).
        List<ro.uvt.pokedex.core.model.scopus.canonical.ScholardexBookFact> titleHits;
        try {
            titleHits = bookFactRepository.searchByTitleText(q, org.springframework.data.domain.PageRequest.of(0, 20));
        } catch (RuntimeException textIndexMissing) {
            titleHits = List.of();
        }
        if (titleHits.isEmpty()) {
            titleHits = bookFactRepository.findTop20ByTitleContainingIgnoreCaseOrderByTitleAsc(q);
        }
        for (var book : titleHits) {
            byId.putIfAbsent(book.getId(), toBookOption(book));
        }
        return List.copyOf(byId.values());
    }

    private BookOption toBookOption(ro.uvt.pokedex.core.model.scopus.canonical.ScholardexBookFact book) {
        String isbn = !isBlank(book.getPrintIsbn()) ? book.getPrintIsbn() : book.getElectronicIsbn();
        return new BookOption(book.getId(), book.getTitle(), book.getPublisher(), isbn, book.getPublicationYear());
    }

    public List<ScholardexForumView> listForums() {
        return scholardexProjectionReadService.findAllForums();
    }

    public Optional<String> resolveForumId(ScholardexForumView newForum, String selectedId) {
        if (selectedId != null && !selectedId.isEmpty()) {
            return scholardexProjectionReadService.findForumById(selectedId).map(ScholardexForumView::getId);
        }
        if (newForum != null && !isBlank(newForum.getPublicationName())) {
            return Optional.of(generateForumSourceId(newForum));
        }
        return Optional.empty();
    }

    public List<ScholardexAuthorView> findAuthorsForAffiliation(String affiliationId) {
        if (isBlank(affiliationId)) {
            return Collections.emptyList();
        }
        return scholardexProjectionReadService.findAuthorsByAffiliationId(affiliationId);
    }

    public WizardPublicationCommand buildPublicationDraft(
            String forumId,
            String authors,
            String creator,
            ScholardexForumView wizardForumDraft
    ) {
        WizardPublicationCommand command = new WizardPublicationCommand();
        command.setForum(forumId);
        command.setCreator(creator);
        command.setAuthorIdsCsv(authors);
        command.setAuthorIds(parseCsvList(authors));

        if (wizardForumDraft != null && !isBlank(wizardForumDraft.getPublicationName())) {
            applyForumDraft(command, wizardForumDraft);
            return command;
        }

        scholardexProjectionReadService.findForumById(forumId).ifPresent(forum -> applyForumDraft(command, forum));
        return command;
    }

    public SubmissionResult submitPublication(WizardPublicationCommand command, User submitter) {
        validateCommand(command);

        List<String> authorIds = parseCsvList(command.getAuthorIdsCsv());
        if (!authorIds.isEmpty()) {
            command.setAuthorIds(authorIds);
        }

        // H99 item 7 — a book/chapter's venue is a book ENTITY (Scopus book list or wizard-minted),
        // never a forum: that mirrors how Scopus-sourced ch/bk pubs are shaped (bookId, no forumId).
        boolean bookKind = isBookSubtype(command.getSubtype());
        String bookId = bookKind ? resolveOrMintBook(command) : null;
        String forumSourceId = bookKind ? "" : resolveForumSourceId(command);
        // The deterministic pub identity keys on the venue; for books the book id plays that role.
        String sourceRecordId = buildSourceRecordId(command, bookKind ? bookId : forumSourceId);
        String sourceRecordSuffix = sourceRecordSuffix(sourceRecordId);
        String eid = UserDefinedWizardOnboardingContract.SOURCE + ":EID:" + sourceRecordSuffix;
        String batchId = "wizard-publication-" + sourceRecordSuffix + "-" + System.currentTimeMillis();
        String correlationId = buildCorrelationId(sourceRecordId, submitter);

        Map<String, Object> payload = buildCanonicalPayload(command, submitter, eid, forumSourceId, sourceRecordId);
        if (bookId != null) {
            payload.put("book_id", bookId);
        }
        ScopusImportEventIngestionService.EventIngestionOutcome outcome = importEventIngestionService.ingest(
                ScopusImportEntityType.PUBLICATION,
                UserDefinedWizardOnboardingContract.SOURCE,
                sourceRecordId,
                batchId,
                correlationId,
                PAYLOAD_FORMAT_JSON_OBJECT,
                payload
        );
        if (outcome.error()) {
            throw new IllegalStateException("Wizard publication ingestion failed: " + outcome.message());
        }

        canonicalMaterializationService.rebuildFactsAndViews("wizard-publication-submit", batchId);
        confirmSubmitterAuthorship(submitter, eid);
        return new SubmissionResult(outcome.imported(), sourceRecordId, eid, forumSourceId);
    }

    /**
     * H99 item 7: the submitter just declared their own publication — write the CONFIRMED authorship
     * decision for them at submit time. Without it the pub scored nothing until the researcher found the
     * separate confirm control (scoring reads only decision-confirmed publications), which is a trap.
     * Best-effort: a failure here must not roll back an already-materialized submission.
     */
    private void confirmSubmitterAuthorship(User submitter, String eid) {
        if (submitter == null || isBlank(submitter.getEmail())) {
            return;
        }
        try {
            scholardexPublicationFactRepository.findByEid(eid).ifPresentOrElse(
                    publication -> publicationAuthorshipDecisionService.upsertDecision(
                            submitter.getEmail(), publication.getId(),
                            ro.uvt.pokedex.core.model.scopus.canonical.PublicationAuthorshipDecision.Status.CONFIRMED,
                            "wizard-self-submission"),
                    () -> log.warn("Wizard submit: canonical publication not found by eid {} — authorship not auto-confirmed", eid));
        } catch (Exception e) {
            log.warn("Wizard submit: authorship auto-confirm failed for eid {}: {}", eid, e.getMessage());
        }
    }

    /**
     * Resolve the book entity for a bk/ch submission: a selected {@code book_facts} id must exist; else a
     * draft (title + publisher, ISBN optional) mints a deterministic USER_DEFINED book row. Idempotent —
     * resubmitting the same book lands on the same entity.
     */
    private String resolveOrMintBook(WizardPublicationCommand command) {
        String selectedId = trim(command.getBookId());
        if (!isBlank(selectedId)) {
            return bookFactRepository.findById(selectedId)
                    .map(ro.uvt.pokedex.core.model.scopus.canonical.ScholardexBookFact::getId)
                    .orElseThrow(() -> new IllegalArgumentException("Selected book not found: " + selectedId));
        }
        String title = trim(command.getWizardBookTitle());
        String publisher = trim(command.getWizardBookPublisher());
        String isbn = trim(command.getWizardBookIsbn());
        String id = UserDefinedWizardOnboardingContract.deterministicBookId(title, isbn, publisher);
        var existing = bookFactRepository.findById(id);
        if (existing.isPresent()) {
            return id;
        }
        var book = new ro.uvt.pokedex.core.model.scopus.canonical.ScholardexBookFact();
        Instant now = Instant.now();
        book.setId(id);
        book.setTitle(title);
        book.setPublisher(publisher);
        String isbnKey = isbn.replaceAll("[^0-9Xx]", "");
        book.setPrintIsbn(isBlank(isbnKey) ? null : isbnKey);
        book.setPublicationYear(extractYear(command.getCoverDate()).orElse(null));
        book.setSource(UserDefinedWizardOnboardingContract.SOURCE);
        book.setAsOf(now.toString());
        book.setCreatedAt(now);
        book.setUpdatedAt(now);
        bookFactRepository.save(book);
        return id;
    }

    // Optional, not a nullable Integer: the security-validation guardrail rejects bare null
    // returns anywhere downstream of resolveForumId in this file (it scans the raw text).
    private Optional<Integer> extractYear(String coverDate) {
        String normalized = normalizeDate(coverDate);
        if (normalized.length() < 4) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(normalized.substring(0, 4)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private Map<String, Object> buildCanonicalPayload(
            WizardPublicationCommand command,
            User submitter,
            String eid,
            String forumSourceId,
            String sourceRecordId
    ) {
        List<String> authorIds = command.getAuthorIds() == null ? List.of() : command.getAuthorIds();
        List<ScholardexAuthorView> authors = scholardexProjectionReadService.findAuthorsByIdIn(authorIds);
        Map<String, ScholardexAuthorView> authorsById = authors.stream()
                .collect(Collectors.toMap(ScholardexAuthorView::getId, a -> a, (left, right) -> left, LinkedHashMap::new));

        List<String> orderedAuthorNames = authorIds.stream()
                .map(authorsById::get)
                .map(a -> a == null ? "" : trim(a.getName()))
                .toList();
        List<String> orderedAuthorAfids = authorIds.stream()
                .map(authorsById::get)
                .map(this::authorAfidsDashSeparated)
                .toList();

        Set<String> affiliationIds = new LinkedHashSet<>();
        for (ScholardexAuthorView author : authorsById.values()) {
            if (author == null || author.getAffiliations() == null) {
                continue;
            }
            for (ScholardexAffiliationView affiliation : author.getAffiliations()) {
                if (affiliation != null && !isBlank(affiliation.getAfid())) {
                    affiliationIds.add(trim(affiliation.getAfid()));
                }
            }
        }

        List<ScholardexAffiliationView> affiliations = affiliationIds.stream()
                .map(scholardexProjectionReadService::findAffiliationById)
                .flatMap(Optional::stream)
                .toList();

        String subtypeDescription = trim(command.getSubtypeDescription());
        String subtype = normalizeSubtype(command.getSubtype(), subtypeDescription);
        String coverDate = normalizeDate(command.getCoverDate());
        String creator = trim(command.getCreator());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eid", eid);
        payload.put("doi", normalizeDoi(command.getDoi()));
        payload.put("title", trim(command.getTitle()));
        payload.put("subtype", subtype);
        payload.put("subtypeDescription", subtypeDescription);
        payload.put("creator", creator);
        // H99 item 7: free-text co-authors outside the canonical graph count toward author_count and land
        // in author_names — never minted as authors. The scoring divisor max(N-2,1) needs the bibliographic
        // author count, not the resolved-id count (the H99 item 9 lesson).
        List<String> externalNames = parseExternalAuthorNames(command.getExternalAuthorNames());
        payload.put("author_count", authorIds.size() + externalNames.size());
        payload.put("description", "");
        payload.put("citedby_count", 0);
        payload.put("openaccess", 0);
        payload.put("freetoread", "");
        payload.put("freetoreadLabel", "");
        payload.put("article_number", "");
        payload.put("pageRange", trim(command.getPageRange()));
        payload.put("coverDate", coverDate);
        payload.put("coverDisplayDate", coverDate);
        payload.put("volume", trim(command.getVolume()));
        payload.put("issueIdentifier", trim(command.getIssueIdentifier()));

        payload.put("author_ids", joinSemicolon(authorIds));
        List<String> allAuthorNames = new java.util.ArrayList<>(orderedAuthorNames);
        allAuthorNames.addAll(externalNames);
        payload.put("author_names", joinSemicolon(allAuthorNames));
        payload.put("author_afids", joinSemicolon(orderedAuthorAfids));

        payload.put("afid", joinSemicolon(affiliations.stream().map(ScholardexAffiliationView::getAfid).map(this::trim).toList()));
        payload.put("affilname", joinSemicolon(affiliations.stream().map(ScholardexAffiliationView::getName).map(this::trim).toList()));
        payload.put("affiliation_city", joinSemicolon(affiliations.stream().map(ScholardexAffiliationView::getCity).map(this::trim).toList()));
        payload.put("affiliation_country", joinSemicolon(affiliations.stream().map(ScholardexAffiliationView::getCountry).map(this::trim).toList()));

        payload.put("source_id", forumSourceId);
        payload.put("publicationName", trim(command.getWizardForumPublicationName()));
        payload.put("issn", normalizeIssnOrBlank(command.getWizardForumIssn()));
        payload.put("eIssn", normalizeIssnOrBlank(command.getWizardForumEIssn()));
        payload.put("isbn", trim(command.getWizardForumIsbn()));
        payload.put("aggregationType", trim(command.getWizardForumAggregationType()));
        payload.put("publisher", trim(command.getWizardForumPublisher()));

        payload.put("fund_acr", "");
        payload.put("fund_no", "");
        payload.put("fund_sponsor", "");
        payload.put("approved", 0);

        payload.put("wizardSubmitterEmail", submitter == null ? "" : trim(submitter.getEmail()));
        payload.put("wizardSubmitterResearcherId", submitter == null ? "" : trim(submitter.getEmail()));
        payload.put("wizardSourceRecordId", sourceRecordId);
        payload.put("wizardSubmittedAt", Instant.now().toString());
        return payload;
    }

    private String resolveForumSourceId(WizardPublicationCommand command) {
        String forumId = trim(command.getForum());
        if (!isBlank(forumId) && scholardexProjectionReadService.findForumById(forumId).isPresent()) {
            return forumId;
        }
        ScholardexForumView draft = new ScholardexForumView();
        draft.setPublicationName(trim(command.getWizardForumPublicationName()));
        draft.setIssn(normalizeIssnOrBlank(command.getWizardForumIssn()));
        draft.setEIssn(normalizeIssnOrBlank(command.getWizardForumEIssn()));
        draft.setAggregationType(trim(command.getWizardForumAggregationType()));
        return generateForumSourceId(draft);
    }

    String generateForumSourceId(ScholardexForumView forum) {
        return UserDefinedWizardOnboardingContract.deterministicForumSourceRecordId(
                forum == null ? null : forum.getPublicationName(),
                forum == null ? null : forum.getIssn(),
                forum == null ? null : forum.getEIssn(),
                forum == null ? null : forum.getAggregationType()
        );
    }

    String buildSourceRecordId(WizardPublicationCommand command, String forumSourceId) {
        return UserDefinedWizardOnboardingContract.deterministicPublicationSourceRecordId(
                command.getDoi(),
                command.getTitle(),
                command.getCoverDate(),
                command.getCreator(),
                forumSourceId
        );
    }

    private void validateCommand(WizardPublicationCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Missing publication payload.");
        }
        if (isBlank(command.getTitle())) {
            throw new IllegalArgumentException("Title is required.");
        }
        if (isBlank(command.getCreator())) {
            throw new IllegalArgumentException("Creator is required.");
        }
        if (isBlank(command.getSubtypeDescription())) {
            throw new IllegalArgumentException("Type is required.");
        }
        normalizeDate(command.getCoverDate());
        // H99 item 7: books/chapters are book-entity-venued — no forum involved. A selected book id or a
        // draft (title + publisher) is required instead; everything else keeps the forum requirements.
        if (isBookSubtype(command.getSubtype())) {
            if (isBlank(command.getBookId())
                    && (isBlank(command.getWizardBookTitle()) || isBlank(command.getWizardBookPublisher()))) {
                throw new IllegalArgumentException("A book (or its title and publisher) is required.");
            }
            return;
        }
        if (isBlank(command.getForum())
                && isBlank(command.getWizardForumPublicationName())) {
            throw new IllegalArgumentException("Forum is required.");
        }
        if (isBlank(command.getWizardForumAggregationType())) {
            throw new IllegalArgumentException("Forum type is required.");
        }
    }

    private String buildCorrelationId(String sourceRecordId, User submitter) {
        String submitterEmail = submitter == null ? "" : trim(submitter.getEmail());
        return "wizard|" + submitterEmail + "|" + sourceRecordId;
    }

    private String authorAfidsDashSeparated(ScholardexAuthorView author) {
        if (author == null || author.getAffiliations() == null || author.getAffiliations().isEmpty()) {
            return "";
        }
        return author.getAffiliations().stream()
                .filter(a -> a != null && !isBlank(a.getAfid()))
                .map(ScholardexAffiliationView::getAfid)
                .map(this::trim)
                .collect(Collectors.joining("-"));
    }

    private String joinSemicolon(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream()
                .map(this::trim)
                .filter(v -> !isBlank(v))
                .collect(Collectors.joining(";"));
    }

    /** Free-text external co-author names: one per line or comma-separated, blanks dropped. */
    private List<String> parseExternalAuthorNames(String raw) {
        if (isBlank(raw)) {
            return List.of();
        }
        return Arrays.stream(raw.split("[\\n,;]"))
                .map(this::trim)
                .filter(v -> !isBlank(v))
                .toList();
    }

    private List<String> parseCsvList(String values) {
        if (isBlank(values)) {
            return List.of();
        }
        return Arrays.stream(values.split(","))
                .map(this::trim)
                .filter(v -> !isBlank(v))
                .toList();
    }

    private String normalizeSubtype(String subtype, String subtypeDescription) {
        String normalizedSubtype = trim(subtype).toLowerCase(Locale.ROOT);
        if (!isBlank(normalizedSubtype)) {
            return normalizedSubtype;
        }
        return switch (trim(subtypeDescription).toLowerCase(Locale.ROOT)) {
            case "article" -> "ar";
            case "review" -> "re";
            case "conference paper" -> "cp";
            case "book chapter" -> "ch";
            case "book" -> "bk";
            case "editorial" -> "ed";
            case "letter" -> "le";
            case "note" -> "no";
            case "short survey" -> "sh";
            case "data paper" -> "dp";
            case "erratum" -> "er";
            default -> "ar";
        };
    }

    private String normalizeDate(String rawDate) {
        String value = trim(rawDate);
        if (isBlank(value)) {
            throw new IllegalArgumentException("Cover date is required.");
        }
        try {
            return LocalDate.parse(value).toString();
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Cover date must be ISO format YYYY-MM-DD.");
        }
    }

    private String normalizeDoi(String doi) {
        String value = trim(doi);
        if (isBlank(value)) {
            return "";
        }
        String normalized = value
                .replaceFirst("(?i)^https?://(dx\\.)?doi\\.org/", "")
                .replaceFirst("(?i)^doi:", "")
                .trim()
                .toLowerCase(Locale.ROOT);
        return normalized;
    }

    private String normalizeIssnOrBlank(String raw) {
        String value = trim(raw).replace("-", "").toUpperCase(Locale.ROOT);
        if (isBlank(value)) {
            return "";
        }
        if (value.length() != 8) {
            return "";
        }
        return value.substring(0, 4) + "-" + value.substring(4);
    }

    private void applyForumDraft(WizardPublicationCommand command, ScholardexForumView forum) {
        command.setWizardForumPublicationName(trim(forum.getPublicationName()));
        command.setWizardForumIssn(normalizeIssnOrBlank(forum.getIssn()));
        command.setWizardForumEIssn(normalizeIssnOrBlank(forum.getEIssn()));
        command.setWizardForumIsbn(trim(forum.getIsbn()));
        command.setWizardForumAggregationType(trim(forum.getAggregationType()));
        command.setWizardForumPublisher(trim(forum.getPublisher()));
    }

    private String sourceRecordSuffix(String sourceRecordId) {
        if (isBlank(sourceRecordId)) {
            throw new IllegalStateException("Wizard sourceRecordId must not be blank.");
        }
        int idx = sourceRecordId.lastIndexOf(':');
        return idx >= 0 && idx + 1 < sourceRecordId.length()
                ? sourceRecordId.substring(idx + 1)
                : sourceRecordId;
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public record SubmissionResult(
            boolean imported,
            String sourceRecordId,
            String eid,
            String forumSourceId
    ) {
    }
}
