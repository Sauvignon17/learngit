/**
 * Utility class for showStatsLive and showStats methods
 * @author lbang
 * @version v0.8 2017-12-12
 */

package com.tailf.packages.ned.iosxr;

import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.HashMap;

import java.util.ArrayList;
import java.util.List;

import com.tailf.ned.NedWorker;
import com.tailf.ned.NedException;
import com.tailf.ned.NedTTL;

import java.text.CharacterIterator;
import java.text.StringCharacterIterator;

import com.tailf.conf.ConfPath;
import com.tailf.conf.ConfObject;
import com.tailf.conf.ConfKey;

import com.tailf.maapi.Maapi;

import com.tailf.ned.CliSession;

import org.apache.log4j.Logger;


//
// NedDefaults
//
@SuppressWarnings("deprecation")
public class NedLiveStatus {

    /*
     * NED Specific local data
     */
    private String PROMPT = "\\A[a-zA-Z0-9][^\\# ]+#[ ]?$";
    private String CMDERROR = "Invalid input detected at";
    private static Logger LOGGER = Logger.getLogger(IosxrNedCli.class);

    /*
     * Constructor data
     */
    private CliSession session;
    private String device_id;
    private boolean trace;
    private boolean logVerbose;
    private int liveStatusTTL;
    private int readTimeout;

    /*
     * Internal data
     */
    private String root = "live-status/\\S+?:";
    private ArrayList<LSEntry> lsList = new ArrayList<LSEntry>();
    private HashMap<String, String> cachedShow = new HashMap<String, String>();
    private ArrayList<String> mms = null;

    /*
     * Constructor
     */
    NedLiveStatus(CliSession session,
                  String device_id,
                  boolean trace,
                  boolean logVerbose,
                  int liveStatusTTL,
                  int readTimeout)
        throws NedException {
        this.session    = session;
        this.device_id  = device_id;
        this.trace      = trace;
        this.logVerbose = logVerbose;
        this.liveStatusTTL = liveStatusTTL;
        this.readTimeout = readTimeout;
    }

    /*
     * tick
     */
    private long tick(long t) {
        return System.currentTimeMillis() - t;
    }

    /*
     * tickToString
     */
    private String tickToString(long start) {
        long stop = tick(start);
        return String.format("[%d ms]", stop);
    }

    /*
     * pathToString
     */
    private String pathToString(String path) {
        int i = path.indexOf("live-status/");
        return path.substring(i + 12);
    }

    /*
     * traceVerbose
     */
    private void traceVerbose(NedWorker worker, String info) {
        if (logVerbose && trace) {
            worker.trace("-- " + info + "\n", "out", device_id);
        }
    }

    /*
     * traceInfo
     */
    private void traceInfo(NedWorker worker, String info) {
        if (trace)
            worker.trace("-- " + info + "\n", "out", device_id);
    }

    /*
     * logInfo
     */
    private void logInfo (NedWorker worker, String info) {
        LOGGER.info(device_id + " " + info);
        if (trace && worker != null)
            worker.trace("-- " + info + "\n", "out", device_id);
    }

    /*
     * logError
     */
    private void logError(NedWorker worker, String text, Exception e) {
        LOGGER.error(device_id + " " + text, e);
        if (trace && worker != null) {
            if (e != null)
                worker.trace("-- " + text + ": " + e.getMessage() + "\n", "out", device_id);
            else
                worker.trace("-- " + text + ": unknown\n", "out", device_id);
        }
    }

    /*
     * print_line_exec
     */
    private String print_line_exec(NedWorker worker, String line)
        throws Exception {

        // Send command and wait for echo
        session.print(line + "\n");
        session.expect(new String[] { Pattern.quote(line) }, worker);

        // Return command output
        return session.expect(PROMPT, worker);
    }

    /*
     * showCache
     */
    private void showCache(NedWorker worker, String showcmd, String res) {
        traceVerbose(worker, "   caching: '"+showcmd+"' ["+res.length()+" bytes]");
        cachedShow.put(showcmd, res + "!TICK=" + tick(0));
    }

