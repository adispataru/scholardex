package ro.uvt.pokedex.core.repository.reporting;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.reporting.Indicator;

public interface IndicatorRepository extends MongoRepository<Indicator, String> {
    // H52 slice 11d.5: the {@code findAllByOutputType(Indicator.Type)} query was
    // never called; deleted along with the legacy {@code Indicator.Type} enum.
    // Future kind-shaped queries dispatch on {@code kind._class}.
}
