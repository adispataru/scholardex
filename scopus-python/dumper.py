from datetime import datetime, timedelta
import os
import sys

import pandas as pd
from pybliometrics.scopus import AffiliationRetrieval, AffiliationSearch, AuthorSearch, ScopusSearch
try:
    from pybliometrics.exception import Scopus401Error, Scopus404Error, ScopusError
except ImportError:
    from pybliometrics.scopus.exception import Scopus401Error, Scopus404Error, ScopusError


class ScopusDumper:

    def __init__(self, query='AF-ID ( "Universitatea de Vest din Timisoara"   60000434 ) ', affil='60000434'):
        self.query = query
        self.affil = affil

    def __parse_cover_date(self, value):
        if value is None:
            return None
        try:
            if pd.isna(value):
                return None
        except Exception:
            pass
        raw = str(value).strip()
        if raw == "":
            return None
        for fmt in ("%Y-%m-%d", "%Y-%m", "%Y"):
            try:
                parsed = datetime.strptime(raw, fmt).date()
                if fmt == "%Y":
                    parsed = parsed.replace(month=1, day=1)
                if fmt == "%Y-%m":
                    parsed = parsed.replace(day=1)
                return parsed
            except ValueError:
                continue
        return None

    def __load_existing_verified_json(self, baseline_json_path):
        if not baseline_json_path or not os.path.isfile(baseline_json_path):
            return pd.DataFrame()
        try:
            return pd.read_json(baseline_json_path)
        except Exception as inst:
            print("Failed to load baseline json {} because {}!".format(type(inst), inst.args))
            return pd.DataFrame()

    def __infer_last_publication_date(self, records):
        latest = None
        for row in records:
            parsed = self.__parse_cover_date(row.get('coverDate'))
            if parsed is not None and (latest is None or parsed > latest):
                latest = parsed
        return latest

    def __infer_last_citation_date_per_eid(self, records):
        last_citation_per_eid = {}
        for row in records:
            publication_eid = row.get('eid')
            if not publication_eid:
                continue
            citing_articles = row.get('citing articles')
            if not isinstance(citing_articles, list):
                continue
            for citation in citing_articles:
                if not isinstance(citation, dict):
                    continue
                parsed = self.__parse_cover_date(citation.get('coverDate'))
                if parsed is None:
                    continue
                if publication_eid not in last_citation_per_eid or parsed > last_citation_per_eid[publication_eid]:
                    last_citation_per_eid[publication_eid] = parsed
        return last_citation_per_eid

    def __build_publication_incremental_query(self, last_publication_date):
        if last_publication_date is None:
            return self.query
        year_floor = last_publication_date.year - 1
        return "({}) AND PUBYEAR > {}".format(self.query, year_floor)

    def __build_citation_query(self, cited_eid, lower_bound_date):
        if lower_bound_date is None:
            return "REF({})".format(cited_eid)
        year_floor = lower_bound_date.year - 1
        return "REF({}) AND PUBYEAR > {}".format(cited_eid, year_floor)

    def __filter_by_cover_date(self, dst, min_date):
        if min_date is None or dst.empty or 'coverDate' not in dst.columns:
            return dst

        def _is_new_enough(value):
            parsed = self.__parse_cover_date(value)
            return parsed is None or parsed >= min_date

        return dst[dst['coverDate'].apply(_is_new_enough)].reset_index(drop=True)

    def _affil_search(self, refresh=True):
        try:
            aff_search = AffiliationSearch(self.query, refresh=refresh)
        except Exception as inst:
            print("Affiliation Search Scopus error {} because {}!".format(type(inst), inst.args))
            sys.exit()
        print("Found {} matches".format(aff_search.get_results_size()))
        print("Found {} documents".format(aff_search.affiliations[0].documents))
        return aff_search.get_results_size(), int(aff_search.affiliations[0].documents)

    def __affil_author(self):
        aff_author = AffiliationRetrieval(self.affil)
        print("Author count {}".format(aff_author.author_count))

    def __scopus_dump(self, subscriber=True, refresh=True, query=None):
        print("Fetching new scopus entries ...")
        run_query = self.query if query is None else query
        try:
            st = ScopusSearch(run_query, subscriber=subscriber, refresh=refresh)
        except Scopus401Error:
            print("Unauthorized, check API Key settings!")
            sys.exit()
        except Exception as inst:
            print("Scopus error {} because {}!".format(type(inst), inst.args))
            sys.exit()
        dst = pd.DataFrame(st.results)
        return dst

    def __db_check(self):
        exists = os.path.isfile('dump/full_scopus_dump.csv')
        return exists

    def __check_citation(self, dst, ddst, subscriber, refresh, citation_lower_bounds=None):
        print("Found {} failed, fixing ...".format(len(ddst)))
        count_f = 0
        lower_bounds = {} if citation_lower_bounds is None else citation_lower_bounds
        for k, v in ddst.items():
            count_f += 1
            print("Fixing {} out of {}".format(count_f, len(ddst)))
            try:
                lower_bound = lower_bounds.get(v)
                query = self.__build_citation_query(v, lower_bound)
                citing_art = ScopusSearch(query, subscriber=subscriber, refresh=refresh)
                p1_cite = []
                if citing_art.results is None:
                    p1_cite.append(0)
                else:
                    for c in citing_art.results:
                        cover_date = self.__parse_cover_date(c.coverDate)
                        if lower_bound is not None and cover_date is not None and cover_date < lower_bound:
                            continue
                        rp_str = {
                            "issn": c.issn,
                            "isbn": c.isbn if hasattr(c, "isbn") else "",
                            "eid": c.eid,
                            "aggregationType": c.aggregationType,
                            "publicationName": c.publicationName,
                            "coverDate": c.coverDate,
                            "doi": c.doi,
                            "author_count": c.author_count,
                            "authkeywords": c.authkeywords,
                            "author_afids": c.author_afids,
                            "author_ids": c.author_ids,
                            "author_names": c.author_names,
                            "affiliation_country": c.affiliation_country,
                            "affilname": c.affilname,
                            "citedby_count": c.citedby_count,
                            "description": c.description,
                            "creator": c.creator,
                            "fund_acr": c.fund_acr,
                            "fund_no": c.fund_no,
                            "fund_sponsor": c.fund_sponsor,
                            "title": c.title,
                            "pageRange": c.pageRange,
                            "volume": c.volume,
                            "subtype": c.subtype,
                            "afid": c.afid,
                            "affiliation_city": c.affiliation_city,
                            "article_number": c.article_number,
                            "eIssn": c.eIssn,
                            "issueIdentifier": c.issueIdentifier,
                            "openaccess": c.openaccess,
                            "source_id": c.source_id
                        }
                        p1_cite.append(rp_str)
                dst.loc[k, 'citing articles'] = p1_cite
            except Exception as inst:
                print("Failed with {} and {}".format(type(inst), inst.args))
        return dst

    def _get_citations(self, dst, subscriber, refresh, citation_lower_bounds=None):
        print("Getting citations ...")
        citing = []
        count = 0
        lower_bounds = {} if citation_lower_bounds is None else citation_lower_bounds
        for e in dst['eid']:
            count += 1
            print("Fetching {} out of {}".format(count, len(dst['eid'])))
            try:
                lower_bound = lower_bounds.get(e)
                query = self.__build_citation_query(e, lower_bound)
                citing_art = ScopusSearch(query, subscriber=subscriber, refresh=refresh)
                p1_cite = []
                if citing_art.results is None:
                    p1_cite.append(0)
                else:
                    for c in citing_art.results:
                        cover_date = self.__parse_cover_date(c.coverDate)
                        if lower_bound is not None and cover_date is not None and cover_date < lower_bound:
                            continue
                        rp_str = {
                            "issn": c.issn,
                            "isbn": c.isbn if hasattr(c, "isbn") else "",
                            "eid": c.eid,
                            "aggregationType": c.aggregationType,
                            "publicationName": c.publicationName,
                            "coverDate": c.coverDate,
                            "doi": c.doi,
                            "author_count": c.author_count,
                            "authkeywords": c.authkeywords,
                            "author_afids": c.author_afids,
                            "author_ids": c.author_ids,
                            "author_names": c.author_names,
                            "affiliation_country": c.affiliation_country,
                            "affilname": c.affilname,
                            "citedby_count": c.citedby_count,
                            "description": c.description,
                            "creator": c.creator,
                            "fund_acr": c.fund_acr,
                            "fund_no": c.fund_no,
                            "fund_sponsor": c.fund_sponsor,
                            "title": c.title,
                            "pageRange": c.pageRange,
                            "volume": c.volume,
                            "subtype": c.subtype,
                            "afid": c.afid,
                            "affiliation_city": c.affiliation_city,
                            "article_number": c.article_number,
                            "eIssn": c.eIssn,
                            "issueIdentifier": c.issueIdentifier,
                            "openaccess": c.openaccess,
                            "source_id": c.source_id
                        }
                        p1_cite.append(rp_str)
                citing.append(p1_cite)
            except Exception as inst:
                print("Failed with {} and {}".format(type(inst), inst.args))
                citing.append('fail')
        print("Processing done. Saving ..")
        dst['citing articles'] = citing
        return dst

    def __verify_citation(self, dst, fix=True):
        missing_cite = 0
        if 'citing articles' not in dst.columns:
            return dst
        for index, row in dst.iterrows():
            row_citations = row.get('citing articles')
            if isinstance(row_citations, list) and len(row_citations) > 0 and row_citations[0] != 0:
                citedby_raw = row['citedby_count']
                if pd.isna(citedby_raw):
                    missing_cite += 1
                else:
                    try:
                        citedby_count = int(citedby_raw)
                    except (TypeError, ValueError):
                        missing_cite += 1
                        continue
                    if citedby_count != len(row_citations):
                        print('Mismatch found at index {}: {} -> {}'.format(index, row['citedby_count'], len(row_citations)))
                        if fix:
                            dst.loc[index, 'citedby_count'] = len(row_citations)
        print("Found a total of {} None cites".format(missing_cite))
        return dst

    def dump(self, subscriber=True, refresh=True, cite=False,
             baseline_json_path='complete_scopus_with_citing_and_date_verified_sp.json',
             incremental_output_path='_checkpoint/complete_scopus_incremental_sp.json'):
        self._affil_search()
        self.__affil_author()

        directory = "_checkpoint"
        if not os.path.exists(directory):
            os.makedirs(directory)
        if not os.path.exists('dump'):
            os.makedirs('dump')

        baseline_dst = self.__load_existing_verified_json(baseline_json_path)
        baseline_records = baseline_dst.to_dict('records') if not baseline_dst.empty else []
        baseline_eids = {str(row.get('eid')) for row in baseline_records if row.get('eid') is not None}
        last_publication_date = self.__infer_last_publication_date(baseline_records)
        publication_overlap_start = last_publication_date - timedelta(days=365) if last_publication_date else None
        last_citation_per_eid = self.__infer_last_citation_date_per_eid(baseline_records)

        incremental_query = self.__build_publication_incremental_query(last_publication_date)
        n_dst = self.__scopus_dump(subscriber, refresh, query=incremental_query)
        incremental_dst = self.__filter_by_cover_date(n_dst, publication_overlap_start)

        if 'eid' in incremental_dst.columns:
            incremental_dst = incremental_dst.drop_duplicates(subset=['eid'], keep='last').reset_index(drop=True)
            if baseline_eids:
                incremental_dst = incremental_dst[
                    ~incremental_dst['eid'].astype(str).isin(baseline_eids)
                ].reset_index(drop=True)
        else:
            incremental_dst = incremental_dst.drop_duplicates(keep='last').reset_index(drop=True)

        print("Found {} incremental publications".format(len(incremental_dst)))

        if self.__db_check():
            print("Dump file found")
            dst = pd.read_csv('dump/full_scopus_dump.csv')
        else:
            print("Dump file not found, creating ...")
            dst = pd.DataFrame()

        if not n_dst.empty:
            if dst.empty:
                dst = n_dst
            else:
                print("Merging files ...")
                dst = dst.merge(n_dst, how='outer')
            if 'eid' in dst.columns:
                dst = dst.drop_duplicates(subset=['eid'], keep='last')
            else:
                dst = dst.drop_duplicates(keep='last')
            dst = dst.reset_index(drop=True)
            dst.to_csv('dump/full_scopus_dump.csv', index=False)

        if not cite:
            incremental_dst.to_json(incremental_output_path)
            return incremental_dst

        if incremental_dst.empty or 'eid' not in incremental_dst.columns:
            c_dst = incremental_dst.copy()
            c_dst['citing articles'] = []
        else:
            citation_lower_bounds = {}
            for eid in incremental_dst['eid']:
                str_eid = str(eid)
                citation_lower_bounds[eid] = last_citation_per_eid.get(str_eid, publication_overlap_start)
            c_dst = self._get_citations(incremental_dst.copy(), subscriber, refresh, citation_lower_bounds)

        c_dst.to_pickle(os.path.join(directory, 'complete_scopus_with_citing_and_date_sp.pkl'))
        c_dst.to_csv(os.path.join(directory, "complete_scopus_with_citing_and_date_sp.csv"), index=False)
        c_dst.to_json(os.path.join(directory, "complete_scopus_with_citing_and_date_sp.json"))
        print("Done saving checkpoint!")
        print("Checking for failed ...")

        if 'citing articles' in c_dst.columns and 'eid' in c_dst.columns:
            ddst = c_dst[c_dst['citing articles'] == 'fail']['eid']
            if len(ddst) > 0:
                c_dst = self.__check_citation(c_dst, ddst, subscriber, refresh, citation_lower_bounds)
        c_dst = self.__verify_citation(c_dst)
        print("Dump verified ..")
        c_dst.to_csv('complete_scopus_with_citing_and_date_verified_sp.csv', index=False)
        c_dst.to_pickle('complete_scopus_with_citing_and_date_verified_sp.pkl')
        c_dst.to_json("complete_scopus_with_citing_and_date_verified_sp.json")
        c_dst.to_json(incremental_output_path)
        print("All done!")
        return c_dst


import pybliometrics
pybliometrics.scopus.init()
dumper = ScopusDumper()
dumper.dump(cite=True)
