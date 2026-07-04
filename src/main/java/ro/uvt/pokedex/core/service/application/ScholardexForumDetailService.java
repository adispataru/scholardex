package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.SenseBookRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.service.application.model.ScholardexForumDetailViewModel;
import ro.uvt.pokedex.core.service.reporting.ComputerScienceBookService;
import ro.uvt.pokedex.core.service.reporting.ComputerScienceConferenceScoringService;

import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ScholardexForumDetailService {

    private final ScholardexProjectionReadService scholardexProjectionReadService;
    private final WosRankingDetailsReadService wosRankingDetailsReadService;
    private final WosForumResolutionService wosForumResolutionService;
    private final ComputerScienceConferenceScoringService computerScienceConferenceScoringService;
    private final ComputerScienceBookService computerScienceBookService;

    public Optional<ScholardexForumDetailViewModel> findDetail(String forumId) {
        return scholardexProjectionReadService.findForumById(forumId)
                .map(this::toViewModel);
    }

    /**
     * Public provenance/indexing badges for a forum, from its {@code forum_membership_view} snapshot
     * (Scopus / OpenAlex / WoS editions / DOAJ / ERIH + APC). Web of Science badges are login-gated by the
     * rendering fragment. Kept off the view model so the many call sites that build it stay untouched.
     */
    public java.util.List<ro.uvt.pokedex.core.service.application.model.ProvenanceBadge> provenanceBadges(String forumId) {
        ro.uvt.pokedex.core.service.application.model.ForumIndexingSnapshot indexing =
                scholardexProjectionReadService.findForumIndexing(forumId);
        return ProvenanceBadges.forForum(indexing.databases(), indexing.apc());
    }

    private ScholardexForumDetailViewModel toViewModel(ScholardexForumView forum) {
        ScholardexForumDetailViewModel.ForumType forumType = classifyForumType(forum.getAggregationType());
        WoSRanking wosRanking = null;
        if (forumType == ScholardexForumDetailViewModel.ForumType.JOURNAL) {
            String wosJournalId = wosForumResolutionService.resolveJournalId(forum);
            if (wosJournalId != null) {
                wosRanking = wosRankingDetailsReadService.findByJournalId(wosJournalId).orElse(null);
            }
        }
        CoreConferenceRanking coreRanking = null;
        if (forumType == ScholardexForumDetailViewModel.ForumType.CONFERENCE) {
            coreRanking = computerScienceConferenceScoringService
                    .matchByForumName(forum.getPublicationName())
                    .orElse(null);
        }
        SenseBookRanking senseBookRanking = null;
        if (forumType == ScholardexForumDetailViewModel.ForumType.BOOK) {
            senseBookRanking = computerScienceBookService
                    .matchByPublisher(forum.getPublisher())
                    .orElse(null);
        }
        return new ScholardexForumDetailViewModel(
                forum,
                forumType,
                wosRanking,
                wosRanking != null,
                coreRanking,
                senseBookRanking,
                forumType == ScholardexForumDetailViewModel.ForumType.CONFERENCE && coreRanking == null,
                forumType == ScholardexForumDetailViewModel.ForumType.BOOK && senseBookRanking == null,
                forumType == ScholardexForumDetailViewModel.ForumType.OTHER
        );
    }

    static ScholardexForumDetailViewModel.ForumType classifyForumType(String aggregationType) {
        String normalized = normalize(aggregationType);
        if (normalized.contains("journal")) {
            return ScholardexForumDetailViewModel.ForumType.JOURNAL;
        }
        if (normalized.contains("conference") || normalized.contains("proceeding")) {
            return ScholardexForumDetailViewModel.ForumType.CONFERENCE;
        }
        if (normalized.contains("book")) {
            return ScholardexForumDetailViewModel.ForumType.BOOK;
        }
        return ScholardexForumDetailViewModel.ForumType.OTHER;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