    /*
     * showGet
     */
    private String showGet(NedWorker worker, String showcmd)
        throws Exception {
        String res;
        String ticklog = "";

        // Look up in hash-map for cached show commands
        res = cachedShow.get(showcmd);
        if (res != null) {
            String startbuf = getMatch(res, "!TICK=(\\S+)");
            long start = Long.parseLong(startbuf, 10);
            long diff = tick(start);
            ticklog = String.format("[cached %d ms]", diff);
            if ((diff / 1000) >= (long)liveStatusTTL) {
                traceVerbose(worker, "   uncaching: '"+showcmd+"' "+ticklog);
                cachedShow.remove(showcmd);
            } else {
                traceVerbose(worker, "SENDING_SHOW(cached): '"+showcmd+"' "+ticklog);
                return res.substring(0, res.indexOf("!TICK="));
            }
        }

        // Not found, run show command on device
        traceVerbose(worker, "SENDING_SHOW: '"+showcmd+"' "+ticklog);
        res = "\n" + print_line_exec(worker, showcmd);
        if (res.contains(CMDERROR))
            throw new NedException("NedLiveStatus ERROR: running '"+showcmd+"': " + res);
        showCache(worker, showcmd, res);
        if (res.trim().isEmpty())
            return null;
        return res;
    }

    /*
     * stringQuote
     */
    private String stringQuote(String aText) {
        StringBuilder result = new StringBuilder();
        StringCharacterIterator iterator =
            new StringCharacterIterator(aText);
        char character =  iterator.current();
        result.append("\"");
        while (character != CharacterIterator.DONE ){
            if (character == '"')
                result.append("\\\"");
            else if (character == '\\')
                result.append("\\\\");
            else if (character == '\b')
                result.append("\\b");
            else if (character == '\n')
                result.append("\\n");
            else if (character == '\r')
                result.append("\\r");
            else if (character == (char) 11) // \v
                result.append("\\v");
            else if (character == '\f')
                result.append("'\\f");
            else if (character == '\t')
                result.append("\\t");
            else if (character == (char) 27) // \e
                result.append("\\e");
            else
                result.append(character);
            character = iterator.next();
        }
        result.append("\"");
        return result.toString();
    }

    /*
     * getMatch
     */
    private String getMatch(String text, String regexp) {
        Pattern p = Pattern.compile(regexp);
        Matcher m;
        try {
            m = p.matcher(text);
        } catch (Exception e) {
            logError(null, "NedLiveStatus ERROR: Pattern.matcher", e);
            return null;
        }
        if (!m.find())
            return null;
        return m.group(1);
    }

    /*
     * getMatches0
     */
    private String[] getMatches0(String text, String regexp, Pattern p) {
        Matcher m;
        try {
            m = p.matcher(text);
        } catch (Exception e) {
            logError(null, "NedLiveStatus ERROR: Pattern.matcher", e);
            return null;
        }
        if (!m.find())
            return null;
        String[] matches = new String[m.groupCount()+1];
        matches[0] = ""+m.groupCount();
        for (int i = 1; i <= m.groupCount(); i++) {
            matches[i] = m.group(i);
        }
        return matches;
    }

    /*
     * getMatches
     */
    private String[] getMatches(String text, String regexp, int flags) {
        Pattern p = Pattern.compile(regexp, flags);
        return getMatches0(text, regexp, p);
    }

    /*
     * getMatches
     */
    private String[] getMatches(String text, String regexp) {
        Pattern p = Pattern.compile(regexp);
        return getMatches0(text, regexp, p);
    }

    //
    // CLASS - LSEntry
    //
    private class LSEntry {
        private String id;
        private String showCmd;
        private String separator;
        private String templates[];

        // LSEntry constructor
        LSEntry(NedWorker worker, String id, String showCmd, String separator, String templates[]) {
            this.id = id;
            this.showCmd = showCmd;
            this.separator = separator;
            this.templates = templates;
        }

        //
        // LSEntry.getId
        //
        public String getId() {
            return id;
        }

        //
        // LSEntry.getTemplates
        //
        public String[] getTemplates() {
            return templates;
        }

