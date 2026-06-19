package ro.uvt.pokedex.core.service.dblp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DblpConferenceCandidateDetectorTest {

    @Mock private ScholardexForumFactRepository forumFactRepository;
    @InjectMocks private DblpConferenceCandidateDetector detector;

    @Test
    void flagsBothSignalsAndLeavesResolvedVenuesAlone() {
        ScholardexPublicationFact openAlexConf = pub("p_oa", "proceedings-article", null);    // ISSN-less → candidate
        ScholardexPublicationFact lncsChapter = pub("p_lncs", "ch", "f_lncs");                 // LNCS series → candidate
        ScholardexPublicationFact realConference = pub("p_conf", "cp", "f_conf");              // real conf forum → no
        ScholardexPublicationFact journalArticle = pub("p_ar", "ar", "f_journal");             // not conference → no

        when(forumFactRepository.findAllById(any())).thenReturn(List.of(
                forum("f_lncs", "Lecture Notes in Computer Science"),
                forum("f_conf", "International Conference on Computational Science"),
                forum("f_journal", "Future Generation Computer Systems")));

        List<String> ids = detector.detect(List.of(openAlexConf, lncsChapter, realConference, journalArticle))
                .stream().map(ScholardexPublicationFact::getId).collect(Collectors.toList());

        assertThat(ids).containsExactlyInAnyOrder("p_oa", "p_lncs");
    }

    private ScholardexPublicationFact pub(String id, String subtype, String forumId) {
        ScholardexPublicationFact p = new ScholardexPublicationFact();
        p.setId(id);
        p.setSubtype(subtype);
        p.setForumId(forumId);
        return p;
    }

    private ScholardexForumFact forum(String id, String name) {
        ScholardexForumFact f = new ScholardexForumFact();
        f.setId(id);
        f.setName(name);
        return f;
    }
}
