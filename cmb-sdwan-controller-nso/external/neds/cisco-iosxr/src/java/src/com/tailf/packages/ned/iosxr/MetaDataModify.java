package com.tailf.packages.ned.iosxr;

import com.tailf.ned.NedWorker;
import com.tailf.ned.NedException;

import com.tailf.conf.ConfPath;
import com.tailf.conf.ConfValue;

import com.tailf.maapi.Maapi;

import com.tailf.navu.NavuContainer;
import com.tailf.navu.NavuContext;
import com.tailf.navu.NavuList;
import com.tailf.navu.NavuNode;
import com.tailf.navu.NavuLeaf;


/**
 * Utility class for modifying config data based on YANG model meta data provided by NCS.
 *
 * @author lbang
 * @version 2018-01-12
 */

@SuppressWarnings("deprecation")
public class MetaDataModify {

    /*
     * Local data
     */
    private String device_id;
    private String model;
    private boolean isNetsim;
    private boolean trace;
    private boolean showVerbose;
    private boolean autoVrfForwardingRestore;


    /**
     * Constructor
     */
    MetaDataModify(String device_id, String model,
                   boolean trace,
                   boolean showVerbose, boolean autoVrfForwardingRestore ) {
        this.device_id   = device_id;
        this.model       = model;
        this.trace       = trace;
        this.showVerbose = showVerbose;
        this.autoVrfForwardingRestore = autoVrfForwardingRestore;

        this.isNetsim = model.equals("NETSIM");
    }

    /*
     * Write info in NED trace
     *
     * @param info - log string
     */
    private void traceInfo(NedWorker worker, String info) {
        if (trace) {
            worker.trace("-- " + info + "\n", "out", device_id);
        }
    }

    /*
     * Write info in NED trace if verbose output
     *
     * @param info - log string
     */
    private void traceVerbose(NedWorker worker, String info) {
        if (showVerbose && trace) {
            worker.trace("-- " + info + "\n", "out", device_id);
        }
    }

    private boolean isTopExit(String line) {
        if (line.equals("!"))
            return true;
        if (line.equals("exit"))
            return true;
        return false;
    }

    private String duplicateToX(String lprefix, String values, String postfix, int x, String sep) {
        String val[] = values.split(sep+"+");
        if (val.length <= x)
            return lprefix + " " + values + postfix + "\n";
        return duplicateToX2(lprefix, val, postfix, x, sep);
    }

    private String duplicateToX2(String lprefix, String[] val, String postfix, int x, String sep) {
        String buf = "";
        for (int n = 0; n < val.length; n = n + x) {
            String line = "";
            for (int j = n; (j < n + x) && (j < val.length); j++) {
                if (j != n)
                    line += sep;
                line += val[j];
            }
            buf = buf + lprefix + " " + line + postfix + "\n";
        }
        return buf;
    }

    private int NindexOf(String text, String str, int num) {
        int n, i = 0;
        for (n = 0; n < num - 1; n++) {
            i = text.indexOf(str, i);
            if (i < 0)
                return -1;
            i++;
        }
        return text.indexOf(str, i);
    }

    /*
     * Trim cmd and all meta-data tags that goes with it
     */
    private String[] trimCmd(String lines[], int i, int cmd) {
        for (int n = i; n <= cmd; n++)
            lines[n] = "";
        return lines;
    }

    /*
     * getCmd - get first config line after meta-data(s)
     */
    private int getCmd(String lines[], int i) {
        for (int cmd = i; cmd < lines.length; cmd++) {
            String trimmed = lines[cmd].trim();
            if (trimmed.isEmpty())
                continue;
            if (trimmed.startsWith("! meta-data :: /ncs:devices/device{"))
                continue;
            return cmd;
        }
        return -1;
    }

    /*
     * maapiExists
     */
    private boolean maapiExists(NedWorker worker, Maapi mm, int th, String path)
        throws NedException {
        try {
            if (mm.exists(th, path)) {
                traceVerbose(worker, "maapiExists("+path+") = true");
                return true;
            }
        } catch (Exception e) {
            throw new NedException("maapiExists("+path+") ERROR : " + e.getMessage());
        }
        traceVerbose(worker, "maapiExists("+path+") = false");
        return false;
    }

    /*
     * maapiGetLeafString
     */
    private String maapiGetLeafString(NedWorker worker, Maapi mm, int th, String path) {
        // Trim to absolute path
        int up;
        while ((up = path.indexOf("/../")) > 0) {
            int slash = path.lastIndexOf("/", up-1);
            path = path.substring(0, slash) + path.substring(up + 3);
        }
        // Get leaf
        try {
            if (mm.exists(th, path)) {
                return ConfValue.getStringByValue(path, mm.getElem(th, path));
            }
        } catch (Exception ignore) {
            traceInfo(worker, "maapiGetLeafString ERROR: failed to get "+ path);
        }
        return null;
    }

