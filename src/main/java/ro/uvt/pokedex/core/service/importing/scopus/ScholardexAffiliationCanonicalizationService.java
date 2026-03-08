package ro.uvt.pokedex.core.service.importing.scopus;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAffiliationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexSourceLinkRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAffiliationFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ScholardexAffiliationCanonicalizationService {

    private static final String LINK_STATE_LINKED = "LINKED";
    private static final String STATUS_OPEN = "OPEN";
    private static final String LINK_REASON_SCOPUS_BRIDGE = "scopus-affiliation-bridge";
    private static final String CONFLICT_SOURCE_ID_COLLISION = "SOURCE_ID_COLLISION";
    private static final Pattern NON_ALNUM_OR_SPACE = Pattern.compile("[^\\p{Alnum}\\s]");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private final ScopusAffiliationFactRepository scopusAffiliationFactRepository;
    private final ScholardexAffiliationFactRepository scholardexAffiliationFactRepository;
    private final ScholardexSourceLinkRepository sourceLinkRepository;
    private final ScholardexIdentityConflictRepository identityConflictRepository;

    public ImportProcessingResult rebuildCanonicalAffiliationFactsFromScopusFacts() {
        ImportProcessingResult result = new ImportProcessingResult(20);
        List<ScopusAffiliationFact> sourceFacts = new ArrayList<>(scopusAffiliationFactRepository.findAll());
        sourceFacts.sort(Comparator.comparing(ScopusAffiliationFact::getAfid, Comparator.nullsLast(String::compareTo)));
        for (ScopusAffiliationFact sourceFact : sourceFacts) {
            result.markProcessed();
            upsertFromScopusFact(sourceFact, result);
        }
        return result;
    }

    public void upsertFromScopusFact(ScopusAffiliationFact sourceFact, ImportProcessingResult result) {
        String sourceRecordId = normalizeBlank(sourceFact == null ? null : sourceFact.getAfid());
        if (sourceFact == null || sourceRecordId == null) {
            if (result != null) {
                result.markSkipped("missing scopus affiliation id");
            }
            return;
        }

        Optional<ScholardexSourceLink> existingSourceLink = sourceLinkRepository
                .findByEntityTypeAndSourceAndSourceRecordId(ScholardexEntityType.AFFILIATION, sourceFact.getSource(), sourceRecordId);
        Optional<ScholardexAffiliationFact> existingBySource = scholardexAffiliationFactRepository.findByScopusAffiliationIdsContains(sourceRecordId);
        String canonicalId = existingSourceLink.map(ScholardexSourceLink::getCanonicalEntityId)
                .or(() -> existingBySource.map(ScholardexAffiliationFact::getId))
                .orElseGet(() -> buildCanonicalAffiliationId(sourceRecordId, sourceFact.getName(), sourceFact.getCity(), sourceFact.getCountry()));

        if (existingSourceLink.isPresent() && existingSourceLink.get().getCanonicalEntityId() != null
                && !existingSourceLink.get().getCanonicalEntityId().equals(canonicalId)) {
            saveConflict(sourceFact, sourceRecordId, CONFLICT_SOURCE_ID_COLLISION, List.of(existingSourceLink.get().getCanonicalEntityId(), canonicalId));
            if (result != null) {
                result.markSkipped("affiliation-source-id-collision:" + sourceRecordId);
            }
            return;
        }

        ScholardexAffiliationFact target = scholardexAffiliationFactRepository.findById(canonicalId).orElseGet(ScholardexAffiliationFact::new);
        boolean created = target.getId() == null;
        Instant now = Instant.now();
        if (target.getCreatedAt() == null) {
            target.setCreatedAt(now);
        }
        target.setId(canonicalId);
        addUnique(target.getScopusAffiliationIds(), sourceRecordId);
        target.setName(sourceFact.getName());
        target.setNameNormalized(normalizeName(sourceFact.getName()));
        target.setCity(sourceFact.getCity());
        target.setCountry(sourceFact.getCountry());
        addUnique(target.getAliases(), normalizeAlias(sourceFact.getName(), sourceFact.getCity(), sourceFact.getCountry()));
        target.setSourceEventId(sourceFact.getSourceEventId());
        target.setSource(sourceFact.getSource());
        target.setSourceRecordId(sourceRecordId);
        target.setSourceBatchId(sourceFact.getSourceBatchId());
        target.setSourceCorrelationId(sourceFact.getSourceCorrelationId());
        target.setUpdatedAt(now);
        scholardexAffiliationFactRepository.save(target);
        upsertSourceLink(sourceFact, sourceRecordId, target.getId(), now);

        if (result != null) {
            if (created) {
                result.markImported();
            } else {
                result.markUpdated();
            }
        }
    }

    public String buildCanonicalAffiliationId(String scopusAffiliationId, String name, String city, String country) {
        String material;
        if (!isBlank(scopusAffiliationId)) {
            material = "scopus|" + normalizeToken(scopusAffiliationId);
        } else {
            material = "name|" + normalizeToken(normalizeName(name))
                    + "|city|" + normalizeToken(city)
                    + "|country|" + normalizeToken(country);
        }
        return "saff_" + shortHash(material);
    }

    private void upsertSourceLink(ScopusAffiliationFact sourceFact, String sourceRecordId, String canonicalId, Instant now) {
        if (isBlank(sourceFact.getSource())) {
            return;
        }
        ScholardexSourceLink sourceLink = sourceLinkRepository
                .findByEntityTypeAndSourceAndSourceRecordId(ScholardexEntityType.AFFILIATION, sourceFact.getSource(), sourceRecordId)
                .orElseGet(ScholardexSourceLink::new);
        sourceLink.setEntityType(ScholardexEntityType.AFFILIATION);
        sourceLink.setSource(sourceFact.getSource());
        sourceLink.setSourceRecordId(sourceRecordId);
        sourceLink.setCanonicalEntityId(canonicalId);
        sourceLink.setLinkState(LINK_STATE_LINKED);
        sourceLink.setLinkReason(LINK_REASON_SCOPUS_BRIDGE);
        sourceLink.setSourceEventId(sourceFact.getSourceEventId());
        sourceLink.setSourceBatchId(sourceFact.getSourceBatchId());
        sourceLink.setSourceCorrelationId(sourceFact.getSourceCorrelationId());
        if (sourceLink.getLinkedAt() == null) {
            sourceLink.setLinkedAt(now);
        }
        sourceLink.setUpdatedAt(now);
        sourceLinkRepository.save(sourceLink);
    }

    private void saveConflict(ScopusAffiliationFact sourceFact, String sourceRecordId, String reason, List<String> candidates) {
        ScholardexIdentityConflict conflict = identityConflictRepository
                .findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                        ScholardexEntityType.AFFILIATION,
                        sourceFact.getSource(),
                        sourceRecordId,
                        reason,
                        STATUS_OPEN
                )
                .orElseGet(ScholardexIdentityConflict::new);
        conflict.setEntityType(ScholardexEntityType.AFFILIATION);
        conflict.setIncomingSource(sourceFact.getSource());
        conflict.setIncomingSourceRecordId(sourceRecordId);
        conflict.setReasonCode(reason);
        conflict.setStatus(STATUS_OPEN);
        conflict.setCandidateCanonicalIds(candidates == null ? List.of() : new ArrayList<>(candidates));
        conflict.setSourceEventId(sourceFact.getSourceEventId());
        conflict.setSourceBatchId(sourceFact.getSourceBatchId());
        conflict.setSourceCorrelationId(sourceFact.getSourceCorrelationId());
        if (conflict.getDetectedAt() == null) {
            conflict.setDetectedAt(Instant.now());
        }
        identityConflictRepository.save(conflict);
    }

    private String normalizeName(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFKD);
        normalized = COMBINING_MARKS.matcher(normalized).replaceAll("");
        normalized = NON_ALNUM_OR_SPACE.matcher(normalized).replaceAll(" ");
        normalized = MULTI_SPACE.matcher(normalized).replaceAll(" ").trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizeAlias(String name, String city, String country) {
        String alias = normalizeToken(normalizeName(name)) + "|" + normalizeToken(city) + "|" + normalizeToken(country);
        return alias.equals("||") ? null : alias;
    }

    private void addUnique(List<String> values, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!values.contains(value)) {
            values.add(value);
        }
    }

    private String normalizeBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeToken(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? "" : normalized;
    }

    private String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
