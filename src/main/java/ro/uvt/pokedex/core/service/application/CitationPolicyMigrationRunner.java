package ro.uvt.pokedex.core.service.application;

import com.mongodb.client.model.Filters;
import com.mongodb.client.result.UpdateResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.reporting.scoring.SelfCitationPolicy;

import java.util.List;

/**
 * H61 back-compat migration. Indicators persisted before the self-citation {@code policy} enum stored a boolean
 * {@code kind.excludeSelf}; the {@code IndicatorKind.Citations} record now has a {@code policy} component, so those
 * documents fail to deserialize (the canonical constructor rejects a null policy). This runner rewrites the raw
 * documents — {@code excludeSelf:true → policy:CANDIDATE_ONLY}, {@code false/absent → NONE} — <b>without</b>
 * deserializing them (the typed repository would itself fail on the legacy shape).
 *
 * <p>Runs on every boot (idempotent: the filter only matches documents that still carry {@code kind.excludeSelf}),
 * so a deploy self-heals against a database that still holds pre-H61 indicators. Ordered early so it runs before any
 * request loads an indicator.</p>
 */
@Component
@Order(0)
@RequiredArgsConstructor
@Slf4j
public class CitationPolicyMigrationRunner implements CommandLineRunner {

    private final MongoTemplate mongoTemplate;

    @Override
    public void run(@NonNull String... args) {
        UpdateResult result = mongoTemplate.getCollection("indicators").updateMany(
                Filters.and(
                        Filters.regex("kind._class", "Citations$"),
                        Filters.exists("kind.excludeSelf", true)),
                List.of(
                        new Document("$set", new Document("kind.policy",
                                new Document("$cond", List.of(
                                        new Document("$eq", List.of("$kind.excludeSelf", true)),
                                        SelfCitationPolicy.CANDIDATE_ONLY.name(),
                                        SelfCitationPolicy.NONE.name())))),
                        new Document("$unset", "kind.excludeSelf")));
        if (result.getModifiedCount() > 0) {
            log.warn("H61: migrated {} Citations indicator(s) from legacy kind.excludeSelf → kind.policy",
                    result.getModifiedCount());
        }
    }
}
