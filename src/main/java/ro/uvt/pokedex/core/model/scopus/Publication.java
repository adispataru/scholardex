package ro.uvt.pokedex.core.model.scopus;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Data
@Document(collection = "scopus.publications2025")
public class Publication {
    @Id
    private String id;
    private String doi;
    private String eid;
    private String wosId;
    private Integer importId;
    private String pii;
    private String pubmedId;
    private String title;
    private String scopusSubtype;
    private String subtype;
    private String scopusSubtypeDescription;
    private String subtypeDescription;
    private String creator;
    private List<String> affiliations;
    private int authorCount;
    private List<String> authors;
    private List<String> correspondingAuthors;
    private String forum;
    private String volume;
    private String issueIdentifier;

    private String coverDate;
    private String coverDisplayDate;
    private String description;
    private List<String> authKeywords;
    private int citedbyCount;
    private Set<String> citedBy = new HashSet<>();
    private boolean openAccess;
    private String freetoread;
    private String freetoreadLabel;
    private String fundingId;
    private String articleNumber;
    private String pageRange;
    private boolean approved;

    public static final String NON_WOS_ID = "NON-WOS";
}
