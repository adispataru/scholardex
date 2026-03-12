package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Affiliation;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEntityType;
import ro.uvt.pokedex.core.service.application.model.WizardPublicationCommand;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusCanonicalMaterializationService;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusImportEventIngestionService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

    static final String SOURCE_USER_PUBLICATION_WIZARD = "USER_PUBLICATION_WIZARD";
    static final String PAYLOAD_FORMAT_JSON_OBJECT = "json-object";
    private static final int MANUAL_HASH_LEN = 24;

    private final ScholardexProjectionReadService scholardexProjectionReadService;
    private final ScopusImportEventIngestionService importEventIngestionService;
    private final ScopusCanonicalMaterializationService canonicalMaterializationService;

    public List<Forum> listForums() {
        return scholardexProjectionReadService.findAllForums();
    }

    public Optional<String> resolveForumId(Forum newForum, String selectedId) {
        if (selectedId != null && !selectedId.isEmpty()) {
            Forum existingForum = scholardexProjectionReadService.findForumById(selectedId).orElse(null);
            if (existingForum != null) {
                return Optional.of(existingForum.getId());
            }
        } else if (newForum != null && !isBlank(newForum.getPublicationName())) {
            return Optional.of(generateForumSourceId(newForum));
        }
        return Optional.empty();
    }

    public List<Author> findAuthorsForAffiliation(String affiliationId) {
        if (isBlank(affiliationId)) {
            return Collections.emptyList();
        }
        return scholardexProjectionReadService.findAuthorsByAffiliationId(affiliationId);
    }

    public WizardPublicationCommand buildPublicationDraft(
            String forumId,
            String authors,
            String creator,
            Forum wizardForumDraft
    ) {
        WizardPublicationCommand command = new WizardPublicationCommand();
        command.setForum(forumId);
        command.setCreator(creator);
        command.setAuthorIdsCsv(authors);
        command.setAuthorIds(parseCsvList(authors));

        if (wizardForumDraft != null && !isBlank(wizardForumDraft.getPublicationName())) {
            command.setWizardForumPublicationName(trim(wizardForumDraft.getPublicationName()));
            command.setWizardForumIssn(normalizeIssnOrBlank(wizardForumDraft.getIssn()));
            command.setWizardForumEIssn(normalizeIssnOrBlank(wizardForumDraft.getEIssn()));
            command.setWizardForumIsbn(trim(wizardForumDraft.getIsbn()));
            command.setWizardForumAggregationType(trim(wizardForumDraft.getAggregationType()));
            command.setWizardForumPublisher(trim(wizardForumDraft.getPublisher()));
            return command;
        }

        scholardexProjectionReadService.findForumById(forumId).ifPresent(forum -> {
            command.setWizardForumPublicationName(trim(forum.getPublicationName()));
            command.setWizardForumIssn(normalizeIssnOrBlank(forum.getIssn()));
            command.setWizardForumEIssn(normalizeIssnOrBlank(forum.getEIssn()));
            command.setWizardForumIsbn(trim(forum.getIsbn()));
            command.setWizardForumAggregationType(trim(forum.getAggregationType()));
            command.setWizardForumPublisher(trim(forum.getPublisher()));
        });
        return command;
    }

    public SubmissionResult submitPublication(WizardPublicationCommand command, User submitter) {
        validateCommand(command);

        List<String> authorIds = parseCsvList(command.getAuthorIdsCsv());
        if (!authorIds.isEmpty()) {
            command.setAuthorIds(authorIds);
        }

        String forumSourceId = resolveForumSourceId(command);
        String sourceRecordId = buildSourceRecordId(command, forumSourceId);
        String eid = "MANUAL:EID:" + sourceRecordId.substring("MANUAL:".length());
        String batchId = "wizard-publication-" + sourceRecordId.substring("MANUAL:".length()) + "-" + System.currentTimeMillis();
        String correlationId = buildCorrelationId(sourceRecordId, submitter);

        Map<String, Object> payload = buildCanonicalPayload(command, submitter, eid, forumSourceId, sourceRecordId);
        ScopusImportEventIngestionService.EventIngestionOutcome outcome = importEventIngestionService.ingest(
                ScopusImportEntityType.PUBLICATION,
                SOURCE_USER_PUBLICATION_WIZARD,
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
        return new SubmissionResult(outcome.imported(), sourceRecordId, eid, forumSourceId);
    }

    private Map<String, Object> buildCanonicalPayload(
            WizardPublicationCommand command,
            User submitter,
            String eid,
            String forumSourceId,
            String sourceRecordId
    ) {
        List<String> authorIds = command.getAuthorIds() == null ? List.of() : command.getAuthorIds();
        List<Author> authors = scholardexProjectionReadService.findAuthorsByIdIn(authorIds);
        Map<String, Author> authorsById = authors.stream()
                .collect(Collectors.toMap(Author::getId, a -> a, (left, right) -> left, LinkedHashMap::new));

        List<String> orderedAuthorNames = authorIds.stream()
                .map(authorsById::get)
                .map(a -> a == null ? "" : trim(a.getName()))
                .toList();
        List<String> orderedAuthorAfids = authorIds.stream()
                .map(authorsById::get)
                .map(this::authorAfidsDashSeparated)
                .toList();

        Set<String> affiliationIds = new LinkedHashSet<>();
        for (Author author : authorsById.values()) {
            if (author == null || author.getAffiliations() == null) {
                continue;
            }
            for (Affiliation affiliation : author.getAffiliations()) {
                if (affiliation != null && !isBlank(affiliation.getAfid())) {
                    affiliationIds.add(trim(affiliation.getAfid()));
                }
            }
        }

        List<Affiliation> affiliations = affiliationIds.stream()
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
        payload.put("author_count", authorIds.size());
        payload.put("description", "");
        payload.put("citedby_count", 0);
        payload.put("openaccess", 0);
        payload.put("freetoread", "");
        payload.put("freetoreadLabel", "");
        payload.put("article_number", "");
        payload.put("pageRange", "");
        payload.put("coverDate", coverDate);
        payload.put("coverDisplayDate", coverDate);
        payload.put("volume", trim(command.getVolume()));
        payload.put("issueIdentifier", trim(command.getIssueIdentifier()));

        payload.put("author_ids", joinSemicolon(authorIds));
        payload.put("author_names", joinSemicolon(orderedAuthorNames));
        payload.put("author_afids", joinSemicolon(orderedAuthorAfids));

        payload.put("afid", joinSemicolon(affiliations.stream().map(Affiliation::getAfid).map(this::trim).toList()));
        payload.put("affilname", joinSemicolon(affiliations.stream().map(Affiliation::getName).map(this::trim).toList()));
        payload.put("affiliation_city", joinSemicolon(affiliations.stream().map(Affiliation::getCity).map(this::trim).toList()));
        payload.put("affiliation_country", joinSemicolon(affiliations.stream().map(Affiliation::getCountry).map(this::trim).toList()));

        payload.put("source_id", forumSourceId);
        payload.put("publicationName", trim(command.getWizardForumPublicationName()));
        payload.put("issn", normalizeIssnOrBlank(command.getWizardForumIssn()));
        payload.put("eIssn", normalizeIssnOrBlank(command.getWizardForumEIssn()));
        payload.put("isbn", trim(command.getWizardForumIsbn()));
        payload.put("aggregationType", trim(command.getWizardForumAggregationType()));

        payload.put("fund_acr", "");
        payload.put("fund_no", "");
        payload.put("fund_sponsor", "");
        payload.put("approved", 0);

        payload.put("wizardSubmitterEmail", submitter == null ? "" : trim(submitter.getEmail()));
        payload.put("wizardSubmitterResearcherId", submitter == null ? "" : trim(submitter.getResearcherId()));
        payload.put("wizardSourceRecordId", sourceRecordId);
        payload.put("wizardSubmittedAt", Instant.now().toString());
        return payload;
    }

    private String resolveForumSourceId(WizardPublicationCommand command) {
        String forumId = trim(command.getForum());
        if (!isBlank(forumId) && scholardexProjectionReadService.findForumById(forumId).isPresent()) {
            return forumId;
        }
        Forum draft = new Forum();
        draft.setPublicationName(command.getWizardForumPublicationName());
        draft.setIssn(command.getWizardForumIssn());
        draft.setEIssn(command.getWizardForumEIssn());
        draft.setAggregationType(command.getWizardForumAggregationType());
        draft.setIsbn(command.getWizardForumIsbn());
        draft.setPublisher(command.getWizardForumPublisher());
        return generateForumSourceId(draft);
    }

    String generateForumSourceId(Forum forum) {
        String issn = normalizeIssnOrBlank(forum == null ? null : forum.getIssn());
        String eIssn = normalizeIssnOrBlank(forum == null ? null : forum.getEIssn());
        String forumKeyBase;
        if (!isBlank(issn) || !isBlank(eIssn)) {
            forumKeyBase = "issn|" + issn + "|" + eIssn;
        } else {
            forumKeyBase = "name|"
                    + normalizeToken(forum == null ? null : forum.getPublicationName())
                    + "|type|"
                    + normalizeToken(forum == null ? null : forum.getAggregationType());
        }
        return "MANUAL:FORUM:" + shortHash(forumKeyBase);
    }

    String buildSourceRecordId(WizardPublicationCommand command, String forumSourceId) {
        String normalizedDoi = normalizeDoi(command.getDoi());
        String material;
        if (!isBlank(normalizedDoi)) {
            material = "doi|" + normalizedDoi;
        } else {
            material = "title|" + normalizeToken(command.getTitle())
                    + "|date|" + normalizeDate(command.getCoverDate())
                    + "|creator|" + normalizeToken(command.getCreator())
                    + "|forum|" + normalizeToken(forumSourceId);
        }
        return "MANUAL:" + shortHash(material);
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

    private String authorAfidsDashSeparated(Author author) {
        if (author == null || author.getAffiliations() == null || author.getAffiliations().isEmpty()) {
            return "";
        }
        return author.getAffiliations().stream()
                .filter(a -> a != null && !isBlank(a.getAfid()))
                .map(Affiliation::getAfid)
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

    private String normalizeToken(String raw) {
        return trim(raw).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private String shortHash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                out.append(String.format("%02x", b));
            }
            return out.substring(0, MANUAL_HASH_LEN);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
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
