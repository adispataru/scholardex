package ro.uvt.pokedex.core.service.importing.wos.model;

public record WosIdentitySourceContext(
        Integer year,
        String editionRaw,
        String sourceEventId,
        String sourceFile,
        String sourceVersion,
        String sourceRowItem
) {
    public static WosIdentitySourceContext empty() {
        return new WosIdentitySourceContext(null, null, null, null, null, null);
    }
}
