package ro.uvt.pokedex.core.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A publisher from the Web of Science "Master Book List" — the list the physics standard (COMISIA 3,
 * and UVT's own 2016 procedure before it) cites as "editurile recunoscute Web of Science", at
 * {@code http://wokinfo.com/mbl/publishers/}.
 *
 * <p>Clarivate DISCONTINUED that page; the standard still points at the dead URL. The 834 names here
 * were recovered from the Internet Archive snapshot of 2026-02-20 (mirrored for provenance at
 * {@code data/standards/fizica/wos-master-book-list-archived-20260220.html}) and are therefore a
 * FROZEN list: a publisher admitted after the snapshot cannot appear, so a researcher whose book is
 * at such a publisher needs a manual ruling. Flat name-matched allowlist, like
 * {@link FeaaAnexa1Publisher} and {@link FeaaA2Publisher}.
 */
@Data
@Document(collection = "scholardex.wosMasterBookList")
public class WosMasterBookListPublisher {
    @Id
    private String id;
    private Integer nr;
    private String name;
}
