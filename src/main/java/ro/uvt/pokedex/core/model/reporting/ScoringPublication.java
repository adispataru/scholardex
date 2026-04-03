package ro.uvt.pokedex.core.model.reporting;

import java.util.List;
import java.util.Set;

public record ScoringPublication(
        String id,
        String eid,
        String forumId,
        String coverDate,
        String subtype,
        String scopusSubtype,
        List<String> authorIds,
        int authorCount,
        String doi,
        String wosId,
        String title,
        int citedByCount,
        Set<String> citingPublicationIds
) implements ScoringPublicationReadModel {

    @Override
    public String getId() { return id; }

    @Override
    public String getEid() { return eid; }

    @Override
    public String getForumId() { return forumId; }

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
}
