package ro.uvt.pokedex.core.service.issn;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The ISSN a researcher typed cannot be a journal: wrong check digit, or the international register denies it. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class InvalidIssnException extends RuntimeException {

    private final String messageKey;
    private final String issn;

    public InvalidIssnException(String messageKey, String issn) {
        super(messageKey + ": " + issn);
        this.messageKey = messageKey;
        this.issn = issn;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public String getIssn() {
        return issn;
    }
}
