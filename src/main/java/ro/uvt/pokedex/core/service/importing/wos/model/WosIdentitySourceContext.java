package ro.uvt.pokedex.core.service.importing.wos.model;

public record WosIdentitySourceContext(
        Integer year,
        String editionRaw,
        String sourceEventId,
        String sourceFile,
        String sourceVersion,
        String sourceRowItem,
        String abbreviatedTitle
) {
    /** Legacy shape without abbreviatedTitle — sources that don't carry the WoS abbreviation. */
    public WosIdentitySourceContext(
            Integer year,
            String editionRaw,
            String sourceEventId,
            String sourceFile,
            String sourceVersion,
            String sourceRowItem
    ) {
        this(year, editionRaw, sourceEventId, sourceFile, sourceVersion, sourceRowItem, null);
    }

    public static WosIdentitySourceContext empty() {
        return new WosIdentitySourceContext(null, null, null, null, null, null, null);
    }
}
