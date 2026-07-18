package ro.uvt.pokedex.core.service.importing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.DepartmentAffiliation;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.reporting.Position;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.repository.InstitutionRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentAffiliationRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.GroupMembershipService;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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

@Service
public class GroupService {
    private static final Pattern SIMPLE_EMAIL_PATTERN = Pattern.compile("^[^\\s@]++@[^\\s@.]++(?:\\.[^\\s@.]++)++$");

    /** Ordered prefix → position map. Order matters: more specific prefixes ("Asist. Cerc.") come before less specific ("Asist."). */
    private static final List<Map.Entry<String, Position>> POSITION_PREFIXES = List.of(
            Map.entry("Asist. Cerc.", Position.ASIST_C),
            Map.entry("Asist.",       Position.ASIST_UNIV),
            Map.entry("Lect.",        Position.LECT_UNIV),
            Map.entry("Conf.",        Position.CONF_UNIV),
            Map.entry("Prof.",        Position.PROF_UNIV),
            Map.entry("CS III",       Position.CS_III),
            Map.entry("CS II",        Position.CS_II),
            Map.entry("CS I",         Position.CS_I)
    );

    private final GroupRepository groupRepository;
    private final InstitutionRepository institutionRepository;
    private final DepartmentRepository departmentRepository;
    private final DepartmentAffiliationRepository departmentAffiliationRepository;
    private final GroupMembershipService groupMembershipService;
    private final PasswordEncoder passwordEncoder;
    private final UserService userService;
    private final int requiredColumnCount;

    public GroupService(
            GroupRepository groupRepository,
            InstitutionRepository institutionRepository,
            DepartmentRepository departmentRepository,
            DepartmentAffiliationRepository departmentAffiliationRepository,
            GroupMembershipService groupMembershipService,
            PasswordEncoder passwordEncoder,
            UserService userService,
            @Value("${h07.groups.import.required-column-count:6}") int requiredColumnCount
    ) {
        this.groupRepository = groupRepository;
        this.institutionRepository = institutionRepository;
        this.departmentRepository = departmentRepository;
        this.departmentAffiliationRepository = departmentAffiliationRepository;
        this.groupMembershipService = groupMembershipService;
        this.passwordEncoder = passwordEncoder;
        this.userService = userService;
        this.requiredColumnCount = requiredColumnCount;
    }

    /**
     * Imports groups + researchers + memberships + department affiliations from a CSV.
     * <p>Required columns: {@code groupName, email, lastName, firstName, position, departmentId, [scopusIds]}.
     * @param file CSV upload (UTF-8).
     * @param institutionId target institution; departments referenced in the CSV must belong to it.
     */
    @Transactional
    public void importGroupsFromCsv(MultipartFile file, String institutionId) throws Exception {
        importGroupsFromCsv(file.getInputStream(), institutionId);
    }

    /**
     * Stream-based variant for callers that don't have a {@link MultipartFile} (e.g. the dev
     * seed runner loading a classpath resource). Same validation and transactional semantics.
     */
    @Transactional
    public void importGroupsFromCsv(java.io.InputStream csvStream, String institutionId) throws Exception {
        if (institutionId == null || institutionId.isBlank()) {
            throw new IllegalArgumentException("institutionId is required.");
        }
        institutionRepository.findById(institutionId)
                .orElseThrow(() -> new IllegalArgumentException("Institution not found: " + institutionId));

        Map<String, Department> departmentsByIdInScope = new HashMap<>();
        for (Department department : departmentRepository.findByInstitutionId(institutionId)) {
            departmentsByIdInScope.put(department.getId(), department);
        }

        List<CsvRow> rows = parseAndValidateCsv(csvStream, departmentsByIdInScope);

        Map<String, Group> groupsByName = new LinkedHashMap<>();
        Map<String, List<String>> userIdsByGroupName = new LinkedHashMap<>();
        Map<String, String> primaryDeptByUserId = new LinkedHashMap<>();

        for (CsvRow row : rows) {
            Group group = groupsByName.computeIfAbsent(row.groupName(), name -> {
                Group g = new Group();
                g.setName(name);
                g.setDescription("Imported from CSV");
                g.setInstitutionId(institutionId);
                g.setDepartmentIds(new ArrayList<>());
                Instant now = Instant.now();
                g.setCreatedAt(now);
                g.setUpdatedAt(now);
                return g;
            });
            if (!group.getDepartmentIds().contains(row.departmentId())) {
                group.getDepartmentIds().add(row.departmentId());
            }

            Optional<User> existing = userService.getUserByEmail(row.email());
            User user;
            if (existing.isEmpty()) {
                user = new User();
                user.setEmail(row.email());
                // OIDC-only auth (H84): provisioned accounts get an unusable scrambled password —
                // researchers sign in through Keycloak with their e-uvt.ro identity.
                user.setPassword(passwordEncoder.encode(java.util.UUID.randomUUID().toString()));
                user.getRoles().add(UserRole.RESEARCHER);
                user.setResearcherProfile(buildProfile(row));
                userService.createUser(user);
            } else {
                user = existing.get();
                user.setResearcherProfile(buildProfile(row));
                user.getRoles().add(UserRole.RESEARCHER);
                userService.updateUser(user.getEmail(), user);
            }

            userIdsByGroupName.computeIfAbsent(row.groupName(), k -> new ArrayList<>()).add(row.email());
            primaryDeptByUserId.putIfAbsent(row.email(), row.departmentId());
        }

        // Persist groups first so they have ids for membership rows.
        groupRepository.saveAll(groupsByName.values());

        for (Group group : groupsByName.values()) {
            List<String> userIds = userIdsByGroupName.getOrDefault(group.getName(), List.of());
            groupMembershipService.addMembers(group.getId(), userIds);
        }

        upsertPrimaryDepartmentAffiliations(primaryDeptByUserId);
    }

