package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.repository.reporting.WosRankingViewRepository;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class WosForumResolutionService {

    private static final Pattern ISSN_PATTERN = Pattern.compile("([0-9Xx]{4})-?([0-9Xx]{4})");
    private static final Pattern NON_ALNUM_OR_SPACE = Pattern.compile("[^\\p{Alnum}\\s]");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private final WosRankingViewRepository wosRankingViewRepository;

    public String resolveJournalId(Forum forum) {
        ResolutionIndex index = buildResolutionIndex();
        return resolveJournalId(forum, index);
    }

    ResolutionIndex buildResolutionIndex() {
        Map<String, String> journalIdsByIssn = new HashMap<>();
        Map<String, String> journalIdsByName = new HashMap<>();
        for (WosRankingView view : wosRankingViewRepository.findAll()) {
            if (isBlank(view.getId())) {
                continue;
            }
            putIfPresent(journalIdsByIssn, view.getIssnNorm(), view.getId());
            putIfPresent(journalIdsByIssn, view.getEIssnNorm(), view.getId());
            if (view.getAlternativeIssnsNorm() != null) {
                for (String alternativeIssnNorm : view.getAlternativeIssnsNorm()) {
                    putIfPresent(journalIdsByIssn, alternativeIssnNorm, view.getId());
                }
            }
            putIfPresent(journalIdsByName, normalizeName(view.getName()), view.getId());
            if (view.getAlternativeNames() != null) {
                for (String alternativeName : view.getAlternativeNames()) {
                    putIfPresent(journalIdsByName, normalizeName(alternativeName), view.getId());
                }
            }
        }
        return new ResolutionIndex(journalIdsByIssn, journalIdsByName);
    }

    String resolveJournalId(Forum forum, ResolutionIndex index) {
        for (String issn : extractIssnCandidates(forum.getIssn(), forum.getEIssn())) {
            String wosJournalId = index.journalIdsByIssn().get(issn);
            if (!isBlank(wosJournalId)) {
                return wosJournalId;
            }
        }
        return index.journalIdsByName().get(normalizeName(forum.getPublicationName()));
    }

    private LinkedHashSet<String> extractIssnCandidates(String... values) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String value : values) {
            if (isBlank(value)) {
                continue;
            }
            Matcher matcher = ISSN_PATTERN.matcher(value);
            while (matcher.find()) {
                out.add((matcher.group(1) + matcher.group(2)).toUpperCase(Locale.ROOT));
            }
        }
        return out;
    }

    private String normalizeName(String value) {
        if (isBlank(value)) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKD);
        normalized = COMBINING_MARKS.matcher(normalized).replaceAll("");
        normalized = NON_ALNUM_OR_SPACE.matcher(normalized).replaceAll(" ");
        normalized = MULTI_SPACE.matcher(normalized.trim()).replaceAll(" ");
        return normalized.toLowerCase(Locale.ROOT);
    }

    private void putIfPresent(Map<String, String> index, String key, String journalId) {
        if (!isBlank(key) && !isBlank(journalId)) {
            index.putIfAbsent(key.toUpperCase(Locale.ROOT), journalId);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    record ResolutionIndex(
            Map<String, String> journalIdsByIssn,
            Map<String, String> journalIdsByName
    ) {
    }
}
