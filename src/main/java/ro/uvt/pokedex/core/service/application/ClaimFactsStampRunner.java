package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.activities.PublisherClaim;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * H145 — gives every request made before H145 the fingerprint of its record's facts as they are at startup, so that
 * from then on a change of the facts sends it back to the head (a decision is about the record it saw). Idempotent:
 * only requests without facts are touched; a failure is logged, never fatal.
 */
@Component
@RequiredArgsConstructor
@Order(30)
public class ClaimFactsStampRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ClaimFactsStampRunner.class);

    private final ActivityInstanceRepository activityInstanceRepository;

    @Override
    public void run(String... args) {
        try {
            List<ActivityInstance> toSave = new ArrayList<>();
            for (ActivityInstance instance : activityInstanceRepository.findByPublisherClaim_StatusIn(
                    EnumSet.allOf(PublisherClaim.Status.class))) {
                PublisherClaim claim = instance.getPublisherClaim();
                if (claim != null && claim.getFacts() == null) {
                    claim.setFacts(PublisherClaim.factsOf(instance));
                    toSave.add(instance);
                }
            }
            if (!toSave.isEmpty()) {
                activityInstanceRepository.saveAll(toSave);
                log.info("Request facts stamped on {} records (H145)", toSave.size());
            }
        } catch (RuntimeException e) {
            log.warn("Request facts not stamped: {}", e.getMessage());
        }
    }
}
