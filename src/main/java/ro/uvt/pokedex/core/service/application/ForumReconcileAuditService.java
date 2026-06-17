package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.service.application.model.ForumReconcileAuditReport;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * H66 C2.2 — read-only audit run after the one-time full forum rebuild to verify the canonical registry is
 * reconciled (no orphaned publication links) and that the forum-keyed Postgres projection (B2/B3 FK path)
 * covers the WoS-linked forums. Mutates nothing; safe to run against prod. See {@link ForumReconcileAuditReport}.
 */
@Service
@RequiredArgsConstructor
public class ForumReconcileAuditService {

    private static final Logger log = LoggerFactory.getLogger(ForumReconcileAuditService.class);

    private static final String PUBLICATION_FACTS_COLLECTION = "scholardex.publication_facts";
    private static final int ORPHAN_SAMPLE_CAP = 50;

    private final ScholardexForumFactRepository forumRepository;
    private final MongoTemplate mongoTemplate;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public ForumReconcileAuditReport audit() {
        List<ScholardexForumFact> forums = forumRepository.findAll();
        Set<String> allForumIds = new HashSet<>();
        Set<String> wosLinkedForumIds = new HashSet<>();
        long scopusLinked = 0;
        long wosLinked = 0;
        long both = 0;
        long noExternalId = 0;
        for (ScholardexForumFact forum : forums) {
            if (forum.getId() != null) {
                allForumIds.add(forum.getId());
            }
            boolean hasScopus = isNotEmpty(forum.getScopusForumIds());
            boolean hasWos = isNotEmpty(forum.getWosForumIds());
            if (hasScopus) {
                scopusLinked++;
            }
            if (hasWos) {
                wosLinked++;
                if (forum.getId() != null) {
                    wosLinkedForumIds.add(forum.getId());
                }
            }
            if (hasScopus && hasWos) {
                both++;
            }
            if (!hasScopus && !hasWos) {
                noExternalId++;
            }
        }

        // Orphaned publication links: a publication.forumId that no longer resolves to a canonical forum.
        List<String> publicationForumIds = mongoTemplate.findDistinct(
                new Query(), "forumId", PUBLICATION_FACTS_COLLECTION, String.class);
        long distinctPublicationForumIds = 0;
        long orphaned = 0;
        List<String> orphanSample = new ArrayList<>();
        for (String forumId : publicationForumIds) {
            if (forumId == null || forumId.isBlank()) {
                continue;
            }
            distinctPublicationForumIds++;
            if (!allForumIds.contains(forumId)) {
                orphaned++;
                if (orphanSample.size() < ORPHAN_SAMPLE_CAP) {
                    orphanSample.add(forumId);
                }
            }
        }

        // FK projection coverage (the B2/B3 forum-keyed views). Pull the metric forum-id set so we can prove
        // the WoS-linked forums resolve by FK; category/membership only need a coverage count.
        Set<String> metricForumIds = new HashSet<>(distinctForumIds("scholardex_forum_metric_view"));
        long categoryForums = countDistinctForumIds("scholardex_forum_category_view");
        long membershipForums = countDistinctForumIds("scholardex_forum_membership_view");

        long wosResolving = 0;
        for (String forumId : wosLinkedForumIds) {
            if (metricForumIds.contains(forumId)) {
                wosResolving++;
            }
        }
        long wosMissing = wosLinked - wosResolving;
        boolean healthy = orphaned == 0;

        ForumReconcileAuditReport report = new ForumReconcileAuditReport(
                forums.size(),
                scopusLinked,
                wosLinked,
                both,
                noExternalId,
                distinctPublicationForumIds,
                orphaned,
                orphanSample,
                metricForumIds.size(),
                categoryForums,
                membershipForums,
                wosResolving,
                wosMissing,
                healthy);
        log.info("Forum reconcile audit: total={} scopusLinked={} wosLinked={} both={} noExternalId={} "
                        + "pubForumIds={} orphaned={} fkMetric={} fkCategory={} fkMembership={} "
                        + "wosResolvingByFk={} wosMissingFk={} healthy={}",
                report.forumsTotal(), report.forumsScopusLinked(), report.forumsWosLinked(),
                report.forumsBothScopusAndWos(), report.forumsNoExternalId(),
                report.distinctPublicationForumIds(), report.orphanedPublicationForumLinks(),
                report.fkMetricForums(), report.fkCategoryForums(), report.fkMembershipForums(),
                report.wosLinkedResolvingMetricsByFk(), report.wosLinkedMissingMetricsByFk(), report.healthy());
        return report;
    }

    private List<String> distinctForumIds(String view) {
        return namedParameterJdbcTemplate.queryForList(
                "SELECT DISTINCT forum_id FROM reporting_read." + view,
                new MapSqlParameterSource(),
                String.class);
    }

    private long countDistinctForumIds(String view) {
        Long count = namedParameterJdbcTemplate.queryForObject(
                "SELECT count(DISTINCT forum_id) FROM reporting_read." + view,
                new MapSqlParameterSource(),
                Long.class);
        return count == null ? 0L : count;
    }

    private boolean isNotEmpty(List<String> list) {
        return list != null && !list.isEmpty();
    }
}
