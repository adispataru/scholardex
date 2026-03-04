package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.URAPUniversityRanking;
import ro.uvt.pokedex.core.repository.URAPUniversityRankingRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UrapRankingFacadeTest {

    @Mock
    private URAPUniversityRankingRepository repository;

    @InjectMocks
    private UrapRankingFacade facade;

    @Test
    void listAndFindDelegateToRepository() {
        URAPUniversityRanking ranking = new URAPUniversityRanking();
        ranking.setName("u1");
        when(repository.findAll()).thenReturn(List.of(ranking));
        when(repository.findById("u1")).thenReturn(Optional.of(ranking));

        assertEquals(1, facade.listRankings().size());
        assertTrue(facade.findRankingDetails("u1").isPresent());
    }
}
