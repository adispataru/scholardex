package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.repository.reporting.RankingRepository;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserRankingFacade {

    private final ScopusProjectionReadService scopusProjectionReadService;
    private final RankingRepository rankingRepository;

    public Optional<WoSRanking> resolveJournalRankingForForum(String forumId) {
        Optional<Forum> forumOptional = scopusProjectionReadService.findForumById(forumId);
        if (forumOptional.isEmpty()) {
            return Optional.empty();
        }

        Forum forum = forumOptional.get();
        if (!"Journal".equals(forum.getAggregationType())) {
            return Optional.empty();
        }
        if ((forum.getIssn() == null || forum.getIssn().isBlank())
                && (forum.getEIssn() == null || forum.getEIssn().isBlank())) {
            return Optional.empty();
        }

        String generatedId = WoSRanking.getGeneratedId(forum.getIssn(), forum.getEIssn());
        if (generatedId == null) {
            return Optional.empty();
        }
        return rankingRepository.findById(generatedId);
    }
}
