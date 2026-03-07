package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusCitationFact;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ScopusCitationFactRepository extends MongoRepository<ScopusCitationFact, String> {
    Optional<ScopusCitationFact> findByCitedEidAndCitingEid(String citedEid, String citingEid);
    List<ScopusCitationFact> findByCitedEid(String citedEid);
    List<ScopusCitationFact> findByCitedEidIn(Collection<String> citedEids);
    List<ScopusCitationFact> findByCitedEidInAndCitingEidIn(Collection<String> citedEids, Collection<String> citingEids);
}
