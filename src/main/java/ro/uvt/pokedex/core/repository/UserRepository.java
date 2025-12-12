package ro.uvt.pokedex.core.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.user.User;

public interface UserRepository extends MongoRepository<User, String> {}

