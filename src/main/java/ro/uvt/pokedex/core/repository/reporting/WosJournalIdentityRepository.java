package ro.uvt.pokedex.core.repository.reporting;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import ro.uvt.pokedex.core.model.reporting.wos.WosJournalIdentity;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WosJournalIdentityRepository extends MongoRepository<WosJournalIdentity, String> {
    Optional<WosJournalIdentity> findByIdentityKey(String identityKey);
    List<WosJournalIdentity> findAllByIdentityKeyIn(Collection<String> identityKeys);

    @Query("{'$or':[{'primaryIssn': {'$in': ?0}}, {'eIssn': {'$in': ?0}}, {'aliasIssns': {'$in': ?0}}]}")
    List<WosJournalIdentity> findCandidatesByIssnTokens(Collection<String> normalizedIssnTokens);
}