        //
        // LSEntry.lookupTemplates
        //
        private String[] lookupTemplates(NedWorker worker) {
            if (this.templates[0].startsWith("<TEMPLATES ")) {
                String template = getMatch(this.templates[0], "<TEMPLATES (\\S+)>");
                //traceVerbose(worker, "TEMP: looking for: "+template);
                for (int n = 0; n < lsList.size(); n++) {
                    LSEntry lslist = lsList.get(n);
                    //traceVerbose(worker, "TEMP: comparing: "+lslist.getId());
                    if (lslist.getId().equals(template))
                        return lslist.getTemplates();
                }
            }
            return templates;
        }

        //
        // LSEntry.toString
        //
        public String toString() {
            String buf = "   " + id + ":\n";
            buf += "     showCmd = '"+showCmd+"'\n";
            if (separator != null)
                buf += "   separator = "+stringQuote(separator);
            return buf;
        }

        //
        // LSEntry.templateRegionUpdate
        //
        private String templateRegionUpdate(NedWorker worker, String buf0, String buf, String template)
            throws Exception {
            int start = -1;
            int end = -1;

            if (buf == null)
                buf = buf0;
            if (!template.trim().startsWith("<REGION_"))
                return buf;

            traceVerbose(worker, "");
            traceVerbose(worker, "templateRegionUpdate:");
            traceVerbose(worker, "   template = "+stringQuote(template));
            //traceVerbose(worker, "   BUF1=\n"+stringQuote(buf));

            // REGION_RESET
            if (template.trim().equals("<REGION_RESET>")) {
                String lines[] = buf0.trim().split("\n");
                traceVerbose(worker, "   region-reset: "+lines[0]);
                return buf0;
            }

            // REGION_START
            Pattern p = Pattern.compile("<REGION_START>(.+)</REGION_START>", Pattern.DOTALL);
            Matcher m = p.matcher(template);
            if (m.find()) {
                String regexp = m.group(1);
                p = Pattern.compile(regexp);
                m = p.matcher(buf);
                if (m.find()) {
                    start = m.start();
                    traceVerbose(worker, "   region-start: "+stringQuote(regexp)+" ="+start);
                } else {
                    traceVerbose(worker, "   region-start: "+stringQuote(regexp)+" =<not found>");
                }
            }

            // REGION_END
            p = Pattern.compile("<REGION_END>(.+)</REGION_END>", Pattern.DOTALL);
            m = p.matcher(template);
            if (m.find()) {
                String regexp = m.group(1);
                p = Pattern.compile(regexp);
                m = p.matcher(buf);
                if (m.find()) {
                    end = m.start();
                    traceVerbose(worker, "   region-end: "+stringQuote(regexp)+" ="+end);
                } else {
                    traceVerbose(worker, "   region-end: "+stringQuote(regexp)+" =<not found>");
                }
            }

            if (start != -1 || end != -1) {
                traceVerbose(worker, "   region update: start=" + start + " end="+end);
                if (start != -1 && end != -1)
                    buf = buf.substring(start, end);
                else if (end == -1)
                    buf = buf.substring(start);
                else
                    buf = buf.substring(0, end);
                //traceVerbose(worker, "   BUF2=\n"+stringQuote(buf));
            }

            return buf;
        }

        //
        // LSEntry.transformLeaf
        //
        private String transformLeaf(NedWorker worker, String options, String leaf) {
            String output = leaf;

            // tolower
            if (options.contains("tolower")) {
                output = output.toLowerCase();
            }

            // type=phys-address
            if (options.contains("type=phys-address")) {
                if (output.contains("Unknown"))
                    output = null;
                else {
                    output = output.replace("-", "").replace(":", "").replace(".", "");
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < output.length(); i++) {
                        if (i > 0 && ((i % 2) == 0))
                            sb.append(":");
                        sb.append(output.substring(i,i+1));
                    }
                    output = sb.toString();
                }
            }

            // type=integer
            else if (options.contains("type=integer")) {
                output = output.toLowerCase();
                output = output.replace(" ", "").replace(",", "");
                output = getMatch(output, "(\\d+(?:[kmg])?)");
                if (output != null) {
                    if (output.contains("k"))
                        output = output.replace("k", "000");
                    if (output.contains("m"))
                        output = output.replace("m", "000000");
                    if (output.contains("g"))
                        output = output.replace("g", "000000000");
                }
            }

