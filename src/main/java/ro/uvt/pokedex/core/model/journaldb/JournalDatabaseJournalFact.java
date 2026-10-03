package ro.uvt.pokedex.core.model.journaldb;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * H142 slice 4 — a journal on the title list of a journal database a standard names (EBSCO, ProQuest, JSTOR, Project
 * MUSE, RILM, Cambridge Core, Oxford Academic, Taylor &amp; Francis, CEEOL, Sciendo — {@code JournalDatabases}), one
 * per journal and database. Reference data like {@code doaj.journal_facts}: imported from the files under
 * {@code data/journal-databases/<DATABASE>/}, outside the derived collections (it survives a full rebuild; re-import
 * to refresh), onboarded into forums by ISSN (create-or-match), projected as {@code scholardex_forum_membership_view}
 * rows with {@code database} = the database and {@code source} = {@code TITLE_LIST}.
 */
@Data
@Document(collection = "journaldb.journal_facts")
public class JournalDatabaseJournalFact {

    /** The database and the journal's first ISSN on the list, e.g. {@code EBSCO:02625245}. */
    @Id
    private String id;

    /** The database ({@code JournalDatabases}), e.g. {@code EBSCO}. */
    private String database;
    private String title;
    /** Normalised ISSNs (eight characters, no hyphen): print, online, and any other the lists give. */
    private String issn;
    private String eIssn;
    private List<String> aliasIssns = new ArrayList<>();
    /** Coverage as the lists give it, kept for reference: membership is the list's present state, like DOAJ's. */
    private Integer coverageFrom;
    private Integer coverageTo;
    /** The files of the database's folder that list the journal. */
    private List<String> files = new ArrayList<>();
    /** The date of the newest file of the database's folder; surfaces as membership_view.as_of. */
    private String asOf;
    private String sourceBatchId;
    private Instant createdAt;
    private Instant updatedAt;
}
