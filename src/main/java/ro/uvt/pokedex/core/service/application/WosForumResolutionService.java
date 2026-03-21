package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.EmptySqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.Forum;

import java.sql.Array;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
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

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public String resolveJournalId(Forum forum) {
        ResolutionIndex index = buildResolutionIndex();
        return resolveJournalId(forum, index);
    }

    ResolutionIndex buildResolutionIndex() {
        Map<String, String> journalIdsByIssn = new HashMap<>();
        Map<String, String> journalIdsByName = new HashMap<>();
        namedParameterJdbcTemplate.query(
                """
                SELECT journal_id, issn_norm, e_issn_norm, alternative_issns_norm, name, alternative_names
                FROM reporting_read.wos_ranking_view
                """,
                EmptySqlParameterSource.INSTANCE,
                (rs, rowNum) -> {
                    String id = rs.getString("journal_id");
                    if (!isBlank(id)) {
                        putIfPresent(journalIdsByIssn, rs.getString("issn_norm"), id);
                        putIfPresent(journalIdsByIssn, rs.getString("e_issn_norm"), id);
                        for (String altIssn : toStringList(rs.getArray("alternative_issns_norm"))) {
                            putIfPresent(journalIdsByIssn, altIssn, id);
                        }
                        putIfPresent(journalIdsByName, normalizeName(rs.getString("name")), id);
                        for (String altName : toStringList(rs.getArray("alternative_names"))) {
                            putIfPresent(journalIdsByName, normalizeName(altName), id);
                        }
                    }
                    return null;
                }
        );
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

    private List<String> toStringList(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        Object value = array.getArray();
        if (value instanceof String[] items) {
            return List.of(items);
        }
        return List.of();
    }

    record ResolutionIndex(
            Map<String, String> journalIdsByIssn,
            Map<String, String> journalIdsByName
    ) {
    }
}
