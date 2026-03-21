package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.WoSRanking;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class WosRankingDetailsReadService {

    private final PostgresWosRankingDetailsReadPort postgresWosRankingDetailsReadPort;

    public Optional<WoSRanking> findByJournalId(String journalId) {
        return postgresWosRankingDetailsReadPort.findByJournalId(journalId);
    }
}
