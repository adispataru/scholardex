package ro.uvt.pokedex.core.service.application;

/** H145 — the researcher already holds the one record this type allows (a Google Scholar profile); nothing was stored. */
public class ActivitySingleRecordException extends RuntimeException {

    private final String typeName;

    public ActivitySingleRecordException(String typeName) {
        super("one record per researcher: " + typeName);
        this.typeName = typeName;
    }

    public String getTypeName() {
        return typeName;
    }
}