            // type=activity
            else if (options.contains("type=activity")) {
                output = output.replace(":", "").replace("h", "");
                output = output.replace("m", "").replace("s", "");
                Pattern p = Pattern.compile("^([0]+)$");
                Matcher m = p.matcher(output);
                output = m.find() ? "active" : "inactive";
            }

            if (output == null)
                traceVerbose(worker, "   transformed '"+leaf+"' -> null");
            else if (!output.equals(leaf))
                traceVerbose(worker, "   transformed '"+leaf+"' -> '"+output+"'");

            return output;
        }

        //
        // LSEntry.templateParse
        //
        private String templateParse(NedWorker worker, Maapi mm, int th, String path, ArrayList<NedTTL> ttls, String buf, String template)
            throws Exception {

            if (template.trim().startsWith("<REGION_"))
                return path;

            traceVerbose(worker, "");
            traceVerbose(worker, "templateParse "+pathToString(path));
            traceVerbose(worker, "   template = "+template);

            //
            // <LIST <name>
            //
            Pattern p = Pattern.compile("<LIST (\\S+)( .+?)?>");
            Matcher m = p.matcher(template);
            if (m.find()) {
                String name = m.group(1);
                String listpath = path + "/" + name;
                traceVerbose(worker, "   found <LIST> = "+name);
                String check = m.group(2);
                if (check != null && !check.equals(" ?") && getMatch(buf, check) == null) {
                    traceVerbose(worker, "   no match for list "+name);
                    traceVerbose(worker, "   deleting list: " + listpath);
                    mms.add("delete :: "+listpath);
                    ttls.add(new NedTTL(new ConfPath(listpath), liveStatusTTL));
                } else {
                    int found = doShowStatsList(worker, mm, th, listpath, ttls, null);
                    if (found == 0 && check != null && check.equals(" ?")) {
                        traceVerbose(worker, "   no buffers for list "+name);
                        traceVerbose(worker, "   deleting list: " + path);
                        mms.remove("create :: "+path);
                        mms.add("delete :: "+path);
                    }
                }
                return path; // Continue with same path
            }

            //
            // {list[ options]}[keys]{/list}
            //
            p = Pattern.compile("\\{(\\S+)( .+?)?\\}(.*)\\{/\\1\\}");
            m = p.matcher(template);
            if (m.find()) {
                String options = m.group(2) != null ? m.group(2).trim() : "";
                traceVerbose(worker, "   found {list} = "+m.group(1)+", keys = "+m.group(3)+", options: "+options);

                // Extract keys
                String[] group = getMatches(buf, m.group(3), Pattern.DOTALL);
                if (group == null) {
                    traceVerbose(worker, "   ignoring buf: "+stringQuote(buf));
                    return null; // Non-matching entry, ignore
                }
                String key = null;
                for (int g = 1; g <= Integer.parseInt(group[0]); g++) {
                    if (key == null)
                        key = path + "{" + group[g];
                    else
                        key += (" " + group[g]);
                }
                key += "}";
                path = key.replace("{ ", "{");
                traceVerbose(worker, "   creating list: "+pathToString(path));
                mms.add("create :: "+path);
                ttls.add(new NedTTL(new ConfPath(path), liveStatusTTL));
                return path; // Modified path
            }

            //
            // <leaf[ options]><regexp></leaf>
            //
            p = Pattern.compile("<(\\S+)( .+?)?>(.*)</\\1>");
            m = p.matcher(template);
            String regexp = null;
            String[] group = null;
            int groupIndex = 0;
            while (m.find()) {
                groupIndex++;
                String options = m.group(2) != null ? m.group(2).trim() : "";
                traceVerbose(worker, "   found <leaf> = "+m.group(1)+", options: "+options);
                String lpath = path + "/" + m.group(1);
                traceVerbose(worker, "   leaf path = " + pathToString(lpath));

                try {
                    //
                    // Set TTL (even if not found)
                    //
                    int ttl = liveStatusTTL;
                    String opt;
                    if ((opt = getMatch(options, "ttl=(-?\\d+)")) != null) {
                        ttl = Integer.parseInt(opt);
                        if (ttl == -1)
                            ttl = 1048575; // 2147483647 crashes NSO 4.1,4.3 etc
                    }
                    NedTTL nedttl = new NedTTL(new ConfPath(lpath), ttl);
                    ttls.add(nedttl);

                    //
                    // Ignore empty leaves. Note: Still set TTL to avoid re-fetching
                    //
                    String value = m.group(3);
                    //traceVerbose(worker, "   value = " + value);
                    if (value.isEmpty()) {
                        traceVerbose(worker, "   ignoring empty leaf <" + m.group(1) + ">");
                        continue;
                    }

                    //
                    // Get leaf value
                    //
                    String leaf = null;
                    String[] linecol = null;
                    if (options.contains("type=constant") || options.contains("type=presence")) {
                        // Constant
                        leaf = value;
                    }
                    else if ((linecol = getMatches(value, "^(\\d+?)?\\$(\\d+)$")) != null) {
                        // [line]$<column> - line and/or column leaf value
                        traceVerbose(worker, "   template line = "+linecol[1]+" column = "+linecol[2]);
                        String line = buf;
                        if (linecol[1] != null && !linecol[1].isEmpty()) {
                            int lineno = Integer.parseInt(linecol[1]);
                            String lines[] = buf.trim().split("\n");
                            line = (lineno <= lines.length) ? lines[lineno-1] : null;
                        }
                        if (line == null) {
                            traceVerbose(worker, "   missing line for "+value);
                        } else {
                            int column = Integer.parseInt(linecol[2]);
                            String columns[] = line.trim().split("[ ]+");
                            if (column <= columns.length) {
                                leaf = columns[column-1];
                            } else {
                                traceVerbose(worker, "   missing column for "+value);
                            }
                        }
                    } else {
                        // Create regexp and lookup leaf value in buf
                        if (regexp == null) {
                            regexp = template.replaceAll("<(\\S+)(?: .+?)?>(.*)</\\1>", "$2");
                            traceVerbose(worker, "   template regexp = " + stringQuote(regexp));
                            if ((group = getMatches(buf, regexp)) != null) {
                                traceVerbose(worker, "   matched "+Integer.parseInt(group[0])+" item(s)");
                                //for (int g = 1; g <= Integer.parseInt(group[0]); g++)
                                //  traceVerbose(worker, "   group["+g+"] = " + group[g]);
                            }
                        }
                        if (group != null && groupIndex <= Integer.parseInt(group[0])) {
                            leaf = group[groupIndex]; // note: may be null for optional leaves
                        }
                    }

                    //
                    // Transform leaf value
                    //
                    if (leaf != null && !leaf.isEmpty() && !options.contains("type=check")) {
                        traceVerbose(worker, "   found leaf = " + leaf);
                        leaf = transformLeaf(worker, options, leaf);
                    }

                    //
                    // Set leaf value
                    //
                    if (options.contains("type=check")) {
                        if (leaf != null) {
                            traceVerbose(worker, "   found list <" + m.group(1) + ">");
                            ttls.remove(nedttl); // Trigger a new lookup
                        } else {
                            traceVerbose(worker, "   no match for list <" + m.group(1) + ">");
                        }
                    } else if (options.contains("type=presence")) {
                        traceVerbose(worker, "   creating presence container: "+pathToString(lpath));
                        mms.add("create :: "+lpath);
                    } else if (leaf != null) {
                        traceVerbose(worker, "   setting leaf: " + m.group(1) + " = "+leaf);
                        mms.add("setelem :: "+lpath+" :: " + leaf);
                    } else {
                        traceVerbose(worker, "   no match for leaf <" + m.group(1) + ">");
                    }
                } catch (Exception e) {
                    logError(worker, "NedLiveStatus ERROR: parsing template "+stringQuote(template), e);
                }
            }

            if (groupIndex == 0) {
                traceInfo(worker, "NedLiveStatus WARNING: bad template "+stringQuote(template));
            }
            return path;
        }

