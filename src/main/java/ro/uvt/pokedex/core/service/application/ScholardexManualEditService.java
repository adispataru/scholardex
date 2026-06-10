package ro.uvt.pokedex.core.service.application;

import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * User manual edits of canonical Scholardex forum/author/affiliation facts (H54.5c).
 *
 * <p>Relocated out of {@code ScholardexProjectionReadService} (a read service should not write) so
 * the writes flow through the sanctioned per-entity writers' {@code applyManualEdit} surface. Behavior
 * is preserved exactly: each edit resolves the canonical id from source links, loads-or-creates the
 * canonical fact, sets the edited content, and delegates persistence + source-link to the writer
 * (which stamps source/sourceRecordId/updatedAt but preserves batch/correlation).
 */
@Service
public class ScholardexManualEditService {

    private static final String SOURCE_FORUM = "MANUAL_FORUM_EDIT";
    private static final String SOURCE_AUTHOR = "MANUAL_AUTHOR_EDIT";
    private static final String SOURCE_AFFILIATION = "MANUAL_AFFILIATION_EDIT";

    private final ScholardexForumFactRepository forumFactRepository;
    private final ScholardexAuthorFactRepository authorFactRepository;
    private final ScholardexAffiliationFactRepository affiliationFactRepository;
    private final ScholardexForumWriter forumWriter;
    private final ScholardexAuthorWriter authorWriter;
    private final ScholardexAffiliationWriter affiliationWriter;
    private final ScholardexEdgeWriterService edgeWriterService;
    private final ScholardexCanonicalIdResolver canonicalIdResolver;

    public ScholardexManualEditService(
            ScholardexForumFactRepository forumFactRepository,
            ScholardexAuthorFactRepository authorFactRepository,
            ScholardexAffiliationFactRepository affiliationFactRepository,
            ScholardexForumWriter forumWriter,
            ScholardexAuthorWriter authorWriter,
            ScholardexAffiliationWriter affiliationWriter,
            ScholardexEdgeWriterService edgeWriterService,
            ScholardexCanonicalIdResolver canonicalIdResolver) {
        this.forumFactRepository = forumFactRepository;
        this.authorFactRepository = authorFactRepository;
        this.affiliationFactRepository = affiliationFactRepository;
        this.forumWriter = forumWriter;
        this.authorWriter = authorWriter;
        this.affiliationWriter = affiliationWriter;
        this.edgeWriterService = edgeWriterService;
        this.canonicalIdResolver = canonicalIdResolver;
    }

    public ScholardexForumView saveForum(ScholardexForumView forum) {
        String sourceRecordId = normalizeBlank(forum.getId());
        String canonicalId = canonicalIdResolver.resolveCanonicalId(ScholardexEntityType.FORUM, sourceRecordId)
                .orElse(sourceRecordId == null
                        ? "sforum_manual_" + Integer.toHexString(Objects.hash(forum.getPublicationName(), forum.getIssn(), forum.getEIssn(), forum.getAggregationType()))
                        : sourceRecordId);
        Instant now = Instant.now();
        ScholardexForumFact canonicalFact = forumFactRepository.findById(canonicalId).orElseGet(ScholardexForumFact::new);
        if (canonicalFact.getCreatedAt() == null) {
            canonicalFact.setCreatedAt(now);
        }
        canonicalFact.setId(canonicalId);
        canonicalFact.setName(forum.getPublicationName());
        canonicalFact.setNameNormalized(normalizeName(forum.getPublicationName()));
        canonicalFact.setIssn(normalizeBlank(forum.getIssn()));
        canonicalFact.setEIssn(normalizeBlank(forum.getEIssn()));
        canonicalFact.setAggregationType(normalizeBlank(forum.getAggregationType()));
        canonicalFact.setAggregationTypeNormalized(normalizeName(forum.getAggregationType()));
        forumWriter.applyManualEdit(canonicalFact, SOURCE_FORUM, sourceRecordId, "manual-forum-save");

        ScholardexForumView out = new ScholardexForumView();
        out.setId(canonicalId);
        out.setPublicationName(forum.getPublicationName());
        out.setIssn(forum.getIssn());
        out.setEIssn(forum.getEIssn());
        out.setAggregationType(forum.getAggregationType());
        return out;
    }

