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
}