        //
        // LSEntry.populateStatsList
        //
        public int populateStatsList(NedWorker worker, Maapi mm, int th, String path, ArrayList<NedTTL> ttls, String res)
            throws Exception {
            String regexp = separator;

            //traceVerbose(worker, "populateStatsList " + path);

            // Delete old list and set ttl in case not found
            traceVerbose(worker, "   deleting list: " + path);
            mms.add("delete :: "+path);
            ttls.add(new NedTTL(new ConfPath(path), liveStatusTTL));

            String name = getMatch(path, root+"(\\S+)");

            if (showCmd == null) {
                traceVerbose(worker, "   null-list for path = " + path);
                return 0;
            }

            // Run show on device
            if (res == null) {
                String cmd = showCmd;
                if (name.contains("{")) {
                    Pattern p = Pattern.compile("\\{(.+?)\\}");
                    Matcher m = p.matcher(name);
                    while (m.find()) {
                        String keys[] = m.group(1).split(" "); // FIXME: does not support quoted keys
                        for (int i = 0; i < keys.length; i++) {
                            cmd = cmd.replaceFirst("\\$1", keys[i]);
                        }
                    }
                }
                // trim leftovers for showing the whole list
                cmd = cmd.replaceAll("\\$\\S+ \\$1", "");
                cmd = cmd.replaceAll("\\$1", "");
                cmd = cmd.replace(" $", " "); // Strip used names $
                traceVerbose(worker, "   showing " + name + ":");
                res = showGet(worker, cmd);
                if (res == null) {
                    traceVerbose(worker, "   empty buffer for "+pathToString(path));
                    return 0;
                }

                // Trim buffer header
                if (regexp != null && regexp.contains(" :: ")) {
                    String tokens[] = regexp.split(" :: ");
                    Pattern p = Pattern.compile(tokens[0]);
                    Matcher m = p.matcher(res);
                    if (m.find()) {
                        traceVerbose(worker, "   trimming "+m.end()+" header bytes from show");
                        res = "\n" + res.substring(m.end()).trim();
                    }
                    regexp = tokens[1];
                }
            }

            // Prepare output, split into optional entries and add to show cache
            List<String> entry = new ArrayList<String>();
            if (regexp != null) {
                // List, split items
                res = "\n" + res;
                Pattern p = Pattern.compile(regexp);
                Matcher m = p.matcher(res);
                int start = -1;
                String key = null;
                while (m.find()) {
                    traceVerbose(worker, "   found buffer '"+m.group(1)+"'");
                    if (start != -1) {
                        String buf = res.substring(start, m.start());
                        entry.add(buf);
                        if (showCmd.contains("$1"))
                            showCache(worker, showCmd.replace("$1", key), buf);
                    }
                    key = m.group(1);
                    start = m.start();
                }
                if (start != -1) {
                    entry.add(res.substring(start));
                    if (showCmd.contains("$1"))
                        showCache(worker, showCmd.replace("$1", key), res.substring(start));
                    traceVerbose(worker, "   found "+entry.size()+" entries for "+pathToString(path));
                } else {
                    traceVerbose(worker, "   non-matching buffer for "+pathToString(path));
                    //traceVerbose(worker, "   separator = "+stringQuote(separator));
                    //traceVerbose(worker, "   buffer = "+stringQuote(res));
                    return 0;
                }
            } else {
                // Presence container
                traceVerbose(worker, "   creating presence container: "+pathToString(path));
                ttls.add(new NedTTL(new ConfPath(path), liveStatusTTL));
                mms.add("create :: "+path);
                entry.add(res);
            }

            //
            // Loop through each buffer and populate list/container leaves
            //
            String path0 = path;
            int buffer = 1;
            long lastTime = System.currentTimeMillis();
            for (String buf : entry) {
                traceVerbose(worker, "\nshowStatsList buffer #"+(buffer++)+" for "+name);
                String buf0 = buf;
                path = path0; // New buffer, reset path
                //String lines[] = buf.split("\n");
                //for (int i = 0; i < lines.length; i++) traceVerbose(worker, "   line["+i+"] = " + lines[i]);
                for (String template : lookupTemplates(worker)) {
                    buf = templateRegionUpdate(worker, buf0, buf, template);
                    String newpath = templateParse(worker, mm, th, path, ttls, buf, template);
                    if (newpath == null)
                        break;
                    path = newpath;

                    // Update read timeout
                    long time = System.currentTimeMillis();
                    if ((time - lastTime) > (0.8 * readTimeout)) {
                        lastTime = time;
                        worker.setTimeout(readTimeout);
                    }
                }
            }
            return entry.size();
        }

