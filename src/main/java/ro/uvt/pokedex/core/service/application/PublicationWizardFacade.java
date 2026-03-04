package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.Affiliation;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.repository.scopus.ScopusAffiliationRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusAuthorRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusForumRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusPublicationRepository;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PublicationWizardFacade {

    private final ScopusForumRepository forumRepository;
    private final ScopusAuthorRepository authorRepository;
    private final ScopusPublicationRepository publicationRepository;
    private final ScopusAffiliationRepository affiliationRepository;

    public List<Forum> listForums() {
        return forumRepository.findAll();
    }

    public Optional<String> resolveForumId(Forum newForum, String selectedId) {
        if (selectedId != null && !selectedId.isEmpty()) {
            Forum existingForum = forumRepository.findById(selectedId).orElse(null);
            if (existingForum != null) {
                return Optional.of(existingForum.getId());
            }
        } else if (newForum.getPublicationName() != null && !newForum.getPublicationName().isEmpty()) {
            return Optional.of(forumRepository.save(newForum).getId());
        }
        return Optional.empty();
    }

    public List<Author> findAuthorsForAffiliation(String affiliationId) {
        Optional<Affiliation> affiliation = affiliationRepository.findById(affiliationId);
        return affiliation.map(authorRepository::findAllByAffiliationsContaining).orElse(Collections.emptyList());
    }

    public Publication buildPublicationDraft(String forumId, String authors, String creator) {
        Publication publication = new Publication();
        publication.setForum(forumId);
        publication.setCreator(creator);
        publication.setAuthors(Arrays.asList(authors.split(",")));
        return publication;
    }

    public Publication savePublication(Publication publication) {
        return publicationRepository.save(publication);
    }
}
