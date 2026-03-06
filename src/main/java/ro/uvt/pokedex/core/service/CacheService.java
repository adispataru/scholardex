package ro.uvt.pokedex.core.service;

import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.scopus.Affiliation;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.repository.reporting.CoreConferenceRankingRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusAffiliationRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusAuthorRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusForumRepository;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import java.util.stream.Collectors;

@Service
@Data
public class CacheService {
    private final ScopusAuthorRepository scopusAuthorRepository;
    private final ScopusAffiliationRepository scopusAffiliationRepository;
    private final ConcurrentMap<String, Forum> forumCache;
    private final ScopusForumRepository scopusForumRepository;

    private final CoreConferenceRankingRepository coreConferenceRankingRepository;
    private final GroupRepository groupRepository;
    private final ConcurrentMap<String, List<CoreConferenceRanking>> confRankingCache;
    private final Map<String, Affiliation> affiliationCache = new HashMap<>();
    private final Map<String, Author> authorCache = new HashMap<>();
    private final Set<String> universityAuthorIds = new HashSet<>();

    @Autowired
    public CacheService(
            ScopusForumRepository scopusForumRepository,
            CoreConferenceRankingRepository coreConferenceRankingRepository,
            ScopusAuthorRepository scopusAuthorRepository,
            ScopusAffiliationRepository scopusAffiliationRepository,
            GroupRepository groupRepository
    ) {
        this.scopusForumRepository = scopusForumRepository;
        this.coreConferenceRankingRepository = coreConferenceRankingRepository;
        this.scopusAffiliationRepository = scopusAffiliationRepository;
        this.groupRepository = groupRepository;
        this.forumCache = new ConcurrentHashMap<>();
        this.scopusForumRepository.findAll().forEach(f -> {
            forumCache.put(f.getId(), f);
        });

        this.confRankingCache = new ConcurrentHashMap<>();
        confRankingCache.putAll(coreConferenceRankingRepository.findAll().stream().collect(Collectors.groupingBy(CoreConferenceRanking::getAcronym)));
        this.scopusAuthorRepository = scopusAuthorRepository;
        List<Author> all = scopusAuthorRepository.findAll();
        groupRepository.findAll().forEach(group ->
                group.getResearchers().forEach(researcher ->
                        universityAuthorIds.addAll(researcher.getScopusId())));
        all.forEach(a -> {
            authorCache.put(a.getId(), a);
        });
        scopusAffiliationRepository.findAll().forEach(a -> {
            affiliationCache.put(a.getAfid(), a);
        });
    }

    public List<CoreConferenceRanking> getCachedConfRankings(String acronym) {
        return confRankingCache.computeIfAbsent(acronym, coreConferenceRankingRepository::findAllByAcronymIgnoreCase);
    }

    public Forum getCachedForums(String issn) {
        return forumCache.get(issn);
    }

    public Affiliation getAffiliation(String id) {
        return affiliationCache.get(id);
    }

    public void putAffiliation(String id, Affiliation affiliation) {
        affiliationCache.put(id, affiliation);
    }

    public Author getAuthor(String id) {
        return authorCache.get(id);
    }

    public void putAuthor(String id, Author author) {
        authorCache.put(id, author);
    }

    public Forum getForum(String id) {
        return forumCache.get(id);
    }

    public void putForum(String id, Forum forum) {
        forumCache.put(id, forum);
    }

    public void clear() {
        affiliationCache.clear();
        authorCache.clear();
        forumCache.clear();
    }

    public void syncCoreConferenceRankingCacheToDb() {
        confRankingCache.forEach((id, rankings) -> coreConferenceRankingRepository.saveAll(rankings));
    }

    public void saveAllAuthors() {
        scopusAuthorRepository.saveAll(authorCache.values());
    }

    public void saveAllForums() {
        scopusForumRepository.saveAll(forumCache.values());
    }

    public void saveAllAffiliations() {
        scopusAffiliationRepository.saveAll(affiliationCache.values());
    }
}
