package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.model.registry.RegistryDomainExperts;
import ro.uvt.pokedex.core.model.registry.RegistryItem;
import ro.uvt.pokedex.core.model.registry.RegistryKind;
import ro.uvt.pokedex.core.repository.RegistryDomainExpertsRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * H142 slice 3, H144 — an admin says, per domain, which departments answer for it (their directors and the heads of
 * their faculty rank its entries, in every registry, and their researchers propose into it) and names further experts.
 * The domains are those the registries name, those already configured, and any an admin adds.
 */
@Service
@RequiredArgsConstructor
public class RegistryExpertsAdminService {

    static final int DOMAIN_MAX = 80;

    private final RegistryDomainExpertsRepository repository;
    private final RegistryStores stores;
    private final DepartmentRepository departmentRepository;
    private final OrgDivisionRepository divisionRepository;

    public record DepartmentOption(String id, String label) {
    }

    public record DomainView(String domain, Set<String> departmentIds, String expertEmails) {
    }

    public record Page(List<DomainView> domains, List<DepartmentOption> departments) {
    }

    public Page page() {
        Set<String> domains = new TreeSet<>();
        for (RegistryKind kind : RegistryKind.values()) {
            stores.all(kind).stream().map(RegistryItem::getDomainId).filter(d -> d != null && !d.isBlank())
                    .forEach(domains::add);
        }
        Map<String, RegistryDomainExperts> saved = repository.findAll().stream()
                .collect(Collectors.toMap(RegistryDomainExperts::getDomain, Function.identity(), (a, b) -> a));
        domains.addAll(saved.keySet());
        List<DomainView> views = domains.stream().map(d -> {
            RegistryDomainExperts s = saved.get(d);
            return new DomainView(d, s == null ? Set.of() : new LinkedHashSet<>(s.getDepartmentIds()),
                    s == null ? "" : String.join("\n", s.getExpertEmails()));
        }).toList();
        Map<String, String> faculties = divisionRepository.findAll().stream()
                .collect(Collectors.toMap(OrgDivision::getId, v -> v.getName() == null ? "" : v.getName(), (a, b) -> a));
        List<DepartmentOption> departments = departmentRepository.findAll().stream()
                .map(dep -> new DepartmentOption(dep.getId(), (dep.getName() == null ? dep.getId() : dep.getName())
                        + (dep.getDivisionId() != null && faculties.containsKey(dep.getDivisionId())
                        ? " — " + faculties.get(dep.getDivisionId()) : "")))
                .sorted(Comparator.comparing(DepartmentOption::label))
                .toList();
        return new Page(views, departments);
    }

    /** Saves who answers for one domain: departments by id (unknown ones dropped), experts one per line. */
    public void save(String domain, List<String> departmentIds, String expertEmails, String adminEmail) {
        String name = domainName(domain);
        if (name == null) {
            throw new IllegalArgumentException("domain");
        }
        Set<String> known = departmentRepository.findAll().stream().map(Department::getId).collect(Collectors.toSet());
        RegistryDomainExperts s = repository.findById(name).orElseGet(RegistryDomainExperts::new);
        s.setDomain(name);
        s.setDepartmentIds(departmentIds == null ? new ArrayList<>()
                : departmentIds.stream().filter(known::contains).distinct().collect(Collectors.toCollection(ArrayList::new)));
        s.setExpertEmails(expertEmails == null ? new ArrayList<>() : Arrays.stream(expertEmails.split("[\\s,;]+"))
                .map(String::trim).filter(e -> e.contains("@")).map(e -> e.toLowerCase(Locale.ROOT))
                .distinct().collect(Collectors.toCollection(ArrayList::new)));
        s.setUpdatedBy(adminEmail);
        s.setUpdatedAt(Instant.now());
        repository.save(s);
    }

    /**
     * Adds a domain without experts yet (a registry other than the artistic one names none until it is used): its name
     * as saved (spaces collapsed), or empty when it is blank, too long or already there.
     */
    public Optional<String> addDomain(String domain, String adminEmail) {
        String name = domainName(domain);
        if (name == null || repository.existsById(name)) {
            return Optional.empty();
        }
        save(name, List.of(), "", adminEmail);
        return Optional.of(name);
    }

    private static String domainName(String domain) {
        if (domain == null) return null;
        String name = domain.trim().replaceAll("\\s+", " ");
        return name.isEmpty() || name.length() > DOMAIN_MAX ? null : name;
    }
}
