package ro.uvt.pokedex.core.service.issn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in: the real register, through the production client (UA header, deadlines, title parsing). ISSN_PORTAL_IT=1 */
class IssnPortalLiveIT {

    @Test
    @EnabledIfEnvironmentVariable(named = "ISSN_PORTAL_IT", matches = ".+")
    void theRealRegisterAnswersAsTheClientExpects() {
        IssnPortalClient client = new IssnPortalClient("https://portal.issn.org", 15_000L, true,
                "ScholarDex/1.0 (mailto:adrian.spataru@e-uvt.ro)");
        IssnPortalClient.Lookup real = client.lookup("1583-7165");
        System.out.println("1583-7165 -> " + real);
        assertTrue(real.exists());
        assertTrue(real.keyTitle().orElse("").contains("Anale"));
        assertFalse(client.lookup("9999-9994").exists(), "well-formed but unassigned");
    }
}