    public ScholardexAuthorView saveAuthor(ScholardexAuthorView author) {
        String sourceRecordId = normalizeBlank(author.getId());
        String canonicalId = canonicalIdResolver.resolveCanonicalId(ScholardexEntityType.AUTHOR, sourceRecordId)
                .orElse(sourceRecordId == null ? "sauth_manual_" + Integer.toHexString(Objects.hash(author.getName())) : sourceRecordId);
        List<String> affiliationSourceIds = author.getAffiliations() == null
                ? List.of()
                : author.getAffiliations().stream().map(ScholardexAffiliationView::getAfid).filter(Objects::nonNull).toList();
        List<String> affiliationIds = canonicalIdResolver.resolveCanonicalIds(ScholardexEntityType.AFFILIATION, affiliationSourceIds);

        ScholardexAuthorFact canonicalFact = authorFactRepository.findById(canonicalId).orElseGet(ScholardexAuthorFact::new);
        Instant now = Instant.now();
        if (canonicalFact.getCreatedAt() == null) {
            canonicalFact.setCreatedAt(now);
        }
        canonicalFact.setId(canonicalId);
        canonicalFact.setDisplayName(author.getName());
        canonicalFact.setNameNormalized(normalizeName(author.getName()));
        canonicalFact.setAffiliationIds(new ArrayList<>(affiliationIds));
        authorWriter.applyManualEdit(canonicalFact, SOURCE_AUTHOR, sourceRecordId, "manual-author-save");

        for (String affiliationId : affiliationIds) {
            edgeWriterService.upsertAuthorAffiliationEdge(new ScholardexEdgeWriterService.EdgeWriteCommand(
                    canonicalId,
                    affiliationId,
                    SOURCE_AUTHOR,
                    canonicalId + "::affiliation::" + affiliationId,
                    null,
                    null,
                    null,
                    ScholardexSourceLinkService.STATE_LINKED,
                    "manual-author-save",
                    false
            ));
        }

        ScholardexAuthorView out = new ScholardexAuthorView();
        out.setId(canonicalId);
        out.setName(author.getName());
        out.setAlternativeNames(author.getAlternativeNames() == null ? List.of() : new ArrayList<>(author.getAlternativeNames()));
        out.setAffiliations(affiliationIds.stream().map(id -> {
            ScholardexAffiliationView affiliation = new ScholardexAffiliationView();
            affiliation.setAfid(id);
            return affiliation;
        }).toList());
        return out;
    }

    public ScholardexAffiliationView saveAffiliation(ScholardexAffiliationView affiliation) {
        String sourceRecordId = normalizeBlank(affiliation.getAfid());
        String canonicalId = canonicalIdResolver.resolveCanonicalId(ScholardexEntityType.AFFILIATION, sourceRecordId)
                .orElse(sourceRecordId == null ? "saff_manual_" + Integer.toHexString(Objects.hash(affiliation.getName(), affiliation.getCity(), affiliation.getCountry())) : sourceRecordId);
        Instant now = Instant.now();

        ScholardexAffiliationFact canonicalFact = affiliationFactRepository.findById(canonicalId).orElseGet(ScholardexAffiliationFact::new);
        if (canonicalFact.getCreatedAt() == null) {
            canonicalFact.setCreatedAt(now);
        }
        canonicalFact.setId(canonicalId);
        canonicalFact.setName(affiliation.getName());
        canonicalFact.setNameNormalized(normalizeName(affiliation.getName()));
        canonicalFact.setCity(affiliation.getCity());
        canonicalFact.setCountry(affiliation.getCountry());
        affiliationWriter.applyManualEdit(canonicalFact, SOURCE_AFFILIATION, sourceRecordId, "manual-affiliation-save");

        ScholardexAffiliationView out = new ScholardexAffiliationView();
        out.setAfid(canonicalId);
        out.setName(affiliation.getName());
        out.setCity(affiliation.getCity());
        out.setCountry(affiliation.getCountry());
        return out;
    }

    private static String normalizeBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalizeName(String value) {
        String normalized = normalizeBlank(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }
}
