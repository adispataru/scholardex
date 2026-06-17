package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.service.application.model.ForumReconcileAuditReport;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ForumReconcileAuditServiceTest {

    @Mock private ScholardexForumFactRepository forumRepository;
    @Mock private MongoTemplate mongoTemplate;
    @Mock private NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    private ForumReconcileAuditService service;

    @BeforeEach
    void setUp() {
        service = new ForumReconcileAuditService(forumRepository, mongoTemplate, namedParameterJdbcTemplate);
        // FK projection coverage stubs (shared by both tests): metric view returns f2,f3; counts for the others.
        lenient().when(namedParameterJdbcTemplate.queryForList(
                        argSqlContains("scholardex_forum_metric_view"), any(SqlParameterSource.class), eq(String.class)))
                .thenReturn(List.of("f2", "f3"));
        lenient().when(namedParameterJdbcTemplate.queryForObject(
                        argSqlContains("scholardex_forum_category_view"), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(2L);
        lenient().when(namedParameterJdbcTemplate.queryForObject(
                        argSqlContains("scholardex_forum_membership_view"), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(1L);
    }

    private static String argSqlContains(String fragment) {
        return org.mockito.ArgumentMatchers.argThat(sql -> sql != null && sql.contains(fragment));
    }

    private static ScholardexForumFact forum(String id, List<String> scopusIds, List<String> wosIds) {
        ScholardexForumFact f = new ScholardexForumFact();
        f.setId(id);
        f.setScopusForumIds(scopusIds);
        f.setWosForumIds(wosIds);
        return f;
    }

    @Test
    void auditReportsCompositionAndFkCoverageAndIsHealthyWhenNoOrphans() {
        when(forumRepository.findAll()).thenReturn(List.of(
                forum("f1", List.of("s1"), List.of()),      // scopus only
                forum("f2", List.of(), List.of("w1")),       // wos only
                forum("f3", List.of("s3"), List.of("w3")),   // both
                forum("f4", List.of(), List.of())            // no external id
        ));
        when(mongoTemplate.findDistinct(any(Query.class), eq("forumId"),
                eq("scholardex.publication_facts"), eq(String.class)))
                .thenReturn(java.util.Arrays.asList("f1", "f3", null, "  "));

        ForumReconcileAuditReport report = service.audit();

        assertEquals(4, report.forumsTotal());
        assertEquals(2, report.forumsScopusLinked());     // f1, f3
        assertEquals(2, report.forumsWosLinked());         // f2, f3
        assertEquals(1, report.forumsBothScopusAndWos());  // f3
        assertEquals(1, report.forumsNoExternalId());      // f4
        assertEquals(2, report.distinctPublicationForumIds()); // f1, f3 (null/blank ignored)
        assertEquals(0, report.orphanedPublicationForumLinks());
        assertTrue(report.orphanedForumIdSample().isEmpty());
        assertTrue(report.healthy());

        assertEquals(2, report.fkMetricForums());          // f2, f3
        assertEquals(2, report.fkCategoryForums());
        assertEquals(1, report.fkMembershipForums());
        // WoS-linked f2 and f3 both appear in the metric view → all resolve by FK, none missing.
        assertEquals(2, report.wosLinkedResolvingMetricsByFk());
        assertEquals(0, report.wosLinkedMissingMetricsByFk());
    }

    @Test
    void auditFlagsOrphanedPublicationLinkAndIsUnhealthy() {
        when(forumRepository.findAll()).thenReturn(List.of(
                forum("f1", List.of("s1"), List.of()),
                forum("f2", List.of(), List.of("w1"))
        ));
        when(mongoTemplate.findDistinct(any(Query.class), eq("forumId"),
                eq("scholardex.publication_facts"), eq(String.class)))
                .thenReturn(List.of("f1", "fGhost"));
        // Metric view does NOT contain f2 → its wosForumId does not resolve metrics by FK (coverage-only case).
        when(namedParameterJdbcTemplate.queryForList(
                        argSqlContains("scholardex_forum_metric_view"), any(SqlParameterSource.class), eq(String.class)))
                .thenReturn(List.of("fOther"));

        ForumReconcileAuditReport report = service.audit();

        assertEquals(1, report.orphanedPublicationForumLinks());
        assertEquals(List.of("fGhost"), report.orphanedForumIdSample());
        assertFalse(report.healthy());
        // f2 carries a wosForumId but is not in the metric view → counted as missing FK metrics (e.g. coverage-only).
        assertEquals(1, report.forumsWosLinked());
        assertEquals(0, report.wosLinkedResolvingMetricsByFk());
        assertEquals(1, report.wosLinkedMissingMetricsByFk());
    }
}
