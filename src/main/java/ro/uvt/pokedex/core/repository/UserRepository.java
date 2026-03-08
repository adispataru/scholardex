package ro.uvt.pokedex.core.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;

public interface UserRepository extends MongoRepository<User, String> {

    boolean existsByRolesContaining(UserRole role);
}
