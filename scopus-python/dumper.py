from pybliometrics.scopus import AffiliationSearch, AffiliationRetrieval, AuthorSearch, ScopusSearch
from pybliometrics.scopus.exception import Scopus401Error, Scopus404Error, ScopusError
import pandas as pd
import os
import sys


class ScopusDumper:

    def __init__(self, query='AF-ID ( "Universitatea de Vest din Timisoara"   60000434 ) ', affil='60000434'):
        self.query = query
        self.affil = affil

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

    def __scopus_dump(self, subscriber=True, refresh=True):
        print("Fetching new scopus entries ...")
        try:
            st = ScopusSearch(self.query, subscriber=subscriber, refresh=refresh)
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

    def __check_citation(self, dst, ddst, subscriber, refresh):
        print("Found {} failed, fixing ...".format(len(ddst)))
        count_f = 0
        for k, v in ddst.items():
            count_f += 1
            print("Fixing {} out of {}".format(count_f, len(ddst)))
            try:
                citing_art = ScopusSearch(v, subscriber=subscriber, refresh=refresh)
                p1_cite = []
                if citing_art.results is None:
                    p1_cite.append(0)
                else:
                    for c in citing_art.results:
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
                dst['citing articles'][k] = p1_cite
            except Exception as inst:
                print("Failed with {} and {}".format(type(inst), inst.args))
            return dst

    def _get_citations(self, dst, subscriber, refresh):
        print("Getting citations ...")
        citing = []
        count = 0
        for e in dst['eid']:
            count += 1
            print("Fetching {} out of {}".format(count, len(dst['eid'])))
            try:
                citing_art = ScopusSearch(e, subscriber=subscriber, refresh=refresh)
                p1_cite = []
                if citing_art.results is None:
                    p1_cite.append(0)
                else:
                    for c in citing_art.results:
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
        for index, row in dst.iterrows():
            if row['citing articles'][0] != 0:
                if row['citedby_count'] is None:
                    missing_cite += 1
                else:
                    if int(row['citedby_count']) != len(row['citing articles']):
                        print('Mismatch found at index {}: {} -> {}'.format(index, row['citedby_count'], len(row['citing articles'])))
                        if fix:
                            dst['citedby_count'][index] = len(row['citing articles'])
        print("Found a total of {} None cites".format(missing_cite))
        return dst

    def dump(self, subscriber=True, refresh=True, cite=False):
        results_size, n_doc_length = self._affil_search()
        self.__affil_author()
        directory = "_checkpoint"
        if not os.path.exists(directory):
            os.makedirs(directory)
        if self.__db_check():
            print("Dump file found")
            dst = pd.read_csv('dump/full_scopus_dump.csv')
            if n_doc_length > len(dst):
                print("Found {} new documents".format(n_doc_length - len(dst)))
                n_dst = self.__scopus_dump(subscriber, refresh)
                print("Merging files ...")
                dst = dst.merge(n_dst, how='outer')
        else:
            print("File not found fetching ...")
            dst = self.__scopus_dump(subscriber, refresh)
            dst.to_csv('dump/full_scopus_dump.csv', index=False)
        if not cite:
            return 0
        c_dst = self._get_citations(dst, subscriber, refresh)
        print("Processing done. Saving ..")
        c_dst.to_pickle(os.path.join(directory, 'complete_scopus_with_citing_and_date_sp.pkl'))
        c_dst.to_csv(os.path.join(directory, "complete_scopus_with_citing_and_date_sp.csv"), index=False)
        c_dst.to_json(os.path.join(directory, "complete_scopus_with_citing_and_date_sp.json"))
        print("Done saving checkpoint!")
        print("Checking for failed ...")

        ddst = dst[dst['citing articles'] == 'fail']['eid']
        if len(ddst) > 0:
            c_dst = self.__check_citation(dst, ddst, subscriber, refresh)
        c_dst = self.__verify_citation(c_dst)
        print("Dump verified ..")
        c_dst.to_csv('complete_scopus_with_citing_and_date_verified_sp.csv', index=False)
        c_dst.to_pickle('complete_scopus_with_citing_and_date_verified_sp.pkl')
        c_dst.to_json("complete_scopus_with_citing_and_date_verified_sp.json")
        print("All done!")
        return c_dst


import pybliometrics
pybliometrics.scopus.init()
dumper = ScopusDumper()
dumper.dump(cite=False)
