package ro.uvt.pokedex.core.service;

import ro.uvt.pokedex.core.model.Researcher;
import java.util.List;
import java.util.Optional;

public interface ResearcherService {
    Researcher saveResearcher(Researcher researcher);
    Optional<Researcher> findResearcherById(String id);
    List<Researcher> findAllResearchers();
    Researcher updateResearcher(String id, Researcher researcher);
    void deleteResearcher(String id);

    public Optional<Researcher> matchAuthorToResearcher(String authorName);
}
