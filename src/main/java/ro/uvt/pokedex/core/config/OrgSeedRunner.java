package ro.uvt.pokedex.core.config;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.Institution;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.DivisionType;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.repository.InstitutionRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentAffiliationRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.org.MembershipRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;
import ro.uvt.pokedex.core.service.importing.GroupService;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;

/**
 * Bootstraps a minimal org hierarchy (UVT institution → Faculty/Institute/Service divisions
 * → departments) for local development. Active under the {@code dev} and {@code agent-dev}
 * profiles only.
 *
 * <h2>Modes</h2>
 * <ul>
 *   <li>Default ({@code dev.seed.reset=false}): if no institutions exist, seed the bundle.
 *       Otherwise leave the database alone.</li>
 *   <li>{@code dev.seed.reset=true}: wipe and re-seed.
 *       <strong>Refuses to wipe</strong> when the existing data looks larger than the bundled
 *       sample (interpreted as "real" user data) unless
 *       {@code dev.seed.confirm-destroy=true} is also set.</li>
 * </ul>
 *
 * <p>The wipe always logs a multi-line WARN banner before doing anything irreversible.
 */
@Component
@Profile({"dev", "agent-dev"})
@RequiredArgsConstructor
@Order(0)
public class OrgSeedRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(OrgSeedRunner.class);
    private static final String INSTITUTION_ID = "inst-uvt";
    private static final String INSTITUTION_NAME = "UVT";
    private static final String SAMPLE_CSV_PATH = "dev-seed/groups-sample.csv";

    /** Thresholds that approximate the bundled seed's footprint. Existing data at or below
     *  this size is assumed to be a previous seed run and can be wiped without confirmation. */
    static final long BUNDLED_INSTITUTION_LIMIT = 1;
    static final long BUNDLED_DIVISION_LIMIT = 3;
    static final long BUNDLED_DEPARTMENT_LIMIT = 4;
    static final long BUNDLED_GROUP_LIMIT = 3;
    static final long BUNDLED_MEMBERSHIP_LIMIT = 7;
    static final long BUNDLED_AFFILIATION_LIMIT = 7;

    private final InstitutionRepository institutionRepository;
    private final OrgDivisionRepository orgDivisionRepository;
    private final DepartmentRepository departmentRepository;
    private final DepartmentAffiliationRepository departmentAffiliationRepository;
    private final MembershipRepository membershipRepository;
    private final GroupRepository groupRepository;
    private final GroupService groupService;

    @Value("${dev.seed.reset:false}")
    private boolean reset;

    @Value("${dev.seed.confirm-destroy:false}")
    private boolean confirmDestroy;

    @Value("${dev.seed.import-sample-csv:true}")
    private boolean importSampleCsv;

    @Override
    public void run(String... args) throws Exception {
        if (reset) {
            if (!attemptWipe()) {
                // Refused — leave the DB alone, do not seed.
                return;
            }
        }
        if (institutionRepository.count() > 0) {
            log.info("Org seed skipped: institutions already present (count={})", institutionRepository.count());
            return;
        }
        seed();
        if (importSampleCsv) {
            importSample();
        }
    }

    /**
     * Inspects current collection sizes and decides whether the wipe is safe to run.
     * @return {@code true} if the wipe ran; {@code false} if it refused (caller should abort).
     */
    private boolean attemptWipe() {
        Counts counts = currentCounts();
        boolean looksReal = counts.exceedsBundledFootprint();

        if (looksReal && !confirmDestroy) {
            logRefuseBanner(counts);
            return false;
        }

        logDestroyBanner(counts, looksReal);
        wipe();
        return true;
    }

    private Counts currentCounts() {
        return new Counts(
                institutionRepository.count(),
                orgDivisionRepository.count(),
                departmentRepository.count(),
                groupRepository.count(),
                membershipRepository.count(),
                departmentAffiliationRepository.count());
    }

    private void logRefuseBanner(Counts c) {
        log.warn("================================================================");
        log.warn("OrgSeedRunner REFUSED to wipe — existing data looks larger than");
        log.warn("the bundled seed footprint:");
        log.warn("    institutions={}  divisions={}  departments={}",
                c.institutions(), c.divisions(), c.departments());
        log.warn("    groups={}  memberships={}  departmentAffiliations={}",
                c.groups(), c.memberships(), c.affiliations());
        log.warn("");
        log.warn("To wipe anyway, restart with:");
        log.warn("    --dev.seed.reset=true --dev.seed.confirm-destroy=true");
        log.warn("");
        log.warn("Skipping wipe AND skipping seed. App is starting normally.");
        log.warn("================================================================");
    }

    private void logDestroyBanner(Counts c, boolean overrode) {
        log.warn("================================================================");
        log.warn("OrgSeedRunner is about to DESTROY org-hierarchy collections:");
        log.warn("    institutions, org_divisions, departments,");
        log.warn("    scholardex.groups, memberships, department_affiliations");
        if (overrode) {
            log.warn("");
            log.warn("dev.seed.confirm-destroy=true overrode the size guard. Sizes:");
        } else {
            log.warn("");
            log.warn("Current sizes (within bundled-seed footprint):");
        }
        log.warn("    institutions={}  divisions={}  departments={}",
                c.institutions(), c.divisions(), c.departments());
        log.warn("    groups={}  memberships={}  departmentAffiliations={}",
                c.groups(), c.memberships(), c.affiliations());
        log.warn("================================================================");
    }

    private void wipe() {
        membershipRepository.deleteAll();
        departmentAffiliationRepository.deleteAll();
        groupRepository.deleteAll();
        departmentRepository.deleteAll();
        orgDivisionRepository.deleteAll();
        institutionRepository.deleteAll();
    }

    private void seed() {
        Instant now = Instant.now();

        Institution uvt = new Institution();
        uvt.setId(INSTITUTION_ID);
        uvt.setName(INSTITUTION_NAME);
        uvt.setDescription("Universitatea de Vest din Timișoara (dev seed)");
        institutionRepository.save(uvt);

        OrgDivision fmi = newDivision("div-fmi", "Faculty of Mathematics and Computer Science", "FMI",
                DivisionType.FACULTY, INSTITUTION_ID, now);
        OrgDivision iaer = newDivision("div-iaer", "Institute for Advanced Environmental Research", "IAER",
                DivisionType.INSTITUTE, INSTITUTION_ID, now);
        OrgDivision ccs = newDivision("div-ccs", "Centrul de Calcul Service", "CCS",
                DivisionType.SERVICE, INSTITUTION_ID, now);
        orgDivisionRepository.saveAll(List.of(fmi, iaer, ccs));

        Department cs = newDepartment("dept-cs", "Computer Science", fmi.getId(), INSTITUTION_ID, now);
        Department math = newDepartment("dept-math", "Mathematics", fmi.getId(), INSTITUTION_ID, now);
        Department env = newDepartment("dept-env", "Environmental Data Science", iaer.getId(), INSTITUTION_ID, now);
        Department infra = newDepartment("dept-infra", "Research Infrastructure", ccs.getId(), INSTITUTION_ID, now);
        departmentRepository.saveAll(List.of(cs, math, env, infra));

        log.info("Org seed complete: institution={}({}), divisions=3, departments=4",
                INSTITUTION_NAME, INSTITUTION_ID);
    }

    private void importSample() {
        ClassPathResource resource = new ClassPathResource(SAMPLE_CSV_PATH);
        if (!resource.exists()) {
            log.warn("Sample CSV not found at classpath:{} — skipping group import", SAMPLE_CSV_PATH);
            return;
        }
        try (InputStream in = resource.getInputStream()) {
            groupService.importGroupsFromCsv(in, INSTITUTION_ID);
            log.info("Sample group CSV imported: groups={}, memberships={}",
                    groupRepository.count(), membershipRepository.count());
        } catch (Exception ex) {
            log.error("Sample group CSV import failed", ex);
        }
    }

    private OrgDivision newDivision(String id, String name, String shortName, DivisionType type,
                                    String institutionId, Instant now) {
        OrgDivision d = new OrgDivision();
        d.setId(id);
        d.setName(name);
        d.setShortName(shortName);
        d.setType(type);
        d.setInstitutionId(institutionId);
        d.setCreatedAt(now);
        d.setUpdatedAt(now);
        return d;
    }

    private Department newDepartment(String id, String name, String divisionId,
                                     String institutionId, Instant now) {
        Department d = new Department();
        d.setId(id);
        d.setName(name);
        d.setDivisionId(divisionId);
        d.setInstitutionId(institutionId);
        d.setCreatedAt(now);
        d.setUpdatedAt(now);
        return d;
    }

    record Counts(long institutions, long divisions, long departments,
                  long groups, long memberships, long affiliations) {
        boolean exceedsBundledFootprint() {
            return institutions > BUNDLED_INSTITUTION_LIMIT
                    || divisions > BUNDLED_DIVISION_LIMIT
                    || departments > BUNDLED_DEPARTMENT_LIMIT
                    || groups > BUNDLED_GROUP_LIMIT
                    || memberships > BUNDLED_MEMBERSHIP_LIMIT
                    || affiliations > BUNDLED_AFFILIATION_LIMIT;
        }
    }
}
