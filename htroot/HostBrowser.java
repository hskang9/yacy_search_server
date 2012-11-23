/**
 *  HostBrowser
 *  Copyright 2012 by Michael Peter Christen, mc@yacy.net, Frankfurt am Main, Germany
 *  First released 27.09.2012 at http://yacy.net
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program in the file lgpl21.txt
 *  If not, see <http://www.gnu.org/licenses/>.
 */

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;

import org.apache.solr.common.SolrDocument;

import net.yacy.cora.document.ASCII;
import net.yacy.cora.document.MultiProtocolURI;
import net.yacy.cora.document.UTF8;
import net.yacy.cora.federate.solr.FailType;
import net.yacy.cora.federate.solr.YaCySchema;
import net.yacy.cora.federate.solr.connector.AbstractSolrConnector;
import net.yacy.cora.protocol.RequestHeader;
import net.yacy.cora.sorting.ClusteredScoreMap;
import net.yacy.cora.sorting.ReversibleScoreMap;
import net.yacy.crawler.data.NoticedURL.StackType;
import net.yacy.crawler.retrieval.Request;
import net.yacy.kelondro.data.meta.DigestURI;
import net.yacy.kelondro.data.meta.URIMetadataNode;
import net.yacy.kelondro.logging.Log;
import net.yacy.peers.graphics.WebStructureGraph.StructureEntry;
import net.yacy.search.Switchboard;
import net.yacy.search.index.Fulltext;
import net.yacy.server.serverObjects;
import net.yacy.server.serverSwitch;

public class HostBrowser {
    
    final static long TIMEOUT = 10000L;
    
    public static enum StoreType {
        LINK, INDEX, EXCLUDED, FAILED;
    }
    