        //
        // LSEntry.populateStats
        //
        public boolean populateStats(NedWorker worker, Maapi mm, int th, String leaf, ArrayList<NedTTL> ttls, String[] group)
            throws Exception {

            // Run show on device
            String cmd = showCmd;
            if (Integer.parseInt(group[0]) == 4) {
                String key = group[3].replace(" ", "");
                cmd = showCmd.replace("$1", key); // FIXME, support for $2
            }
            String buf0 = showGet(worker, cmd);
            if (buf0 == null) {
                traceVerbose(worker, "   empty reply for command "+cmd);
                return false;
            }

            // Find the matching template with this leaf (note: may be multiple leaves in one template)
            String path = group[1];
            String regexp = "<"+leaf+"(?: .+?)?>(.*)</"+leaf+">";
            traceVerbose(worker, "   regexp = " + stringQuote(regexp));
            String buf = buf0;
            for (String template : lookupTemplates(worker)) {
                buf = templateRegionUpdate(worker, buf0, buf, template);
                Pattern p = Pattern.compile(regexp);
                Matcher m = p.matcher(template);
                if (m.find()) {
                    // Populate leaf
                    traceVerbose(worker, "   matched id=" + this.getId() + ", leaf=" + leaf);
                    templateParse(worker, mm, th, path, ttls, buf, template);
                    return true;
                }
            }
            traceInfo(worker, "NedLiveStatus.doShowStats("+pathToString(path)+") WARNING: no match for regexp = "+regexp);
            return false;
        }
    }


    //
    // PUBLIC - NedLiveStatus
    //

    //
    // NedLiveStatus.toString
    //
    public String toString() {
        String buf = "";
        for (int n = 0; n < lsList.size(); n++) {
            LSEntry lslist = lsList.get(n);
            buf += lslist.toString();
        }
        return buf;
    }

    //
    // NedLiveStatus.add
    //
    public void add(NedWorker worker, String id, String showCmd, String separator, String templates[])
        throws NedException {
        LSEntry lslist = new LSEntry(worker, id, showCmd, separator, templates);
        lsList.add(lslist);
    }

    //
    // NedLiveStatus.doShowStatsList
    //
    private int doShowStatsList(NedWorker worker, Maapi mm, int th, String path, ArrayList<NedTTL> ttls, String res)
        throws Exception {

        //traceVerbose(worker, "   doShowStatsList() path = " + path);

        String name = getMatch(path, root+"(\\S+)");
        traceVerbose(worker, "   name = " + name);

        String regexp = name.replaceAll("\\{.+?\\}", Matcher.quoteReplacement("{\\S+}"));
        regexp = "^" + regexp.replace("{", "\\{").replace("}","\\}") + "$";
        traceVerbose(worker, "   regexp = " + stringQuote(regexp));

        // Lookup lsList
        boolean matched = false;
        int found = 0;
        for (int n = 0; n < lsList.size(); n++) {
            LSEntry lslist = lsList.get(n);
            if (lslist.getId().matches(regexp)) {
                // Note: Multiple matches supported in order to support diff show commands
                matched = true;
                traceVerbose(worker, "   matched id = " + lslist.getId());
                found += lslist.populateStatsList(worker, mm, th, path, ttls, res);
            }
        }
        if (matched == false) {
            traceInfo(worker, "NedLiveStatus.doShowStatsList("+pathToString(path)+") WARNING: no match for name = "+name);
        }
        return found;
    }


    //
    // NedLiveStatus.updateCdb
    //
    private void updateCdb(NedWorker worker, Maapi mm, int th)
        throws Exception {

        //
        // MAAPI METHOD
        //
        mm.attach(th, -1, 1);
        for (int n = 0; n < mms.size(); n++) {
            String tokens[] = mms.get(n).split(" :: ");
            String path = tokens[1];
            String log = "   MM["+n+"] "+tokens[0] + " "+tokens[1];
            if (tokens.length > 2)
                log += (" " + tokens[2]);
            traceVerbose(worker, log);
            try {
                if (tokens[0].equals("delete")) {
                    mm.safeDelete(th, path);
                }
                else if (tokens[0].equals("create")) {
                    mm.create(th, path);
                }
                else if (tokens[0].equals("setelem")) {
                    mm.setElem(th, tokens[2], path);
                }
            } catch (Exception e)  {
                traceInfo(worker, "NedLiveStatus MAAPI ERROR: "+tokens[0]+" "+e.getMessage());
            }
        }
        mm.detach(th);
    }


    //
    // NedLiveStatus.showStatsList
    //
    public ArrayList<NedTTL> showStatsList(NedWorker worker, Maapi mm, int th, ConfPath cpath)
        throws Exception {

        long start = tick(0);
        String path = cpath.toString();
        traceInfo(worker, "SHOW_STATS_LIST BEGIN("+pathToString(path)+")");

        // Lookup lsList and populate list with optional leaves
        ArrayList<NedTTL> ttls = null;
        try {
            ttls = new ArrayList<NedTTL>();
            mms = new ArrayList<String>();
            doShowStatsList(worker, mm, th, path, ttls, null);
        } catch (Exception e) {
            traceInfo(worker, "DONE SHOW_STATS_LIST("+pathToString(path)+") Exception ERROR: "+e.getMessage());
            throw e;
        }

        // Report and return
        traceInfo(worker, "DONE SHOW_STATS_LIST("+pathToString(path)+") "+ttls.size()+" entries in "+tickToString(start));
        for (int n = 0; n < ttls.size(); n++)
            traceVerbose(worker, "   TTL "+ttls.get(n).getPath()+" : "+ttls.get(n).getTTL());
        if (ttls.size() == 0)
            ttls = null;
        updateCdb(worker, mm, th);
        return ttls;
    }

    //
    // NedLiveStatus.showStats
    //
    public ArrayList<NedTTL> showStats(NedWorker worker, Maapi mm, int th, ConfPath cpath)
        throws Exception {

        long start = tick(0);
        String path = cpath.toString();
        traceInfo(worker, "SHOW_STATS BEGIN("+pathToString(path)+")");

        // Extract variables
        String[] group;
        String leaf;
        if ((group = getMatches(path, "(.*/"+root+"(\\S+)\\{(.*)\\})/(\\S+)")) != null) {
            leaf = group[4]; // List
        } else if ((group = getMatches(path, "(.*/"+root+"(\\S+?))/(\\S+)")) != null) {
            leaf = group[3]; // Leaf
        } else if ((group = getMatches(path, "(.*/+"+root+"(\\S+))")) != null) {
            return showStatsList(worker, mm, th, cpath); // Presence container
        } else {
            throw new NedException("NedLiveStatus ERROR: regexp parsing "+path);
        }

        String name = group[2];
        traceVerbose(worker, "   name = "+name+", leaf = "+leaf+" groupCount = "+Integer.parseInt(group[0]));

        // Lookup lsList and populate leaf
        ArrayList<NedTTL> ttls = new ArrayList<NedTTL>();
        mms = new ArrayList<String>();
        for (int n = 0; n < lsList.size(); n++) {
            LSEntry lslist = lsList.get(n);
            if (name.equals(lslist.getId())) {
                // Found match, populate leaf
                if (lslist.populateStats(worker, mm, th, leaf, ttls, group))
                    break;
            }
        }

        // Report and return
        traceInfo(worker, "DONE SHOW_STATS("+pathToString(path)+") "+ttls.size()+" entries in "+tickToString(start));
        for (int n = 0; n < ttls.size(); n++)
            traceVerbose(worker, "   TTL "+ttls.get(n).getPath()+" : "+ttls.get(n).getTTL());
        if (ttls.size() == 0)
            ttls = null;
        updateCdb(worker, mm, th);
        return ttls;
    }
}
