package ro.uvt.pokedex.core.service.application;

import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.Researcher;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Service
public class ResearcherAuthorLookupService {

    public List<String> resolveAuthorLookupKeys(Researcher researcher) {
        if (researcher == null) {
            return List.of();
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        add(keys, researcher.getPrimaryScholardexAuthorId());
        addAll(keys, researcher.getScopusId());
        addAll(keys, researcher.getWosId());
        add(keys, researcher.getScholarId());
        return new ArrayList<>(keys);
    }

    private void addAll(LinkedHashSet<String> keys, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            add(keys, value);
        }
    }

    private void add(LinkedHashSet<String> keys, String value) {
        if (value == null) {
            return;
        }
        String normalized = value.trim();
        if (!normalized.isEmpty()) {
            keys.add(normalized);
        }
    }
}
