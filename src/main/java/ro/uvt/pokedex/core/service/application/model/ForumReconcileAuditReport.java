package ro.uvt.pokedex.core.service.application.model;

import java.util.List;

/**
 * H66 C2.2 — read-only verification of the canonical forum registry after the one-time full rebuild.
 *
 * <p>Proves the rebuild reconciled the registry rather than leaving drift: every publication still points at
 * a live canonical forum ({@code orphanedPublicationForumLinks == 0} ⇒ {@link #healthy()}), and the
 * forum-keyed Postgres projection (the H66 B2/B3 FK path) actually covers the WoS-linked forums — i.e. the
 * journals that used to score 0 under the fuzzy resolver now resolve by {@code forum_id} FK.
 *
 * @param forumsTotal                    canonical forums in scholardex.forum_facts
 * @param forumsScopusLinked             forums carrying ≥1 scopusForumId
 * @param forumsWosLinked                forums carrying ≥1 wosForumId
 * @param forumsBothScopusAndWos         forums carrying both a scopus and a wos id (the bridged journals)
 * @param forumsNoExternalId             forums with neither a scopus nor a wos id (user-only / unmatched)
 * @param distinctPublicationForumIds    distinct forumId values referenced by publications
 * @param orphanedPublicationForumLinks  publication forumId values with no matching canonical forum (must be 0)
 * @param orphanedForumIdSample          a capped sample of the orphaned forumIds (empty when healthy)
 * @param fkMetricForums                 distinct forum_id in scholardex_forum_metric_view (FK metric coverage)
 * @param fkCategoryForums               distinct forum_id in scholardex_forum_category_view
 * @param fkMembershipForums             distinct forum_id in scholardex_forum_membership_view (MJL)
 * @param wosLinkedResolvingMetricsByFk  WoS-linked forums whose forum_id resolves metrics by FK
 * @param wosLinkedMissingMetricsByFk    WoS-linked forums with no FK metric row (expected: coverage-only journals)
 * @param healthy                        true iff there are no orphaned publication→forum links
 */
public record ForumReconcileAuditReport(
        long forumsTotal,
        long forumsScopusLinked,
        long forumsWosLinked,
        long forumsBothScopusAndWos,
        long forumsNoExternalId,
        long distinctPublicationForumIds,
        long orphanedPublicationForumLinks,
        List<String> orphanedForumIdSample,
        long fkMetricForums,
        long fkCategoryForums,
        long fkMembershipForums,
        long wosLinkedResolvingMetricsByFk,
        long wosLinkedMissingMetricsByFk,
        boolean healthy
) {
}
