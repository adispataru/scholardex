package ro.uvt.pokedex.core.service.issn;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.issn.IssnVerification;
import ro.uvt.pokedex.core.model.issn.IssnVerification.Status;
import ro.uvt.pokedex.core.repository.issn.IssnVerificationRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Verifies ISSNs of journals outside our corpus against the international register and remembers the answer.
 * Policy (2026-09-18): a valid check digit the register could not confirm yet is UNVERIFIED — it scores as a
 * category D journal right away, shows a marker, and is retried nightly; a clear "no such ISSN" is NOT_FOUND.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IssnVerificationService implements IssnRegistrySupport.Registry {

    private final IssnVerificationRepository repository;
    private final IssnPortalClient portalClient;
    private final ro.uvt.pokedex.core.repository.ActivityInstanceRepository activityInstanceRepository;

    @PostConstruct
    void registerForScorers() {
        IssnRegistrySupport.register(this);
    }

    @Override
    public Optional<IssnVerification> find(String normalizedIssn) {
        return repository.findById(normalizedIssn);
    }

    /**
     * Ask the register about one ISSN (already normalized and check-digit valid) and store the answer. A VERIFIED
     * record is final and returned without a new call.
     */
    public IssnVerification verify(String normalizedIssn) {
        IssnVerification record = repository.findById(normalizedIssn).orElseGet(() -> {
            IssnVerification fresh = new IssnVerification();
            fresh.setIssn(normalizedIssn);
            fresh.setFirstAskedAt(Instant.now());
            return fresh;
        });
        if (record.getStatus() == Status.VERIFIED) {
            return record;
        }
        record.setAttempts(record.getAttempts() + 1);
        record.setLastCheckedAt(Instant.now());
        try {
            IssnPortalClient.Lookup lookup = portalClient.lookup(normalizedIssn);
            record.setStatus(lookup.exists() ? Status.VERIFIED : Status.NOT_FOUND);
            record.setKeyTitle(lookup.keyTitle().orElse(null));
        } catch (IssnPortalClient.IssnPortalUnavailableException e) {
            log.warn("ISSN {} could not be verified (attempt {}): {}", normalizedIssn, record.getAttempts(), e.getMessage());
            if (record.getStatus() == null) {
                record.setStatus(Status.UNVERIFIED);
            }
        }
        return repository.save(record);
    }

    /** Nightly: ask again about everything the register could not be asked about. */
    @Scheduled(cron = "${issn.portal.retry-cron:0 40 3 * * *}")
    public void retryUnverified() {
        enqueueFromActivities();
        List<IssnVerification> pending = repository.findByStatus(Status.UNVERIFIED);
        if (pending.isEmpty()) {
            return;
        }
        int verified = 0;
        int notFound = 0;
        for (IssnVerification record : pending) {
            Status after = verify(record.getIssn()).getStatus();
            if (after == Status.VERIFIED) verified++;
            if (after == Status.NOT_FOUND) notFound++;
        }
        log.info("ISSN verification retry: pending={} nowVerified={} nowNotFound={}", pending.size(), verified, notFound);
    }

    /**
     * Entries saved before the save-time check existed (or while it was switched off) never produced a record, so
     * nothing would ever verify them. Every valid ISSN on an activity entry that the register was never asked about
     * becomes UNVERIFIED here and is picked up by the retry that follows.
     *
     * @return how many ISSNs were queued
     */
    int enqueueFromActivities() {
        int queued = 0;
        for (ro.uvt.pokedex.core.model.activities.ActivityInstance instance : activityInstanceRepository.findAll()) {
            if (instance.getReferenceFields() == null) {
                continue;
            }
            for (ro.uvt.pokedex.core.model.activities.Activity.ReferenceField key : List.of(
                    ro.uvt.pokedex.core.model.activities.Activity.ReferenceField.FORUM_ISSN,
                    ro.uvt.pokedex.core.model.activities.Activity.ReferenceField.FORUM_EISSN)) {
                String raw = instance.getReferenceFields().get(key);
                if (!IssnSupport.isValid(raw)) {
                    continue;
                }
                String issn = IssnSupport.normalize(raw);
                if (!repository.existsById(issn)) {
                    IssnVerification record = new IssnVerification();
                    record.setIssn(issn);
                    record.setStatus(Status.UNVERIFIED);
                    record.setFirstAskedAt(Instant.now());
                    repository.save(record);
                    queued++;
                }
            }
        }
        if (queued > 0) {
            log.info("ISSN verification: queued {} ISSN(s) found on existing activity entries", queued);
        }
        return queued;
    }
}
