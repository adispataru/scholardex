package ro.uvt.pokedex.core.derivation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexInstitutionFact;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexInstitutionFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusPublicationFactRepository;
import ro.uvt.pokedex.core.service.derivation.CanonicalDerivationV2Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H75 — end-to-end "it runs" proof for the whole V2 canonical derivation (affiliations → publications → authors)
 * against a real Mongo on a small fixture, asserting OUTPUT INVARIANTS (not V1 parity): one canonical pub per shared
 * DOI, the OpenAlex-keyed author carrying the Scopus AU-ID, the ROR backbone present, and referential integrity
 * (every pub.authorId references an existing author).
 */
class CanonicalDerivationV2IntegrationTest extends CanonicalDerivationIntegrationTestBase {

    @Autowired private OpenAlexInstitutionFactRepository institutionFactRepository;
    @Autowired private ScopusPublicationFactRepository scopusPublicationFactRepository;
    @Autowired private OpenAlexPublicationFactRepository openAlexPublicationFactRepository;
    @Autowired private ScopusAuthorFactRepository scopusAuthorFactRepository;
    @Autowired private ScholardexAffiliationFactRepository scholardexAffiliationFactRepository;
    @Autowired private ScholardexPublicationFactRepository scholardexPublicationFactRepository;
    @Autowired private ScholardexAuthorFactRepository scholardexAuthorFactRepository;
    @Autowired private CanonicalDerivationV2Service v2Service;

    @BeforeEach
    void wipe() {
        mongoTemplate.getDb().drop();
    }

    @Test
    void v2CanonicalDerivationRunsEndToEndAndHoldsInvariants() {
        seedSources();

        v2Service.rebuildCanonicalV2();

        // ROR backbone derived from the institution source fact.
        assertThat(scholardexAffiliationFactRepository.findAll())
                .anyMatch(a -> a.getRorIds().contains("0583a0t97"));

        // One canonical pub for the shared DOI.
        List<ScholardexPublicationFact> pubs = scholardexPublicationFactRepository.findAll();
        assertThat(pubs).hasSize(1);
        ScholardexPublicationFact pub = pubs.getFirst();
        assertThat(pub.getTitle()).isEqualTo("Shared Paper"); // OpenAlex title authoritative
        assertThat(pub.getEid()).isEqualTo("2-s2.0-X");        // Scopus eid enriched

        // The Scopus AU-ID folded into the OpenAlex-keyed author.
        ScholardexAuthorFact author = scholardexAuthorFactRepository.findAll().stream()
                .filter(a -> a.getScopusAuthorIds().contains("57000000001")).findFirst().orElseThrow();
        assertThat(author.getOrcidIds()).contains("0000-0003-1034-8409");

        // Referential integrity: pub.authorIds back-filled and every id references an existing author.
        Set<String> authorIds = scholardexAuthorFactRepository.findAll().stream()
                .map(ScholardexAuthorFact::getId).collect(Collectors.toSet());
        assertThat(pub.getAuthorIds()).isNotEmpty().allMatch(authorIds::contains);
        assertThat(pub.getAuthorIds()).contains(author.getId());

        // Edges: authorship (pub->author, both sources) + the ROR-backbone affiliation edges, endpoints resolve.
        String affId = ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport
                .buildRorBackboneAffiliationId("0583a0t97");
        assertThat(mongoTemplate.findAll(ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorshipFact.class))
                .anyMatch(e -> e.getPublicationId().equals(pub.getId()) && e.getAuthorId().equals(author.getId()));
        assertThat(mongoTemplate.findAll(ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorAffiliationFact.class))
                .anyMatch(e -> e.getAuthorId().equals(author.getId()) && e.getAffiliationId().equals(affId));
        assertThat(mongoTemplate.findAll(
                ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationAuthorAffiliationFact.class))
                .anyMatch(e -> e.getPublicationId().equals(pub.getId()) && e.getAuthorId().equals(author.getId())
                        && e.getAffiliationId().equals(affId));
    }

    private void seedSources() {
        OpenAlexInstitutionFact inst = new OpenAlexInstitutionFact();
        inst.setId("I123");
        inst.setRor("0583a0t97");
        inst.setDisplayName("West University of Timișoara");
        institutionFactRepository.save(inst);

        ScopusAuthorFact author = new ScopusAuthorFact();
        author.setAuthorId("57000000001");
        author.setName("Frincu, Marc");
        author.setSource("SCOPUS");
        author.setSourceRecordId("57000000001");
        scopusAuthorFactRepository.save(author);

        ScopusPublicationFact scopusPub = new ScopusPublicationFact();
        scopusPub.setEid("2-s2.0-X");
        scopusPub.setDoi("10.1/shared");
        scopusPub.setTitle("Scopus Shared Paper");
        scopusPub.setSource("SCOPUS");
        scopusPub.setSourceRecordId("2-s2.0-X");
        scopusPub.setAuthors(new java.util.ArrayList<>(List.of("57000000001")));
        scopusPublicationFactRepository.save(scopusPub);

        OpenAlexPublicationFact openAlexPub = new OpenAlexPublicationFact();
        openAlexPub.setSourceRecordId("W1");
        openAlexPub.setDoi("10.1/shared");
        openAlexPub.setTitle("Shared Paper");
        OpenAlexPublicationFact.AuthorRef ref = new OpenAlexPublicationFact.AuthorRef();
        ref.setDisplayName("Marc Frincu");
        ref.setOrcid("0000-0003-1034-8409");
        ref.setOpenAlexAuthorId("A5000000001");
        ref.getInstitutionRors().add("0583a0t97"); // UVT — drives the affiliation edges
        openAlexPub.setAuthorships(List.of(ref));
        openAlexPublicationFactRepository.save(openAlexPub);
    }
}
