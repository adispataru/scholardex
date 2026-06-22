package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.uvt.pokedex.core.service.derivation.CanonicalDerivationV2Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * H75 — trigger + quick count readout for the V2 canonical derivation engine, so we can run it on the real source
 * facts and inspect the output (the "run it and look" validation). Separate from {@code AdminInitializationController}
 * to avoid touching that controller's contract tests. Intended for agent-dev / admin use.
 */
@RestController
@RequestMapping("/admin/canonical-v2")
@RequiredArgsConstructor
public class AdminCanonicalV2Controller {

    private final CanonicalDerivationV2Service derivationV2Service;
    private final MongoTemplate mongoTemplate;

    /** Run the V2 derivation (affiliations → publications → authors) and return elapsed + canonical counts. */
    @PostMapping("/rebuild")
    public Map<String, Object> rebuild() {
        long startedAt = System.currentTimeMillis();
        derivationV2Service.rebuildCanonicalV2();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("elapsedMs", System.currentTimeMillis() - startedAt);
        out.put("affiliations", mongoTemplate.getCollection("scholardex.affiliation_facts").countDocuments());
        out.put("publications", mongoTemplate.getCollection("scholardex.publication_facts").countDocuments());
        out.put("authors", mongoTemplate.getCollection("scholardex.author_facts").countDocuments());
        out.put("authorshipEdges", mongoTemplate.getCollection("scholardex.authorship_facts").countDocuments());
        out.put("authorAffiliationEdges", mongoTemplate.getCollection("scholardex.author_affiliation_facts").countDocuments());
        out.put("pubAuthorAffiliationEdges", mongoTemplate.getCollection("scholardex.publication_author_affiliation_facts").countDocuments());
        out.put("sourceLinks", mongoTemplate.getCollection("scholardex.source_links").countDocuments());
        return out;
    }
}
