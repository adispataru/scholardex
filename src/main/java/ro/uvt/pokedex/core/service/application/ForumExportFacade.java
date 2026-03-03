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
        Set<String> uniqueIssns = new HashSet<>();
        List<ForumExportRow> rows = new ArrayList<>();

        for (Forum forum : forums) {
            if (!uniqueIssns.contains(forum.getIssn())) {
                if (!"null-".equals(forum.getIssn())) {
                    uniqueIssns.add(forum.getIssn());
                }
                rows.add(new ForumExportRow(
                        forum.getPublicationName(),
                        forum.getIssn(),
                        forum.getEIssn(),
                        forum.getId(),
                        forum.getAggregationType()
                ));
            }
        }

        return new ForumExportViewModel(rows);
    }
}