    private String navuLeafGetString(NavuContainer container, String prefix, String name)
        throws Exception {
        NavuLeaf leaf = container.leaf("cisco-ios-xr", name);
        if (leaf == null || leaf.exists() == false)
            return "";
        String value = leaf.valueAsString();
        if (value == null)
            return "";            
        return prefix + value.trim();
    }
    
    private int navuGetIfAddrs(NedWorker worker, NavuContext context, Maapi mm, int th,
                               String ifpath, StringBuilder sb)
        throws Exception {

        // Init NAVU interface container ifroot
        NavuContainer ifroot;
        try {
            ConfPath cp = new ConfPath(ifpath);
            ifroot = (NavuContainer)new NavuContainer(context).getNavuNode(cp);
            if (ifroot == null || ifroot.exists() == false)
                return 0;
        } catch (Exception ignore) {
            return 0; // interface deleted
        }

        // interface * / ipv4 address
        int added = 0;
        String address = maapiGetLeafString(worker, mm, th, ifpath+"/ipv4/address/ip");
        if (address != null) {
            String mask = maapiGetLeafString(worker, mm, th, ifpath+"/ipv4/address/mask");
            sb.append(" ipv4 address "+address+" "+mask);
            String tag = maapiGetLeafString(worker, mm, th, ifpath+"/ipv4/address/route-tag");
            if (tag != null)
                sb.append(" "+tag);
            sb.append("\n");
            added++;
        }

        // interface * / ipv4 address * secondary
        NavuList list = ifroot.container("cisco-ios-xr", "ipv4")
            .container("cisco-ios-xr", "address-secondary-list")
            .list("cisco-ios-xr", "address");
        if (list != null && list.isEmpty() == false) {
            for (NavuContainer addr : list.elements()) {
                String ip = addr.leaf("cisco-ios-xr", "ip").valueAsString().trim();
                String mask = addr.leaf("cisco-ios-xr", "mask").valueAsString().trim();
                String opt = navuLeafGetString(addr, " route-tag ", "route-tag");
                sb.append(" ipv4 address "+ip+" "+mask+opt+" secondary\n");
                added++;
            }
        }

        // interface * / ipv6 enable
        if (maapiExists(worker, mm, th, ifpath+"/ipv6/enable")) {
            sb.append(" ipv6 enable\n");
            added++;
        }
        
        // interface * / ipv6 address *
        list = ifroot.container("cisco-ios-xr", "ipv6")
            .container("cisco-ios-xr", "address")
            .list("cisco-ios-xr", "prefix-list");
        if (list != null && list.isEmpty() == false) {
            for (NavuContainer addr : list.elements()) {
                String prefix = addr.leaf("cisco-ios-xr", "prefix").valueAsString().trim();
                String opt = "";
                if (addr.leaf("cisco-ios-xr", "eui-64").exists())
                    opt += " eiu-64";
                if (addr.leaf("cisco-ios-xr", "link-local").exists())
                    opt += " link-local";
                opt += navuLeafGetString(addr, " route-tag ", "route-tag");
                sb.append(" ipv6 address "+prefix+opt+"\n");
                added++;
            }
        }
        
        return added;
    }
    
    /*
     * Modify config data based on meta-data given by NCS.
     *
     * @param data - config data from applyConfig, before commit
     * @return Config data modified after parsing !meta-data tags
     */
    public String modifyData(NedWorker worker, String data, Maapi mm, int fromTh, int toTh)
        throws Exception {

        NavuContext toContext;
        try {
            toContext = new NavuContext(mm, toTh);
        } catch (Exception e) {
            throw new NedException("MetaDataModify() - ERROR : failed to create NAVU context", e);
        }
        
        // Modify line(s)
        String lines[] = data.split("\n");
        StringBuilder sb = new StringBuilder();
        int lastif = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().isEmpty())
                continue;
            if (lines[i].startsWith("interface "))
                lastif = i;

            // Normal config line -> add
            if (lines[i].trim().startsWith("! meta-data :: /ncs:devices/device{") == false) {
                sb.append(lines[i] + "\n");  
                continue;
            }

