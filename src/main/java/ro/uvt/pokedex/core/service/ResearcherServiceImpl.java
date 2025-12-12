package ro.uvt.pokedex.core.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.repository.ResearcherRepository;
import ro.uvt.pokedex.core.utils.StringUtils;

import java.util.List;
import java.util.Optional;

@Service
public class ResearcherServiceImpl implements ResearcherService {

    private final ResearcherRepository researcherRepository;

    @Autowired
    public ResearcherServiceImpl(ResearcherRepository researcherRepository) {
        this.researcherRepository = researcherRepository;
    }

    @Override
    public Researcher saveResearcher(Researcher researcher) {
        return researcherRepository.save(researcher);
    }

    @Override
    public Optional<Researcher> findResearcherById(String id) {
        return researcherRepository.findById(id);
    }

    @Override
    public List<Researcher> findAllResearchers() {
        return researcherRepository.findAll();
    }

    @Override
    public Researcher updateResearcher(String id, Researcher researcher) {
        researcher.setId(id);
        return researcherRepository.save(researcher);
    }

    @Override
    public void deleteResearcher(String id) {
        researcherRepository.deleteById(id);
    }


    public Optional<Researcher> matchAuthorToResearcher(String authorName) {
        // Normalize and split the author name
        String normalizedAuthorName = StringUtils.normalize(authorName);
        String[] authorNameParts = normalizedAuthorName.split("\\s+");

        List<Researcher> researchers = researcherRepository.findAll();
        for (Researcher researcher : researchers) {
            String researcherName = StringUtils.normalize(researcher.getName());
            String[] researcherNameParts = researcherName.split("\\s+");

            // Check for partial match - ignore middle names
            if (nameMatches(authorNameParts, researcherNameParts)) {
                return Optional.of(researcher);
            }
        }
        return Optional.empty();
    }

    private boolean nameMatches(String[] authorNameParts, String[] researcherNameParts) {
        // Simple example: Match first and last names; ignore middle names or initials
        // Assuming the first part is the first name and the last part is the last name
        if (authorNameParts.length == 0 || researcherNameParts.length == 0) {
            return false;
        }

        String authorFirstName = authorNameParts[0];
        String authorLastName = authorNameParts[authorNameParts.length - 1];

        String researcherFirstName = researcherNameParts[0];
        String researcherLastName = researcherNameParts[researcherNameParts.length - 1];

        if(authorNameParts.length > 2){
            String authorMiddleName = authorNameParts[1];
            return authorFirstName.equals(researcherFirstName) && authorLastName.equals(researcherLastName);
        }

        return authorFirstName.equals(researcherFirstName) && authorLastName.equals(researcherLastName);
    }


}
