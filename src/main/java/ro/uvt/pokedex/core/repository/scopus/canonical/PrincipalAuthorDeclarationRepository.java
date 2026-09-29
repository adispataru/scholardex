package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.PrincipalAuthorDeclaration;

import java.util.List;
import java.util.Optional;

public interface PrincipalAuthorDeclarationRepository extends MongoRepository<PrincipalAuthorDeclaration, String> {

    List<PrincipalAuthorDeclaration> findByUserEmail(String userEmail);

    List<PrincipalAuthorDeclaration> findByUserEmailAndStatus(String userEmail, PrincipalAuthorDeclaration.Status status);

    Optional<PrincipalAuthorDeclaration> findByUserEmailAndPublicationId(String userEmail, String publicationId);

    List<PrincipalAuthorDeclaration> findByStatusOrderByCreatedAtAsc(PrincipalAuthorDeclaration.Status status);

    List<PrincipalAuthorDeclaration> findByStatusInOrderByDecidedAtDesc(List<PrincipalAuthorDeclaration.Status> statuses);
}
