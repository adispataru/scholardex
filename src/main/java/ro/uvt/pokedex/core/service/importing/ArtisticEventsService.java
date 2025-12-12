package ro.uvt.pokedex.core.service.importing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.ArtisticEvent;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.repository.ArtisticEventRepository;
import ro.uvt.pokedex.core.repository.reporting.DomainRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ArtisticEventsService {

    private final ArtisticEventRepository artisticEventRepository;
    private final DomainRepository domainRepository;
    private final String jsonFilePath = "data/arts/event_rankings.json"; // Path to your JSON file
    public void importArtisticEventsFromJson() {
        if(artisticEventRepository.count() == 0) {
            // Load file as resource
            try {
                // Read the JSON file
                String jsonContent = Files.readString(Paths.get(jsonFilePath));
                ObjectMapper objectMapper = new ObjectMapper();
                Map<String, Object> data = objectMapper.readValue(jsonContent, new TypeReference<>() {
                });
                List<Map<String, String>> events = (List<Map<String, String>>) data.get("events");
                for (Map<String, String> event : events) {
                    String domain = event.get("domain");
                    String name = event.get("name");
                    String level = event.get("level");

                    // Check if the domain exists, create it if necessary
                    Optional<Domain> domainOpt = domainRepository.findByName(domain);
                    Domain existingDomain = domainOpt.orElse(null);
                    if (domainOpt.isEmpty()) {
                        Domain domainObj = new Domain();
                        domainObj.setName(domain);
                        domainObj.setDescription("Imported from JSON");

                        existingDomain = domainRepository.save(domainObj);
                    }
                    ArtisticEvent artisticEvent = new ArtisticEvent();
                    artisticEvent.setName(name);
                    artisticEvent.setDomainId(existingDomain.getName());
                    // Set the rank based on the level
                    switch (level) {
                        case "1":
                            artisticEvent.setRank(ArtisticEvent.Rank.INTERNATIONAL_TOP);
                            break;
                        case "2":
                            artisticEvent.setRank(ArtisticEvent.Rank.INTERNATIONAL);
                            break;
                        case "3":
                            artisticEvent.setRank(ArtisticEvent.Rank.NATIONAL);
                            break;
                        default:
                            System.err.println("Unknown level: " + level);
                            continue; // Skip this event if the level is unknown
                    }

                    // Save the ArtisticEvent
                    artisticEventRepository.save(artisticEvent);
                    System.out.println("Imported artistic event: " + name + " with level: " + artisticEvent.getRank());
                }
            } catch (IOException e) {
                System.err.println("Error reading or parsing the JSON file: " + e.getMessage());
            }
        }

    }
}
