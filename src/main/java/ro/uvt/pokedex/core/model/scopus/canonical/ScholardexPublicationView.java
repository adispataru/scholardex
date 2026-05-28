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
    private List<String> affiliationIds = new ArrayList<>();
    private String forumId;
    private Set<String> citingPublicationIds = new LinkedHashSet<>();
    private int citedByCount;
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
        this.authorCount = this.authorIds.size();
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
                coverDate,
                subtype,
                scopusSubtype,
                authorIds == null ? List.of() : List.copyOf(authorIds),
                authorCount,
                doi,
                wosId,
                title,
                citedByCount,
                citingPublicationIds == null ? Set.of() : new LinkedHashSet<>(citingPublicationIds)
        );
    }
}
