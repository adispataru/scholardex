package ro.uvt.pokedex.core.service.importing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.DepartmentAffiliation;
import ro.uvt.pokedex.core.model.org.DivisionType;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.model.reporting.Position;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.repository.InstitutionRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentAffiliationRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;
import ro.uvt.pokedex.core.service.UserService;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Imports staff (researchers + their faculty/department affiliation) from a CSV.
 *
 * <p>Counterpart to {@link GroupService} but keyed on the org tree rather than groups. Required columns:
 * {@code Faculty, Department, Email, Last Name, First Name, Position, [Id SCOPUS]}. Faculty and Department are
 * matched <em>by name</em> (normalized: trimmed, whitespace-collapsed, diacritic- and case-insensitive) within
 * the target institution and <strong>auto-created when missing</strong> — a Faculty becomes a
 * {@link DivisionType#FACULTY} division, a Department a {@link Department} under it. {@code Id SCOPUS} is an
 * optional {@code ;}-separated list of Scopus author ids.
 *
 * <p>Best-effort, not all-or-nothing: malformed rows are skipped and reported in the {@link StaffImportResult}
 * so a single bad row does not block the rest of the sheet.
 */
@Service
public class StaffImportService {

    private static final Pattern SIMPLE_EMAIL_PATTERN =
            Pattern.compile("^[^\\s@]++@[^\\s@.]++(?:\\.[^\\s@.]++)++$");

    /**
     * Ordered normalized-prefix → position map. Both abbreviated ("Lect. Dr.") and full ("Lector universitar")
     * Romanian forms appear in real staff sheets. Order matters: more specific prefixes come first (research
     * assistant before teaching assistant; CS III before CS II before CS I). Keys are pre-normalized.
     */
    private static final List<Map.Entry<String, Position>> POSITION_PREFIXES = List.of(
            Map.entry("asist. cerc.",          Position.ASIST_C),
            Map.entry("asistent de cercetare", Position.ASIST_C),
            Map.entry("cs iii",                Position.CS_III),
            Map.entry("cs ii",                 Position.CS_II),
            Map.entry("cs i",                  Position.CS_I),
            Map.entry("asist.",                Position.ASIST_UNIV),
            Map.entry("asistent",              Position.ASIST_UNIV),
            Map.entry("lect.",                 Position.LECT_UNIV),
            Map.entry("lector",                Position.LECT_UNIV),
            Map.entry("conf.",                 Position.CONF_UNIV),
            Map.entry("conferentiar",          Position.CONF_UNIV),
            Map.entry("prof.",                 Position.PROF_UNIV),
            Map.entry("profesor",              Position.PROF_UNIV)
    );

    private final InstitutionRepository institutionRepository;
    private final OrgDivisionRepository orgDivisionRepository;
    private final DepartmentRepository departmentRepository;
    private final DepartmentAffiliationRepository departmentAffiliationRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserService userService;
    private final int requiredColumnCount;

    public StaffImportService(
            InstitutionRepository institutionRepository,
            OrgDivisionRepository orgDivisionRepository,
            DepartmentRepository departmentRepository,
            DepartmentAffiliationRepository departmentAffiliationRepository,
            PasswordEncoder passwordEncoder,
            UserService userService,
            @Value("${h07.staff.import.required-column-count:6}") int requiredColumnCount
    ) {
        this.institutionRepository = institutionRepository;
        this.orgDivisionRepository = orgDivisionRepository;
        this.departmentRepository = departmentRepository;
        this.departmentAffiliationRepository = departmentAffiliationRepository;
        this.passwordEncoder = passwordEncoder;
        this.userService = userService;
        this.requiredColumnCount = requiredColumnCount;
    }

    @Transactional
    public StaffImportResult importStaffFromCsv(MultipartFile file, String institutionId) throws Exception {
        return importStaffFromCsv(file.getInputStream(), institutionId);
    }

    /** Stream variant (e.g. seed runner). Same validation and transactional semantics. */
    @Transactional
    public StaffImportResult importStaffFromCsv(java.io.InputStream csvStream, String institutionId) throws Exception {
        if (institutionId == null || institutionId.isBlank()) {
            throw new IllegalArgumentException("Target institution is required.");
        }
        institutionRepository.findById(institutionId)
                .orElseThrow(() -> new IllegalArgumentException("Institution not found: " + institutionId));

        // Existing org tree, indexed by normalized name for resolve-or-create.
        Map<String, OrgDivision> divisionByNorm = new HashMap<>();
        for (OrgDivision d : orgDivisionRepository.findByInstitutionId(institutionId)) {
            divisionByNorm.putIfAbsent(normalize(d.getName()), d);
        }
        Map<String, Department> departmentByDivAndNorm = new HashMap<>();
        for (Department dep : departmentRepository.findByInstitutionId(institutionId)) {
            departmentByDivAndNorm.putIfAbsent(dep.getDivisionId() + "::" + normalize(dep.getName()), dep);
        }

        List<CsvRow> rows = parseCsv(csvStream);
        StaffImportResult result = new StaffImportResult();
        result.skipped.addAll(rows.stream().filter(r -> r.error() != null).map(CsvRow::error).toList());

        Map<String, String> primaryDeptByUserId = new LinkedHashMap<>();
        for (CsvRow row : rows) {
            if (row.error() != null) continue;

            OrgDivision division = resolveOrCreateDivision(row.faculty(), institutionId, divisionByNorm, result);
            Department department = resolveOrCreateDepartment(row.department(), division, departmentByDivAndNorm, result);

            Optional<User> existing = userService.getUserByEmail(row.email());
            if (existing.isEmpty()) {
                User user = new User();
                user.setEmail(row.email());
                // OIDC-only auth (H84): provisioned accounts get an unusable scrambled password —
                // researchers sign in through Keycloak with their e-uvt.ro identity.
                user.setPassword(passwordEncoder.encode(java.util.UUID.randomUUID().toString()));
                user.getRoles().add(UserRole.RESEARCHER);
                user.setResearcherProfile(mergeProfile(new User.ResearcherProfile(), row));
                userService.createUser(user);
                result.usersCreated++;
            } else {
                User user = existing.get();
                User.ResearcherProfile profile = user.getResearcherProfile() != null
                        ? user.getResearcherProfile() : new User.ResearcherProfile();
                user.setResearcherProfile(mergeProfile(profile, row));
                user.getRoles().add(UserRole.RESEARCHER);
                userService.updateUser(user.getEmail(), user);
                result.usersUpdated++;
            }

            primaryDeptByUserId.putIfAbsent(row.email(), department.getId());
        }

        result.affiliationsCreated += upsertPrimaryDepartmentAffiliations(primaryDeptByUserId);
        return result;
    }

    private OrgDivision resolveOrCreateDivision(String facultyName, String institutionId,
                                                Map<String, OrgDivision> divisionByNorm, StaffImportResult result) {
        String norm = normalize(facultyName);
        OrgDivision existing = divisionByNorm.get(norm);
        if (existing != null) return existing;
        OrgDivision created = new OrgDivision();
        created.setInstitutionId(institutionId);
        created.setType(DivisionType.FACULTY);
        created.setName(cleanName(facultyName));
        Instant now = Instant.now();
        created.setCreatedAt(now);
        created.setUpdatedAt(now);
        OrgDivision saved = orgDivisionRepository.save(created);
        divisionByNorm.put(norm, saved);
        result.divisionsCreated++;
        return saved;
    }

    private Department resolveOrCreateDepartment(String departmentName, OrgDivision division,
                                                 Map<String, Department> departmentByDivAndNorm, StaffImportResult result) {
        String key = division.getId() + "::" + normalize(departmentName);
        Department existing = departmentByDivAndNorm.get(key);
        if (existing != null) return existing;
        Department created = new Department();
        created.setDivisionId(division.getId());
        created.setInstitutionId(division.getInstitutionId());
        created.setName(cleanName(departmentName));
        Instant now = Instant.now();
        created.setCreatedAt(now);
        created.setUpdatedAt(now);
        Department saved = departmentRepository.save(created);
        departmentByDivAndNorm.put(key, saved);
        result.departmentsCreated++;
        return saved;
    }

    /** Updates the CSV-sourced fields, preserving everything else on an existing profile (orcid, wosId, etc.). */
    private User.ResearcherProfile mergeProfile(User.ResearcherProfile profile, CsvRow row) {
        profile.setFirstName(row.firstName());
        profile.setLastName(row.lastName());
        profile.setPosition(row.position());
        if (row.scopusIds().length > 0) {
            profile.setScopusId(new ArrayList<>(Arrays.asList(row.scopusIds())));
        }
        return profile;
    }

    /** Mirrors {@link GroupService}: set the row's department as the user's current primary, demoting any other. */
    private int upsertPrimaryDepartmentAffiliations(Map<String, String> primaryDeptByUserId) {
        if (primaryDeptByUserId.isEmpty()) return 0;
        Map<String, List<DepartmentAffiliation>> currentByUser = new HashMap<>();
        for (DepartmentAffiliation aff : departmentAffiliationRepository
                .findByUserIdInAndValidToIsNull(primaryDeptByUserId.keySet())) {
            currentByUser.computeIfAbsent(aff.getUserId(), k -> new ArrayList<>()).add(aff);
        }

        List<DepartmentAffiliation> toSave = new ArrayList<>();
        LocalDate today = LocalDate.now();
        Instant now = Instant.now();
        int created = 0;
        for (Map.Entry<String, String> entry : primaryDeptByUserId.entrySet()) {
            String userId = entry.getKey();
            String departmentId = entry.getValue();
            List<DepartmentAffiliation> current = currentByUser.getOrDefault(userId, List.of());
            boolean alreadyPrimary = current.stream()
                    .anyMatch(a -> departmentId.equals(a.getDepartmentId()) && a.isPrimary());
            if (alreadyPrimary) continue;
            for (DepartmentAffiliation a : current) {
                if (a.isPrimary() && !departmentId.equals(a.getDepartmentId())) {
                    a.setPrimary(false);
                    toSave.add(a);
                }
            }
            DepartmentAffiliation existing = current.stream()
                    .filter(a -> departmentId.equals(a.getDepartmentId()))
                    .findFirst()
                    .orElse(null);
            if (existing != null) {
                existing.setPrimary(true);
                toSave.add(existing);
            } else {
                DepartmentAffiliation fresh = new DepartmentAffiliation();
                fresh.setUserId(userId);
                fresh.setDepartmentId(departmentId);
                fresh.setPrimary(true);
                fresh.setValidFrom(today);
                fresh.setCreatedAt(now);
                toSave.add(fresh);
                created++;
            }
        }
        if (!toSave.isEmpty()) {
            departmentAffiliationRepository.saveAll(toSave);
        }
        return created;
    }

    private List<CsvRow> parseCsv(java.io.InputStream csvStream) throws Exception {
        List<CsvRow> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(csvStream, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null || header.isBlank()) {
                throw new IllegalArgumentException("CSV header is missing.");
            }
            if (header.split(",", -1).length < requiredColumnCount) {
                throw new IllegalArgumentException(
                        "CSV schema is invalid. Expected at least " + requiredColumnCount + " columns "
                                + "(Faculty, Department, Email, Last Name, First Name, Position, [Id SCOPUS]).");
            }

            String line;
            int rowNumber = 1;
            while ((line = reader.readLine()) != null) {
                rowNumber++;
                if (line.isBlank()) continue;

                String[] f = line.split(",", -1);
                if (f.length < requiredColumnCount) {
                    rows.add(CsvRow.invalid("Row " + rowNumber + ": expected at least " + requiredColumnCount + " columns."));
                    continue;
                }
                String faculty   = f[0].trim();
                String department = f[1].trim();
                String email     = f[2].trim();
                String lastName  = f[3].trim();
                String firstName = f[4].trim();
                String posRaw    = f[5].trim();
                String[] scopusIds = f.length > 6
                        ? Arrays.stream(f[6].split(";")).map(String::trim).filter(v -> !v.isBlank()).toArray(String[]::new)
                        : new String[0];

                if (faculty.isBlank() || department.isBlank() || email.isBlank()
                        || lastName.isBlank() || firstName.isBlank() || posRaw.isBlank()) {
                    rows.add(CsvRow.invalid("Row " + rowNumber + ": required fields are missing."));
                    continue;
                }
                if (!SIMPLE_EMAIL_PATTERN.matcher(email).matches()) {
                    rows.add(CsvRow.invalid("Row " + rowNumber + ": invalid email '" + email + "'."));
                    continue;
                }
                Position position = parsePosition(posRaw);
                if (position == null) {
                    rows.add(CsvRow.invalid("Row " + rowNumber + ": unknown position '" + posRaw + "'."));
                    continue;
                }
                rows.add(new CsvRow(faculty, department, email, lastName, firstName, position, scopusIds, null));
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("CSV parsing failed. Ensure the file is valid UTF-8 CSV.");
        }
        return rows;
    }

    private static Position parsePosition(String field) {
        String norm = normalize(field);
        for (Map.Entry<String, Position> entry : POSITION_PREFIXES) {
            if (norm.startsWith(entry.getKey())) return entry.getValue();
        }
        return null;
    }

    /** Trim, collapse whitespace; keep original casing/diacritics for display. */
    private static String cleanName(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }

    /** Match key: trimmed, whitespace-collapsed, diacritic-stripped, lower-cased. */
    private static String normalize(String s) {
        if (s == null) return "";
        String stripped = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return stripped.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private record CsvRow(String faculty, String department, String email, String lastName, String firstName,
                          Position position, String[] scopusIds, String error) {
        static CsvRow invalid(String error) {
            return new CsvRow(null, null, null, null, null, null, new String[0], error);
        }
    }

    /** Per-import counters + skipped-row reasons, surfaced to the admin as a flash message. */
    public static final class StaffImportResult {
        public int usersCreated;
        public int usersUpdated;
        public int divisionsCreated;
        public int departmentsCreated;
        public int affiliationsCreated;
        public final List<String> skipped = new ArrayList<>();

        public String toSummaryMessage() {
            StringBuilder sb = new StringBuilder();
            sb.append("Staff import complete: ")
                    .append(usersCreated).append(" created, ")
                    .append(usersUpdated).append(" updated; ")
                    .append(divisionsCreated).append(" faculties + ")
                    .append(departmentsCreated).append(" departments created; ")
                    .append(affiliationsCreated).append(" new affiliations.");
            if (!skipped.isEmpty()) {
                sb.append(" Skipped ").append(skipped.size()).append(" row(s): ")
                        .append(String.join(" ", skipped));
            }
            return sb.toString();
        }
    }
}
