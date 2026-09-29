package ro.uvt.pokedex.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** H128 — the profile that bypasses authentication does not come up inside a cluster. */
class AgentDevSecurityConfigGuardTest {

    @Test
    void refusesToStartInsideAKubernetesPod() {
        assertThrows(IllegalStateException.class, () -> AgentDevSecurityConfig.refuseInsideACluster("10.43.0.1"));
    }

    @Test
    void startsOnADevelopersMachine() {
        assertDoesNotThrow(() -> AgentDevSecurityConfig.refuseInsideACluster(null));
        assertDoesNotThrow(() -> AgentDevSecurityConfig.refuseInsideACluster(" "));
    }
}
