package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.reporting.Position;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Idempotent one-time migration that embeds researcher profile data into
 * the owning User document, migrates group membership from DBRef to
 * memberIds, and migrates ActivityInstance.researcherId from old ObjectId
 * to user email.
 *
 * <p>Safe to run on every startup — all three phases are skipped if the
 * target data is already present.
 */
@Component
@Order(20)
@RequiredArgsConstructor
@Slf4j
public class ResearcherProfileMigrationRunner implements ApplicationRunner {

    private static final String RESEARCHERS_COLLECTION = "scholardex.researchers";

    private final MongoTemplate mongoTemplate;
    private final UserRepository userRepository;
    private final GroupRepository groupRepository;

    @Override
    public void run(ApplicationArguments args) {
        log.info("ResearcherProfileMigration: starting");
        long t0 = System.currentTimeMillis();

        // ── Phase A: embed researcher profile into User ──────────────────
        int profilesEmbedded = migrateProfiles();

        // ── Phase B: migrate group.researchers → group.memberIds ─────────
        int groupsMigrated = migrateGroupMemberships();

        // ── Phase C: update ActivityInstance.researcherId (ObjectId → email)
        int activitiesMigrated = migrateActivityInstanceResearcherIds();

        log.info("ResearcherProfileMigration: done in {}ms — profiles={}, groups={}, activities={}",
                System.currentTimeMillis() - t0,
                profilesEmbedded, groupsMigrated, activitiesMigrated);
    }

    // ── Phase A ──────────────────────────────────────────────────────────

    private int migrateProfiles() {
        // Build a map: researcher ObjectId → raw document from old collection
        List<Document> allResearcherDocs =
                mongoTemplate.getCollection(RESEARCHERS_COLLECTION)
                             .find()
                             .into(new ArrayList<>());

        if (allResearcherDocs.isEmpty()) {
            log.debug("ResearcherProfileMigration/profiles: no researcher documents found — skipping");
            return 0;
        }

        Map<String, Document> byId = new HashMap<>();
        for (Document doc : allResearcherDocs) {
            Object idRaw = doc.get("_id");
            if (idRaw != null) byId.put(idRaw.toString(), doc);
        }

        int count = 0;
        List<User> allUsers = userRepository.findAll();
        for (User user : allUsers) {
            if (user.getResearcherProfile() != null) continue; // already migrated
            String rid = user.getResearcherId();
            if (rid == null || rid.isBlank()) continue;

            Document doc = byId.get(rid);
            if (doc == null) {
                log.warn("ResearcherProfileMigration/profiles: user={} has researcherId={} but no matching document",
                         user.getEmail(), rid);
                continue;
            }

            User.ResearcherProfile profile = documentToProfile(doc);
            user.setResearcherProfile(profile);
            userRepository.save(user);
            count++;
            log.debug("ResearcherProfileMigration/profiles: embedded profile for user={}", user.getEmail());
        }
        log.info("ResearcherProfileMigration/profiles: embedded {} profiles", count);
        return count;
    }

    private User.ResearcherProfile documentToProfile(Document doc) {
        User.ResearcherProfile p = new User.ResearcherProfile();
        p.setFirstName(doc.getString("firstName"));
        p.setLastName(doc.getString("lastName"));
        p.setScholarId(doc.getString("scholarId"));
        p.setPrimaryScholardexAuthorId(doc.getString("primaryScholardexAuthorId"));

        String posStr = doc.getString("position");
        if (posStr != null) {
            try { p.setPosition(Position.valueOf(posStr)); }
            catch (IllegalArgumentException e) {
                log.warn("ResearcherProfileMigration: unknown position '{}' — skipping", posStr);
            }
        }

        List<String> scopusIds = getStringList(doc, "scopusId");
        List<String> wosIds    = getStringList(doc, "wosId");
        p.setScopusId(scopusIds);
        p.setWosId(wosIds);
        return p;
    }

    @SuppressWarnings("unchecked")
    private List<String> getStringList(Document doc, String field) {
        Object raw = doc.get(field);
        if (raw instanceof List<?> list) {
            return list.stream().map(Object::toString).collect(Collectors.toList());
        }
        return new ArrayList<>();
    }

    // ── Phase B ──────────────────────────────────────────────────────────

    private int migrateGroupMemberships() {
        // Build reverse map: researcher ObjectId → user email
        Map<String, String> ridToEmail = new HashMap<>();
        for (User u : userRepository.findAll()) {
            if (u.getResearcherId() != null && !u.getResearcherId().isBlank()) {
                ridToEmail.put(u.getResearcherId(), u.getEmail());
            }
        }

        int count = 0;
        for (Group group : groupRepository.findAll()) {
            if (group.getResearchers() == null || group.getResearchers().isEmpty()) continue;

            // Ensure memberIds is mutable
            List<String> memberIds = group.getMemberIds() != null
                    ? new ArrayList<>(group.getMemberIds())
                    : new ArrayList<>();

            boolean changed = false;
            for (var researcher : group.getResearchers()) {
                if (researcher == null) continue;
                String email = ridToEmail.get(researcher.getId());
                if (email == null) {
                    log.warn("ResearcherProfileMigration/groups: group={} references researcherId={} with no matching user",
                             group.getId(), researcher.getId());
                    continue;
                }
                if (!memberIds.contains(email)) {
                    memberIds.add(email);
                    changed = true;
                }
            }

            if (changed) {
                group.setMemberIds(memberIds);
                groupRepository.save(group);
                count++;
                log.debug("ResearcherProfileMigration/groups: migrated group={}", group.getId());
            }
        }
        log.info("ResearcherProfileMigration/groups: migrated {} groups", count);
        return count;
    }

    // ── Phase C ──────────────────────────────────────────────────────────

    private int migrateActivityInstanceResearcherIds() {
        // Build map: researcher ObjectId → user email
        Map<String, String> ridToEmail = new HashMap<>();
        for (User u : userRepository.findAll()) {
            if (u.getResearcherId() != null && !u.getResearcherId().isBlank()) {
                ridToEmail.put(u.getResearcherId(), u.getEmail());
            }
        }

        // Find all activityInstances whose researcherId looks like an ObjectId (not an email).
        // Heuristic: emails always contain '@'; ObjectIds never do.
        // A single regex criterion is used — chaining .and() on the same field is not allowed
        // by the MongoDB driver because it would produce duplicate keys in the BSON document.
        Query query = new Query(
                Criteria.where("researcherId").regex("^[^@]+$")
        );
        List<Document> instances = mongoTemplate.find(query, Document.class, "activityInstances");

        int count = 0;
        for (Document doc : instances) {
            String oldId = doc.getString("researcherId");
            if (oldId == null) continue;
            String email = ridToEmail.get(oldId);
            if (email == null) {
                log.warn("ResearcherProfileMigration/activities: activityInstance={} has researcherId={} with no matching user",
                         doc.get("_id"), oldId);
                continue;
            }
            mongoTemplate.updateFirst(
                    new Query(Criteria.where("_id").is(doc.get("_id"))),
                    new Update().set("researcherId", email),
                    "activityInstances"
            );
            count++;
        }
        log.info("ResearcherProfileMigration/activities: migrated {} activity instances", count);
        return count;
    }
}
