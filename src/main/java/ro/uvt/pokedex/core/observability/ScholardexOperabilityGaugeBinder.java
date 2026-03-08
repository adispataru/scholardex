package ro.uvt.pokedex.core.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexSourceLinkRepository;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;

@Component
public class ScholardexOperabilityGaugeBinder implements MeterBinder {

    private final ScholardexIdentityConflictRepository identityConflictRepository;
    private final ScholardexSourceLinkRepository sourceLinkRepository;

    public ScholardexOperabilityGaugeBinder(
            ScholardexIdentityConflictRepository identityConflictRepository,
            ScholardexSourceLinkRepository sourceLinkRepository
    ) {
        this.identityConflictRepository = identityConflictRepository;
        this.sourceLinkRepository = sourceLinkRepository;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("core.h19.identity_conflicts.open", this,
                        binder -> binder.identityConflictRepository.countByStatus("OPEN"))
                .description("Current open generic identity conflicts")
                .register(registry);

        Gauge.builder("core.h19.source_links.state", this,
                        binder -> binder.sourceLinkRepository.countByLinkState(ScholardexSourceLinkService.STATE_UNMATCHED))
                .tag("state", ScholardexSourceLinkService.STATE_UNMATCHED)
                .description("Current source-link state counts")
                .register(registry);

        Gauge.builder("core.h19.source_links.state", this,
                        binder -> binder.sourceLinkRepository.countByLinkState(ScholardexSourceLinkService.STATE_CONFLICT))
                .tag("state", ScholardexSourceLinkService.STATE_CONFLICT)
                .description("Current source-link state counts")
                .register(registry);

        Gauge.builder("core.h19.source_links.state", this,
                        binder -> binder.sourceLinkRepository.countByLinkState(ScholardexSourceLinkService.STATE_SKIPPED))
                .tag("state", ScholardexSourceLinkService.STATE_SKIPPED)
                .description("Current source-link state counts")
                .register(registry);
    }
}
