package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.repository.scopus.ScopusForumRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ForumExportFacadeTest {

    @Mock
    private ScopusForumRepository forumRepository;

    @InjectMocks
    private ForumExportFacade facade;

    @Test
    void buildBookAndBookSeriesExportReturnsDeduplicatedRowsByIssn() {
        Forum f1 = forum("A", "1234-5678", "e1", "Book");
        Forum f2 = forum("B", "1234-5678", "e2", "Book Series");
        Forum f3 = forum("C", "9999-9999", "e3", "Book");
        when(forumRepository.findAllByAggregationTypeIn(List.of("Book", "Book Series")))
                .thenReturn(List.of(f1, f2, f3));

        var result = facade.buildBookAndBookSeriesExport();

        assertEquals(2, result.rows().size());
        assertEquals("A", result.rows().get(0).publicationName());
        assertEquals("C", result.rows().get(1).publicationName());
    }

    @Test
    void buildBookAndBookSeriesExportIncludesBothAggregationTypes() {
        Forum f1 = forum("A", "1111-1111", "e1", "Book");
        Forum f2 = forum("B", "2222-2222", "e2", "Book Series");
        when(forumRepository.findAllByAggregationTypeIn(List.of("Book", "Book Series")))
                .thenReturn(List.of(f1, f2));

        var result = facade.buildBookAndBookSeriesExport();

        assertEquals(2, result.rows().size());
        assertEquals("Book", result.rows().get(0).aggregationType());
        assertEquals("Book Series", result.rows().get(1).aggregationType());
    }

    private static Forum forum(String name, String issn, String id, String aggType) {
        Forum forum = new Forum();
        forum.setPublicationName(name);
        forum.setIssn(issn);
        forum.setId(id);
        forum.setAggregationType(aggType);
        forum.setEIssn("e");
        return forum;
    }
}
