package ro.uvt.pokedex.core.service.dblp;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * H66B Phase 4b — the unified "hidden conference" detector. A publication needs DBLP resolution when it is
 * conference-shaped but has no real conference forum, covering BOTH signals:
 * <ul>
 *   <li><b>Scopus LNCS tail</b> — subtype {@code cp}/{@code ch} whose forum is a Lecture-Notes-style book series
 *       (the series, not the conference behind it);</li>
 *   <li><b>OpenAlex ISSN-less conferences</b> — a {@code proceedings} pub Stage 3 left with {@code forumId == null}
 *       because it carries no ISSN.</li>
 * </ul>
 * A pub already pointing at a real conference/journal forum is left alone.
 */
@Service
@RequiredArgsConstructor
public class DblpConferenceCandidateDetector {

    private static final Set<String> CONFERENCE_SUBTYPES = Set.of("cp", "ch");

    private final ScholardexForumFactRepository forumFactRepository;

    public List<ScholardexPublicationFact> detect(Collection<ScholardexPublicationFact> publications) {
        Set<String> forumIds = publications.stream()
                .map(ScholardexPublicationFact::getForumId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, ScholardexForumFact> forumById = forumIds.isEmpty()
                ? Map.of()
                : forumFactRepository.findAllById(forumIds).stream()
                        .collect(Collectors.toMap(ScholardexForumFact::getId, Function.identity(), (a, b) -> a));

        List<ScholardexPublicationFact> candidates = new ArrayList<>();
        for (ScholardexPublicationFact pub : publications) {
            if (!isConferenceShaped(pub.getSubtype())) {
                continue;
            }
            String forumId = pub.getForumId();
            ScholardexForumFact forum = (forumId == null || forumId.isBlank()) ? null : forumById.get(forumId);
            // Candidate iff it lacks a real conference forum: no forum at all, or only a Lecture-Notes-style series.
            if (forum == null || isSeriesForum(forum.getName())) {
                candidates.add(pub);
            }
        }
        return candidates;
    }

    private boolean isConferenceShaped(String subtype) {
        if (subtype == null) {
            return false;
        }
        String s = subtype.toLowerCase(Locale.ROOT);
        return CONFERENCE_SUBTYPES.contains(s) || s.contains("proceedings") || s.contains("conference");
    }

    private boolean isSeriesForum(String forumName) {
        if (forumName == null) {
            return false;
        }
        String n = forumName.toLowerCase(Locale.ROOT);
        return n.startsWith("lecture notes in ") || n.startsWith("lecture notes on ")
                || n.contains("communications in computer and information science");
    }
}
