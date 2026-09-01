package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import ro.uvt.pokedex.core.model.reporting.ScoringPublication;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Data
public class ScholardexPublicationView {
    private String id;
    private String doi;
    private String doiNormalized;
    private String eid;
    private String pii;
    private String pubmedId;
    private String title;
    private String subtype;
    private String subtypeDescription;
    private String scopusSubtype;
    private String scopusSubtypeDescription;
    private String creator;
    private String coverDate;
    private String coverDisplayDate;
    private String volume;
    private String issueIdentifier;
    private String description;
    private List<String> authKeywords = new ArrayList<>();
    private int authorCount;
    private List<String> correspondingAuthors = new ArrayList<>();
    private boolean openAccess;
    private String freetoread;
    private String freetoreadLabel;
    private String fundingId;
    private String articleNumber;
    private String pageRange;
    private boolean approved;
    private List<String> authorIds = new ArrayList<>();
    /**
     * H63: canonical corresponding-author ids (from {@code scholardex.authorship_facts} edges where
     * {@code corresponding=true}), parallel to {@code authorIds}. Distinct from {@code correspondingAuthors}, which is
     * Scopus-sourced name strings. Empty when no corresponding author is known for the publication.
     */
    private List<String> correspondingAuthorIds = new ArrayList<>();
    private List<String> affiliationIds = new ArrayList<>();
    private String forumId;
    /** H85: pre-DBLP-restamp source venue forum id (raw per-year proceedings); null unless re-stamped. */
    private String originalForumId;
    /** H66B M7: book venue Scopus Source ID (set instead of forumId for book-typed publications). */
    private String bookId;
    private Set<String> citingPublicationIds = new LinkedHashSet<>();
    private int citedByCount;
    // H67: incoming citations split by the indexing of the CITING paper's forum (for source-attributed h-index).
    private int graphCitationCount;   // # incoming citations in our internal graph (= citingPublicationIds.size())
    private int scopusCitationCount;  // … whose citing forum is Scopus-indexed
    private int wosCitationCount;     // … whose citing forum is WoS-indexed
    private String wosId;
    private String googleScholarId;

    private String buildVersion;
    private Instant buildAt;
    private Instant updatedAt;

    private String scopusLineage;
    private String wosLineage;
    private String scholarLineage;

    private String linkerVersion;
    private String linkerRunId;
    private Instant linkedAt;

    public String getForum() {
        return forumId;
    }

    public void setForum(String forumId) {
        this.forumId = forumId;
    }

    public List<String> getAuthors() {
        return authorIds;
    }

    public void setAuthors(List<String> authorIds) {
        this.authorIds = authorIds == null ? new ArrayList<>() : new ArrayList<>(authorIds);
        // authorCount is the bibliographic author count (the source record's own tally, persisted in the
        // author_count column) — NOT the number of resolved canonical author ids. The two differ whenever
        // resolution is incomplete (ids < count) or a sync path appended a duplicate identity (ids > count),
        // and the scoring divisor max(N-2,1) must not follow either drift. Derive from the id list only as a
        // fallback for minimal mappers that never read the column; a mapper that set the column keeps it.
        if (this.authorCount == 0) {
            this.authorCount = this.authorIds.size();
        }
    }

    public List<String> getAffiliations() {
        return affiliationIds;
    }

    public void setAffiliations(List<String> affiliationIds) {
        this.affiliationIds = affiliationIds == null ? new ArrayList<>() : new ArrayList<>(affiliationIds);
    }

    public int getCitedbyCount() {
        return citedByCount;
    }

    public int getCitedByCount() {
        return citedByCount;
    }

    public void setCitedByCount(Integer citedByCount) {
        this.citedByCount = citedByCount == null ? 0 : citedByCount;
    }

    public void setCitedbyCount(int citedByCount) {
        this.citedByCount = citedByCount;
    }

    public int getGraphCitationCount() {
        return graphCitationCount;
    }

    public void setGraphCitationCount(int graphCitationCount) {
        this.graphCitationCount = graphCitationCount;
    }

    public int getScopusCitationCount() {
        return scopusCitationCount;
    }

    public void setScopusCitationCount(int scopusCitationCount) {
        this.scopusCitationCount = scopusCitationCount;
    }

    public int getWosCitationCount() {
        return wosCitationCount;
    }

    public void setWosCitationCount(int wosCitationCount) {
        this.wosCitationCount = wosCitationCount;
    }

    public Set<String> getCitedBy() {
        return new LinkedHashSet<>(citingPublicationIds);
    }

    public void setCitedBy(Set<String> citingPublicationIds) {
        this.citingPublicationIds = citingPublicationIds == null
                ? new LinkedHashSet<>()
                : new LinkedHashSet<>(citingPublicationIds);
    }

    public ScoringPublicationReadModel toScoringPublication() {
        return new ScoringPublication(
                id,
                eid,
                forumId,
                bookId,
                originalForumId,
                coverDate,
                subtype,
                scopusSubtype,
                authorIds == null ? List.of() : List.copyOf(authorIds),
                authorCount,
                doi,
                wosId,
                title,
                citedByCount,
                citingPublicationIds == null ? Set.of() : new LinkedHashSet<>(citingPublicationIds),
                scopusCitationCount,
                wosCitationCount,
                graphCitationCount,
                affiliationIds == null ? List.of() : List.copyOf(affiliationIds),
                openAccess
        );
    }
}
