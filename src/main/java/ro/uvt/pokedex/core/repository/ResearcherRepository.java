package ro.uvt.pokedex.core.repository;

import ro.uvt.pokedex.core.model.Researcher;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * @deprecated Researcher is no longer a MongoDB document. All profile data
 *             lives inside {@code User.researcherProfile}. This repository is
 *             kept only to allow a gradual compile-safe migration; it will be
 *             deleted in the cleanup phase once {@code ResearcherServiceImpl}
 *             is fully ported to {@link UserRepository}.
 */
@Deprecated
public interface ResearcherRepository extends MongoRepository<Researcher, String> {
    // Broken derived-query methods removed — Researcher has no 'name' field.
    // Name-based lookup is now handled in ResearcherServiceImpl via UserRepository.
}
