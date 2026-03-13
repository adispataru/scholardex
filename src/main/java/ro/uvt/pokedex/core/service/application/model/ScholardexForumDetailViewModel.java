package ro.uvt.pokedex.core.service.application.model;

import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.scopus.Forum;

public record ScholardexForumDetailViewModel(
        Forum forum,
        ForumType forumType,
        WoSRanking wosRanking,
        boolean wosIndexed,
        boolean showCorePlaceholder,
        boolean showBookPlaceholder,
        boolean showGenericPlaceholder
) {

    public enum ForumType {
        JOURNAL,
        CONFERENCE,
        BOOK,
        OTHER
    }
}
