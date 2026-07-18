package ro.uvt.pokedex.core.service.openalex;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.service.openalex.dto.OpenAlexWorksResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAlex occasionally serves works whose titles/abstracts carry broken surrogate characters
 * (seen live with the 0000-0002-1825-0097 test identity: mathematical alphanumerics like U+1D435
 * with a lost half). The strict decoder rejected the whole page — one bad character failed the
 * entire author sync. These tests pin the lenient path: bad bytes/escapes degrade to U+FFFD in one
 * field, valid surrogate pairs survive untouched, and the parsed strings never contain lone
 * surrogates (which would break BSON encoding later).
 */
class OpenAlexClientLenientDecodeTest {

    private final OpenAlexClient client = new OpenAlexClient(
            null, mapper(), "", 200, 200);

    private static ObjectMapper mapper() {
        return new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Test
    void invalidUtf8SurrogateBytesDegradeToReplacementCharNotAFailedSync() {
        // A CESU-8-style lone high surrogate (0xD835) encoded as raw bytes ED A0 B5 — invalid UTF-8.
        byte[] prefix = "{\"results\":[{\"id\":\"https://openalex.org/W1\",\"title\":\"Bad "
                .getBytes(StandardCharsets.UTF_8);
        byte[] badBytes = {(byte) 0xED, (byte) 0xA0, (byte) 0xB5};
        byte[] suffix = " char\"}],\"meta\":{}}".getBytes(StandardCharsets.UTF_8);
        byte[] body = concat(prefix, badBytes, suffix);

        OpenAlexWorksResponse response = client.parseWorksResponse(body);

        assertThat(response.getResults()).hasSize(1);
        String title = response.getResults().get(0).getTitle();
        assertThat(title).startsWith("Bad ").endsWith(" char").contains("�");
        assertThat(hasLoneSurrogate(title)).isFalse();
    }

    @Test
    void loneEscapedSurrogateDegradesToReplacementChar() {
        String json = "{\"results\":[{\"id\":\"https://openalex.org/W2\",\"title\":\"Math \\ud835 sign\"}],\"meta\":{}}";

        OpenAlexWorksResponse response = client.parseWorksResponse(json.getBytes(StandardCharsets.UTF_8));

        String title = response.getResults().get(0).getTitle();
        assertThat(title).isEqualTo("Math � sign");
        assertThat(hasLoneSurrogate(title)).isFalse();
    }

    @Test
    void validSurrogatePairsSurviveIntact() {
        // 𝐵 (U+1D435 MATHEMATICAL ITALIC CAPITAL B) as a proper escape pair AND as raw UTF-8.
        String json = "{\"results\":[{\"id\":\"https://openalex.org/W3\",\"title\":\"Escaped \\ud835\\udc35 and raw 𝐵\"}],\"meta\":{}}";

        OpenAlexWorksResponse response = client.parseWorksResponse(json.getBytes(StandardCharsets.UTF_8));

        assertThat(response.getResults().get(0).getTitle())
                .isEqualTo("Escaped 𝐵 and raw 𝐵");
    }

    @Test
    void emptyBodyParsesToNull() {
        assertThat(client.parseWorksResponse(null)).isNull();
        assertThat(client.parseWorksResponse(new byte[0])).isNull();
    }

    private static boolean hasLoneSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1)))) return true;
            if (Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(s.charAt(i - 1)))) return true;
        }
        return false;
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) len += p.length;
        byte[] out = new byte[len];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }
}
