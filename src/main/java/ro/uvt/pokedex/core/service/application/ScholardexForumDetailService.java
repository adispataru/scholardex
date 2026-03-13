package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.service.application.model.ScholardexForumDetailViewModel;

import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ScholardexForumDetailService {

    private final ScholardexProjectionReadService scholardexProjectionReadService;
    private final WosRankingDetailsReadService wosRankingDetailsReadService;
    private final WosForumResolutionService wosForumResolutionService;

    public Optional<ScholardexForumDetailViewModel> findDetail(String forumId) {
        return scholardexProjectionReadService.findForumById(forumId)
                .map(this::toViewModel);
    }

    private ScholardexForumDetailViewModel toViewModel(Forum forum) {
        ScholardexForumDetailViewModel.ForumType forumType = classifyForumType(forum.getAggregationType());
        WoSRanking wosRanking = null;
        if (forumType == ScholardexForumDetailViewModel.ForumType.JOURNAL) {
            String wosJournalId = wosForumResolutionService.resolveJournalId(forum);
            if (wosJournalId != null) {
                wosRanking = wosRankingDetailsReadService.findByJournalId(wosJournalId).orElse(null);
            }
        }
        return new ScholardexForumDetailViewModel(
                forum,
                forumType,
                wosRanking,
                wosRanking != null,
                forumType == ScholardexForumDetailViewModel.ForumType.CONFERENCE,
                forumType == ScholardexForumDetailViewModel.ForumType.BOOK,
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
