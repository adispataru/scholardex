package ro.uvt.pokedex.core.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexSourceLinkRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.UserDefinedForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.UserDefinedPublicationFactRepository;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;
import ro.uvt.pokedex.core.service.application.UserDefinedTriageFacade;

@Component
public class ScholardexOperabilityGaugeBinder implements MeterBinder {

    private final ScholardexIdentityConflictRepository identityConflictRepository;
    private final ScholardexSourceLinkRepository sourceLinkRepository;
    private final UserDefinedPublicationFactRepository userDefinedPublicationFactRepository;
    private final UserDefinedForumFactRepository userDefinedForumFactRepository;

    public ScholardexOperabilityGaugeBinder(
            ScholardexIdentityConflictRepository identityConflictRepository,
            ScholardexSourceLinkRepository sourceLinkRepository,
            UserDefinedPublicationFactRepository userDefinedPublicationFactRepository,
            UserDefinedForumFactRepository userDefinedForumFactRepository
    ) {
        this.identityConflictRepository = identityConflictRepository;
        this.sourceLinkRepository = sourceLinkRepository;
        this.userDefinedPublicationFactRepository = userDefinedPublicationFactRepository;
        this.userDefinedForumFactRepository = userDefinedForumFactRepository;
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

        Gauge.builder("core.h21.user_defined.facts", this,
                        binder -> binder.userDefinedPublicationFactRepository.count())
                .tag("entity", "PUBLICATION")
                .description("Current USER_DEFINED source fact counts")
                .register(registry);
        Gauge.builder("core.h21.user_defined.facts", this,
                        binder -> binder.userDefinedForumFactRepository.count())
                .tag("entity", "FORUM")
                .description("Current USER_DEFINED source fact counts")
                .register(registry);

        Gauge.builder("core.h21.user_defined.source_links.state", this,
                        binder -> binder.sourceLinkRepository.countBySourceAndLinkState(
                                UserDefinedTriageFacade.SOURCE, ScholardexSourceLinkService.STATE_LINKED))
                .tag("state", ScholardexSourceLinkService.STATE_LINKED)
                .description("Current USER_DEFINED source-link state counts")
                .register(registry);
        Gauge.builder("core.h21.user_defined.source_links.state", this,
                        binder -> binder.sourceLinkRepository.countBySourceAndLinkState(
                                UserDefinedTriageFacade.SOURCE, ScholardexSourceLinkService.STATE_UNMATCHED))
                .tag("state", ScholardexSourceLinkService.STATE_UNMATCHED)
                .description("Current USER_DEFINED source-link state counts")
                .register(registry);
        Gauge.builder("core.h21.user_defined.source_links.state", this,
                        binder -> binder.sourceLinkRepository.countBySourceAndLinkState(
                                UserDefinedTriageFacade.SOURCE, ScholardexSourceLinkService.STATE_CONFLICT))
                .tag("state", ScholardexSourceLinkService.STATE_CONFLICT)
                .description("Current USER_DEFINED source-link state counts")
                .register(registry);
        Gauge.builder("core.h21.user_defined.source_links.state", this,
                        binder -> binder.sourceLinkRepository.countBySourceAndLinkState(
                                UserDefinedTriageFacade.SOURCE, ScholardexSourceLinkService.STATE_SKIPPED))
                .tag("state", ScholardexSourceLinkService.STATE_SKIPPED)
                .description("Current USER_DEFINED source-link state counts")
                .register(registry);

        Gauge.builder("core.h21.user_defined.identity_conflicts.status", this,
                        binder -> binder.identityConflictRepository.countByIncomingSourceAndStatus(
                                UserDefinedTriageFacade.SOURCE, "OPEN"))
                .tag("status", "OPEN")
                .description("Current USER_DEFINED identity conflict counts")
                .register(registry);
        Gauge.builder("core.h21.user_defined.identity_conflicts.status", this,
                        binder -> binder.identityConflictRepository.countByIncomingSourceAndStatus(
                                UserDefinedTriageFacade.SOURCE, "RESOLVED"))
                .tag("status", "RESOLVED")
                .description("Current USER_DEFINED identity conflict counts")
                .register(registry);
        Gauge.builder("core.h21.user_defined.identity_conflicts.status", this,
                        binder -> binder.identityConflictRepository.countByIncomingSourceAndStatus(
                                UserDefinedTriageFacade.SOURCE, "DISMISSED"))
                .tag("status", "DISMISSED")
                .description("Current USER_DEFINED identity conflict counts")
                .register(registry);
    }
}
