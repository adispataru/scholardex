package ro.uvt.pokedex.core.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;

import java.util.List;

public interface UserRepository extends MongoRepository<User, String> {

    boolean existsByRolesContaining(UserRole role);

    /** All users that have an embedded researcher profile. */
    List<User> findAllByResearcherProfileIsNotNull();

    /**
     * Case-insensitive search across both first and last name of the
     * embedded researcher profile. Used for author matching.
     */
    @Query("{ 'researcherProfile': { $ne: null }, $or: [ " +
           "  { 'researcherProfile.firstName': { $regex: ?0, $options: 'i' } }, " +
           "  { 'researcherProfile.lastName':  { $regex: ?0, $options: 'i' } } " +
           "] }")
    List<User> findByResearcherProfileNameContainingIgnoreCase(String namePart);
}
