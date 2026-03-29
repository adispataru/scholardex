package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusForumFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WosScholardexOnboardingServiceTest {

    @Mock private NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    @Mock private ScopusForumFactRepository scopusForumFactRepository;
    @Mock private ScholardexForumFactRepository scholardexForumFactRepository;
    @Mock private ScholardexSourceLinkService sourceLinkService;
    @Mock private ScholardexIdentityConflictRepository scholardexIdentityConflictRepository;
    @Mock private ScholardexPublicationFactRepository scholardexPublicationFactRepository;

    @Test
    void runWosOnboardingCreatesCanonicalForumForWosOnlyJournal() {
        WosScholardexOnboardingService service = new WosScholardexOnboardingService(
                namedParameterJdbcTemplate,
                scopusForumFactRepository,
                scholardexForumFactRepository,
                sourceLinkService,
                scholardexIdentityConflictRepository,
                scholardexPublicationFactRepository
        );

        WosRankingView rankingView = new WosRankingView();
        rankingView.setId("wos-j-1");
        rankingView.setName("Journal of Testing");
        rankingView.setIssn("1234567X");

        when(namedParameterJdbcTemplate.query(any(String.class), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(rankingView));
        when(scopusForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexPublicationFactRepository.findAll()).thenReturn(List.of());
        when(sourceLinkService.findByKey(
                ScholardexEntityType.FORUM, "WOS", "wos-j-1")).thenReturn(Optional.empty());
        when(scholardexForumFactRepository.save(any(ScholardexForumFact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ImportProcessingResult result = service.runWosOnboarding("batch-1", "corr-1");

        assertEquals(1, result.getImportedCount());
        ArgumentCaptor<ScholardexForumFact> forumCaptor = ArgumentCaptor.forClass(ScholardexForumFact.class);
        verify(scholardexForumFactRepository).save(forumCaptor.capture());
        ScholardexForumFact savedForum = forumCaptor.getValue();
        assertTrue(savedForum.getId().startsWith("sforum_"));
        assertEquals(List.of("wos-j-1"), savedForum.getWosForumIds());
        assertEquals("1234-567X", savedForum.getIssn());
    }

    @Test
    void runWosOnboardingQuarantinesPublicationSourceLinkCollision() {
        WosScholardexOnboardingService service = new WosScholardexOnboardingService(
                namedParameterJdbcTemplate,
                scopusForumFactRepository,
                scholardexForumFactRepository,
                sourceLinkService,
                scholardexIdentityConflictRepository,
                scholardexPublicationFactRepository
        );

        ScholardexPublicationFact publication = new ScholardexPublicationFact();
        publication.setId("spub_1");
        publication.setWosId("WOS:123");

        ScholardexSourceLink existing = new ScholardexSourceLink();
        existing.setCanonicalEntityId("spub_other");

        when(namedParameterJdbcTemplate.query(any(String.class), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        when(scopusForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexPublicationFactRepository.findAll()).thenReturn(List.of(publication));
        when(sourceLinkService.findByEntityTypeAndSourceRecordId(
                ScholardexEntityType.PUBLICATION,
                "WOS:123"
        )).thenReturn(List.of(existing));
        when(scholardexIdentityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                eq(ScholardexEntityType.PUBLICATION), eq("WOS"), eq("WOS:123"), eq("SOURCE_ID_COLLISION"), eq("OPEN")
        )).thenReturn(Optional.empty());

        service.runWosOnboarding("batch-1", "corr-1");

        ArgumentCaptor<ScholardexIdentityConflict> conflictCaptor = ArgumentCaptor.forClass(ScholardexIdentityConflict.class);
        verify(scholardexIdentityConflictRepository).save(conflictCaptor.capture());
        assertEquals("SOURCE_ID_COLLISION", conflictCaptor.getValue().getReasonCode());
        assertEquals(ScholardexEntityType.PUBLICATION, conflictCaptor.getValue().getEntityType());
    }
}
