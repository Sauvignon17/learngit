/**
 * Utility class for caching cleartext secrets in oper data, in order
 * to avoid a compare-config diff when device encrypts the secret(s).
 *
 * @author lbang
 * @version 20161221
 */

package com.tailf.packages.ned.iosxr;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

import com.tailf.ned.NedWorker;
import com.tailf.ned.NedException;

import java.io.IOException;

import com.tailf.conf.ConfException;
import com.tailf.conf.ConfBuf;
import com.tailf.conf.ConfPath;
import com.tailf.conf.ConfKey;

import com.tailf.maapi.Maapi;
import com.tailf.maapi.MaapiSchemas.CSNode;

import com.tailf.cdb.Cdb;
import com.tailf.cdb.CdbDBType;
import com.tailf.cdb.CdbSession;

import com.tailf.ncs.ns.Ncs;

import com.tailf.navu.NavuContainer;
import com.tailf.navu.NavuContext;
import com.tailf.navu.NavuList;
import com.tailf.navu.NavuNode;

import org.apache.log4j.Logger;

/*
 * NedSecrets
 */
@SuppressWarnings("deprecation")
public class NedSecrets {

    private String PW_REGEXP = "((?:(?:[0-9]|clear|encrypted|password|password6) )?\\S+(?: encrypted)?)";
    private static Logger LOGGER = Logger.getLogger(IosxrNedCli.class);

    /*
     * Local data
     */
    private CdbSession cdbOper;
    private String device_id;
    private boolean trace;
    private boolean logVerbose;
    private boolean resync = false;

    private String SP = "^";
    private boolean newEntries = false;

