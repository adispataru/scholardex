package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.ArtisticEvent;
import ro.uvt.pokedex.core.model.ArtisticEventDomainExperts;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.repository.ArtisticEventDomainExpertsRepository;
import ro.uvt.pokedex.core.repository.ArtisticEventRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * H142 slice 3 — an admin says, per artistic domain, which departments answer for it (their directors and the heads
 * of their faculty rank its events) and names further experts.
 */
@Service
@RequiredArgsConstructor
public class ArtisticEventExpertsAdminService {

    private final ArtisticEventDomainExpertsRepository repository;
    private final ArtisticEventRepository eventRepository;
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
        eventRepository.findAll().stream().map(ArtisticEvent::getDomainId).filter(d -> d != null && !d.isBlank())
                .forEach(domains::add);
        Map<String, ArtisticEventDomainExperts> saved = repository.findAll().stream()
                .collect(Collectors.toMap(ArtisticEventDomainExperts::getDomain, Function.identity(), (a, b) -> a));
        domains.addAll(saved.keySet());
        List<DomainView> views = domains.stream().map(d -> {
            ArtisticEventDomainExperts s = saved.get(d);
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
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("domain");
        }
        Set<String> known = departmentRepository.findAll().stream().map(Department::getId).collect(Collectors.toSet());
        ArtisticEventDomainExperts s = repository.findById(domain.trim()).orElseGet(ArtisticEventDomainExperts::new);
        s.setDomain(domain.trim());
        s.setDepartmentIds(departmentIds == null ? new ArrayList<>()
                : departmentIds.stream().filter(known::contains).distinct().collect(Collectors.toCollection(ArrayList::new)));
        s.setExpertEmails(expertEmails == null ? new ArrayList<>() : Arrays.stream(expertEmails.split("[\\s,;]+"))
                .map(String::trim).filter(e -> e.contains("@")).map(e -> e.toLowerCase(java.util.Locale.ROOT))
                .distinct().collect(Collectors.toCollection(ArrayList::new)));
        s.setUpdatedBy(adminEmail);
        s.setUpdatedAt(Instant.now());
        repository.save(s);
    }
}
