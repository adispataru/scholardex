package ro.uvt.pokedex.core.service.importing;

import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.*;

@Service
public class GroupService {
    @Autowired
    private GroupRepository groupRepository;
    @Autowired
    private InstitutionRepository institutionRepository;
    @Autowired
    private ResearcherService researcherService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private UserService userService;
    @Value("${user.default.password}")
    private String defaultPassword;

    public void importGroupsFromCsv(MultipartFile file) throws Exception {
        List<Institution> uvt = institutionRepository.findByNameIgnoreCase("UVT");
        if(uvt.isEmpty())
            return;
        Map<String, Group> groups = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            reader.lines()
                    .skip(1) // Skip header row
                    .forEach(line -> {
                        String[] fields = line.split(",");
                        String groupName = fields[0];
                        User user = new User();
                        user.setEmail(fields[1]);
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
                            populateResearcher(fields, researcher);
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
                                    populateResearcher(fields, researcher);
                                    researcherService.saveResearcher(researcher);
                                    found = true;
                                }
                            }
                            if(!found){
                                researcher = new Researcher();
                                populateResearcher(fields, researcher);
                                Researcher savedResearcher = researcherService.saveResearcher(researcher);
                                savedUser.setResearcherId(savedResearcher.getId());
                            }
                            user.getRoles().add(UserRole.RESEARCHER);
                            userService.updateUser(user.getEmail(), savedUser);
                        }
                        group.getResearchers().add(researcher);
                        groups.put(groupName, group);
                    });
            groupRepository.saveAll(groups.values());
        }
    }

    private void populateResearcher(String[] fields, Researcher researcher) {
        researcher.setFirstName(fields[3]);
        researcher.setLastName(fields[2]);
        researcher.setPosition(parsePosition(fields[4]));
        String[] scopusIds = new String[0];
        if(fields.length > 5){
            scopusIds = fields[5].split(";");
            for(String scopusId : scopusIds){
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


}
