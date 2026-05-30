package ro.uvt.pokedex.core.service.application.model;

import ro.uvt.pokedex.core.model.Institution;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.user.User;

import java.util.List;
import java.util.Map;

public record GroupListViewModel(
        List<Group> groups,
        List<Domain> allDomains,
        List<Institution> institutions,
        List<DepartmentOption> departmentOptions,
        List<User> allResearchers,
        Map<String, Domain> domainsById,
        Map<String, DepartmentOption> departmentsById,
        Map<String, Integer> memberCountByGroupId,
        Group group
) {
}
