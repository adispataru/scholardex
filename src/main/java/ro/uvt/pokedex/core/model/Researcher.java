package ro.uvt.pokedex.core.model;

import lombok.Data;
import ro.uvt.pokedex.core.model.reporting.Position;
import ro.uvt.pokedex.core.model.user.User;

import java.util.ArrayList;
import java.util.List;

/**
 * Value-object representation of a researcher's profile.
 * <p>
 * No longer a MongoDB document — profile data is stored inside
 * {@link User#getResearcherProfile()}. Use {@link #fromUser(User)} to
 * materialise a Researcher from a User, and pass the resulting object
 * wherever the existing service / facade API expects a Researcher.
 */
@Data
public class Researcher {
    /** Equals the owning {@link User#getEmail()} after migration. */
    private String id;
    private String firstName;
    private String lastName;
    private String scholarId;
    private List<String> scopusId = new ArrayList<>();
    private List<String> wosId = new ArrayList<>();
    private String primaryScholardexAuthorId;
    private Position position;

    public String getName() {
        return firstName + " " + lastName;
    }

    /**
     * Build a Researcher value-object from a User, using the embedded
     * {@link User.ResearcherProfile}. The researcher's {@code id} is set
     * to the user's email so all existing keying on researcher-id continues
     * to work after migration.
     *
     * @return a fully-populated Researcher, or {@code null} if the user has
     *         no linked profile ({@code researcherProfile == null}).
     */
    public static Researcher fromUser(User user) {
        if (user == null) return null;
        User.ResearcherProfile p = user.getResearcherProfile();
        if (p == null) return null;

        Researcher r = new Researcher();
        r.setId(user.getEmail());
        r.setFirstName(p.getFirstName());
        r.setLastName(p.getLastName());
        r.setScholarId(p.getScholarId());
        r.setScopusId(p.getScopusId() != null ? p.getScopusId() : new ArrayList<>());
        r.setWosId(p.getWosId() != null ? p.getWosId() : new ArrayList<>());
        r.setPrimaryScholardexAuthorId(p.getPrimaryScholardexAuthorId());
        r.setPosition(p.getPosition());
        return r;
    }
}