    /*
     * Constructor
     */
    NedSecrets(CdbSession cdbOper, String device_id, boolean trace, boolean logVerbose)
        throws NedException {
        this.cdbOper    = cdbOper;
        this.device_id  = device_id;
        this.trace      = trace;
        this.logVerbose = logVerbose;
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
        if (trace)
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
     * getMatch
     */
    private String getMatch(String text, String regexp) {
        Pattern pattern = Pattern.compile(regexp);
        Matcher matcher;
        try {
            matcher = pattern.matcher(text);
        } catch (Exception e) {
            logError(null, "getMatch() :: ERROR in pattern.matcher", e);
            return null;
        }
        if (!matcher.find())
            return null;
        return matcher.group(1);
    }

    /*
     * isClearText
     */
    private boolean isClearText(String password) {
        // encrypted
        if (password.matches("[0-9a-f]{2}(:([0-9a-f]){2})+"))
            return false;   // aa:11 .. :22:bb
        if (password.trim().indexOf(" encrypted") > 0)
            return false;  // XXX encrypted
        if (password.startsWith("password "))
            return false;  // password XXX
        if (password.startsWith("password6 "))
            return false;  // password6 XXX
        if (password.trim().endsWith(" 7"))
            return false;   // XXX 7

        // cleartext
        if (password.trim().indexOf(" ") < 0)
            return true;   // XXX
        if (password.trim().charAt(0) == '0')
            return true;   // 0 XXX
        if (password.startsWith("clear "))
            return true;  // clear XXX
        if (password.trim().endsWith(" 0"))
            return true;   // XXX 0

        // Default to encrypted
        return false;      // 5 XXX, 6 XXX, 7 XXX
    }


    /*
     * operSetElem
     */
    private void operSetElem(NedWorker worker, String value, String path) {
        String root = path.substring(0,path.lastIndexOf("/"));
        String elem = path.substring(path.lastIndexOf("/"));
        try {
            ConfPath cp = new ConfPath("/ncs:devices/ncs:device{"+device_id+"}/ncs:ned-settings/iosxr-op:cisco-iosxr-oper/secrets{"+root+"}");
            if (!cdbOper.exists(cp)) {
                cdbOper.create(cp);
                //traceVerbose(worker, "SECRETS - Created " + path + " = " + value);
            }
            cdbOper.setElem(new ConfBuf(value), cp.append(elem));
            //traceVerbose(worker, "          " + path + " = " + value);
        } catch (Exception e) {
            logError(worker, "SECRETS - ERROR : failed to set "+path, e);
        }
    }

    /*
     * operDeleteList
     */
    private void operDeleteList(NedWorker worker, String path) {
        try {
            ConfPath cp = new ConfPath("/ncs:devices/ncs:device{"+device_id+"}/ncs:ned-settings/iosxr-op:cisco-iosxr-oper/secrets{"+path+"}");
            if (cdbOper.exists(cp)) {
                cdbOper.delete(cp);
                //traceVerbose(worker, "SECRETS - Deleted " + path);
            }
        } catch (Exception e) {
            logError(worker, "SECRETS - ERROR : failed to delete "+path, e);
        }
    }


    /*
     * isMetaDataSecret
     */
    private boolean isMetaDataSecret(String line) {
        if (!line.trim().startsWith("! meta-data :: "))
            return false;
        if (line.indexOf(" :: secret-password") < 0 && line.indexOf(" :: secret-string") < 0)
            return false;
        return true;
    }


    /*
     * insertKeys
     */
    private String insertKeys(String root, String line, String regexp)
        throws NedException {
        int i;
        for (i = regexp.indexOf("<"); i >= 0; i = regexp.indexOf("<", i+1)) {
            int end;
            int key = -1;
            if ((end = regexp.indexOf(">", i+1)) < 0)
                continue;
            String name = regexp.substring(i+1,end);
            if (name.equals("PASSWORD") || name.equals("NL") || name.equals("*")
                || name.equals("STRING") || name.equals("NUMBER"))
                continue;
            // key index {$0 $1 ..} - example: aaa-server <aaa-server $0> <aaa-server $1> host ..
            if ((end = name.indexOf(" $")) > 0) {
                key = (int)(name.charAt(end+2) - '0');
                name = name.substring(0,end);
            }
            String value = getMatch(root, ".*?"+name+"\\{(.*?)\\}"); // look for value in path keys
            if (value == null) {
                value = getMatch(line, " "+name+" (\\S+) "); // look for value in command line
                if (value == null)
                    throw new NedException("Internal error : malformed secrets name='"+name+"' regexp='"+regexp+"'");
            }
            if (key != -1) {
                String values[] = value.split(" ");
                if (key < values.length)
                    value = values[key];
            }
            value = value.replace("(", "\\(").replace(")", "\\)"); // actual brackets
            value = value.replace("+", "\\+"); // actual +
            if (key != -1) {
                regexp = regexp.replace("<"+name+" $"+key+">", value);
            } else {
                regexp = regexp.replace("<"+name+">", value);
            }
        }
        regexp = regexp.replace("<STRING>", "\\S+?");
        regexp = regexp.replace("<NUMBER>", "\\d+?");
        return regexp;
    }

    //
    // prepareLine
    //
    private void prepareLine(NedWorker worker, String line, String meta)
        throws NedException {
        int i;

        // Get root path for secret-password container|leaf
        String root = getMatch(meta, " meta-data :: (.*?) :: secret-(password|string)");
        if (root == null) {
            throw new NedException("Internal error : missing secrets meta-data");
        }

        // Root postfix, to be able to store multiple passwords from a single line
        String rootPostfix = getMatch(meta, " meta-data :: .*? :: secret-(?:password|string)-(\\S+) ");

        // Root path
        if (meta.contains("secret-password"))
            root = root.substring(0, root.lastIndexOf("/")); // strip leaf name
        String orgroot = root;

        // Create or delete
        boolean create = true;
        line = line.trim();
        if (line.startsWith("no ")) {
            traceVerbose(worker, "SECRETS - Preparing delete : " + root);
            create = false;
            line = line.substring(3);
        } else {
            traceVerbose(worker, "SECRETS - Preparing create : " + root);
        }
        traceVerbose(worker, "            line = " + line);

        // Trim root, stripping the absolute path
        root = root.replaceFirst("(.*)}/config/(?:cisco-ios-xr:)?(.*)", "$2");

        //
        // Get password and regexp
        //

        // Use secret-path meta-data
        String password = null;
        String regexp = null;
        if (create) {
            String metas[] = meta.split(" :: ");
            if (metas.length > 3) {

                // Get password from command line
                regexp = metas.length == 5 ? metas[4] : metas[3];
                if (!regexp.contains("<PASSWORD>") && !regexp.contains("<SECRET>"))
                    throw new NedException("Internal error : malformed secrets regexp = " + regexp);

                // Lookup password and create regexp for command line
                if (regexp.contains("<SECRET>")) {
                    // New 100% regexp style
                    Pattern p = Pattern.compile(regexp.trim().replace("<SECRET>", PW_REGEXP));
                    Matcher m = p.matcher(line);
                    if (m.find()) {
                        regexp = m.group(1) + " " + PW_REGEXP;
                        if (m.groupCount() == 3)
                            regexp += " " + m.group(3);
                        password = m.group(2);
                    }
                }
                else {
                    // Old mix style, using path and home made match syntax
                    regexp = insertKeys(root, line, regexp);
                    regexp = regexp.replace("<PASSWORD>", PW_REGEXP);
                    password = getMatch(line, regexp.trim());
                }

                // Failed to look up password
                if (password == null) {
                    traceInfo(worker, "SECRETS - ERROR : failed to get "+root+" password");
                    traceVerbose(worker, "            regexp = '"+regexp.trim()+"'");
                    return;
                }

                // Create regexp for mode path(s)
                if (metas.length == 5) {
                    String mode = insertKeys(root, line, metas[3]);
                    regexp = mode.replace("<NL>", "(?:[ \r])*\n") + regexp;
                }

                regexp = regexp.replace("<*>", ".*?");
                traceVerbose(worker, "            regexp = '"+regexp.replace("\n", "\\n")+"'");
            }

            // Extract password from command line, using the last leaf name
            else {
                String leafname = orgroot.substring(orgroot.lastIndexOf("/")+1);
                int offset = line.lastIndexOf(leafname,line.lastIndexOf(" "));
                password = line.substring(offset + leafname.length() + 1);
            }
            traceVerbose(worker, "            password = '" + password+"'");
        }

        // Replace '/' within keys with SP to avoid being replaced with ' '
        int depth = 0;
        for (i = 0; i < root.length(); i++) {
            if (root.charAt(i) == '{')
                depth++;
            else if (root.charAt(i) == '}')
                depth--;
            else if  (root.charAt(i) == '/' && depth > 0) {
                root = root.substring(0,i)+SP+root.substring(i+1);
            }
        }

        // Create standard regexp
        if (create) {
            if (regexp == null) {
                regexp = root.replaceAll("/[A-Za-z-]*?-list", "");
                regexp = regexp.replaceAll("[A-Za-z-]*?-conf/", "");
                regexp = regexp.replace("/", " ");
                regexp = regexp.replace("{", "[ ]?"); // optional blank due to support for join-key
                regexp = regexp.replace("} ", "\\s.*?"); // make sure not match abbrev
                regexp = regexp.replace("(", "\\(").replace(")", "\\)"); // actual brackets
                regexp = regexp.replace(SP, ".*?"); // secret-add-mode, ignore sub-mode entries
                regexp = regexp.replace("-plus", "\\+"); // + sign
                regexp = regexp+" ([ \\S]+)";
                traceVerbose(worker, "            regexp = '"+regexp+"'");
            }
        }

        // Make root path a valid key
        root = root.replace(" ", SP); // multiple keys, can't have blank in keys
        root = root.replace("{", "(").replace("}", ")");
        if (rootPostfix != null)
            root += "-" + rootPostfix;

        // Add/update or delete secrets cache
        if (create == false || !isClearText(password)) {
            // Delete command OR password encrypted and no need to cache
            traceInfo(worker, "SECRETS - Deleting : " + root);
            operDeleteList(worker, root);
        }
        else
        {
            // Add/update secrets cache with cleartext pw. Clear encrypted
            String buf = root;
            if (logVerbose)
                buf += " = " + password;
            traceInfo(worker, "SECRETS - Adding : "+buf);
            operSetElem(worker, password, root+"/cleartext");
            operSetElem(worker, "", root+"/encrypted");
            operSetElem(worker, regexp, root+"/regexp");
            newEntries = true;
        }
    }


    /*
     * prepare
     */
    public boolean prepare(NedWorker worker, String lines[])
        throws NedException {
        int i, c;

        for (i = 0 ; i < lines.length - 1; i++) {
            if (!isMetaDataSecret(lines[i]))
                continue; // not a meta-data secret
            for (c = i + 1; c < lines.length; c++) {
                if (lines[c].equals(lines[i]))
                    lines[c] = ""; // Temporary strip duplicate tags
                else if (!lines[c].trim().startsWith("! meta-data :: "))
                    break; // found command line
            }
            prepareLine(worker, lines[c], lines[i]);
        }

        return newEntries;
    }


    /*
     * update
     */
    public String update(NedWorker worker, String res, boolean convert, boolean partial)
        throws NedException {

        if (!convert && !newEntries) {
            //traceVerbose(worker, "SECRETS - ignoring update(), no new entries to cache");
            return res;
        }

        NavuContext context = null;
        NavuContainer container = null;
        try {
            // Init NAVU
            NavuList secretsList;
            try {
                context = new NavuContext(cdbOper);
                container = new NavuContainer(context);
                secretsList = container
                    .container(Ncs.hash)
                    .container(Ncs._devices_)
                    .list(Ncs._device_)
                    .elem(new ConfKey(new ConfBuf(device_id)))
                    .container("ncs", "ned-settings")
                    .container("iosxr-op", "cisco-iosxr-oper")
                    .list("secrets");
            } catch (Exception e) {
                throw new NedException("failed to create and init secrets navu");
            }

            // Loop through all entries
            for (NavuContainer entry : secretsList.elements()) {
                String log = "";
                String root = entry.leaf("path").valueAsString();
                String encrypted = entry.leaf("encrypted").valueAsString();
                if (convert == false && encrypted.isEmpty() == false) {
                    // Ignore updating active entries if caching only
                    continue;
                }

                // Look for the entry in show running-config
                String regexp = entry.leaf("regexp").valueAsString();
                traceVerbose(worker, "SECRETS - Checking '"+regexp.replace("\n", "\\n")+"'");
                Pattern pattern;
                try {
                    pattern = Pattern.compile("\n"+regexp, Pattern.DOTALL);
                } catch (Exception e) {
                    logError(worker, "SECRETS - ERROR in '"+regexp+"' pattern", e);
                    continue;
                }
                Matcher matcher;
                try {
                    matcher = pattern.matcher(res);
                } catch (Exception e) {
                    logError(worker, "SECRETS - ERROR in pattern.matcher, res="+res, e);
                    continue;
                }

                if (matcher.find() == false) {
                    // Entry not found on device, delete cached secret
                    if (partial) {
                        traceInfo(worker, "SECRETS - No entry on device (partial), ignoring : "+root);
                    } else {
                        traceInfo(worker, "SECRETS - No entry on device, deleting : "+root);
                        operDeleteList(worker, root);
                    }
                    continue;
                }

                String devicePw = matcher.group(1).trim();
                if (isClearText(devicePw)) {
                    // Device password is cleartext, no need to cache it anymore
                    traceInfo(worker, "SECRETS - Device cleartext entry '"+devicePw+"', deleting : "+root);
                    operDeleteList(worker, root);
                    continue;
                }


                if (encrypted.isEmpty() || resync) {
                    // NEW/UPDATED : Cache encrypted password for device comparison
                    traceInfo(worker, "SECRETS - Device encrypted secret, caching '"+devicePw+"' for : "+root);
                    operSetElem(worker, devicePw, root+"/encrypted");
                    continue;
                }

                // ACTIVE : Compare encrypted device password vs cached
                if (!encrypted.equals(devicePw)) {
                    // Password changed on device, ignore entry (may resync later)
                    traceInfo(worker, "SECRETS - Device diff, ignoring : "+root);
                    continue;
                }

                // Device entry matches cached, replace with cleartext secret
                String cleartext = entry.leaf("cleartext").valueAsString();
                if (logVerbose)
                    log = " '"+cleartext+"'";
                traceInfo(worker, "SECRETS - Device match, injecting cleartext"+log+" in "+root);
                res = res.substring(0,matcher.start(1))
                    + cleartext
                    + res.substring(matcher.start(1) + devicePw.length());

                // DIRTY patch to strip dynamic 'encrypted' once we inserted cleartext secret
                // snmp-server user * * v3 encrypted auth * <PASSWORD> priv * [128] <PASSWORD>
                String line = matcher.group(0).trim().replace(devicePw, cleartext);
                if (line.startsWith("snmp-server user ")) {
                    String stripped = line.replace(" encrypted auth ", " auth ");
                    if (!line.equals(stripped)) {
                        traceVerbose(worker, "SECRETS - PATCH: stripping encrypted keyword");
                        res = res.replace(line, stripped);
                    }
                }
            }
        }
        catch (Exception e) {
            logError(worker, "SECRETS - update() ERROR", e);
        }
        finally {
            if (container != null) {
                container.stopCdbSession();
                container = null;
            }
            context = null;
            resync = false;
            newEntries = false;
        }

        return res;
    }

    /*
     * delete
     */
    public int delete(NedWorker worker)
        throws NedException {
        int found = 0;
        NavuContext context = null;
        NavuContainer container = null;
        try {

            // Init NAVU
            NavuList secretsList;
            try {
                context = new NavuContext(cdbOper);
                container = new NavuContainer(context);
                secretsList = container
                    .container(Ncs.hash)
                    .container(Ncs._devices_)
                    .list(Ncs._device_)
                    .elem(new ConfKey(new ConfBuf(device_id)))
                    .container("ncs", "ned-settings")
                    .container("iosxr-op", "cisco-iosxr-oper")
                    .list("secrets");
            } catch (Exception e) {
                throw new NedException("failed to create and init secrets navu");
            }

            // Loop through all entries and delete them
            for (NavuContainer entry : secretsList.elements()) {
                String root = entry.leaf("path").valueAsString();
                operDeleteList(worker, root);
                found++;
            }
        } catch (Exception e) {
            logError(worker, "SECRETS - delete() ERROR", e);
        }
        finally {
            if (found > 0)
                traceInfo(worker, "SECRETS - deleted "+found+" cached entries");
            if (container != null) {
                container.stopCdbSession();
                container = null;
            }
            context = null;
            resync = false;
            newEntries = false;
        }
        return found;
    }

    /*
     * getNewEntries
     */
    public boolean getNewEntries() {
        return newEntries;
    }

    /*
     * clrNewEntries
     */
    public void clrNewEntries() {
        newEntries = false;
    }

    /*
     * resync
     */
    public void enableReSync() {
        resync = true;
    }

}
