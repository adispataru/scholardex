package ro.uvt.pokedex.core.service.wos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.wos.WosAccessionLookup;
import ro.uvt.pokedex.core.model.wos.WosAccessionLookup.Status;
import ro.uvt.pokedex.core.repository.wos.WosAccessionLookupRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WosAccessionServiceTest {

    @Mock private WosAccessionLookupRepository repository;
    @Mock private WosOpenUrlClient client;

    private WosAccessionService service() {
        return new WosAccessionService(repository, client, 90);
    }

    private static WosAccessionLookup cached(Status status, String wosId, Instant checkedAt) {
        WosAccessionLookup c = new WosAccessionLookup();
        c.setDoi("10.1/x");
        c.setStatus(status);
        c.setWosId(wosId);
        c.setCheckedAt(checkedAt);
        return c;
    }

    @Test
    void keysAreLowerCasedAndStrippedOfResolverPrefixes() {
        assertEquals("10.1016/j.fss.2019.09.014", WosAccessionService.key("https://doi.org/10.1016/J.FSS.2019.09.014"));
        assertEquals("10.1/x", WosAccessionService.key("doi:10.1/X "));
    }

    @Test
    void aFoundAnswerIsServedFromTheCacheWithoutACall() {
        when(repository.findById("10.1/x")).thenReturn(Optional.of(cached(Status.FOUND, "WOS:1", Instant.now())));
        assertEquals("WOS:1", service().resolve("10.1/X").orElseThrow());
        verify(client, never()).lookup(any());
    }

    @Test
    void aRecentNoRecordIsTrustedButAnOldOneIsAskedAgain() {
        when(repository.findById("10.1/x")).thenReturn(Optional.of(cached(Status.NOT_FOUND, null, Instant.now().minus(10, ChronoUnit.DAYS))));
        assertTrue(service().resolve("10.1/x").isEmpty());
        verify(client, never()).lookup(any());

        when(repository.findById("10.1/x")).thenReturn(Optional.of(cached(Status.NOT_FOUND, null, Instant.now().minus(200, ChronoUnit.DAYS))));
        when(client.lookup("10.1/x")).thenReturn(WosOpenUrlClient.Lookup.found("WOS:2"));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        assertEquals("WOS:2", service().resolve("10.1/x").orElseThrow());
    }

    @Test
    void aLiveAnswerIsStoredAndAnUnavailableGatewayIsNot() {
        when(repository.findById("10.1/x")).thenReturn(Optional.empty());
        when(client.lookup("10.1/x")).thenReturn(WosOpenUrlClient.Lookup.notFound());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        assertTrue(service().resolve("10.1/x").isEmpty());
        verify(repository).save(any());

        when(repository.findById("10.1/y")).thenReturn(Optional.empty());
        when(client.lookup("10.1/y")).thenReturn(WosOpenUrlClient.Lookup.unavailable());
        assertTrue(service().resolve("10.1/y").isEmpty());
        verify(repository, org.mockito.Mockito.times(1)).save(any());
    }
}
