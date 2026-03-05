package ro.uvt.pokedex.core.model.scopus;
import java.util.List;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.DBRef;
import java.util.List;
import java.util.Objects;

@Data
@Document(collection = "scopus.authors")
public class Author {

    @Id
    private String id;
    @Indexed
    private String name;
    @DBRef(lazy = true)
    private List<Affiliation> affiliations;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Author author = (Author) o;
        return Objects.equals(id, author.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}

