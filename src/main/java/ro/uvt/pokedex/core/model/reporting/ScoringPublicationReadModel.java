package ro.uvt.pokedex.core.model.reporting;

import java.util.List;
import java.util.Set;

public interface ScoringPublicationReadModel {
    String getId();
    String getEid();
    String getForumId();
    /** H66B M7: book venue Scopus Source ID (set instead of forumId for book-typed publications). */
    String getBookId();
    String getCoverDate();
    String getSubtype();
    String getScopusSubtype();
    List<String> getAuthorIds();
    int getAuthorCount();
    String getDoi();
    String getWosId();
    String getTitle();
    int getCitedByCount();
    Set<String> getCitingPublicationIds();

    /* H67 source-attributed citation splits + institution/OA signals — defaulted so non-projection
     * constructors (tests, synthetic pubs) keep working; the read-model projection fills the real values. */
    /** Incoming citations whose citing forum is Scopus-indexed (H67 source split). */
    default int getScopusCitationCount() { return 0; }
    /** Incoming citations whose citing forum is WoS-indexed (H67 source split). */
    default int getWosCitationCount() { return 0; }
    /** Incoming citations counted in our internal graph (= citingPublicationIds.size()). */
    default int getGraphCitationCount() { return 0; }
    /** Canonical affiliation ids (saff_) of the publication — institution-attributed scoring. */
    default List<String> getAffiliationIds() { return List.of(); }
    /** Open-access flag. */
    default Boolean getOpenAccess() { return null; }
}
