package ro.uvt.pokedex.core.service.application.model;

public record BreadcrumbItem(
        String label,
        String href
) {
    public BreadcrumbItem(String label) {
        this(label, null);
    }
}
