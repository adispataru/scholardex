package ro.uvt.pokedex.core.service.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import ro.uvt.pokedex.core.model.reporting.uefiscdi.CompetitionFamily;
import ro.uvt.pokedex.core.model.reporting.uefiscdi.PrincipalAuthorRule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * H138 — the 13 competition domains of the PN-IV PD/TE 2026 packages (Anexa 1), each with the platform's reading
 * of its Web of Science categories, its Anexa 2 rule family and its Anexa 6 principal-author rule. Read from the
 * bundled {@code report-data/uefiscdi-domains-2026.json}, reviewed by Adrian Spătaru on 2026-10-02.
 *
 * <p>The {@code Domain} documents the indicators score against are derived from these entries by
 * {@link UefiscdiDomainCatalogService} (id {@code "UEFISCDI 2026 — <name>"}); this class is the static catalog.</p>
 */
public final class UefiscdiCompetitionDomains {

    static final String FIXTURE = "report-data/uefiscdi-domains-2026.json";
    public static final String DOMAIN_ID_PREFIX = "UEFISCDI 2026 — ";

    public record CompetitionDomain(int code, String name, CompetitionFamily family, PrincipalAuthorRule principalAuthorRule,
                                    String ercPanels, List<String> wosCategories) {
        /** The id of the {@code Domain} document derived from this entry. */
        public String domainId() {
            return DOMAIN_ID_PREFIX + name;
        }
    }

    private static final List<CompetitionDomain> DOMAINS = load();

    private UefiscdiCompetitionDomains() {
    }

    public static List<CompetitionDomain> all() {
        return DOMAINS;
    }

    public static Optional<CompetitionDomain> byCode(int code) {
        return DOMAINS.stream().filter(d -> d.code() == code).findFirst();
    }

    public static Optional<CompetitionDomain> byDomainId(String domainId) {
        return DOMAINS.stream().filter(d -> d.domainId().equals(domainId)).findFirst();
    }

    private static List<CompetitionDomain> load() {
        try {
            JsonNode root = new ObjectMapper().readTree(new ClassPathResource(FIXTURE).getInputStream());
            List<CompetitionDomain> out = new ArrayList<>();
            for (JsonNode node : root.get("domains")) {
                List<String> keys = new ArrayList<>();
                node.get("wosCategories").forEach(k -> keys.add(k.asText()));
                out.add(new CompetitionDomain(node.get("code").asInt(), node.get("name").asText(),
                        CompetitionFamily.valueOf(node.get("family").asText()),
                        PrincipalAuthorRule.valueOf(node.get("principalAuthorRule").asText()),
                        node.get("ercPanels").asText(), List.copyOf(keys)));
            }
            return List.copyOf(out);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + FIXTURE, e);
        }
    }
}
