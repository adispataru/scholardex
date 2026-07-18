package ro.uvt.pokedex.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.user.User;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One-time migration for the OIDC-only cutover (H84): local password authentication no longer
 * exists, so every stored password hash is scrambled to a random value. This neutralizes the
 * accounts provisioned with the historical shared default password that rode in with the dev
 * database dump — even if a password code path were ever reintroduced, there is nothing guessable
 * left. Idempotent via a marker document; safe on empty databases.
 */
@Component
@Order(5)
public class LocalPasswordScrambleRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalPasswordScrambleRunner.class);
    static final String MARKER_COLLECTION = "scholardex.app_migrations";
    static final String MARKER_ID = "local-password-scramble-v1";

    private final MongoTemplate mongoTemplate;
    private final PasswordEncoder passwordEncoder;

    public LocalPasswordScrambleRunner(MongoTemplate mongoTemplate, PasswordEncoder passwordEncoder) {
        this.mongoTemplate = mongoTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (mongoTemplate.exists(Query.query(Criteria.where("_id").is(MARKER_ID)), MARKER_COLLECTION)) {
            return;
        }
        List<User> users = mongoTemplate.findAll(User.class);
        int scrambled = 0;
        for (User user : users) {
            mongoTemplate.updateFirst(
                    Query.query(Criteria.where("_id").is(user.getEmail())),
                    new Update().set("password", passwordEncoder.encode(UUID.randomUUID().toString())),
                    User.class);
            scrambled++;
        }
        mongoTemplate.save(new org.bson.Document("_id", MARKER_ID).append("appliedAt", Instant.now().toString())
                .append("scrambledUsers", scrambled), MARKER_COLLECTION);
        log.info("Local-password scramble complete: {} account(s) neutralized (OIDC-only auth).", scrambled);
    }
}
