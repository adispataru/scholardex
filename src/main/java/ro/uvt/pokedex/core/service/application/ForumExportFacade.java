package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.repository.scopus.ScopusForumRepository;
import ro.uvt.pokedex.core.service.application.model.ForumExportRow;
import ro.uvt.pokedex.core.service.application.model.ForumExportViewModel;

import java.util.*;

@Service
@RequiredArgsConstructor
public class ForumExportFacade {
    private final ScopusForumRepository forumRepository;

    public ForumExportViewModel buildBookAndBookSeriesExport() {
        List<Forum> forums = forumRepository.findAllByAggregationTypeIn(List.of("Book", "Book Series"));
        Set<String> dedupeKeys = new HashSet<>();
        List<ForumExportRow> rows = new ArrayList<>();

        for (Forum forum : forums) {
            String dedupeKey = dedupeKey(forum);
            if (dedupeKeys.add(dedupeKey)) {
                rows.add(mapRow(forum));
            }
        }

        return new ForumExportViewModel(rows);
    }

    private ForumExportRow mapRow(Forum forum) {
        return new ForumExportRow(
                forum.getPublicationName(),
                forum.getIssn(),
                forum.getEIssn(),
                forum.getId(),
                forum.getAggregationType()
        );
    }

    private String dedupeKey(Forum forum) {
        String issn = normalizeIssn(forum.getIssn());
        if (!issn.isEmpty()) {
            return "issn:" + issn;
        }
        String eIssn = normalizeIssn(forum.getEIssn());
        if (!eIssn.isEmpty()) {
            return "eissn:" + eIssn;
        }
        return "sourceId:" + forum.getId();
    }

    private String normalizeIssn(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.trim();
        if (normalized.isEmpty()) {
            return "";
        }
        String alphaOnly = normalized.replace("-", "");
        if ("null".equalsIgnoreCase(alphaOnly) || "n/a".equalsIgnoreCase(normalized)) {
            return "";
        }
        return normalized.toLowerCase(Locale.ROOT);
    }
}
