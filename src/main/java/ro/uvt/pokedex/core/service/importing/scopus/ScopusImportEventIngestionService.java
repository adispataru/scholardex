package ro.uvt.pokedex.core.service.importing.scopus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEvent;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusImportEventRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class ScopusImportEventIngestionService {

    private final ScopusImportEventRepository repository;
    private final ObjectMapper objectMapper;

    public EventIngestionOutcome ingest(
            ScopusImportEntityType entityType,
            String source,
            String sourceRecordId,
            String batchId,
            String correlationId,
            String payloadFormat,
            Object payloadObject
    ) {
        try {
            String payload = normalizePayload(payloadObject);
            String payloadHash = sha256Hex(payload);

            ScopusImportEvent event = new ScopusImportEvent();
            event.setEntityType(entityType);
            event.setSource(source);
            event.setSourceRecordId(sourceRecordId);
            event.setBatchId(batchId);
            event.setCorrelationId(correlationId);
            event.setPayloadFormat(payloadFormat);
            event.setPayload(payload);
            event.setPayloadHash(payloadHash);
            event.setIngestedAt(Instant.now());
            repository.insert(event);
            return EventIngestionOutcome.imported(event.getId());
        } catch (DuplicateKeyException ignored) {
            return EventIngestionOutcome.skipped();
        } catch (Exception e) {
            return EventIngestionOutcome.error(e.getMessage());
        }
    }

    private String normalizePayload(Object payloadObject) throws JsonProcessingException {
        return objectMapper.writeValueAsString(payloadObject);
    }

    private String sha256Hex(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public record EventIngestionOutcome(boolean imported, boolean error, String message, String eventId) {
        public static EventIngestionOutcome imported(String eventId) {
            return new EventIngestionOutcome(true, false, null, eventId);
        }

        public static EventIngestionOutcome skipped() {
            return new EventIngestionOutcome(false, false, null, null);
        }

        public static EventIngestionOutcome error(String message) {
            return new EventIngestionOutcome(false, true, message, null);
        }
    }
}
