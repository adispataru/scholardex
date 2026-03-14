package ro.uvt.pokedex.core.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexSourceLinkRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.UserDefinedForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.UserDefinedPublicationFactRepository;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScholardexOperabilityGaugeBinderTest {

    @Mock
    private ScholardexIdentityConflictRepository identityConflictRepository;
    @Mock
    private ScholardexSourceLinkRepository sourceLinkRepository;
    @Mock
    private UserDefinedPublicationFactRepository userDefinedPublicationFactRepository;
    @Mock
    private UserDefinedForumFactRepository userDefinedForumFactRepository;

    @Test
    void bindToPublishesUserDefinedOperabilityGauges() {
        when(userDefinedPublicationFactRepository.count()).thenReturn(11L);
        when(userDefinedForumFactRepository.count()).thenReturn(4L);
        when(sourceLinkRepository.countBySourceAndLinkState("USER_DEFINED", ScholardexSourceLinkService.STATE_LINKED)).thenReturn(7L);
        when(sourceLinkRepository.countBySourceAndLinkState("USER_DEFINED", ScholardexSourceLinkService.STATE_UNMATCHED)).thenReturn(2L);
        when(sourceLinkRepository.countBySourceAndLinkState("USER_DEFINED", ScholardexSourceLinkService.STATE_CONFLICT)).thenReturn(3L);
        when(sourceLinkRepository.countBySourceAndLinkState("USER_DEFINED", ScholardexSourceLinkService.STATE_SKIPPED)).thenReturn(1L);
        when(identityConflictRepository.countByIncomingSourceAndStatus("USER_DEFINED", "OPEN")).thenReturn(5L);
        when(identityConflictRepository.countByIncomingSourceAndStatus("USER_DEFINED", "RESOLVED")).thenReturn(6L);
        when(identityConflictRepository.countByIncomingSourceAndStatus("USER_DEFINED", "DISMISSED")).thenReturn(0L);

        ScholardexOperabilityGaugeBinder binder = new ScholardexOperabilityGaugeBinder(
                identityConflictRepository,
                sourceLinkRepository,
                userDefinedPublicationFactRepository,
                userDefinedForumFactRepository
        );
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        binder.bindTo(registry);

        assertEquals(11.0, registry.get("core.h21.user_defined.facts").tag("entity", "PUBLICATION").gauge().value());
        assertEquals(4.0, registry.get("core.h21.user_defined.facts").tag("entity", "FORUM").gauge().value());
        assertEquals(7.0, registry.get("core.h21.user_defined.source_links.state").tag("state", "LINKED").gauge().value());
        assertEquals(2.0, registry.get("core.h21.user_defined.source_links.state").tag("state", "UNMATCHED").gauge().value());
        assertEquals(3.0, registry.get("core.h21.user_defined.source_links.state").tag("state", "CONFLICT").gauge().value());
        assertEquals(1.0, registry.get("core.h21.user_defined.source_links.state").tag("state", "SKIPPED").gauge().value());
        assertEquals(5.0, registry.get("core.h21.user_defined.identity_conflicts.status").tag("status", "OPEN").gauge().value());
        assertEquals(6.0, registry.get("core.h21.user_defined.identity_conflicts.status").tag("status", "RESOLVED").gauge().value());
        assertEquals(0.0, registry.get("core.h21.user_defined.identity_conflicts.status").tag("status", "DISMISSED").gauge().value());
    }
}