            // Find command index (reason: can be multiple meta-data tags per command)
            int cmd = getCmd(lines, i + 1);
            if (cmd == -1)
                continue;
            String trimmed = lines[cmd].trim();
            String pxSpace = lines[cmd].substring(0, lines[cmd].length() - trimmed.length());
            
            // Extract meta-data and meta-value(s), store in metas[] where:
            // metas[1] = meta path
            // metas[2] = meta tag name
            // metas[3] = first meta-value (each value separated by ' :: '
            String meta = lines[i].trim();
            String metas[] = meta.split(" :: ");
            String metaPath = metas[1];
            String metaTag = metas[2];

            
            // max-values
            // max-values-mode
            // ====================
            // Split config lines with multiple values into multiple lines with a maximum
            // number of values per line.
            // metas[3] = offset in values[] for first value
            // metas[4] = maximum number of values per line
            // metas[5] = value separator [OPTIONAL]
            // Example:
            // tailf:meta-data "max-values" {
            //  tailf:meta-value "4 :: 8";
            // }
            if (metaTag.startsWith("max-values")) {
                // Do not split modes with separators if contents in submode
                if (metaTag.equals("max-values-mode")
                    && cmd + 1 < lines.length
                    && !isTopExit(lines[cmd+1])) {
                    continue;
                }
                String sep = " ";
                if (metas.length > 5)
                    sep = metas[5];
                int offset = Integer.parseInt(metas[3]);
                if (trimmed.startsWith("no "))
                    offset++;
                int start = NindexOf(trimmed, " ", offset);
                if (start > 0) {
                    int maxValues = Integer.parseInt(metas[4]);
                    String val[] = trimmed.substring(start+1).trim().split(sep+"+");
                    if (val.length > maxValues) {
                        String lprefix = pxSpace + trimmed.substring(0, start).trim();
                        traceInfo(worker, "meta-data max-values :: transformed => split '"+trimmed
                                  +"' into max "+maxValues+" values, separator='"+sep+"'");
                        sb.append(duplicateToX2(lprefix, val, "", maxValues, sep));
                        lines = trimCmd(lines, i, cmd);
                    }
                }
            }

            // string-remove-quotes
            // ====================
            // metas[3] = regexp, where <STRING> is the string to look at.
            // example:
            // tailf:meta-data "string-remove-quotes" {
            //  tailf:meta-value "route-policy <STRING>";
            // }
            else if (metaTag.startsWith("string-remove-quotes")) {
                if (isNetsim)
                    continue;
                String regexp = metas[3].replace("<STRING>", "\\\"(.*)\\\"");
                String replacement = metas[3].replace("<STRING>", "$1");
                String newline = lines[cmd].replaceFirst(regexp, replacement);
                if (lines[cmd].equals(newline) == false) {
                    lines[cmd] = newline;
                    traceInfo(worker, "meta-data string-remove-quotes :: transformed => unquoted '"+lines[cmd]+"'");
                }
            }

            // if-vrf-restore
            // ==============
            // Restore interface addresses if vrf is modified
            else if (metaTag.equals("if-vrf-restore")) {
                if (isNetsim || autoVrfForwardingRestore == false)
                    continue;
                String ifpath = metaPath.substring(0,metaPath.lastIndexOf("}")+1);
                
                // Trim all (subsequent) address changes in this transaction
                for (int j = cmd + 1; j < lines.length; j++) {
                    if (lines[j].equals("exit"))
                        break;
                    if (lines[j].matches("^ (no )?ipv(4|6) address .*$"))
                        lines[j] = "";
                    if (lines[j].matches("^ (no )?ipv6 enable$"))
                        lines[j] = "";
                }

                // Delete addresses
                sb.append(" no ipv4 address\n");
                if (maapiExists(worker, mm, fromTh, ifpath+"/ipv6/address/prefix-list"))
                    sb.append(" no ipv6 address\n");
                if (maapiExists(worker, mm, fromTh, ifpath+"/ipv6/enable"))
                    sb.append(" no ipv6 enable\n");

                // Add the vrf line
                sb.append(lines[cmd]+"\n");
                lines[cmd] = "";
                
                // Add back all current interface addresses and (optionally) 'ipv6 enable'
                int num = navuGetIfAddrs(worker, toContext, mm, toTh, ifpath, sb);
                if (num > 0) {
                    traceInfo(worker, "meta-data if-vrf-restore :: transformed => "
                              +lines[lastif]+" vrf modified, restored "+num+" item(s)");
                }
            }
            
            // metaTag not handled by this loop -> copy it over
            else {
                sb.append(lines[i] + "\n");
            }
        }

        // Make single string again
        data = "\n" + sb.toString();
        return data;
    }
}
