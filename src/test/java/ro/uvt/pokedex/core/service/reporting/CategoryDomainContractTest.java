package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.service.reporting.ReportingLookupPort;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CategoryDomainContractTest {

    @Test
    void abstractBasesShareSameCategoryDomainDecision() {
        Domain allDomain = new Domain();
        allDomain.setName("ALL");

        Domain specificDomain = new Domain();
        specificDomain.setName("SPECIFIC");
        specificDomain.setWosCategories(List.of("COMPUTER SCIENCE-SCIE"));

        TestForumBase forumBase = new TestForumBase(Mockito.mock(ReportingLookupPort.class));
        TestWoSBase wosBase = new TestWoSBase(Mockito.mock(ReportingLookupPort.class));

        assertEquals(
                forumBase.exposedIsCategoryInDomain(allDomain, "COMPUTER SCIENCE-SCIE"),
                wosBase.exposedIsCategoryInDomain(allDomain, "COMPUTER SCIENCE-SCIE")
        );
        assertEquals(
                forumBase.exposedIsCategoryInDomain(allDomain, "MALFORMED"),
                wosBase.exposedIsCategoryInDomain(allDomain, "MALFORMED")
        );
        assertEquals(
                forumBase.exposedIsCategoryInDomain(specificDomain, "COMPUTER SCIENCE-SCIE"),
                wosBase.exposedIsCategoryInDomain(specificDomain, "COMPUTER SCIENCE-SCIE")
        );
        assertEquals(
                forumBase.exposedIsCategoryInDomain(specificDomain, "OTHER-SCIE"),
                wosBase.exposedIsCategoryInDomain(specificDomain, "OTHER-SCIE")
        );
        assertEquals(
                forumBase.exposedIsCategoryInDomain(specificDomain, null),
                wosBase.exposedIsCategoryInDomain(specificDomain, null)
        );
    }

    private static final class TestForumBase extends AbstractForumScoringService {
        private TestForumBase(ReportingLookupPort cacheService) {
            super(cacheService);
        }

        private boolean exposedIsCategoryInDomain(Domain domain, String category) {
            return isCategoryInDomain(domain, category);
        }

        @Override
        public Score getScore(Publication publication, Indicator indicator) {
            return new Score();
        }

        @Override
        public Score getScore(ActivityInstance activity, Indicator indicator) {
            return new Score();
        }

        @Override
        public String getDescription() {
            return "test";
        }
    }

    private static final class TestWoSBase extends AbstractWoSForumScoringService {
        private TestWoSBase(ReportingLookupPort cacheService) {
            super(cacheService);
        }

        private boolean exposedIsCategoryInDomain(Domain domain, String category) {
            return isCategoryInDomain(domain, category);
        }

        @Override
        public Score getScore(Publication publication, Indicator indicator) {
            return new Score();
        }

        @Override
        public Score getScore(ActivityInstance activity, Indicator indicator) {
            return new Score();
        }

        @Override
        public String getDescription() {
            return "test";
        }
    }
}