    private void upsertPrimaryDepartmentAffiliations(Map<String, String> primaryDeptByUserId) {
        if (primaryDeptByUserId.isEmpty()) return;
        Map<String, List<DepartmentAffiliation>> currentByUser = new HashMap<>();
        for (DepartmentAffiliation aff : departmentAffiliationRepository
                .findByUserIdInAndValidToIsNull(primaryDeptByUserId.keySet())) {
            currentByUser.computeIfAbsent(aff.getUserId(), k -> new ArrayList<>()).add(aff);
        }

        List<DepartmentAffiliation> toSave = new ArrayList<>();
        LocalDate today = LocalDate.now();
        Instant now = Instant.now();
        for (Map.Entry<String, String> entry : primaryDeptByUserId.entrySet()) {
            String userId = entry.getKey();
            String departmentId = entry.getValue();
            List<DepartmentAffiliation> current = currentByUser.getOrDefault(userId, List.of());
            boolean alreadyPrimary = current.stream()
                    .anyMatch(a -> departmentId.equals(a.getDepartmentId()) && a.isPrimary());
            if (alreadyPrimary) continue;
            // Demote any other current primary; respect joint appointments by keeping non-primary rows intact.
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
            }
        }
        if (!toSave.isEmpty()) {
            departmentAffiliationRepository.saveAll(toSave);
        }
    }

    private User.ResearcherProfile buildProfile(CsvRow row) {
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setFirstName(row.firstName());
        profile.setLastName(row.lastName());
        profile.setPosition(row.position());
        if (row.scopusIds().length > 0) {
            profile.setScopusId(new ArrayList<>(Arrays.asList(row.scopusIds())));
        }
        return profile;
    }

    private Position parsePosition(String field, int rowNumber, List<String> errors) {
        if (field == null || field.isBlank()) {
            errors.add("Row " + rowNumber + ": position is required.");
            return null;
        }
        for (Map.Entry<String, Position> entry : POSITION_PREFIXES) {
            if (field.startsWith(entry.getKey())) return entry.getValue();
        }
        errors.add("Row " + rowNumber + ": unknown position '" + field + "'.");
        return null;
    }

    private List<CsvRow> parseAndValidateCsv(java.io.InputStream csvStream, Map<String, Department> departmentsInScope) throws Exception {
        List<CsvRow> rows = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(csvStream, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null || header.isBlank()) {
                throw new IllegalArgumentException("CSV header is missing.");
            }

            String[] headerFields = header.split(",", -1);
            if (headerFields.length < requiredColumnCount) {
                throw new IllegalArgumentException(
                        "CSV schema is invalid. Expected at least " + requiredColumnCount + " columns "
                                + "(groupName, email, lastName, firstName, position, departmentId, [scopusIds]).");
            }

            String line;
            int rowNumber = 1;
            while ((line = reader.readLine()) != null) {
                rowNumber++;
                if (line.isBlank()) continue;

                String[] fields = line.split(",", -1);
                if (fields.length < requiredColumnCount) {
                    errors.add("Row " + rowNumber + ": expected at least " + requiredColumnCount + " columns.");
                    continue;
                }

                String groupName    = fields[0].trim();
                String email        = fields[1].trim();
                String lastName     = fields[2].trim();
                String firstName    = fields[3].trim();
                String positionRaw  = fields[4].trim();
                String departmentId = fields[5].trim();
                String[] scopusIds = fields.length > 6
                        ? Arrays.stream(fields[6].split(";"))
                                .map(String::trim)
                                .filter(v -> !v.isBlank())
                                .toArray(String[]::new)
                        : new String[0];

                if (groupName.isBlank() || email.isBlank() || lastName.isBlank()
                        || firstName.isBlank() || positionRaw.isBlank() || departmentId.isBlank()) {
                    errors.add("Row " + rowNumber + ": required fields are missing.");
                    continue;
                }
                if (!SIMPLE_EMAIL_PATTERN.matcher(email).matches()) {
                    errors.add("Row " + rowNumber + ": invalid email format.");
                    continue;
                }
                if (!departmentsInScope.containsKey(departmentId)) {
                    errors.add("Row " + rowNumber + ": departmentId '" + departmentId
                            + "' not found in target institution.");
                    continue;
                }
                Position position = parsePosition(positionRaw, rowNumber, errors);
                if (position == null) continue;

                rows.add(new CsvRow(groupName, email, lastName, firstName, position, departmentId, scopusIds));
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("CSV parsing failed. Ensure file is valid UTF-8 CSV.");
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("CSV validation failed: " + String.join(" ", errors));
        }
        return rows;
    }

    private record CsvRow(
            String groupName, String email, String lastName, String firstName,
            Position position, String departmentId, String[] scopusIds) {}
}
