package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Document(collection = "scholardex.publication_view")
public class ScholardexPublicationView {
    @Id
    private String id;
    private String doi;
    private String doiNormalized;
    private String eid;
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
    private Integer authorCount;
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
    private List<String> citingPublicationIds = new ArrayList<>();
    private Integer citedByCount;
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
}
