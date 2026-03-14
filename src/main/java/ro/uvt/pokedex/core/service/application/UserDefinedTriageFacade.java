package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexSourceLinkRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.UserDefinedForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.UserDefinedPublicationFactRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserDefinedTriageFacade {

    public static final String SOURCE = "USER_DEFINED";

    private final UserDefinedPublicationFactRepository userDefinedPublicationFactRepository;
    private final UserDefinedForumFactRepository userDefinedForumFactRepository;
    private final ScholardexSourceLinkRepository sourceLinkRepository;
    private final ScholardexIdentityConflictRepository identityConflictRepository;

    public UserDefinedTriageSnapshot snapshot(int linksLimit, int conflictsLimit) {
        int safeLinksLimit = Math.max(1, linksLimit);
        int safeConflictsLimit = Math.max(1, conflictsLimit);
        List<ScholardexSourceLink> recentSourceLinks = sourceLinkRepository
                .findBySourceOrderByUpdatedAtDesc(SOURCE, PageRequest.of(0, safeLinksLimit))
                .getContent();
        List<ScholardexIdentityConflict> recentConflicts = identityConflictRepository
                .findByIncomingSourceOrderByDetectedAtDesc(SOURCE, PageRequest.of(0, safeConflictsLimit))
                .getContent();
        return new UserDefinedTriageSnapshot(
                userDefinedPublicationFactRepository.count(),
                userDefinedForumFactRepository.count(),
                new SourceLinkStateSummary(
                        sourceLinkRepository.countBySourceAndLinkState(SOURCE, ScholardexSourceLinkService.STATE_LINKED),
                        sourceLinkRepository.countBySourceAndLinkState(SOURCE, ScholardexSourceLinkService.STATE_UNMATCHED),
                        sourceLinkRepository.countBySourceAndLinkState(SOURCE, ScholardexSourceLinkService.STATE_CONFLICT),
                        sourceLinkRepository.countBySourceAndLinkState(SOURCE, ScholardexSourceLinkService.STATE_SKIPPED)
                ),
                new ConflictStateSummary(
                        identityConflictRepository.countByIncomingSourceAndStatus(SOURCE, "OPEN"),
                        identityConflictRepository.countByIncomingSourceAndStatus(SOURCE, "RESOLVED"),
                        identityConflictRepository.countByIncomingSourceAndStatus(SOURCE, "DISMISSED")
                ),
                recentSourceLinks,
                recentConflicts
        );
    }

    public record UserDefinedTriageSnapshot(
            long publicationFactCount,
            long forumFactCount,
            SourceLinkStateSummary sourceLinks,
            ConflictStateSummary conflicts,
            List<ScholardexSourceLink> recentSourceLinks,
            List<ScholardexIdentityConflict> recentConflicts
    ) {
    }

    public record SourceLinkStateSummary(long linked, long unmatched, long conflict, long skipped) {
        public long total() {
            return linked + unmatched + conflict + skipped;
        }
    }

    public record ConflictStateSummary(long open, long resolved, long dismissed) {
        public long total() {
            return open + resolved + dismissed;
        }
    }
}
