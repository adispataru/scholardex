package ro.uvt.pokedex.core.service.importing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import ro.uvt.pokedex.core.model.Institution;
import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.reporting.Position;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.repository.InstitutionRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;
import ro.uvt.pokedex.core.service.ResearcherService;
import ro.uvt.pokedex.core.service.UserService;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class GroupService {
    private static final Pattern SIMPLE_EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final GroupRepository groupRepository;
    private final InstitutionRepository institutionRepository;
    private final ResearcherService researcherService;
    private final PasswordEncoder passwordEncoder;
    private final UserService userService;
    private final String defaultPassword;
    private final int requiredColumnCount;

    public GroupService(
            GroupRepository groupRepository,
            InstitutionRepository institutionRepository,
            ResearcherService researcherService,
            PasswordEncoder passwordEncoder,
            UserService userService,
            @Value("${user.default.password}") String defaultPassword,
            @Value("${h07.groups.import.required-column-count:5}") int requiredColumnCount
    ) {
        this.groupRepository = groupRepository;
        this.institutionRepository = institutionRepository;
        this.researcherService = researcherService;
        this.passwordEncoder = passwordEncoder;
        this.userService = userService;
        this.defaultPassword = defaultPassword;
        this.requiredColumnCount = requiredColumnCount;
    }

    public void importGroupsFromCsv(MultipartFile file) throws Exception {
        List<Institution> uvt = institutionRepository.findByNameIgnoreCase("UVT");
        if(uvt.isEmpty())
            return;
        Map<String, Group> groups = new HashMap<>();
        List<CsvRow> rows = parseAndValidateCsv(file);
        for (CsvRow row : rows) {
            String groupName = row.groupName();
            User user = new User();
            user.setEmail(row.email());
            user.setPassword(passwordEncoder.encode(defaultPassword));

            Group group = groups.get(groupName);
            if(group == null) {
                group = new Group();
                group.setName(groupName);
                group.setDescription("Imported from CSV");
                group.setInstitution(uvt.getFirst());
            }

            Researcher researcher = null;
            Optional<User> userByEmail = userService.getUserByEmail(user.getEmail());
            if(userByEmail.isEmpty()){
                researcher = new Researcher();
                populateResearcher(row, researcher);
                Researcher savedResearcher = researcherService.saveResearcher(researcher);
                user.setResearcherId(savedResearcher.getId());
                user.getRoles().add(UserRole.RESEARCHER);
                userService.createUser(user);
            }else{
                User savedUser = userByEmail.get();
                boolean found = false;
                if(savedUser.getResearcherId() != null){
                    Optional<Researcher> researcherById = researcherService.findResearcherById(savedUser.getResearcherId());
                    if (researcherById.isPresent()) {
                        researcher = researcherById.get();
                        populateResearcher(row, researcher);
                        researcherService.saveResearcher(researcher);
                        found = true;
                    }
                }
                if(!found){
                    researcher = new Researcher();
                    populateResearcher(row, researcher);
                    Researcher savedResearcher = researcherService.saveResearcher(researcher);
                    savedUser.setResearcherId(savedResearcher.getId());
                }
                user.getRoles().add(UserRole.RESEARCHER);
                userService.updateUser(user.getEmail(), savedUser);
            }
            group.getResearchers().add(researcher);
            groups.put(groupName, group);
        }
        groupRepository.saveAll(groups.values());
    }

    private void populateResearcher(CsvRow row, Researcher researcher) {
        researcher.setFirstName(row.firstName());
        researcher.setLastName(row.lastName());
        researcher.setPosition(parsePosition(row.position()));
        if(row.scopusIds().length > 0){
            for(String scopusId : row.scopusIds()){
                researcher.getScopusId().add(scopusId);
            }
        }
    }

    private Position parsePosition(String field) {
        if(field.startsWith("Asist. Cerc."))
            return Position.ASIST_C;
        if(field.startsWith("Asist."))
            return Position.ASIST_UNIV;
        if(field.startsWith("Lect."))
            return Position.LECT_UNIV;
        if(field.startsWith("Conf."))
            return Position.CONF_UNIV;
        if(field.startsWith("Prof."))
            return Position.PROF_UNIV;
        if(field.startsWith("CS I"))
            return Position.CS_I;
        if(field.startsWith("CS II"))
            return Position.CS_II;
        if(field.startsWith("CS III"))
            return Position.CS_III;
        return Position.OTHER;
    }

    private List<CsvRow> parseAndValidateCsv(MultipartFile file) throws Exception {
        List<CsvRow> rows = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null || header.isBlank()) {
                throw new IllegalArgumentException("CSV header is missing.");
            }

            String[] headerFields = header.split(",", -1);
            if (headerFields.length < requiredColumnCount) {
                throw new IllegalArgumentException("CSV schema is invalid. Expected at least " + requiredColumnCount + " columns.");
            }

            String line;
            int rowNumber = 1;
            while ((line = reader.readLine()) != null) {
                rowNumber++;
                if (line.isBlank()) {
                    continue;
                }

                String[] fields = line.split(",", -1);
                if (fields.length < requiredColumnCount) {
                    errors.add("Row " + rowNumber + ": expected at least " + requiredColumnCount + " columns.");
                    continue;
                }

                String groupName = fields[0].trim();
                String email = fields[1].trim();
                String lastName = fields[2].trim();
                String firstName = fields[3].trim();
                String position = fields[4].trim();
                String[] scopusIds = fields.length > 5
                        ? Arrays.stream(fields[5].split(";"))
                        .map(String::trim)
                        .filter(v -> !v.isBlank())
                        .toArray(String[]::new)
                        : new String[0];

                if (groupName.isBlank() || email.isBlank() || lastName.isBlank() || firstName.isBlank() || position.isBlank()) {
                    errors.add("Row " + rowNumber + ": required fields are missing.");
                    continue;
                }
                if (!SIMPLE_EMAIL_PATTERN.matcher(email).matches()) {
                    errors.add("Row " + rowNumber + ": invalid email format.");
                    continue;
                }

                rows.add(new CsvRow(groupName, email, lastName, firstName, position, scopusIds));
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

    private record CsvRow(String groupName, String email, String lastName, String firstName, String position, String[] scopusIds) {}


}
