package ro.uvt.pokedex.core.service.dblp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DblpDumpConferenceSweepServiceTest {

    @Mock private DblpConferenceCandidateDetector candidateDetector;
    @Mock private ScholardexPublicationFactRepository publicationFactRepository;
    @Mock private DblpConferenceResolveService resolveService;

    @InjectMocks private DblpDumpConferenceSweepService service;

    private static final String DUMP = """
            <dblp>
              <inproceedings key="conf/ica3pp/Smith19">
                <title>Online resource coalition reorganization.</title>
                <booktitle>ICA3PP 2019</booktitle>
                <year>2019</year>
                <ee>https://doi.org/10.1007/conf-ica3pp-2019-42</ee>
              </inproceedings>
              <article key="journals/tocs/Jones20">
                <title>A journal paper that must be ignored.</title>
                <year>2020</year>
                <ee>https://doi.org/10.1/journal</ee>
              </article>
              <inproceedings key="conf/other/NoMatch21">
                <title>Some unrelated conference paper.</title>
                <booktitle>OTHER 2021</booktitle>
                <year>2021</year>
              </inproceedings>
            </dblp>
            """;

    @Test
    void matchesOnlyConferenceRecordsForOurCandidatesAndRoutesThroughApplyMatch() throws Exception {
        ScholardexPublicationFact candidate = pub("p1", "10.1007/conf-ica3pp-2019-42");
        when(candidateDetector.detect(any())).thenReturn(List.of(candidate));
        when(resolveService.applyMatch(any(), anyString(), anyString(), any(), any(), any(), anyString(), anyString()))
                .thenReturn(true);

        service.runSweep(reader(DUMP), new ImportProcessingResult(20));

        // The conference record matching our candidate by DOI is applied with the conf key + booktitle as the name;
        // the journal record and the non-matching conference record are not.
        verify(resolveService).applyMatch(eq(candidate), eq("conf/ica3pp/Smith19"), eq("ICA3PP 2019"),
                eq("10.1007/conf-ica3pp-2019-42"), anyString(), eq(2019), eq("dump-doi"), eq("test-dump"));
        verify(resolveService, never()).applyMatch(any(), eq("journals/tocs/Jones20"),
                anyString(), any(), any(), any(), anyString(), anyString());
    }

    @Test
    void matchesByTitleAndYearWhenDoiAbsent() throws Exception {
        ScholardexPublicationFact candidate = pub("p2", null);
        candidate.setTitle("Some unrelated conference paper");
        candidate.setTitleNormalized(ro.uvt.pokedex.core.service.importing.scopus
                .ScholardexPublicationCanonicalizationService.normalizeTitle("Some unrelated conference paper"));
        candidate.setCoverDate("2021-01-01");
        when(candidateDetector.detect(any())).thenReturn(List.of(candidate));
        when(resolveService.applyMatch(any(), anyString(), anyString(), any(), any(), any(), anyString(), anyString()))
                .thenReturn(true);

        service.runSweep(reader(DUMP), new ImportProcessingResult(20));

        verify(resolveService).applyMatch(eq(candidate), eq("conf/other/NoMatch21"), eq("OTHER 2021"),
                any(), anyString(), eq(2021), eq("dump-title"), eq("test-dump"));
    }

    private XMLStreamReader reader(String xml) throws Exception {
        // reflect the configured dump version onto the field the sweep stamps into evidence
        var field = DblpDumpConferenceSweepService.class.getDeclaredField("dumpVersion");
        field.setAccessible(true);
        field.set(service, "test-dump");
        return XMLInputFactory.newFactory().createXMLStreamReader(new StringReader(xml));
    }

    private ScholardexPublicationFact pub(String id, String doiNormalized) {
        ScholardexPublicationFact p = new ScholardexPublicationFact();
        p.setId(id);
        p.setDoiNormalized(doiNormalized);
        return p;
    }
}