    public static serverObjects respond(final RequestHeader header, final serverObjects post, final serverSwitch env) {
        // return variable that accumulates replacements
        final Switchboard sb = (Switchboard) env;
        Fulltext fulltext = sb.index.fulltext();
        final boolean admin = sb.verifyAuthentication(header);
        final boolean autoload = admin && sb.getConfigBool("browser.autoload", true);
        final boolean load4everyone = sb.getConfigBool("browser.load4everyone", false);
        final boolean loadRight = autoload || load4everyone; // add config later
        final boolean searchAllowed = sb.getConfigBool("publicSearchpage", true) || admin;

        final serverObjects prop = new serverObjects();
        
        // set default values
        prop.put("path", "");
        prop.put("result", "");
        prop.putNum("ucount", fulltext.size());
        prop.put("hosts", 0);
        prop.put("files", 0);
        prop.put("admin", 0);

        if (!searchAllowed) {
            prop.put("result", "You are not allowed to use this page. Please ask an administrator for permission.");
            return prop;
        }

        String path = post == null ? "" : post.get("path", "").trim();
        if (admin && path.length() == 0) sb.index.fulltext().commit();
        if (post == null || env == null) {
            return prop;
        }

        int p = path.lastIndexOf('/');
        if (p < 0 && path.length() > 0) path = path + "/"; else if (p > 7) path = path.substring(0, p + 1); // the search path shall always end with "/"
        if (path.length() > 0 && (
            !path.startsWith("http://") &&
            !path.startsWith("https://") &&
            !path.startsWith("ftp://") &&
            !path.startsWith("smb://") &&
            !path.startsWith("file://"))) { path = "http://" + path; }
        prop.putHTML("path", path);
        prop.put("delete", admin && path.length() > 0 ? 1 : 0);
        
        DigestURI pathURI = null;
        try {pathURI = new DigestURI(path);} catch (MalformedURLException e) {}

        String load = post.get("load", "");
        boolean wait = false;
        if (loadRight && autoload && path.length() != 0 && pathURI != null && load.length() == 0 && !sb.index.exists(pathURI.hash())) {
            // in case that the url does not exist and loading is wanted turn this request into a loading request
            load = path;
            wait = true;
        }
        if (load.length() > 0 && loadRight) {
            // stack URL
            DigestURI url;
            if (sb.crawlStacker.size() > 2) wait = false;
            try {
                url = new DigestURI(load);
                String reasonString = sb.crawlStacker.stackCrawl(new Request(
                        sb.peers.mySeed().hash.getBytes(),
                        url, null, load, new Date(),
                        sb.crawler.defaultProxyProfile.handle(),
                        0, 0, 0, 0
                    ));
                prop.put("result", reasonString == null ? ("added url to indexer: " + load) : ("not indexed url '" + load + "': " + reasonString));
                if (wait) for (int i = 0; i < 30; i++) {
                    if (sb.index.exists(url.hash())) break;
                    try {Thread.sleep(100);} catch (InterruptedException e) {}
                }
            } catch (MalformedURLException e) {
                prop.put("result", "bad url '" + load + "'");
            }
        }
        
        if (post.containsKey("hosts")) {
            // generate host list
            try {
                boolean onlyCrawling = "crawling".equals(post.get("hosts", ""));
                boolean onlyErrors = "error".equals(post.get("hosts", ""));
                
                int maxcount = admin ? 2 * 3 * 2 * 5 * 7 * 2 * 3 : 360; // which makes nice matrixes for 2, 3, 4, 5, 6, 7, 8, 9 rows/colums
                
                // collect hosts from index
                ReversibleScoreMap<String> hostscore = fulltext.getSolr().getFacets("*:*", maxcount, YaCySchema.host_s.getSolrFieldName()).get(YaCySchema.host_s.getSolrFieldName());
                if (hostscore == null) hostscore = new ClusteredScoreMap<String>();
                
                // collect hosts from crawler
                final Map<String, Integer[]> crawler = (admin) ? sb.crawlQueues.noticeURL.getDomainStackHosts(StackType.LOCAL, sb.robots) : new HashMap<String, Integer[]>();
                for (Map.Entry<String, Integer[]> host: crawler.entrySet()) {
                    hostscore.inc(host.getKey(), host.getValue()[0]);
                }
                
                // collect the errorurls
                Map<String, ReversibleScoreMap<String>> exclfacets = admin ? fulltext.getSolr().getFacets(YaCySchema.failtype_s.getSolrFieldName() + ":" + FailType.excl.name(), maxcount, YaCySchema.host_s.getSolrFieldName()) : null;
                ReversibleScoreMap<String> exclscore = exclfacets == null ? new ClusteredScoreMap<String>() : exclfacets.get(YaCySchema.host_s.getSolrFieldName());
                Map<String, ReversibleScoreMap<String>> failfacets = admin ? fulltext.getSolr().getFacets(YaCySchema.failtype_s.getSolrFieldName() + ":" + FailType.fail.name(), maxcount, YaCySchema.host_s.getSolrFieldName()) : null;
                ReversibleScoreMap<String> failscore = failfacets == null ? new ClusteredScoreMap<String>() : failfacets.get(YaCySchema.host_s.getSolrFieldName());
                
                int c = 0;
                Iterator<String> i = hostscore.keys(false);
                String host;
                while (i.hasNext() && c < maxcount) {
                    host = i.next();
                    prop.put("hosts_list_" + c + "_host", host);
                    boolean inCrawler = crawler.containsKey(host);
                    int exclcount = exclscore.get(host);
                    int failcount = failscore.get(host);
                    int errors = exclcount + failcount;
                    prop.put("hosts_list_" + c + "_count", hostscore.get(host) - errors);
                    prop.put("hosts_list_" + c + "_crawler", inCrawler ? 1 : 0);
                    if (inCrawler) prop.put("hosts_list_" + c + "_crawler_pending", crawler.get(host)[0]);
                    prop.put("hosts_list_" + c + "_errors", errors > 0 ? 1 : 0);
                    if (errors > 0) {
                        prop.put("hosts_list_" + c + "_errors_exclcount", exclcount);
                        prop.put("hosts_list_" + c + "_errors_failcount", failcount);
                    }
                    prop.put("hosts_list_" + c + "_type", inCrawler ? 2 : errors > 0 ? 1 : 0);
                    if (onlyCrawling) {
                        if (inCrawler) c++;
                    } else if (onlyErrors) {
                        if (errors > 0) c++;
                    } else {
                        c++;
                    }
                }
                prop.put("hosts_list", c);
                prop.put("hosts", 1);
            } catch (IOException e) {
                Log.logException(e);
            }
        }
        
        if (path.length() > 0) {
            boolean delete = false;
            if (admin && post.containsKey("delete")) {
                // delete the complete path!! That includes everything that matches with this prefix.
                delete = true;
            }
            int facetcount=post.getInt("facetcount", 0);
            boolean complete = post.getBoolean("complete");
            if (complete) { // we want only root paths for complete lists
                p = path.indexOf('/', 10);
                if (p > 0) path = path.substring(0, p + 1);
            }
            prop.put("files_complete", complete ? 1 : 0);
            prop.put("files_complete_path", path);
            p = path.substring(0, path.length() - 1).lastIndexOf('/');
            if (p < 8) {
                prop.put("files_root", 1);
            } else {
                prop.put("files_root", 0);
                prop.put("files_root_path", path.substring(0, p + 1));
            }
            try {
                // generate file list from path
                DigestURI uri = new DigestURI(path);
                String host = uri.getHost();
                prop.putHTML("outbound_host", host);
                prop.putHTML("inbound_host", host);
                String hosthash = ASCII.String(uri.hash(), 6, 6);
                String[] pathparts = uri.getPaths();
                
                // get all files for a specific host from the index
                StringBuilder q = new StringBuilder();
                q.append(YaCySchema.host_s.getSolrFieldName()).append(':').append(host);
                if (pathparts.length > 0 && pathparts[0].length() > 0) {
                    for (String pe: pathparts) {
                        if (pe.length() > 0) q.append(" AND ").append(YaCySchema.url_paths_sxt.getSolrFieldName()).append(':').append(pe);
                    }
                } else {
                    if (facetcount > 1000 || post.containsKey("nepr")) {
                        q.append(" AND ").append(YaCySchema.url_paths_sxt.getSolrFieldName()).append(":[* TO *]");
                    }
                }
                BlockingQueue<SolrDocument> docs = fulltext.getSolr().concurrentQuery(q.toString(), 0, 100000, TIMEOUT, 100,
                        YaCySchema.id.getSolrFieldName(),
                        YaCySchema.sku.getSolrFieldName(),
                        YaCySchema.failreason_t.getSolrFieldName(),
                        YaCySchema.failtype_s.getSolrFieldName(),
                        YaCySchema.inboundlinks_protocol_sxt.getSolrFieldName(),
                        YaCySchema.inboundlinks_urlstub_txt.getSolrFieldName(),
                        YaCySchema.outboundlinks_protocol_sxt.getSolrFieldName(),
                        YaCySchema.outboundlinks_urlstub_txt.getSolrFieldName()
                        );
                SolrDocument doc;
                Set<String> storedDocs = new HashSet<String>();
                Map<String, FailType> errorDocs = new HashMap<String, FailType>();
                Set<String> inboundLinks = new HashSet<String>();
                Map<String, ReversibleScoreMap<String>> outboundHosts = new HashMap<String, ReversibleScoreMap<String>>();
                int hostsize = 0;
                final List<byte[]> deleteIDs = new ArrayList<byte[]>();
                long timeout = System.currentTimeMillis() + TIMEOUT;
                while ((doc = docs.take()) != AbstractSolrConnector.POISON_DOCUMENT) {
                    String u = (String) doc.getFieldValue(YaCySchema.sku.getSolrFieldName());
                    String errortype = (String) doc.getFieldValue(YaCySchema.failtype_s.getSolrFieldName());
                    FailType error = errortype == null ? null : FailType.valueOf(errortype);  
                    if (u.startsWith(path)) {
                        if (delete) {
                            deleteIDs.add(ASCII.getBytes((String) doc.getFieldValue(YaCySchema.id.getSolrFieldName())));
                        } else {
                            if (error == null) storedDocs.add(u); else if (admin) errorDocs.put(u, error);
                        }
                    } else if (complete) {
                        if (error == null) storedDocs.add(u); else if (admin) errorDocs.put(u, error);
                    }
                    if ((complete || u.startsWith(path)) && !storedDocs.contains(u)) inboundLinks.add(u); // add the current link
                    if (error == null) {
                        hostsize++;
                        // collect inboundlinks to browse the host
                        Iterator<String> links = URIMetadataNode.getLinks(doc, true);
                        while (links.hasNext()) {
                            u = links.next();
                            if ((complete || u.startsWith(path)) && !storedDocs.contains(u)) inboundLinks.add(u);
                        }
                        
                        // collect outboundlinks to browse to the outbound
                        links = URIMetadataNode.getLinks(doc, false);
                        while (links.hasNext()) {
                            u = links.next();
                            try {
                                MultiProtocolURI mu = new MultiProtocolURI(u);
                                if (mu.getHost() != null) {
                                    ReversibleScoreMap<String> lks = outboundHosts.get(mu.getHost());
                                    if (lks == null) {
                                        lks = new ClusteredScoreMap<String>(UTF8.insensitiveUTF8Comparator);
                                        outboundHosts.put(mu.getHost(), lks);
                                    }
                                    lks.set(u, u.length());
                                }
                            } catch (MalformedURLException e) {}
                        }
                    }
                    if (System.currentTimeMillis() > timeout) break;
                }
                if (deleteIDs.size() > 0) {
                    for (byte[] b: deleteIDs) sb.crawlQueues.urlRemove(b);
                    sb.index.fulltext().remove(deleteIDs, true);
                }
                
                // collect from crawler
                List<Request> domainStackReferences = (admin) ? sb.crawlQueues.noticeURL.getDomainStackReferences(StackType.LOCAL, host, 1000, 3000) : new ArrayList<Request>(0);
                Set<String> loadingLinks = new HashSet<String>();
                for (Request crawlEntry: domainStackReferences) loadingLinks.add(crawlEntry.url().toNormalform(true));
                
                // now combine all lists into one
                Map<String, StoreType> files = new HashMap<String, StoreType>();
                for (String u: storedDocs) files.put(u, StoreType.INDEX);
                for (Map.Entry<String, FailType> e: errorDocs.entrySet()) files.put(e.getKey(), e.getValue() == FailType.fail ? StoreType.FAILED : StoreType.EXCLUDED);
                for (String u: inboundLinks) if (!storedDocs.contains(u)) files.put(u, StoreType.LINK);
                for (String u: loadingLinks) if (u.startsWith(path) && !storedDocs.contains(u)) files.put(u, StoreType.LINK);
                Log.logInfo("HostBrowser", "collected " + files.size() + " urls for path " + path);

                // distinguish files and folders
                Map<String, Object> list = new TreeMap<String, Object>(); // a directory list; if object is boolean, its a file; if its a int[], then its a folder
                int pl = path.length();
                String file;
                for (Map.Entry<String, StoreType> entry: files.entrySet()) {
                    if (entry.getKey().length() < pl) continue; // this is not inside the path
                    if (!entry.getKey().startsWith(path)) continue;
                    file = entry.getKey().substring(pl);
                    StoreType type = entry.getValue();
                    p = file.indexOf('/');
                    if (p < 0) {
                        // this is a file
                        list.put(entry.getKey(), type); // StoreType value: this is a file; true -> file is in index; false -> not in index, maybe in crawler
                    } else {
                        // this is a directory path or a file in a subdirectory
                        String remainingPath = file.substring(0, p + 1);
                        if (complete && remainingPath.indexOf('.') > 0) {
                            list.put(entry.getKey(), type); // StoreType value: this is a file
                        } else {
                            String dir = path + remainingPath;
                            Object c = list.get(dir);
                            if (c == null) {
                                int[] linkedStoredIncrawlerError = new int[]{0,0,0,0};
                                if (type == StoreType.LINK) linkedStoredIncrawlerError[0]++;
                                if (type == StoreType.INDEX) linkedStoredIncrawlerError[1]++;
                                if (loadingLinks.contains(entry.getKey())) linkedStoredIncrawlerError[2]++;
                                if (errorDocs.containsKey(entry.getKey())) linkedStoredIncrawlerError[3]++;
                                list.put(dir, linkedStoredIncrawlerError);
                            } else if (c instanceof int[]) {
                                if (type == StoreType.LINK) ((int[]) c)[0]++;
                                if (type == StoreType.INDEX) ((int[]) c)[1]++;
                                if (loadingLinks.contains(entry.getKey())) ((int[]) c)[2]++;
                                if (errorDocs.containsKey(entry.getKey())) ((int[]) c)[3]++;
                            }
                        }
                    }
                }
                
                int maxcount = 1000;
                int c = 0;
                // first list only folders
                int filecounter = 0;
                for (Map.Entry<String, Object> entry: list.entrySet()) {
                    if ((entry.getValue() instanceof StoreType)) {
                        filecounter++;
                    } else {
                        // this is a folder
                        prop.put("files_list_" + c + "_type", 1);
                        prop.put("files_list_" + c + "_type_url", entry.getKey());
                        int linked = ((int[]) entry.getValue())[0];
                        int stored = ((int[]) entry.getValue())[1];
                        int crawler = ((int[]) entry.getValue())[2];
                        int error = ((int[]) entry.getValue())[3];
                        prop.put("files_list_" + c + "_type_stored", stored);
                        prop.put("files_list_" + c + "_type_linked", linked);
                        prop.put("files_list_" + c + "_type_pendingVisible", crawler > 0 ? 1 : 0);
                        prop.put("files_list_" + c + "_type_pending", crawler);
                        prop.put("files_list_" + c + "_type_excludedVisible", 0);
                        prop.put("files_list_" + c + "_type_excluded", 0);
                        prop.put("files_list_" + c + "_type_failedVisible", error > 0 ? 1 : 0);
                        prop.put("files_list_" + c + "_type_failed", error);
                        if (++c >= maxcount) break;
                    }
                }
                // then list files
                for (Map.Entry<String, Object> entry: list.entrySet()) {
                    if (entry.getValue() instanceof StoreType) {
                        // this is a file
                        prop.put("files_list_" + c + "_type", 0);
                        prop.put("files_list_" + c + "_type_url", entry.getKey());
                        StoreType type = (StoreType) entry.getValue();
                        try {uri = new DigestURI(entry.getKey());} catch (MalformedURLException e) {uri = null;}
                        boolean loading = load.equals(entry.getKey()) || (uri != null && sb.crawlQueues.urlExists(uri.hash()) != null);
                        prop.put("files_list_" + c + "_type_stored", type == StoreType.INDEX ? 1 : (type == StoreType.EXCLUDED || type == StoreType.FAILED) ? 3 : loading ? 2 : 0 /*linked*/);
                        prop.put("files_list_" + c + "_type_stored_load", loadRight ? 1 : 0);
                        if (type == StoreType.EXCLUDED || type == StoreType.FAILED) prop.put("files_list_" + c + "_type_stored_error", errorDocs.get(entry.getKey()).name());
                        if (loadRight) {
                            prop.put("files_list_" + c + "_type_stored_load_url", entry.getKey());
                            prop.put("files_list_" + c + "_type_stored_load_path", path);
                        }
                        if (++c >= maxcount) break;
                    }
                }
                prop.put("files_list", c);
                prop.putHTML("files_path", path);
                prop.put("files_hostsize", hostsize);
                prop.put("files_subpathloadsize", storedDocs.size());
                prop.put("files_subpathdetectedsize", filecounter - storedDocs.size());
                prop.put("files", 1);

                // generate inbound-links table
                StructureEntry struct = sb.webStructure.incomingReferences(hosthash);
                if (struct != null && struct.references.size() > 0) {
                    maxcount = 200;
                    ReversibleScoreMap<String> score = new ClusteredScoreMap<String>(UTF8.insensitiveUTF8Comparator);
                    for (Map.Entry<String, Integer> entry: struct.references.entrySet()) score.set(entry.getKey(), entry.getValue());
                    c = 0;
                    Iterator<String> i = score.keys(false);
                    while (i.hasNext() && c < maxcount) {
                        host = i.next();
                        prop.put("inbound_list_" + c + "_host", sb.webStructure.hostHash2hostName(host));
                        prop.put("inbound_list_" + c + "_count", score.get(host));
                        c++;
                    }
                    prop.put("inbound_list", c);
                    prop.put("inbound", 1);
                } else {
                    prop.put("inbound", 0);
                }
                
                // generate outbound-links table
                if (outboundHosts.size() > 0) {
                    maxcount = 200;
                    ReversibleScoreMap<String> score = new ClusteredScoreMap<String>(UTF8.insensitiveUTF8Comparator);
                    for (Map.Entry<String, ReversibleScoreMap<String>> entry: outboundHosts.entrySet()) score.set(entry.getKey(), entry.getValue().size());
                    c = 0;
                    Iterator<String> i = score.keys(false);
                    while (i.hasNext() && c < maxcount) {
                        host = i.next();
                        prop.put("outbound_list_" + c + "_host", host);
                        prop.put("outbound_list_" + c + "_count", score.get(host));
                        prop.put("outbound_list_" + c + "_link", outboundHosts.get(host).getMinKey());
                        c++;
                    }
                    prop.put("outbound_list", c);
                    prop.put("outbound", 1);
                } else {
                    prop.put("outbound", 0);
                }
                
            } catch (Throwable e) {
                Log.logException(e);
            }
        }

        // insert constants
        prop.putNum("ucount", fulltext.size());
        // return rewrite properties
        return prop;
    }


}
