package ro.uvt.pokedex.core.service;

import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.scopus.Affiliation;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.repository.reporting.CoreConferenceRankingRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;
import ro.uvt.pokedex.core.repository.reporting.RankingRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusAffiliationRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusAuthorRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusForumRepository;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Data
public class CacheService {
    private final boolean resetIF2022 = false;
    private final ScopusAuthorRepository scopusAuthorRepository;
    private final ScopusAffiliationRepository scopusAffiliationRepository;
    private boolean computeRanks = false;
    private boolean computeRanksAndQuarters = false;
    private boolean mergeRankings = true;
    private final ConcurrentMap<String, Forum> forumCache;
    private final ScopusForumRepository scopusForumRepository;

    private final CoreConferenceRankingRepository coreConferenceRankingRepository;
    private final GroupRepository groupRepository;
    private final ConcurrentMap<String, List<CoreConferenceRanking>> confRankingCache;

    private final RankingRepository rankingRepository;
    private final ConcurrentMap<String, List<WoSRanking>> rankingCacheByIssn = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, WoSRanking> rankingCacheById = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> topRankingCache = new ConcurrentHashMap<>();
    private final Map<String, Affiliation> affiliationCache = new HashMap<>();
    private final Map<String, Author> authorCache = new HashMap<>();
    private final Set<String> wosCategories = new HashSet<>();
    private final Set<String> universityAuthorIds = new HashSet<>();

    public Set<String> getWosCategories() {
        return wosCategories;
    }

    @Autowired
    public CacheService(ScopusForumRepository scopusForumRepository, CoreConferenceRankingRepository coreConferenceRankingRepository, RankingRepository rankingRepository, ScopusAuthorRepository scopusAuthorRepository, ScopusAffiliationRepository scopusAffiliationRepository, GroupRepository groupRepository) {
        this.scopusForumRepository = scopusForumRepository;
        this.coreConferenceRankingRepository = coreConferenceRankingRepository;
        this.rankingRepository = rankingRepository;
        this.scopusAffiliationRepository = scopusAffiliationRepository;
        this.groupRepository = groupRepository;
        this.forumCache = new ConcurrentHashMap<>();
        this.scopusForumRepository.findAll().forEach(f -> {
            forumCache.put(f.getId(), f);
        });

        cacheRankings();

        this.confRankingCache = new ConcurrentHashMap<>();
        confRankingCache.putAll(coreConferenceRankingRepository.findAll().stream().collect(Collectors.groupingBy(CoreConferenceRanking::getAcronym)));
        this.scopusAuthorRepository = scopusAuthorRepository;
//        Institution uvt = institutionRepository.findByNameIgnoreCase("UVT").getFirst();
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

        if(resetIF2022){
            System.out.println("Resetting impact factor");
            List<WoSRanking> toDelete = new ArrayList<>();
            for (WoSRanking r : rankingCacheById.values()) {
                HashSet<String> categories = new HashSet<>(r.getWebOfScienceCategoryIndex().keySet());

                    if (!r.getScore().getIF().isEmpty()) {
                        boolean empty = true;
                        for(Double d : r.getScore().getIF().values())
                            if(d != null){
                                empty = false;
                                break;
                            }
                        if(empty && r.getScore().getAis().isEmpty()) {
//                            r.getWebOfScienceCategoryIndex().remove(cat);
                        }
                    }

                if(r.getWebOfScienceCategoryIndex().isEmpty()){
                    toDelete.add(r);
                }
            }

            System.out.println("Successfully reset impact factor. Saving cache...");
            syncRankingCacheToDb();
            System.out.println("Successfully saved cache. Deleting empty rankings...");
            rankingRepository.deleteAll(toDelete);
            System.out.println("Successfully deleted empty rankings...");
        }



    }

    public void cacheRankings() {
        clearRankingCache();
        List<WoSRanking> all = rankingRepository.findAll();
        all.forEach(r -> {
            rankingCacheById.put(r.getId(), r);
            cacheRankingByIssnKey(r.getIssn(), r);
            cacheRankingByIssnKey(r.getEIssn(), r);
            r.getWebOfScienceCategoryIndex().forEach((key, val) -> {
                wosCategories.add(key);
                for(int year : val.getQAis().keySet()) {
                    if (val.getQAis().get(year).equals(WoSRanking.Quarter.Q1)) {
                        String topCacheKey = key + "-" + year;
                        topRankingCache.computeIfAbsent(topCacheKey, k -> 0);
                        topRankingCache.computeIfPresent(topCacheKey, (k, v) -> v + 1);
                    }
                }

            });

        });
    }

    public List<CoreConferenceRanking> getCachedConfRankings(String acronym) {
        return confRankingCache.computeIfAbsent(acronym, coreConferenceRankingRepository::findAllByAcronymIgnoreCase);
    }

    public Forum getCachedForums(String issn) {
        return forumCache.get(issn);
    }

    public List<WoSRanking> getCachedRankingsByIssn(String issn) {
        String normalizedIssn = normalizeIssnKey(issn);
        if (normalizedIssn == null) {
            return List.of();
        }

        return rankingCacheByIssn.computeIfAbsent(normalizedIssn, key -> {
            List<WoSRanking> byIssn = rankingRepository.findAllByIssn(key);
            List<WoSRanking> byeIssn = rankingRepository.findAllByeIssn(key);
            return Stream.concat(byIssn.stream(), byeIssn.stream())
                    .collect(Collectors.toMap(WoSRanking::getId, ranking -> ranking, (left, right) -> left, LinkedHashMap::new))
                    .values()
                    .stream()
                    .toList();
        });
    }

    public WoSRanking getCachedRankingById(String id) {
        if(id != null) {
            return rankingCacheById.get(id);
        }
        return null;
    }

    public int getCachedTopRankings(String webOfScienceCategoryIndex, Integer year) {
        String cacheKey = webOfScienceCategoryIndex + "-" + year;
        return topRankingCache.get(cacheKey);
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

    public void syncRankingCacheToDb() {
//        rankingCacheByIssn.forEach((issn, rankings) -> rankingRepository.saveAll(rankings));
        rankingRepository.saveAll(getAllRankings());
//        topRankingCache.forEach((key, rankings) -> rankingRepository.saveAll(rankings));
    }

    public void putCachedRankingById(String id, WoSRanking ranking) {
        if(id == null){
            System.out.println("Null id on ranking " + ranking);
            return;
        }
        rankingCacheById.put(id, ranking);
    }

    public void syncCoreConferenceRankingCacheToDb() {
        confRankingCache.forEach((id, rankings) -> coreConferenceRankingRepository.saveAll(rankings));
    }

    public void clearRankingCache() {
        rankingCacheByIssn.clear();
        rankingCacheById.clear();
        topRankingCache.clear();
    }

    private void cacheRankingByIssnKey(String key, WoSRanking ranking) {
        String normalizedKey = normalizeIssnKey(key);
        if (normalizedKey == null) {
            return;
        }

        rankingCacheByIssn.merge(normalizedKey, new ArrayList<>(List.of(ranking)), (existing, incoming) -> {
            Map<String, WoSRanking> merged = new LinkedHashMap<>();
            existing.forEach(item -> merged.put(item.getId(), item));
            incoming.forEach(item -> merged.put(item.getId(), item));
            return new ArrayList<>(merged.values());
        });
    }

    private String normalizeIssnKey(String key) {
        if (key == null) {
            return null;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    public List<WoSRanking> getAllRankings() {
        return rankingCacheById.values().stream().toList();
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
