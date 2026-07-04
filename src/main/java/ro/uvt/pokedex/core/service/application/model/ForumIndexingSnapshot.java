package ro.uvt.pokedex.core.service.application.model;

import java.util.Set;

/**
 * A forum's current indexing membership, read from {@code scholardex_forum_membership_view}: the set of database
 * codes it is a member of (SCOPUS / OPENALEX / SCIE·ESCI·SSCI·AHCI / DOAJ / ERIH …, uppercased) plus whether it
 * charges an APC (DOAJ). Feeds {@code ProvenanceBadges.forForum}.
 */
public record ForumIndexingSnapshot(Set<String> databases, boolean apc) {

    public static ForumIndexingSnapshot empty() {
        return new ForumIndexingSnapshot(Set.of(), false);
    }
}
