package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.URAPUniversityRanking;
import ro.uvt.pokedex.core.repository.URAPUniversityRankingRepository;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UrapRankingFacade {

    private final URAPUniversityRankingRepository urapUniversityRankingRepository;

    public List<URAPUniversityRanking> listRankings() {
        return urapUniversityRankingRepository.findAll();
    }

    public Optional<URAPUniversityRanking> findRankingDetails(String id) {
        return urapUniversityRankingRepository.findById(id);
    }
}
