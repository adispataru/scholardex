package ro.uvt.pokedex.core.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import ro.uvt.pokedex.core.model.workspace.WorkspacePreferences;

@Repository
public interface WorkspacePreferencesRepository extends MongoRepository<WorkspacePreferences, String> {
}
