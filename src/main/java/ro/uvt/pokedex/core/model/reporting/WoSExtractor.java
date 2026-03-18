package ro.uvt.pokedex.core.model.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.scopus.Publication;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Optional;

@Component
public class WoSExtractor {
    private static final Logger log = LoggerFactory.getLogger(WoSExtractor.class);

    public Optional<String> extractData(String doi) {
        try {
            // Build the curl command
            String command = String.format(
                "curl -s -I 'https://ws.isiknowledge.com/cps/openurl/service?url_ver=Z39.88-2004&rft_id=info:doi/%s' | grep Location",
                doi
            );

            // Execute the command
            ProcessBuilder processBuilder = new ProcessBuilder("bash", "-c", command);
            Process process = processBuilder.start();

            // Read the output
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("WOS:")) {
                        // Extract the WoS ID from the Location header
                        int startIndex = line.indexOf("WOS:");
                        int endIndex = line.indexOf("&", startIndex);
                        if (startIndex != -1 && endIndex != -1) {
                            return Optional.of(line.substring(startIndex, endIndex));
                        }
                    }
                }
            }

            // Wait for the process to complete
            process.waitFor();
        } catch (Exception e) {
            log.error("Error during WoS data extraction for DOI {}", doi, e);
        }

        return Optional.empty();
    }

    public Publication findPublicationWosId(Publication publication) {
        if(publication.getWosId() != null && !publication.getWosId().isEmpty()) {
            return publication;
        }
        String doi = publication.getDoi();
        if (doi != null && !doi.isEmpty() && (publication.getWosId() == null)) {
            Optional<String> wosId = extractData(doi);
            if (wosId.isPresent()) {
                publication.setWosId(wosId.get());
            }else{
                publication.setWosId(Publication.NON_WOS_ID);
            }
        }
        return publication;
    }
}
