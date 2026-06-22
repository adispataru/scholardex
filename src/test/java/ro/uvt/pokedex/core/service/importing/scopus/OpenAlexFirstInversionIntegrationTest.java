package ro.uvt.pokedex.core.service.importing.scopus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ro.uvt.pokedex.core.derivation.CanonicalDerivationIntegrationTestBase;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusPublicationFactRepository;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H73 S2.2 — end-to-end proof of the OpenAlex-first author inversion + pub field-precedence against a real
 * MongoDB, on a tiny fixture (one OpenAlex work sharing a DOI with one Scopus publication, one shared author).
 *
 * <p>Runs the real canon services in the rebuild order the migration uses:
 * <ol>
 *   <li>{@code openAlexCanonicalizationService.rebuildCanonicalFacts()} — mints the DOI-keyed canonical pub +
 *       the OpenAlex-keyed author, and writes the positional {@code (AUTHOR, SCOPUS, auid) -> openAlexAuthorId}
 *       inversion source-link;</li>
 *   <li>{@code authorCanonicalizationService} — resolves the Scopus AU-ID <em>into</em> the existing OpenAlex
 *       author via that source-link (no Scopus-keyed twin);</li>
 *   <li>{@code publicationCanonicalizationService} — resolves the Scopus pub into the OpenAlex-owned pub by DOI,
 *       enriching it (adds {@code eid}) without clobbering OpenAlex's title / owning source.</li>
 * </ol>
 */
class OpenAlexFirstInversionIntegrationTest extends CanonicalDerivationIntegrationTestBase {

    @Autowired private OpenAlexPublicationFactRepository openAlexPublicationFactRepository;
    @Autowired private ScopusPublicationFactRepository scopusPublicationFactRepository;
    @Autowired private ScopusAuthorFactRepository scopusAuthorFactRepository;
    @Autowired private ScholardexAuthorFactRepository scholardexAuthorFactRepository;
    @Autowired private ScholardexPublicationFactRepository scholardexPublicationFactRepository;
    @Autowired private ScholardexSourceLinkService sourceLinkService;

    @Autowired private OpenAlexCanonicalizationService openAlexCanonicalizationService;
    @Autowired private ScholardexAuthorCanonicalizationService authorCanonicalizationService;
    @Autowired private ScholardexPublicationCanonicalizationService publicationCanonicalizationService;

    private static final String DOI = "10.1000/shared-inversion";
    private static final String SCOPUS_AUID = "57000000001";

    @BeforeEach
    void wipe() {
        mongoTemplate.getDb().drop();
    }

    @Test
    void scopusAuIdResolvesIntoTheOpenAlexAuthorAndScopusPubEnrichesTheOpenAlexPub() {
        seedOpenAlexWork();
        seedScopusPublication();
        seedScopusAuthor();

        // 1) OpenAlex-first: mint the canonical pub + OpenAlex-keyed author, write the AU-ID -> author source-link.
        openAlexCanonicalizationService.rebuildCanonicalFacts();
        // 2) Scopus author canon: AU-ID must resolve INTO the OpenAlex author via that link (no Scopus-keyed twin).
        authorCanonicalizationService.rebuildCanonicalAuthorFactsFromScopusFacts(CanonicalBuildOptions.defaults());
        // 3) Scopus pub canon: resolve into the OpenAlex pub by DOI, enrich-only (defer-to-OpenAlex).
        publicationCanonicalizationService.rebuildCanonicalPublicationFactsFromScopusFacts(CanonicalBuildOptions.defaults());

        // ── Author inversion: exactly one canonical author, OpenAlex-keyed, carrying the Scopus AU-ID ──────────
        List<ScholardexAuthorFact> authors = scholardexAuthorFactRepository.findAll();
        assertThat(authors).hasSize(1);
        ScholardexAuthorFact author = authors.getFirst();
        assertThat(author.getOrcidIds()).contains("0000-0003-1034-8409");
        assertThat(author.getOpenAlexAuthorIds()).contains("A5000000001");
        assertThat(author.getScopusAuthorIds()).contains(SCOPUS_AUID);
        // The Scopus-keyed twin id must NOT exist — the AU-ID folded into the OpenAlex identity instead.
        String scopusKeyedId = authorCanonicalizationService.buildCanonicalAuthorId(SCOPUS_AUID, "Frincu, Marc");
        assertThat(author.getId()).isNotEqualTo(scopusKeyedId);
        assertThat(scholardexAuthorFactRepository.findById(scopusKeyedId)).isEmpty();

        // ── The inversion source-link persisted with the right key and target ──────────────────────────────────
        Optional<ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink> link =
                sourceLinkService.findByKey(ScholardexEntityType.AUTHOR, "SCOPUS", SCOPUS_AUID);
        assertThat(link).isPresent();
        assertThat(link.get().getCanonicalEntityId()).isEqualTo(author.getId());
        assertThat(link.get().getLinkState()).isEqualTo(ScholardexSourceLinkService.STATE_LINKED);

        // ── Pub field-precedence: one canonical pub, OpenAlex title/source authoritative, Scopus eid enriched ──
        List<ScholardexPublicationFact> pubs = scholardexPublicationFactRepository.findAll();
        assertThat(pubs).hasSize(1);
        ScholardexPublicationFact pub = pubs.getFirst();
        assertThat(pub.getTitle()).isEqualTo("OpenAlex authoritative title");
        assertThat(pub.getSource()).isEqualTo("OPENALEX");
        assertThat(pub.getEid()).isEqualTo("2-s2.0-inv");
        assertThat(pub.getAuthorIds()).contains(author.getId());
    }

    private void seedOpenAlexWork() {
        OpenAlexPublicationFact work = new OpenAlexPublicationFact();
        work.setSourceRecordId("W-INV-1");
        work.setOpenalexWorkId("W-INV-1");
        work.setDoi(DOI);
        work.setTitle("OpenAlex authoritative title");
        work.setCoverDate("2021-05-01");
        work.setCreator("Marc Frincu");
        work.setCitedByCount(120);
        OpenAlexPublicationFact.AuthorRef ref = new OpenAlexPublicationFact.AuthorRef();
        ref.setDisplayName("Marc Frincu");
        ref.setOrcid("0000-0003-1034-8409");
        ref.setOpenAlexAuthorId("A5000000001");
        ref.setCorresponding(true);
        work.setAuthorships(List.of(ref));
        openAlexPublicationFactRepository.save(work);
    }

    private void seedScopusPublication() {
        ScopusPublicationFact pub = new ScopusPublicationFact();
        pub.setEid("2-s2.0-inv");
        pub.setDoi(DOI);
        pub.setTitle("Scopus title (should not win)");
        pub.setSource("SCOPUS");
        pub.setSourceRecordId("2-s2.0-inv");
        pub.setCoverDate("2021-05-01");
        pub.setCitedByCount(40);
        pub.setAuthors(new java.util.ArrayList<>(List.of(SCOPUS_AUID)));
        scopusPublicationFactRepository.save(pub);
    }

    private void seedScopusAuthor() {
        ScopusAuthorFact author = new ScopusAuthorFact();
        author.setAuthorId(SCOPUS_AUID);
        author.setName("Frincu, Marc");
        author.setSource("SCOPUS");
        author.setSourceRecordId(SCOPUS_AUID);
        scopusAuthorFactRepository.save(author);
    }
}
