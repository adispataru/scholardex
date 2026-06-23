package ro.uvt.pokedex.core.model.reporting;

import java.util.List;
import java.util.Set;

public record ScoringPublication(
        String id,
        String eid,
        String forumId,
        String bookId,
        String coverDate,
        String subtype,
        String scopusSubtype,
        List<String> authorIds,
        int authorCount,
        String doi,
        String wosId,
        String title,
        int citedByCount,
        Set<String> citingPublicationIds,
        int scopusCitationCount,
        int wosCitationCount,
        int graphCitationCount,
        List<String> affiliationIds,
        Boolean openAccess
) implements ScoringPublicationReadModel {

    /** H66B M7: convenience constructor for book-less (forum/journal) publications — bookId defaults to null
     *  and the H67 citation-split / affiliation / OA fields default to empty (synthetic + test pubs). */
    public ScoringPublication(String id, String eid, String forumId, String coverDate, String subtype,
            String scopusSubtype, List<String> authorIds, int authorCount, String doi, String wosId,
            String title, int citedByCount, Set<String> citingPublicationIds) {
        this(id, eid, forumId, null, coverDate, subtype, scopusSubtype, authorIds, authorCount, doi, wosId,
                title, citedByCount, citingPublicationIds, 0, 0, 0, List.of(), null);
    }

    @Override
    public String getId() { return id; }

    @Override
    public String getEid() { return eid; }

    @Override
    public String getForumId() { return forumId; }

    @Override
    public String getBookId() { return bookId; }

    @Override
    public String getCoverDate() { return coverDate; }

    @Override
    public String getSubtype() { return subtype; }

    @Override
    public String getScopusSubtype() { return scopusSubtype; }

    @Override
    public List<String> getAuthorIds() { return authorIds; }

    @Override
    public int getAuthorCount() { return authorCount; }

    @Override
    public String getDoi() { return doi; }

    @Override
    public String getWosId() { return wosId; }

    @Override
    public String getTitle() { return title; }

    @Override
    public int getCitedByCount() { return citedByCount; }

    @Override
    public Set<String> getCitingPublicationIds() { return citingPublicationIds; }

    @Override
    public int getScopusCitationCount() { return scopusCitationCount; }

    @Override
    public int getWosCitationCount() { return wosCitationCount; }

    @Override
    public int getGraphCitationCount() { return graphCitationCount; }

    @Override
    public List<String> getAffiliationIds() { return affiliationIds == null ? List.of() : affiliationIds; }

    @Override
    public Boolean getOpenAccess() { return openAccess; }
}
