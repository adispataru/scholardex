package ro.uvt.pokedex.core.service.application;

/**
 * H145 — the publication a researcher adds already exists on the platform under that DOI (from Scopus, OpenAlex or
 * WoS): it is confirmed from the publications list, never added again — an added copy used to overwrite the shared
 * record (its authors, venue, type, date and citations) for every researcher.
 */
public class WizardDoiExistsException extends RuntimeException {

    private final String existingTitle;

    public WizardDoiExistsException(String existingTitle) {
        super("publication already on the platform: " + existingTitle);
        this.existingTitle = existingTitle;
    }

    public String getExistingTitle() {
        return existingTitle;
    }
}
