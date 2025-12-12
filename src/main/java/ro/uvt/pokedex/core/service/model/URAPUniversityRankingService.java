package ro.uvt.pokedex.core.service.model;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.URAPUniversityRanking;
import ro.uvt.pokedex.core.repository.URAPUniversityRankingRepository;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class URAPUniversityRankingService {
    private final URAPUniversityRankingRepository urapUniversityRankingRepository;
    private final Map<String, URAPUniversityRanking> cache = new HashMap<>();
    public URAPUniversityRanking getURAPUniversityRankingByName(String name) {
        if (cache.containsKey(name)) {
            return cache.get(name);
        }
        URAPUniversityRanking urapUniversityRanking = urapUniversityRankingRepository.findByNameIgnoreCase(name).stream().findFirst().orElse(null);
        if (urapUniversityRanking != null) {
            cache.put(name, urapUniversityRanking);
        }
        return urapUniversityRanking;
    }
}
