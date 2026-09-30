package ro.uvt.pokedex.core.service.reporting;

import java.util.Set;

/**
 * H129 — which of the CNFIS sheets a person fills follows from their CNATDCU domain (cod_DS), as the guide
 * assigns them: the artistic domains fill Anexa 5.1, sport 5.2, the humanities listed by the guide 5.3;
 * everybody fills Anexa 5. The codes are those of the "Domenii-CNATDCU" sheet of the templates.
 */
public final class CnfisDomains {

    /** 70 Arhitectură, 71 Urbanism, 72 Arte vizuale, 73 Teatru, 74 Cinematografie și media, 75 Muzică (interpretare). */
    static final Set<String> ARTS = Set.of("70", "71", "72", "73", "74", "75");
    /** 76 and 77: Știința sportului și educației fizice. */
    static final Set<String> SPORT = Set.of("76", "77");
    /** The guide, 3.3: 63, 64 Filologie, 65 Filosofie, 66, 67 Istorie, 68 Teologie, 69 Studii culturale, 721 Istoria și teoria artei, 751 Muzică. */
    static final Set<String> HUMANITIES = Set.of("63", "64", "65", "66", "67", "68", "69", "721", "751");

    private CnfisDomains() {
    }

    public static boolean fillsArts(String domainCode) {
        return domainCode != null && ARTS.contains(domainCode.trim());
    }

    public static boolean fillsSport(String domainCode) {
        return domainCode != null && SPORT.contains(domainCode.trim());
    }

    public static boolean fillsHumanities(String domainCode) {
        return domainCode != null && HUMANITIES.contains(domainCode.trim());
    }
}
