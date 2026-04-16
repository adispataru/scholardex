package ro.uvt.pokedex.core.service.application.model;

public record TabDef(
        String id,
        String label,
        String iconClass,
        boolean defaultTab,
        boolean reloadOnActivate
) {}
