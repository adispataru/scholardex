package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * H145 — opens a pending review for every wizard publication made before verification existed, so that it stops
 * counting until a head decides and shows on the head's page. Idempotent; a failure is logged, never fatal.
 */
@Component
@RequiredArgsConstructor
@Order(31)
public class WizardPublicationReviewRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(WizardPublicationReviewRunner.class);

    private final WizardPublicationReviewService reviews;

    @Override
    public void run(String... args) {
        try {
            int opened = reviews.openMissingReviews();
            if (opened > 0) {
                log.info("Wizard publications awaiting verification: {} reviews opened (H145)", opened);
            }
        } catch (RuntimeException e) {
            log.warn("Wizard publication reviews not opened: {}", e.getMessage());
        }
    }
}
