-- H66B M7-B.2 — books are a distinct entity from forums. A book-typed publication venue
-- (aggregationType=Book) resolves to scholardex.book_facts via book_id instead of a serial forum_id.
-- Additive, nullable column on the publication read projection so book scoring can resolve the book registry.
ALTER TABLE reporting_read.scholardex_publication_view
    ADD COLUMN IF NOT EXISTS book_id text;
