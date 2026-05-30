package ro.uvt.pokedex.core.service.application.model;

/**
 * Flattened department option for group-form dropdowns. The label includes division and
 * institution context so a multi-department picker stays readable when joint labs span
 * faculties or institutes.
 */
public record DepartmentOption(
        String id,
        String name,
        String divisionName,
        String divisionType,
        String institutionId,
        String institutionShortName
) {
    public String label() {
        StringBuilder sb = new StringBuilder();
        if (institutionShortName != null && !institutionShortName.isBlank()) {
            sb.append(institutionShortName).append(" · ");
        }
        if (divisionName != null && !divisionName.isBlank()) {
            sb.append(divisionName).append(" · ");
        }
        sb.append(name == null ? "" : name);
        return sb.toString();
    }
}
