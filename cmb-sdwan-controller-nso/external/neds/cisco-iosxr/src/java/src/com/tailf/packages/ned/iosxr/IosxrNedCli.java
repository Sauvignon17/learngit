package com.tailf.packages.ned.iosxr;

import java.io.IOException;

import ch.ethz.ssh2.Connection;
import ch.ethz.ssh2.SFTPv3Client;
import ch.ethz.ssh2.SFTPv3FileHandle;

import java.math.BigInteger;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Collections;

import org.apache.log4j.Logger;

import java.lang.reflect.Method;

import com.tailf.conf.Conf;
import com.tailf.conf.ConfBuf;
import com.tailf.conf.ConfPath;
import com.tailf.conf.ConfValue;
import com.tailf.conf.ConfUInt16;
import com.tailf.conf.ConfUInt8;
import com.tailf.conf.ConfObject;
import com.tailf.conf.ConfKey;
import com.tailf.conf.ConfXMLParam;
import com.tailf.conf.ConfXMLParamStart;
import com.tailf.conf.ConfXMLParamStop;
import com.tailf.conf.ConfXMLParamValue;

import com.tailf.maapi.Maapi;
import com.tailf.maapi.MaapiException;

import com.tailf.ncs.ResourceManager;
import com.tailf.ncs.annotations.Resource;
import com.tailf.ncs.annotations.ResourceType;
import com.tailf.ncs.annotations.Scope;
import com.tailf.ncs.ns.Ncs;

import com.tailf.ned.NedCapability;
import com.tailf.ned.NedCliBase;
import com.tailf.ned.NedCmd;
import com.tailf.ned.NedException;
import com.tailf.ned.NedExpectResult;
//4.x: import com.tailf.ned.NedWorker;
import com.tailf.ned.NedMux;
import com.tailf.ned.NedTTL;
import com.tailf.ned.NedTracer;
import com.tailf.ned.NedWorker;
import com.tailf.ned.NedWorker.TransactionIdMode;
import com.tailf.ned.SSHSessionException;
import com.tailf.ned.TelnetSession;

import com.tailf.packages.ned.nedcom.Schema;
import com.tailf.packages.ned.nedcom.NedComCliBase;

import com.tailf.cdb.Cdb;
import com.tailf.cdb.CdbDBType;
import com.tailf.cdb.CdbSession;

import com.tailf.navu.NavuContainer;
import com.tailf.navu.NavuContext;
import com.tailf.navu.NavuList;
import com.tailf.navu.NavuNode;

/**
 * This class implements NED interface for cisco iosxr routers
 *
 * NSO PHASES:
 *              prepare (send data to device)
 *               /   \
 *              v     v
 *           abort | commit(send confirmed commit (ios would do noop))
 *                    /   \
 *                   v     v
 *                revert | persist (send confirming commit)
 */

@SuppressWarnings("deprecation")
public class IosxrNedCli extends NedComCliBase {

    private String DATE = "2018-02-08";
    private String VERSION = "6.4";

    public Logger LOGGER = Logger.getLogger(IosxrNedCli.class);

    @Resource(type=ResourceType.MAAPI, scope=Scope.INSTANCE)
    public Maapi mm;
    @Resource(type=ResourceType.CDB, scope=Scope.INSTANCE)
    public Cdb cdb;
    public CdbSession cdbOper = null;
    private String OPER_PATH, CONF_PATH;

    private String iosversion = "unknown";
    private String iosmodel   = "unknown";
    private String iosserial  = "unknown";

    private MetaDataModify metaData;
    private NedSecrets secrets;
    private NedLiveStatus nedLiveStatus;
    private NedLoginScripts login;

    private boolean inConfig = false;
    private String lastGetConfig = null;
    private boolean textMode = false;
    private String commitCommand;
    private int numCommit = 0;
    private int numAdminCommit = 0;

    private int minReadTimeout;
    private boolean rebooting = false;
    private boolean haveSerialNumber = false;

    private String syncFile = null;
    private boolean showRaw = false;
    private String failphase = "";

    // SUPPORT:
    private boolean supportCommitShowError = true;

    // NED-SETTINGS
    private ArrayList<String> dynamicWarning = new ArrayList<String>();
    private ArrayList<String[]> injectCommand = new ArrayList<String[]>();
    private ArrayList<String[]> autoPrompts = new ArrayList<String[]>();
    private String nedSettingLevel = "";
    private String deviceProfile = "cisco-iosxr";
    private String transActionIdMethod;
    private String commitMethod;
    private String commitOptions;
    private String configMethod;
    private boolean showRunningStrictMode;
    private int chunkSize;
    private int retryCount;
    private int waitTime;
    private boolean includeCachedShowVersion;
    private boolean autoVrfForwardingRestore;
    private boolean autoCSCtk60033Patch;
    private boolean apiEditRoutePolicy;
    private String remoteConnection;
    private String remoteAddress = null;
    private String remotePort = null;
    private String remoteCommand = null;
    private String remotePrompt = null;
    private String remoteName = null;
    private String remotePassword = null;
    private String proxyPrompt = null;
    private String getDeviceMethod;
    private String getDeviceFile;
    private int applySftpThreshold;
    private String applyDeviceFile;
    private int applyOobExclusiveRetries;
    private boolean turboParserEnable;
    private boolean robustParserMode;
    private int liveStatusTTL;
    private boolean preferPlatformSN;
    private String loginScript;

    // start of input, 1 character, > 0 non-# and ' ', one #, >= 0 ' ', eol
    private static String prompt = "\\A[a-zA-Z0-9][^\\# ]+#[ ]?$";
    private static String admin_prompt = "\\A[^\\# ]+\\(admin\\)#[ ]?$";
    private static String config_prompt = "\\A.*\\(.*\\)#[ ]?$"; // may also match admin_prompt
    private static String CMD_ERROR = "xyzERRORxyz";

    // List used by the showPartial() handler.
    private static Pattern[] protectedPaths =
        new Pattern[] {
        Pattern.compile("policy-map \\S+( \\\\ class\\s+\\S+).*"),
        Pattern.compile("admin( \\\\.*)")
    };

    private final int sftpMaxSize = 32768;

    private final static Pattern[]
        move_to_top_pattern,
        noprint_line_wait_pattern,
        enter_config_pattern,
        exit_config_pattern;

    private static Schema schema;
    static {
        move_to_top_pattern = new Pattern[] {
            Pattern.compile("\\A.*\\((admin-)?config\\)#"),
            Pattern.compile("Invalid input detected at"),
            Pattern.compile("\\A.*\\(.*\\)#")
        };

        noprint_line_wait_pattern = new Pattern[] {
            Pattern.compile("\\A.*\\((admin-)?config\\)#"),
            Pattern.compile("\\A.*\\(cfg\\)#"),
            Pattern.compile("\\A.*\\((admin-)?config[^\\(\\)# ]+\\)#"),
            Pattern.compile("\\A.*\\(cfg[^\\(\\)# ]+\\)#"),
            Pattern.compile("\\A.*Uncommitted changes found, commit them"),
            Pattern.compile(prompt)
        };

        enter_config_pattern = new Pattern[] {
            Pattern.compile("\\A\\S*\\((admin-)?config.*\\)#"),
            Pattern.compile("\\A.*running configuration is inconsistent with persistent configuration"),
            Pattern.compile("\\A\\S*#")
        };

        exit_config_pattern = new Pattern[] {
            Pattern.compile("\\A.*\\((admin-)?config\\)#"),
            Pattern.compile("\\A.*\\(cfg\\)#"),
            Pattern.compile("\\A.*\\((admin-)?config.*\\)#"),
            Pattern.compile("\\A.*\\(cfg.*\\)#"),
            Pattern.compile(admin_prompt),
            Pattern.compile(prompt),
            Pattern.compile("You are exiting after a 'commit confirm'"),
            Pattern.compile("\\A.*Uncommitted changes found, commit them"),
            Pattern.compile("\\A% Invalid input detected at ")
        };

        schema = loadSchema(IosxrNedCli.class);
        if (schema != null) {
            schema.config.matchRelaxed = true;
            schema.config.matchEnumsIgnoreCase = true;
            schema.config.matchEnumsPartial = true;
        }
    }

    public IosxrNedCli() {
        super();
        try {
            ResourceManager.registerResources(this);
        } catch (Exception e) {
            LOGGER.error("Error injecting Resources", e);
        }
    }


    /**
     * Constructor
     */
    public IosxrNedCli(String device_id,
               InetAddress ip,
               int port,
               String proto,  // ssh or telnet
               String ruser,
               String pass,
               String secpass,
               boolean trace,
               int connectTimeout, // msec
               int readTimeout,    // msec
               int writeTimeout,   // msec
               NedMux mux,
               NedWorker worker) {

        super(device_id, ip, port, proto, ruser, pass, secpass,
              trace, connectTimeout, readTimeout, writeTimeout, mux,
              worker);

        long start = tick(0);
        if (trace)
            tracer = worker;
        else
            tracer = null;
        logInfo(worker, "BEGIN CONNECT");

        // LOG NED version & date
        logInfo(worker, "NED VERSION: cisco-iosxr "+VERSION+" "+DATE);

        //
        // Init NCS resources
        //
        try {
            ResourceManager.registerResources(this);
        } catch (Exception e) {
            LOGGER.error("Error injecting Resources", e);
        }

        //
        // Open maapi read session
        //
        int thr;
        try {
            mm.setUserSession(1);
            thr = mm.startTrans(Conf.DB_RUNNING, Conf.MODE_READ);
        } catch (Exception e) {
            logError(worker, "Error initializing CDB read session :: ", e);
            return;
        }

        //
        // Connect and Init NED
        //
        try {
            // Read ned-settings
            try {
                readNedSettings(worker, thr);
            }
            catch (Exception e) {
                logError(worker, "failed to read cisco-iosxr ned-settings", e);
                throw e;
            }

            // Set connect timeout and log timeouts
            worker.setTimeout(connectTimeout);
            traceInfo(worker, "connect-timeout "+connectTimeout+" read-timeout "+readTimeout+" write-timeout "+writeTimeout);
            minReadTimeout = readTimeout < 30000 ? 30000 : readTimeout;

            // Create Login class
            login = new NedLoginScripts(this,null,device_id,trace,logVerbose,retryCount,waitTime);

            //
            // Legacy login script "default" with support for proxy ned-settings
            //
            if (loginScript.equals("default")) {
                login.addAlias(worker, "default", "exec_prompt", "\\A[a-zA-Z0-9][^\\# ]+#[ ]?$");
                login.addAlias(worker, "default", "config_prompt", "\\A.*\\(.*\\)#[ ]?$");
                login.add(worker, "default", "id 0 state 0 connect next-state 1");
                login.add(worker, "default", "id 100 state 1 send \n next-state 2");
                if (proto.equals("ssh")) {
                    login.add(worker, "default", "id 200 state 2 end");
                } else {
                    login.add(worker, "default", "id 200 state 2 expect Username: send $remote-name\n");
                    login.add(worker, "default", "id 201 state 2 expect Password: send-noecho $remote-password\n");
                    login.add(worker, "default", "id 202 state 2 expect $config_prompt next-state 3");
                    login.add(worker, "default", "id 203 state 2 expect $exec_prompt next-state 3");
                    login.add(worker, "default", "id 300 state 3 end");
                }
                session = login.connect(worker, "default", readTimeout);

                // Optional EXEC proxy connect
                if (remoteConnection.equals("exec")) {
                    proxyExecConnect(worker, thr);
                }
                // Optional SSH or TELNET proxy connect
                else if (remoteConnection.equals("ssh") || remoteConnection.equals("telnet")) {
                    setupProxy(worker, thr);
                }

                // Send newline for terminal support.
                traceInfo(worker, "Sending extra newline");
                session.print("\n");

                // Wait for device prompt
                NedExpectResult res = session.expect(new String[] { "\\A.*[Uu]sername:",
                                                                    config_prompt,
                                                                    prompt}, worker);
                if (res.getHit() == 0)
                    throw new NedException("Authentication failed");
                if (res.getHit() == 1) {
                    traceInfo(worker, "Exiting config mode");
                    exitConfig2(worker, "logged directly into config mode");
                }
            }

            //
            // ned-login external script
            //
            else {
                // Populate from ned-settings
                login.readFromNedSettings(worker, loginScript, "cisco-iosxr", mm);

                // Add proxy settings as macros
                login.addAlias(worker, loginScript, "proxy-remote-address", remoteAddress);
                login.addAlias(worker, loginScript, "proxy-remote-port", remotePort);
                login.addAlias(worker, loginScript, "proxy-remote-command", remoteCommand);
                login.addAlias(worker, loginScript, "proxy-remote-prompt", remotePrompt);
                login.addAlias(worker, loginScript, "proxy-remote-name", remoteName);
                login.addAlias(worker, loginScript, "proxy-remote-password", remotePassword);
                login.addAlias(worker, loginScript, "proxy-prompt", proxyPrompt);

                // Connect
                session = login.connect(worker, loginScript, readTimeout);
            }

            // Set terminal settings
            session.print("terminal length 0\n");
            session.expect("terminal length 0", worker);
            session.expect(prompt, worker);
            session.print("terminal width 0\n");
            session.expect("terminal width 0", worker);
            session.expect(prompt, worker);

            // Issue show version to check device/os type
            session.print("show version brief\n");
            session.expect("show version brief", worker);
            String version = session.expect(prompt, worker);
            if (version.indexOf("Invalid input detected") >= 0) {
                session.print("show version\n");
                session.expect("show version", worker);
                version = session.expect(prompt, worker);
            }

            /* Verify we connected to a XR device */
            if (version.contains("Cisco IOS XR Software") == false) {
                worker.error(NedCmd.CONNECT_CLI, NedCmd.cmdToString(NedCmd.CONNECT_CLI), "unknown device");
                return;
            }

            //
            // NETSIM
            //
            if (version.contains("NETSIM")) {

                // Show CONFD & NED version used by NETSIM in ned trace
                session.print("show confd-state version\n");
                session.expect("show confd-state version", worker);
                session.expect(prompt, worker);

                session.print("show confd-state loaded-data-models "+
                              "data-model tailf-ned-cisco-ios-xr\n");
                session.expect("show confd-state loaded-data-models "+
                               "data-model tailf-ned-cisco-ios-xr", worker);
                session.expect(prompt, worker);

                // Set NETSIM data and defaults
                iosversion = "cisco-iosxr-" + VERSION;
                iosmodel = "NETSIM";
                iosserial = device_id;
                transActionIdMethod = "config-hash"; // override
                getDeviceMethod = "show running-config";
                applySftpThreshold = 2147483647;
            }

            //
            // REAL DEVICE
            //
            else {
                // Get version
                version = version.replaceAll("\\r", "");
                iosversion = findLine(version, "Cisco IOS XR Software, Version ");
                if (iosversion != null) {
                    int n;
                    iosversion = iosversion.substring(31);
                    if ((n = iosversion.indexOf("[")) > 0)
                        iosversion = iosversion.substring(0,n);
                }

                // Get model
                iosmodel = findLine(version, "\ncisco ");
                if (iosmodel != null) {
                    iosmodel = iosmodel.trim();
                    iosmodel = iosmodel.replaceAll("cisco (.*) Series .*", "$1");
                    iosmodel = iosmodel.replaceAll("cisco (.*) \\(.*", "$1");
                }

                // Get serial-number
                iosserial = null;
                if (preferPlatformSN) {
                    long perf = tick(0);
                    String serial = getPlatformData(thr, "serial-number");
                    if (serial != null && serial.equals("unknown") == false) {
                        iosserial = serial;
                        traceInfo(worker, "devices device platform serial-number = "
                                  +iosserial+" "+tickToString(perf));
                    }
                }
                if (iosserial == null
                    && (version.contains("cisco CRS") || version.contains("cisco NCS"))) {
                    // NOTE: Seems 'show inventory' is faster on CRS/NCS (ncs6k), but slower on ASRs
                    long perf = tick(0);
                    String serial = print_line_exec(worker, "show inventory | i SN");
                    if ((iosserial = getMatch(serial, "SN[:]\\s+(\\S+)")) != null) {
                        traceInfo(worker, "show inventory serial-number = "+iosserial+" "+tickToString(perf));
                    }
                }
                if (iosserial == null) {
                    long perf = tick(0);
                    String serial = print_line_exec(worker, "show diag | i S/N");
                    if ((iosserial = getMatch(serial, "S/N(?:[:])?\\s+(\\S+)")) != null) {
                        traceInfo(worker, "show diag serial-number = "+iosserial+" "+tickToString(perf));
                    }
                }
                if (iosserial == null) {
                    traceInfo(worker, "WARNING: failed to retrieve serial-number");
                    iosserial = "unknown";
                }
            }

            //
            // Setup NED
            //
            setupIOSXRNed(worker);

            //
            // Setup NedLiveStatus
            //
            nedLiveStatus = new NedLiveStatus(session, device_id, trace, logVerbose, liveStatusTTL, readTimeout);
            nedLiveStatusRegister(worker); // Register live-status 'commands'

            // SFTP download, create directory if not there
            if (getDeviceMethod.equals("sftp-transfer") && getDeviceFile != null) {
                String path = getMatch(getDeviceFile, "(\\S+)/\\S+");
                if (path != null) {
                    traceVerbose(worker, "sftp-transfer checking "+path+" directory");
                    String reply = print_line_exec(worker, "dir "+path);
                    if (reply.contains("No such file or directory")) {
                        traceInfo(worker, "SFTP creating download directory: "+path);
                        reply = run_command(worker, "mkdir " + path + " | prompts ENTER", false, true);
                        if (!reply.contains("Created dir " + path)) {
                            traceInfo(worker, "sftp-transfer ERROR in creating 'get-device-config-settings file' directory: "+ reply);
                            traceInfo(worker, "Disabling sftp-transfer");
                            getDeviceMethod = "show running-config";
                        }
                    }
                }
            }

            logInfo(worker, "DONE CONNECT "+tickToString(start));
        }
        catch (Exception e) {
            logError(worker, "ERROR failed connect", e);
            worker.error(NedCmd.CONNECT_CLI, e.getMessage());
        }
        finally {
            try {
                mm.finishTrans(thr);
            } catch (Exception ignore) {}
        }
    }


    /**
     * NOCONNECT constructor
     */
    public IosxrNedCli(String device_id, NedMux mux, NedWorker worker) {

        this();
        this.device_id = device_id;

        long start = tick(0);
        trace = true;
        tracer = worker;

        logInfo(worker, "BEGIN NO-CONNECT");
        logInfo(worker, "*NSO VERSION: "+Conf.PROTOVSN+" "+String.format("%x", Conf.LIBVSN));
        logInfo(worker, "*NED VERSION: cisco-iosxr "+VERSION+" "+DATE);

        // Note: Init NCS resources? (get error if connected, disconnect and dry-run)

        // Read ned-settings
        int xthr;
        try {
            mm.setUserSession(1);
            xthr = mm.startTrans(Conf.DB_RUNNING, Conf.MODE_READ);
            readNedSettings(worker, xthr);
        } catch (Exception e) {
            worker.error(NedCmd.CONNECT_CLI, "noconnect failed to read ned-settings: "+e.getMessage());
            return;
        }

        // Set platform data - iosmodel, iosversion and iospolice
        try {
            iosmodel   = getPlatformData(xthr, "model");
            iosversion = getPlatformData(xthr, "version");
            iosserial  = getPlatformData(xthr, "serial-number");
        } catch (Exception e) {
            worker.error(NedCmd.CONNECT_CLI, "noconnect failed to read platform data: "+e.getMessage());
            return;
        }

        // Setup NED
        try {
            setupIOSXRNed(worker);
            mm.finishTrans(xthr);
        } catch (Exception e) {
            worker.error(NedCmd.CONNECT_CLI, "noconnect failed to setup NED: "+e.getMessage());
        }
        logInfo(worker, "DONE NO-CONNECT "+tickToString(start));
    }

    private String getPlatformData(int thr, String leaf)
        throws Exception {

        // First try devices device platform
        String p = "/ncs:devices/device{"+device_id+"}/platform/" + leaf;
        try {
            if (mm.exists(thr, p)) {
                return ConfValue.getStringByValue(p, mm.getElem(thr, p));
            }
        } catch (MaapiException ignore) {}

        // Second try config cached-show version
        if (includeCachedShowVersion) {
            p = "/ncs:devices/device{"+device_id+"}/config/cisco-ios-xr:cached-show/version/"+leaf;
            try {
                if (mm.exists(thr, p)) {
                    return ConfValue.getStringByValue(p, mm.getElem(thr, p));
                }
            } catch (MaapiException ignore) {}
        }

        return "unknown";
    }

    private void nedLiveStatusRegister(NedWorker worker)
        throws Exception {
        String SP = "\\s+";
        String INT = "\\s*(\\d+)";

        // RFC7223 - A YANG Data Model for Interface Management
        // show devices device <devname> live-status interfaces-state interface
        nedLiveStatus.add(worker, "interfaces-state/interface",
                    "show interfaces $1",
                    "(\\S+) is(?: administratively)? (?:down|up|deleted).* line protocol is",
                    new String[] {
                        "{interface}([a-zA-Z]+\\S*[0-9]\\S*) is(?: administratively)? (?:down|up){/interface}",
                        "<type></type>",
                        "<admin-status type=enumeration>\\S+ is(?: administratively)? (down|up).* line protocol is</admin-status>",
                        "<oper-status>Line protocol is (\\S+)</oper-status>",
                        "<last-change></last-change>",
                        "<if-index></if-index>",
                        "<phys-address type=phys-address>Hardware .* address is (\\S+)</phys-address>",
                        "<higher-layer-if></higher-layer-if>",
                        "<lower-layer-if></lower-layer-if>",
                        "<speed type=integer>, BW (.+)</speed>",
                        "<statistics/discontinuity-time></statistics/discontinuity-time>",
                        "<statistics/in-octets>packets input,"+INT+" bytes</statistics/in-octets>",
                        "<statistics/out-octets>packets output,"+INT+" bytes</statistics/out-octets>",
                        "<REGION_START>\\d+ packets input</REGION_START> <REGION_END>\\d+ packets output</REGION_END>",
                        "<statistics/in-unicast-pkts>Unicast:"+INT+"</statistics/in-unicast-pkts>",
                        "<statistics/in-broadcast-pkts>Received "+INT+" broadcast</statistics/in-broadcast-pkts>",
                        "<statistics/in-multicast-pkts> "+INT+" multicast packets</statistics/in-multicast-pkts>",
                        "<statistics/in-discards>"+INT+" total input drops</statistics/in-discards>",
                        "<statistics/in-errors>"+INT+" input errors,</statistics/in-errors>",
                        "<statistics/in-unknown-protos>"+INT+" drops for unrecognized upper-level protocol</statistics/in-unknown-protos>",
                        "<REGION_RESET>",
                        "<REGION_START>\\d+ packets output,</REGION_START>",
                        "<statistics/out-unicast-pkts>"+INT+" packets output</statistics/out-unicast-pkts>",
                        "<statistics/out-broadcast-pkts>Output "+INT+" broadcast packets</statistics/out-broadcast-pkts>",
                        "<statistics/out-multicast-pkts>"+INT+" multicast packets</statistics/out-multicast-pkts>",
                        "<statistics/out-discards>"+INT+" total output drops</statistics/out-discards>",
                        "<statistics/out-errors>"+INT+" output errors,</statistics/out-errors>",
                        "<REGION_RESET>",
                        "<ip:ipv4 type=presence>true</ip:ipv4>",
                        "<ip:ipv4/forwarding type=constant>true</ip:ipv4/forwarding>",
                        "<ip:ipv4/mtu>MTU (\\d+) bytes</ip:ipv4/mtu>",
                        "<ip:ipv4/neighbor></ip:ipv4/neighbor>", // empty list
                        "<ip:ipv6 type=presence>true</ip:ipv6>",
                        "<ip:ipv6/forwarding type=constant>true</ip:ipv6/forwarding>",
                        "<ip:ipv6/mtu>MTU (\\d+) bytes</ip:ipv6/mtu>",
                        "<ip:ipv6/neighbor></ip:ipv6/neighbor>" // empty list
                    });

        // RFC7277 - YANG IP Management
        nedLiveStatus.add(worker, "interfaces-state/interface{$}/ip:ipv4/address",
                    "show run interface $1 | i ipv4 address",
                    "ipv4 address (\\S.*)",
                    new String[] {
                        "{ip:ipv4/address}ipv4 address (\\S+) {/ip:ipv4/address}",
                        "<netmask>ipv4 address \\S+ (\\S+)</netmask>",
                        "<origin></origin>"
                    });
        nedLiveStatus.add(worker, "interfaces-state/interface{$}/ip:ipv6/address",
                    "show run interface $1 | i ipv6 address",
                    "ipv6 address (\\S.*)",
                    new String[] {
                        "{ip:ipv6/address}ipv6 address (\\S+)/{/ip:ipv6/address}",
                        "<prefix-length>ipv6 address \\S+/(\\d+)</prefix-length>",
                        "<origin></origin>",
                        "<status></status>"
                    });

        // show devices device <devname> live-status inventory
        nedLiveStatus.add(worker, "inventory",
                    "show inventory $1",
                    "NAME: (.+), DESCR",
                    new String[] {
                        "{inventory}NAME: (.+), DESCR:{/inventory}",
                        "<descr>NAME: .* DESCR: (.*)</descr>",
                        "<sn>PID: .* SN: (\\S+)</sn>",
                    });

        //traceInfo(worker, "show live-status support:\n"+nedLiveStatus.toString());
    }

    private String tickToString(long start) {
        long stop = tick(start);
        return String.format("[%d ms]", stop);
    }

    /**
     * sleep
     */
    private void sleep(NedWorker worker, long milliseconds, boolean log) {
        if (log)
            traceVerbose(worker, "Sleeping " + milliseconds + " milliseconds");
        try {
            Thread.sleep(milliseconds);
            if (log)
                traceVerbose(worker, "Woke up from sleep");
        } catch (InterruptedException e) {
            System.err.println("sleep interrupted");
        }
    }

    private void proxyExecConnect(NedWorker worker, int th)
        throws Exception, NedException, IOException, ApplyException {
        NedExpectResult res;

        traceInfo(worker, "Connecting using proxy method : "+remoteConnection);

        try {
            if (remoteCommand == null || remotePrompt == null || remoteName == null || remotePassword == null)
                throw new NedException("Missing exec proxy connection information, check your proxy-settings");

            // Send connect string, wait for prompt
            session.println(remoteCommand);
            res = session.expect(new String[] { "timed out", remotePrompt });
            if (res.getHit() <= 0)
                throw new NedException(NedWorker.CONNECT_TIMEOUT,
                                       "PROXY connect failed, connection timed out");

            traceInfo(worker, "PROXY connected");

            // Send newline, wait for username prompt
            session.println("\r");
            res = session.expect(new String[] { config_prompt, prompt, "Username:"});
            if (res.getHit() < 2) {
                traceInfo(worker, "PROXY logged in without username and password");
                if (res.getHit() == 0) {
                    traceInfo(worker, "PROXY exiting config mode");
                    exitConfig2(worker, "PROXY exiting config mode");
                }
            } else {
                // Send username, wait for password prompt
                session.println(remoteName);
                res = session.expect(new String[] { "timed out", "Password:" });
                if (res.getHit() <= 0)
                    throw new NedException(NedWorker.CONNECT_TIMEOUT,
                                           "PROXY connect failed, timeout expired");

                // Send password (mask from log), wait for exec prompt
                if (trace)
                    session.setTracer(null);
                traceInfo(worker, "Sending password (NOT LOGGED)");
                session.println(remotePassword);
                if (trace)
                    session.setTracer(worker);
                res = session.expect(new String[] {
                        "Login invalid",
                        "timed out",
                        prompt},
                    worker);
                if (res.getHit() < 2)
                    throw new NedException(NedWorker.CONNECT_BADAUTH,
                                           "PROXY connect failed, bad name or password?");
            }
        }
        catch (NedException e) {
            throw e;
        }
        catch (Exception e) {
            throw new NedException(NedWorker.CONNECT_CONNECTION_REFUSED, "exec proxy: "+e.getMessage());
        }
    }


    private void setupProxy(NedWorker worker, int th)
        throws NedException, IOException, SSHSessionException, ApplyException {

        traceInfo(worker, "Connecting using proxy method : "+remoteConnection);

        try {
            if (proxyPrompt != null) {
                traceInfo(worker, "Waiting for proxy prompt '" + proxyPrompt + "'");
                session.expect(proxyPrompt, worker);
            }

            // Connect using telnet or ssh
            String cmd;
            if (remoteConnection.equals("telnet"))
                cmd = "telnet "+remoteAddress+" "+remotePort;
            else
                cmd = "ssh -p "+remotePort+" "+remoteName+"@"+remoteAddress;
            session.println(cmd);
            session.expect(new String[] { Pattern.quote(cmd) }, worker);

            // Send username (telnet only)
            NedExpectResult res;
            if (remoteConnection.equals("telnet")) {
                res = session.expect(new Pattern[] {
                        Pattern.compile("[Uu]sername:"),
                        Pattern.compile("telnet: Unable to connect to remote host.*")
                    }, worker);
                if (res.getHit() == 1)
                    throw new NedException(res.getMatch());
                traceInfo(worker, "Sending proxy username");
                session.println(remoteName);
                session.expect(new String[] { Pattern.quote(remoteName) }, worker);
            }

            // Send password (mask from log)
            traceInfo(worker, "Waiting for password prompt");
            res = session.expect(new Pattern[] {
                    Pattern.compile("\\A.*[Pp]assword:"),
                    Pattern.compile("Unable to negotiate with.*"),
                    Pattern.compile("\\Assh: connect to host "+remoteAddress+" .*")
                }, worker);
            if (res.getHit() != 0)
                throw new NedException(res.getMatch());
            if (trace)
                session.setTracer(null);
            traceInfo(worker, "Sending proxy password (NOT LOGGED)");
            session.println(remotePassword);
            if (trace)
                session.setTracer(worker);

            traceInfo(worker, "PROXY connected");
        }
        catch (NedException e) {
            throw e;
        }
        catch (Exception e) {
            throw new NedException(NedWorker.CONNECT_CONNECTION_REFUSED, "proxy: "+e.getMessage());
        }
    }

    /**
     * reconnect
     */
    public void reconnect(NedWorker worker) {
        // all capas and transmode already set in constructor
        // nothing needs to be done
    }


    /**
     * Which Yang modules are covered by the class
     */
    public String [] modules() {
        return new String[] { "tailf-ned-cisco-ios-xr" };
    }


    /**
     * Which identity is implemented by the class
     */
    public String identity() {
        return "cisco-ios-xr-id:cisco-ios-xr";
    }

    private boolean isDevice() {
        return !iosmodel.equals("NETSIM");
    }

    private boolean isNetsim() {
        return iosmodel.equals("NETSIM");
    }

    private String getNedSetting(NedWorker worker, int tid, String path)
       throws Exception {
        String val = null;
        nedSettingLevel = "";

        // Global
        String p = "/ncs:devices/ncs:global-settings/ncs:ned-settings/"+path;
        try {
            if (mm.exists(tid, p)) {
                val = ConfValue.getStringByValue(p, mm.getElem(tid, p));
                nedSettingLevel = " [G]";
            }
        } catch (MaapiException ignore) {
        }

        // Profile
        p = "/ncs:devices/ncs:profiles/profile{"+deviceProfile+"}/ncs:ned-settings/"+path;
        try {
            if (mm.exists(tid, p)) {
                val = ConfValue.getStringByValue(p, mm.getElem(tid, p));
                nedSettingLevel = " [P]";
            }
        } catch (MaapiException ignore) {
        }

        // Device
        p = "/ncs:devices/device{"+device_id+"}/ned-settings/"+path;
        if (mm.exists(tid, p)) {
            val = ConfValue.getStringByValue(p, mm.getElem(tid, p));
            nedSettingLevel = " [D]";
        }

        return val;
    }

    private String getNedSettingString(NedWorker worker, int tid, String path, String defaultValue)
       throws Exception {
        String value = defaultValue;
        String setting = getNedSetting(worker, tid, path);
        if (setting != null)
            value = setting;
        traceInfo(worker, path + " = " + value + nedSettingLevel);
        return value;
    }

    private boolean getNedSettingBoolean(NedWorker worker, int tid, String path, boolean defaultValue)
       throws Exception {
        boolean value = defaultValue;
        String setting = getNedSetting(worker, tid, path);
        if (setting != null)
            value = setting.equals("true") ? true : false;
        traceInfo(worker, path + " = " + value + nedSettingLevel);
        return value;
    }

    private int getNedSettingInt(NedWorker worker, int tid, String path, int defaultValue)
       throws Exception {
        int value = defaultValue;
        String setting = getNedSetting(worker, tid, path);
        if (setting != null)
            value =  Integer.parseInt(setting);
        traceInfo(worker, path + " = " + value + nedSettingLevel);
        return value;
    }


    /**
     * Simple utility to extract the relevant ned-settings from CDB
     * @param worker
     * @param th
     */
    private void readNedSettings(NedWorker worker, int th)
        throws Exception {

        // Get device profile
        String p = "/ncs:devices/device{"+device_id+"}/device-profile";
        try {
            if (mm.exists(th, p)) {
                deviceProfile = ConfValue.getStringByValue(p, mm.getElem(th, p));
            }
        } catch (MaapiException ignore) { }
        traceInfo(worker, "device-profile = " + deviceProfile);

        // Check if have device platform serial-number
        try {
            mm.exists(th, "/ncs:devices/device{"+device_id+"}/platform/serial-number");
            haveSerialNumber = true;
            traceInfo(worker, "have devices device platform serial-number");
        } catch (MaapiException ignore) { }

        //
        // Get NED configuration
        //
        traceInfo(worker, "NED-SETTINGS: ([G]lobal | [P]rofile | [D]evice)");

        // Base roots
        NavuContext context = new NavuContext(mm, th);
        NavuContainer deviceSettings= new NavuContainer(context)
            .container(Ncs.hash)
            .container(Ncs._devices_)
            .list(Ncs._device_)
            .elem(new ConfKey(new ConfBuf(device_id)));

        NavuContainer globalSettings = new NavuContainer(context)
            .container(Ncs.hash)
            .container(Ncs._devices_)
            .container("ncs", "global-settings");

        NavuContainer profileSettings = new NavuContainer(context)
            .container(Ncs.hash)
            .container(Ncs._devices_)
            .container("ncs", "profiles")
            .list(Ncs._profile_)
            .elem(new ConfKey(new ConfBuf(deviceProfile)));

        NavuContainer[] settings = {globalSettings,
                                    profileSettings,
                                    deviceSettings };


        //
        // Get config warnings
        //
        for (NavuContainer s : settings ) {
            if (s == null)
                continue;
            NavuList newWarnings = s.container("ncs", "ned-settings")
                .list("cisco-ios-xr-meta", "cisco-iosxr-config-warning");
            for (NavuContainer entry : newWarnings.elements()) {
                traceInfo(worker, "cisco-iosxr-config-warning \""
                          +entry.leaf("warning").valueAsString()+"\"");
                dynamicWarning.add(entry.leaf("warning").valueAsString());
            }
        }

        //
        // Get auto-prompts
        //
        for (NavuContainer s : settings ) {
            if (s == null)
                continue;
            NavuList prompts = s.container("ncs", "ned-settings")
                .list("cisco-ios-xr-meta", "cisco-iosxr-auto-prompts");
            for (NavuContainer entry : prompts.elements()) {
                String[] newEntry  = new String[3];
                newEntry[0] = entry.leaf("id").valueAsString();
                newEntry[1] = entry.leaf("question").valueAsString();
                newEntry[2] = entry.leaf("answer").valueAsString();
                traceInfo(worker, "cisco-iosxr-auto-prompts "+newEntry[0]
                        + " q \"" +newEntry[1]+"\""
                        + " a \"" +newEntry[2]+"\"");
                autoPrompts.add(newEntry);
            }
        }

        /*
         * Get inject command(s)
         */
        for (NavuContainer s : settings ) {
            if (s == null)
                continue;
            NavuList inject = s.container("ncs", "ned-settings")
                .list("cisco-ios-xr-meta", "cisco-iosxr-inject-command");
            for (NavuContainer entry : inject.elements()) {
                String[] newEntry  = new String[4];
                newEntry[0] = entry.leaf("id").valueAsString();
                newEntry[1] = entry.leaf("config").valueAsString();
                newEntry[2] = entry.leaf("command").valueAsString();
                newEntry[3] = entry.leaf("where").valueAsString();
                if (newEntry[1] == null || newEntry[2] == null || newEntry[3] == null)
                    throw new NedException("inject-command "+newEntry[0]+" missing config, command or <where>, check your ned-settings");
                traceInfo(worker, "inject-command "+newEntry[0]
                        +" cfg "+stringQuote(newEntry[1])
                        +" cmd "+stringQuote(newEntry[2])
                        +" "+newEntry[3]);
                injectCommand.add(newEntry);
            }
        }

        // cisco-iosxr-transaction-id-method
        transActionIdMethod = getNedSettingString(worker, th, "cisco-iosxr-transaction-id-method", "commit-list");

        // cisco-iosxr-commit-method
        commitMethod = getNedSettingString(worker, th, "cisco-iosxr-commit-method", "confirmed");

        // cisco-iosxr apply-device-config-settings commit-options
        commitOptions = getNedSettingString(worker, th, "cisco-iosxr/apply-device-config-settings/commit-options", "show-error");
        commitCommand = "commit " + commitOptions.trim();

        // cisco-iosxr-config-method
        configMethod = getNedSettingString(worker, th, "cisco-iosxr-config-method", "exclusive");

        // cisco-iosxr-show-running-strict-mode
        showRunningStrictMode = getNedSettingBoolean(worker, th, "cisco-iosxr-show-running-strict-mode", false);

        // cisco-iosxr-number-of-lines-to-send-in-chunk
        chunkSize = getNedSettingInt(worker, th, "cisco-iosxr-number-of-lines-to-send-in-chunk", 100);

        // cisco-iosxr-connection-settings number-of-retries
        retryCount = getNedSettingInt(worker, th, "cisco-iosxr-connection-settings/number-of-retries", 0);

        // cisco-iosxr-connection-settings time-between-retry
        waitTime = getNedSettingInt(worker, th, "cisco-iosxr-connection-settings/time-between-retry", 1);

        // cisco-iosxr-connection-settings login-script
        loginScript = getNedSettingString(worker, th, "cisco-iosxr-connection-settings/loginscript", "default");

        // cisco-iosxr-cached-show-enable version
        includeCachedShowVersion = getNedSettingBoolean(worker, th, "cisco-iosxr-cached-show-enable/version", true);

        // cisco-iosxr-log-verbose
        logVerbose = getNedSettingBoolean(worker, th, "cisco-iosxr-log-verbose", false);

        // cisco-iosxr-auto vrf-forwarding-restore
        autoVrfForwardingRestore = getNedSettingBoolean(worker, th, "cisco-iosxr-auto/vrf-forwarding-restore", true);

        // cisco-iosxr-auto CSCtk60033-patch
        autoCSCtk60033Patch = getNedSettingBoolean(worker, th, "cisco-iosxr-auto/CSCtk60033-patch", false);

        // cisco-iosxr api edit-route-policy
        apiEditRoutePolicy = getNedSettingBoolean(worker, th, "cisco-iosxr/api/edit-route-policy", false);

        // cisco-iosxr-proxy-settings
        remoteConnection = getNedSettingString(worker, th, "cisco-iosxr-proxy-settings/remote-connection", "none");
        remoteAddress = getNedSettingString(worker, th, "cisco-iosxr-proxy-settings/remote-address", null);
        remotePort = getNedSettingString(worker, th, "cisco-iosxr-proxy-settings/remote-port", null);
        remoteCommand = getNedSettingString(worker, th, "cisco-iosxr-proxy-settings/remote-command", null);
        remotePrompt = getNedSettingString(worker, th, "cisco-iosxr-proxy-settings/remote-prompt", null);
        remoteName = getNedSettingString(worker, th, "cisco-iosxr-proxy-settings/remote-name", null);
        remotePassword = getNedSettingString(worker, th, "cisco-iosxr-proxy-settings/remote-password", null);

        // cisco-iosxr-proxy-settings proxy-prompt
        proxyPrompt = getNedSettingString(worker, th, "cisco-iosxr-proxy-settings/proxy-prompt", null);

        // cisco-iosxr get-device-config-settings method|file
        getDeviceMethod = getNedSettingString(worker, th, "cisco-iosxr/get-device-config-settings/method", "show running-config");
        getDeviceFile = getNedSettingString(worker, th, "cisco-iosxr/get-device-config-settings/file", "disk0a:/usr/running-config.tmp");

        // cisco-iosxr apply-device-config-settings sftp-threshold
        // cisco-iosxr apply-device-config-settings file
        applySftpThreshold = getNedSettingInt(worker, th, "cisco-iosxr/apply-device-config-settings/sftp-threshold", 2147483647);
        applyDeviceFile = getNedSettingString(worker, th, "cisco-iosxr/apply-device-config-settings/file", "disk0a:/usr/commit-config.tmp");

        // cisco-iosxr apply-device-config-settings oob-exclusive-retries
        applyOobExclusiveRetries = getNedSettingInt(worker, th, "cisco-iosxr/apply-device-config-settings/oob-exclusive-retries", 0);

        // cisco-iosxr extended-parser
        String extendedParser = getNedSettingString(worker, th, "cisco-iosxr/extended-parser", "disable");
        turboParserEnable = extendedParser.contains("turbo");
        robustParserMode = "robust-mode".equals(extendedParser);
        useMaapiSetValues &= "turbo-mode".equals(extendedParser);

        // cisco-ios live-status time-to-live
        liveStatusTTL = getNedSettingInt(worker, th, "cisco-iosxr/live-status/time-to-live", 50);

        // cisco-iosxr behaviour prefer-platform-serial-number
        preferPlatformSN = getNedSettingBoolean(worker, th, "cisco-iosxr/behaviour/prefer-platform-serial-number", true);

        // Done reading ned-settings
        traceInfo(worker, "");
    }


    private void setupIOSXRNed(NedWorker worker)
        throws Exception {

        logInfo(worker, "DEVICE: name=ios-xr model="+iosmodel+" version="+iosversion+" serial="+iosserial);

        if (iosversion.startsWith("4") || isNetsim()) {
            supportCommitShowError = false;
            commitCommand = commitCommand.replace(" show-error", "");
        }
        traceInfo(worker, "SUPPORT commit show-error = " + supportCommitShowError);

        // Add capabilities
        NedCapability capas[] = new NedCapability[3];
        capas[0] = new NedCapability(
                                     "",
                                     "http://tail-f.com/ned/cisco-ios-xr",
                                     "cisco-ios-xr",
                                     "",
                                     DATE,
                                     "");
        capas[1] = new NedCapability("http://tail-f.com/ns/ncs-ned/cli-allow-abbrev-keys","","","","");
        capas[2] = new NedCapability("http://tail-f.com/ns/ncs-ned/show-partial?path-format=cmd-path-modes-only","","","","");
        //Need strictMode: capas[3] = new NedCapability("http://tail-f.com/ns/ncs-ned/cli-strict-parsing", "", "", "", "", "");

        // Add stats capabilities
        NedCapability statscapas[] = new NedCapability[3];
        statscapas[0] = new NedCapability(
                                          "",
                                          "http://tail-f.com/ned/cisco-ios-xr-stats",
                                          "cisco-ios-xr-stats",
                                          "",
                                          DATE,
                                          "");
        statscapas[1] = new NedCapability("",
                                          "urn:ietf:params:xml:ns:yang:ietf-interfaces",
                                          "ietf-interfaces",
                                          "",
                                          "2014-05-08",
                                          "");
        statscapas[2] = new NedCapability("",
                                          "urn:ietf:params:xml:ns:yang:ietf-ip",
                                          "ietf-ip",
                                          "",
                                          "2014-06-16",
                                          "");

        setConnectionData(capas, statscapas,
                          false, // Want reverse diff
                          TransactionIdMode.UNIQUE_STRING);

        /*
         * On NSO 4.0 and later, do register device model and
         * os version.
         */
        if (Conf.LIBVSN >= 0x6000000) {
            traceVerbose(worker, "Updating device platform data");

            ArrayList<ConfXMLParam> xmlParam = new ArrayList<ConfXMLParam>();
            xmlParam.add(new ConfXMLParamStart("ncs", "platform"));
            xmlParam.add(new ConfXMLParamValue("ncs", "name", new ConfBuf("ios-xr")));
            xmlParam.add(new ConfXMLParamValue("ncs", "version", new ConfBuf(iosversion)));
            xmlParam.add(new ConfXMLParamValue("ncs", "model", new ConfBuf(iosmodel)));
            if (haveSerialNumber)
                xmlParam.add(new ConfXMLParamValue("ncs", "serial-number", new ConfBuf(iosserial)));
            xmlParam.add(new ConfXMLParamStop("ncs", "platform"));
            ConfXMLParam[] platformData = xmlParam.toArray(new ConfXMLParam[xmlParam.size()]);
            Method method = this.getClass().getMethod("setPlatformData", new Class[]{ConfXMLParam[].class});
            method.invoke(this, new Object[] { platformData } );
        }

        // Start operational session
        cdbOper = cdb.startSession(CdbDBType.CDB_OPERATIONAL);
        OPER_PATH = "/ncs:devices/ncs:device{"+device_id+"}/ncs:ned-settings/iosxr-op:cisco-iosxr-oper";
        CONF_PATH = "/ncs:devices/ncs:device{"+device_id+"}/config/cisco-ios-xr:";

        // Create utility classes used by IOS NED
        metaData = new MetaDataModify(device_id, iosmodel, trace, logVerbose, autoVrfForwardingRestore);
        secrets = new NedSecrets(cdbOper, device_id, trace, logVerbose);
    }


    /**
     * isCliError
     */
    private boolean isCliError(NedWorker worker, String reply, String trimmed) {

        reply = reply.trim();
        if (reply.isEmpty())
            return false;

        traceVerbose(worker, "Checking device reply="+stringQuote(reply));

        // Ignore dynamic warnings [case sensitive]
        for (int n = 0; n < dynamicWarning.size(); n++) {
            if (findString(dynamicWarning.get(n), reply) >= 0) {
                traceInfo(worker, "ignoring dynamic warning: '"+reply+"'");
                //warningsBuf += "> "+trimmed+"\n"+reply+"\n" ;
                return false;
            }
        }

        // The following is device errors
        if (reply.toLowerCase().indexOf("error") >= 0 ||
            reply.toLowerCase().indexOf("aborted") >= 0 ||
            reply.toLowerCase().indexOf("exceeded") >= 0 ||
            reply.toLowerCase().indexOf("invalid") >= 0 ||
            reply.toLowerCase().indexOf("incomplete") >= 0 ||
            reply.toLowerCase().indexOf("duplicate name") >= 0 ||
            reply.toLowerCase().indexOf("may not be configured") >= 0 ||
            reply.toLowerCase().indexOf("should be in range") >= 0 ||
            reply.toLowerCase().indexOf("is used by") >= 0 ||
            reply.toLowerCase().indexOf("being used") >= 0 ||
            reply.toLowerCase().indexOf("cannot be deleted") >= 0 ||
            reply.toLowerCase().indexOf("bad mask") >= 0 ||
            reply.toLowerCase().indexOf("failed") >= 0) {
            traceInfo(worker, "ERROR SENDING - reply '"+reply+"'");
            return true;
        }

        // Not an error
        return false;
    }


    /**
     * print_line_exec
     */
    private String print_line_exec(NedWorker worker, String line)
        throws NedException, IOException, SSHSessionException, ApplyException {

        // Send command and wait for echo
        session.print(line + "\n");
        session.expect(new String[] { Pattern.quote(line) }, worker);

        // Return command output
        traceVerbose2(worker, "Waiting for prompt");
        return session.expect(prompt, worker);
    }


    /**
     * noprint_line_wait
     */
    private boolean noprint_line_wait(NedWorker worker, int cmd, String line, int retrying)
        throws NedException, IOException, SSHSessionException, ApplyException {

        String trimmed = line.trim();

        // (1) - Expect the echo of the line(s) we sent
        NedExpectResult res;
        if (textMode) {
            // Text mode - multiple lines have been sent, wait for echo of each one
            for (String wait: trimmed.split("\n")) {
                traceVerbose2(worker, "noprint_line_wait(text-mode) - waiting for echo: '"+wait+"'");
                try {
                    session.expect(new String[] { Pattern.quote(wait) }, worker);
                    traceVerbose2(worker, "noprint_line_wait(text-mode) - got echo: '"+wait+"'");
                } catch (SSHSessionException e) {
                    throw new NedException(e.getMessage()+" text-mode waiting for '"+wait+"'");
                }
            }
        } else {
            // Single line sent, wait for its echo
            traceVerbose2(worker, "noprint_line_wait() - waiting for echo: '"+trimmed+"'");
            try {
                session.expect(new String[] { Pattern.quote(trimmed) }, worker);
                traceVerbose2(worker, "noprint_trimmed_wait() - got echo: '"+trimmed+"'");
            } catch (Exception e) {
                // Possibly a timeout, try return the input data from the buffer
                res = session.expect(new Pattern[] { Pattern.compile(".*", Pattern.DOTALL) }, true, 0);
                String msg = e.getMessage()+" sending '"+line+"', blocked on '"+res.getMatch()+"'";
                traceInfo(worker, "ERROR: "+msg);
                throw new NedException(msg);
            }
        }

        // (2) - Wait for the prompt
        traceVerbose2(worker, "noprint_line_wait() - waiting for prompt");
        res = session.expect(noprint_line_wait_pattern, worker);
        traceVerbose2(worker, "noprint_line_wait() - prompt matched("+res.getHit()+"): '"+res.getMatch() + "'");

        // (3) - Check if we exited config mode
        boolean isAtTop;
        switch (res.getHit()) {
        case 0: // (admin-config) | (config)
        case 1: // (cfg)
            isAtTop = true;
            break;
        case 2: // config*
        case 3: // cfg*
            isAtTop = false;
            break;
        case 4: // "Uncommitted changes found, commit them"
            session.print("no\n"); // Send a 'no'
            throw new ExtendedApplyException(trimmed, "exited from config mode", false, false);
        default: // exec prompt
            throw new ExtendedApplyException(trimmed, "exited from config mode with no config", false, false);
        }

        // (4) - Look for errors shown on screen after command was sent
        String reply = res.getText();
        if (isCliError(worker, reply, trimmed)) {
            throw new ExtendedApplyException(trimmed, reply, isAtTop, true);
        }

        return isAtTop;
    }

    /**
     * print_line_wait_oper
     */
    private void print_line_wait_oper(NedWorker worker, int cmd, String line, int timeout)
        throws NedException, IOException, SSHSessionException, ApplyException {
        print_line_wait_oper0(worker, cmd, line, timeout);
    }

    private void print_line_wait_oper(NedWorker worker, int cmd, String line)
        throws NedException, IOException, SSHSessionException, ApplyException {
        print_line_wait_oper0(worker, cmd, line, readTimeout);
    }

    private void print_line_wait_oper0(NedWorker worker, int cmd, String line, int timeout)
        throws NedException, IOException, SSHSessionException, ApplyException {

        Pattern[] operPrompt = new Pattern[] {
            Pattern.compile("if your config is large. Confirm\\?[ ]?\\[y/n\\]\\[confirm\\]"),
            Pattern.compile("Do you wish to proceed with this commit anyway\\?[ ]?\\[no\\]"),
            Pattern.compile(prompt),
            Pattern.compile(config_prompt)
        };

        for (int retries = applyOobExclusiveRetries; retries >= 0; retries--) {

            // Send line to device and wait for echo
            traceVerbose(worker, "SENDING_OPER: '"+line+"'");
            session.print(line+"\n");
            session.expect(new String[] { Pattern.quote(line) }, false, timeout, worker);

            // Wait for (confirmation prompt)
            NedExpectResult res = session.expect(operPrompt, false, timeout, worker);
            if (res.getHit() == 0) {
                session.print("y");
                // Note: not echoing 'y'
                res = session.expect(operPrompt, false, timeout, worker);
            } if (res.getHit() == 1) {
                // Do you wish to proceed with this commit anyway?
                session.print("no\n");
                session.expect(new String[] { Pattern.quote("no") }, false, timeout, worker);
                throw new ExtendedApplyException(line, res.getText(), true, true);
            }

            // Check device reply for error
            String reply = res.getText();
            if (isCliError(worker, reply, line) == false)
                break;
            if (line.trim().startsWith("commit") == false) {
                throw new ExtendedApplyException(line, reply, true, false);
            }

            //
            // commit [confirmed]
            //

            // An out-of-band session has an exclusive configuration lock
            if (retries > 0
                && reply.contains("Failed to commit one or more configuration ") == false) {
                sleep(worker, 1000, true);
                continue;
            }

            // Return detailed error from 'show configuration failed'
            if (reply.contains("issue") && reply.contains("show configuration failed")) {
                String showcmd = "show configuration failed";
                session.println(showcmd);
                session.expect(showcmd, timeout, worker);
                res = session.expect(new String[] { config_prompt, prompt}, false, timeout, worker);
                String msg = res.getText();
                if (msg.contains("No such configuration") == false)
                    throw new ExtendedApplyException(line, msg, true, true);
            }

            // Throw error with output from commit command itself
            throw new ExtendedApplyException(line, reply, true, true);
        }
    }


    /**
     * moveToTopConfig
     */
    private void moveToTopConfig(NedWorker worker)
        throws IOException, SSHSessionException, ApplyException {
        NedExpectResult res;

        traceVerbose(worker, "moveToTopConfig()");

        // Send ENTER to begin by checking our mode
        traceVerbose(worker, "Sending newline");
        session.print("\n");

        for (int i = 0; i < 30; i++) {
            res = session.expect(move_to_top_pattern);
            switch (res.getHit()) {
            case 0: // (admin-config) | (config)
                return;
            case 1: // Invalid input detected at
                session.print("abort\n");
                res = session.expect(new String[] { Pattern.quote("abort") }, worker);
                break;
            case 2: // config sub-mode
                session.print("exit\n");
                res = session.expect(new String[] { Pattern.quote("exit") }, worker);
                break;
            }
        }
        throw new ExtendedApplyException("moveToTopConfig2()", "failed to exit to top", false, true);

    }


    /**
     * enterConfig
     */
    private void enterConfig(NedWorker worker, int cmd)
        throws NedException, IOException, SSHSessionException, ApplyException {
        String line = "config " + configMethod;

        // 0 = (admin-config) | (config)
        // 1 = running configuration is inconsistent with persistent configuration
        // 2 = exec mode
        for (int retries = applyOobExclusiveRetries; retries >= 0; retries--) {
            session.print(line+"\n");
            NedExpectResult res = session.expect(enter_config_pattern, worker);
            String reply = res.getText().trim();
            if (res.getHit() == 0)
                break;
            else if (res.getHit() == 1 || retries == 0)
                throw new ExtendedApplyException(line, res.getText(), true, false);
            sleep(worker, 1000, true);
        }
        inConfig = true;
    }

    /**
     * exitConfig2
     */
    private void exitConfig2(NedWorker worker, String reason)
        throws IOException, SSHSessionException {
        NedExpectResult res;

        traceVerbose(worker, "exitConfig("+reason+")");

        // Check if session is in config mode or not
        session.print("\n");
        res = session.expect( new Pattern[] {
                Pattern.compile(config_prompt), // or admin exec mode
                Pattern.compile(prompt)
            });

        // In exec mode already
        if (res.getHit() != 0)
            return;

        // Exit config mode
        session.print("exit\n");
        while (true) {
            res = session.expect(exit_config_pattern);
            //traceVerbose(worker, "exitConfig2() : got prompt, getHit()="+res.getHit());
            switch (res.getHit()) {
            case 5:
                // exec prompt
                inConfig = false;
                return;
            case 6:
                // "You are exiting after a 'commit confirm'
                session.print("yes\n");
                session.expect(prompt);
                inConfig = false;
                return;
            case 7:
                // Uncommitted changes found, commit them before exiting
                session.print("no\n");
                session.expect(prompt);
                inConfig = false;
                return;
            case 8:
                // % Invalid input detected at
                session.print("abort\n");
                break;
            default:
                // 0-3: different config prompts
                // 4 admin exec prompt
                session.print("exit\n");
                break;
            }
        }
    }


    /**
     * sendBackspaces
     */
    private void sendBackspaces(NedWorker worker, String cmd)
        throws Exception {
        if (cmd.length() <= 1)
            return;
        String buf = "";
        for (int i = 0; i < cmd.length() - 1; i++)
            buf += "\u0008"; // back space
        traceVerbose(worker, "Sending " + (cmd.length()-1) + " backspace(s)");
        session.print(buf);
    }


    /**
     * exitPrompting
     */
    private void exitPrompting(NedWorker worker) throws IOException, SSHSessionException {
        NedExpectResult res;

        Pattern[] cmdPrompt = new Pattern[] {
            // Prompt patterns:
            Pattern.compile(prompt),
            Pattern.compile("\\A\\S*#"),
            // Question patterns:
            Pattern.compile(":\\s*$"),
            Pattern.compile("\\]\\s*$")
        };

        while (true) {
            traceVerbose(worker, "Sending CTRL-C");
            session.print("\u0003");
            traceVerbose(worker, "Waiting for non-question");
            res = session.expect(cmdPrompt, true, readTimeout, worker);
            if (res.getHit() <= 1) {
                return;
            }
        }
    }


    /**
     * getMatch
     */
    private String getMatch(String text, String regexp) {
        Pattern pattern = Pattern.compile(regexp);
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find())
            return null;
        return matcher.group(1);
    }


    /**
     * getMatches
     */
    private String[] getMatches(NedWorker worker, String text, String regexp) {
        Pattern pattern = Pattern.compile(regexp);
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find())
            return null;
        String[] matches = new String[matcher.groupCount()+1];
        matches[0] = ""+matcher.groupCount();
        traceVerbose(worker, "MATCH-COUNT"+matches[0]);
        for (int i = 1; i <= matcher.groupCount(); i++) {
            matches[i] = matcher.group(i);
            traceVerbose(worker, "MATCH-"+i+"="+matches[i]);
        }
        return matches;
    }

    /**
     * indexOf
     */
    private static int indexOf(Pattern pattern, String s, int start) {
        Matcher matcher = pattern.matcher(s);
        return matcher.find(start) ? matcher.start() : -1;
    }

    /**
     * getString
     */
    private static String getString(String buf, int offset) {
        int nl;
        nl = buf.indexOf("\n", offset);
        if (nl < 0)
            return buf;
        return buf.substring(offset, nl).trim();
    }

    /**
     * findString
     */
    private static int findString(String search, String text) {
        return indexOf(Pattern.compile(search), text, 0);
    }

    /**
     * findLine
     */
    private static String findLine(String buf, String search) {
        int i = buf.indexOf(search);
        if (i >= 0) {
            int nl = buf.indexOf("\n", i+1);
            if (nl >= 0)
                return buf.substring(i,nl);
            else
                return buf.substring(i);
        }
        return null;
    }


    /**
     * ExtendedApplyException
     */
    private class ExtendedApplyException extends ApplyException {
        public ExtendedApplyException(String line, String msg,
                                      boolean isAtTop,
                                      boolean inConfigMode) {
            super("command: "+line+": "+msg, isAtTop, inConfigMode);
         }
    }


    /**
     * injectData
     */
    private String injectData(NedWorker worker, String data, String entry[], String dir)
        throws NedException {
        Pattern pattern = Pattern.compile(entry[1]+"(?:[\r])?[\n]", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(data);
        int offset = 0;
        String groups[] = null;
        String insert;

        // before-first
        if (entry[3].equals("before-first")) {
            if (matcher.find()) {
                insert = fillInjectLine(worker, entry[2] + "\n", entry[3], fillGroups(matcher), dir);
                data = data.substring(0, matcher.start(0))
                    + insert
                    + data.substring(matcher.start(0));
            }
        }

        // before-each
        else if (entry[3].equals("before-each")) {
            while (matcher.find()) {
                insert = fillInjectLine(worker, entry[2] + "\n", entry[3], fillGroups(matcher), dir);
                data = data.substring(0, matcher.start(0) + offset)
                    + insert
                    + data.substring(matcher.start(0) + offset);
                offset = offset + insert.length();
            }
        }

        // after-last
        else if (entry[3].equals("after-last")) {
            int end = -1;
            while (matcher.find()) {
                end = matcher.end(0);
                groups = fillGroups(matcher);
            }
            if (end != -1) {
                insert = fillInjectLine(worker, entry[2] + "\n", entry[3], groups, dir);
                data = data.substring(0, end)
                    + insert + "\n"
                    + data.substring(end);
            }
        }

        // after-each
        else if (entry[3].equals("after-each")) {
            while (matcher.find()) {
                insert = fillInjectLine(worker, entry[2] + "\n", entry[3], fillGroups(matcher), dir) + "\n";
                data = data.substring(0, matcher.end(0) + offset)
                    + insert
                    + data.substring(matcher.end(0) + offset);
                offset = offset + insert.length();
            }
        }
        return data;
    }

    /**
     * reoderData
     */
    private String reorderData(NedWorker worker, String data)
        throws NedException {
        int n;

        //
        // Pass 1 - string buffer swapping
        //
        StringBuilder sb = new StringBuilder();
        String lines[] = data.split("\n");
        data = null; // to provoke crash if used below
        String toptag = "";
        String match = null;
        for (n = 0; n < lines.length; n++) {
            boolean swap = false;
            String line = lines[n];
            String trimmed = lines[n].trim();
            if (trimmed.isEmpty())
                continue;
            String nextline = (n + 1 < lines.length) ? lines[n+1].trim() : "";
            if (isTopExit(line)) {
                toptag = "";
            } else if (Character.isLetter(line.charAt(0))) {
                toptag = trimmed;
            }

            // router ospf * / max-metric router-lsa
            if (toptag.startsWith("router isis")
                && trimmed.matches("^set-overload-bit.* level [12]$")
                && nextline.matches("^no set-overload-bit.* level [12]$")) {
                swap = true;
            }

            // Add line, with optional swap before
            if (swap) {
                traceInfo(worker, "DIFFPATCH: swapped '"+trimmed+"' and '"+nextline+"'");
                line = lines[n+1];
                lines[n+1] = lines[n];
            }
            sb.append(line+"\n");
        }
        data = sb.toString();

        //
        // RT19802+30438 - Patch upon delete of referenced policy-map/class-map [CSCtk60033 XR bug]
        //
        if (autoCSCtk60033Patch && data.contains("\npolicy-map ")) {
            boolean patch = false;
            lines = data.split("\n");

            // Step 1 - inject delete of policy-map classes first
            StringBuilder middle = new StringBuilder();
            for (n = 0; n < lines.length; n++) {
                if (lines[n].startsWith("policy-map ")) {
                    middle.append(lines[n]+"\n");
                    for (n = n + 1; n < lines.length; n++) {
                        if (lines[n].startsWith(" end-policy-map")) {
                            middle.append(lines[n]+"\n");
                            break;
                        }
                        if (lines[n].startsWith(" no class ")) {
                            middle.append(lines[n]+"\n");
                            patch = true;
                        }
                        if (lines[n].startsWith(" class ")) {
                            String line = lines[n];
                            for (n = n + 1; n < lines.length; n++) {
                                if (lines[n].startsWith(" !"))
                                    break;
                                if (lines[n].startsWith("  no service-policy ")) {
                                    patch = true;
                                    middle.append(line+"\n");
                                    middle.append(lines[n]+"\n");
                                }
                            }
                        }
                    }
                }
            }

            // Step 2 - move up delete of interface first to be included in the 1st commit
            if (patch) {
                traceInfo(worker, "transformed => injected CSCtk60033 PATCH [policy-map/class-map delete requiring pre-commit]");
                middle.append(commitCommand.replace("commit ", "commit comment CSCtk60033-patch ")+"\n");
                StringBuilder first = new StringBuilder();
                for (n = 0; n < lines.length; n++) {
                    String line = lines[n];
                    if (line == null)
                        continue;
                    if (line.startsWith("no interface ") == false)
                        continue;

                    // Move up all interface sub-mode delete
                    match = getMatch(line, "no (interface .+)");
                    for (int i = 0; i < n; i++) {
                        if (lines[i].startsWith(match)) {
                            for (; i < n; i++) {
                                String temp = lines[i];
                                first.append(temp+"\n");
                                lines[i] = null;
                                if (temp.equals("exit"))
                                    break;
                            }
                        }
                    }

                    // Move up the top delete
                    traceVerbose(worker, "transformed => moved delete of '"+match+"' first");
                    first.append(line+"\n");
                }

                // Step 3 - Append remaining config via 'last'
                StringBuilder last = new StringBuilder();
                for (n = 0; n < lines.length; n++) {
                    String line = lines[n];
                    if (line == null)
                        continue;
                    last.append(line+"\n");
                }

                data = first.toString() + middle.toString() + last.toString();
            }
        }

        return "\n" + data;
    }


    /**
     * modifyLines
     */
    private String[] modifyLines(NedWorker worker, String data)
        throws NedException {
        String lines[];

        lines = data.trim().split("\n");
        if (isNetsim())
            return lines;

        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            String trimmed = lines[n].trim();
            if (trimmed.isEmpty())
                continue;

            //
            // banner <type> <marker> "<message>" <marker>
            //
            if (trimmed.startsWith("banner ")) {
                Pattern p = Pattern.compile("banner (\\S+) (\\S+) (.*) (\\S+)");
                Matcher m = p.matcher(line);
                if (m.find()) {
                    String marker = m.group(2);
                    if (marker.charAt(0) == '"')
                        marker = passwordDequote(worker, marker);
                    String message = stringDequote(worker, m.group(3));
                    message = message.replaceAll("\\r", "");  // device adds \r itself
                    traceVerbose(worker, "transformed => dequoted banner "+m.group(1));
                    lines[n] = "banner "+m.group(1)+" "+marker+message+marker;
                    // Note: textMode set to true when sending banner line(s)
                }
            }

        }

        return lines;
    }

    private String editListToStringBuilder(NedWorker worker, String path, int th, StringBuilder sb)
        throws NedException {

        traceVerbose(worker, "EDIT: path = "+path);

        NavuContainer root;
        NavuContext context = new NavuContext(mm, th);
        String lineno = "";
        try {
            try {
                ConfPath cp = new ConfPath(path);
                root = (NavuContainer)new NavuContainer(context).getNavuNode(cp);
            } catch (Exception e) {
                traceVerbose(worker, "EDIT: '"+path+"' does not exist");
                return "";
            }
            if (root == null || !root.exists()) {
                traceVerbose(worker, "EDIT: '"+path+"' not found");
                return "";
            }

            // Get list
            NavuList list = root.list("cisco-ios-xr", "line");
            if (list == null || list.isEmpty()) {
                traceInfo(worker, "EDIT: '"+path+"' is empty");
                return "";
            }

            // Get lines
            traceVerbose(worker, "EDIT: '"+path+"' = "+list.size()+" line(s)");
            for (NavuContainer line : list.elements()) {
                lineno += (" " + line.leaf("cisco-ios-xr", "id").valueAsString());
                sb.append(line.leaf("cisco-ios-xr", "value").valueAsString()+"\n");
            }
        } catch (Exception e) {
            throw new NedException("EDIT: editListToStringBuilder: "+e.getMessage());
        }
        return lineno;
    }

    /**
     * editData
     */
    private String editData(NedWorker worker, String data, String function, int toTh)
        throws NedException {

        String lines[] = data.split("\n");
        int n = 0;
        try {
            StringBuilder sb = new StringBuilder();
            for (; n < lines.length; n++) {
                String line = lines[n];
                String trimmed = line.trim();
                String cmd = trimmed.startsWith("no ") ? trimmed.substring(3) : trimmed;

                // route-policy *
                if (line.startsWith("route-policy ")) {
                    sb.append(line+"\n");
                    // Read to-transaction and create line number oper cache
                    String name = getMatch(cmd, "route-policy\\s+(.+)");
                    String path = CONF_PATH+"route-policy-edit/route-policy{"+name+"}";
                    //String metas[] = lines[n-1].split(" :: "); String path = metas[1];
                    String lineno = editListToStringBuilder(worker, path, toTh, sb);
                    if (lineno.isEmpty() == false) {
                        name = line.replace("\"", "");
                        ConfPath cp = new ConfPath(OPER_PATH+"/edit-list{\""+name+"\"}");
                        if (cdbOper.exists(cp) == false)
                            cdbOper.create(cp);
                        cdbOper.setElem(new ConfBuf(lineno.trim()), cp.append("/lineno"));
                    }
                    // Trim line changes from NSO
                    for (; n < lines.length; n++) {
                        if (lines[n].trim().equals("end-policy")) {
                            sb.append(lines[n].trim()+"\n");
                            break;
                        }
                    }
                    continue;
                }

                // no route-policy *
                else if (line.startsWith("no route-policy ")) {
                    // Delete line number oper cache
                    String name = cmd.replace("\"", "");
                    ConfPath cp = new ConfPath(OPER_PATH+"/edit-list{\""+name+"\"}");
                    if (cdbOper.exists(cp))
                        cdbOper.delete(cp);
                }

                // Append line
                sb.append(line+"\n");
            }
            data = "\n" + sb.toString();
        } catch (Exception e) {
            throw new NedException("editData '"+lines[n]+"' ERROR: ", e);
        }
        return data;
    }

    private int setListToStringBuilder(NedWorker worker, String path, int th, StringBuilder sb)
        throws NedException {

        traceVerbose(worker, "SET: path = "+path);

        try {
            NavuContainer root;
            NavuContext context = new NavuContext(mm, th);
            try {
                ConfPath cp = new ConfPath(path);
                root = (NavuContainer)new NavuContainer(context).getNavuNode(cp);
            } catch (Exception e) {
                traceVerbose(worker, "SET: '"+path+"' does not exist");
                return 0;
            }
            if (root == null || !root.exists()) {
                traceVerbose(worker, "SET: '"+path+"' not found");
                return 0;
            }

            // Get list
            NavuList list = root.list("cisco-ios-xr", "set");
            if (list == null || list.isEmpty()) {
                traceInfo(worker, "SET: '"+path+"' is empty");
                return 0;
            }

            // Get lines
            traceVerbose(worker, "SET: '"+path+"' = "+list.size()+" line(s)");
            int s = 0;
            for (NavuContainer line : list.elements()) {
                String value = line.leaf("cisco-ios-xr", "value").valueAsString();
                if (value.indexOf("ios-regex \"") >= 0) {
                    value = value.replaceAll("ios-regex \\\"(.*)\\\"", "ios-regex $1");
                }
                if (s + 1 < list.size())
                    sb.append(" "+value+",\n");
                else
                    sb.append(" "+value+"\n");
                s++;
            }
            return s;
        } catch (Exception e) {
            throw new NedException("SET: setListToStringBuilder: "+e.getMessage());
        }
    }

    private String setGetPath(String line) {
        String [] sets = {
            "rd-set ",
            "prefix-set ",
            "as-path-set ",
            "community-set ",
            "extcommunity-set rt ",
            "extcommunity-set soo ",
            "extcommunity-set opaque "
        };
        for (int i = 0; i < sets.length; i++)
            if (line.startsWith(sets[i]))
                return sets[i].trim();
        return null;
    }

    /**
     * modifySets
     */
    private String modifySets(NedWorker worker, String data, String function, int toTh)
        throws NedException {

        int n = 0;
        String lines[] = data.split("\n");
        try {
            StringBuilder sb = new StringBuilder();
            for (; n < lines.length; n++) {
                String line = lines[n];
                sb.append(line+"\n");

                // Get set config path and list name
                String cpath = setGetPath(line);
                if (cpath == null)
                    continue;
                String name = getMatch(line, ".* (\\S+)");
                String path = CONF_PATH+cpath.replace(" ", "/")+"{"+name+"}";

                // Read to-transaction to (re-)send all entries, add comma and trim ios-regex
                int num = setListToStringBuilder(worker, path, toTh, sb);
                if (num > 0)
                    traceInfo(worker, "transformed => restored "+num+" line(s) in "+line.trim());

                // Trim line changes from NSO
                for (; n < lines.length; n++) {
                    if (lines[n].trim().equals("end-set")) {
                        sb.append(lines[n].trim()+"\n");
                        break;
                    }
                }
            }
            data = "\n" + sb.toString();
        } catch (Exception e) {
            throw new NedException("modifySets '"+lines[n]+"' ERROR: ", e);
        }
        return data;
    }


    /**
     * modifyData
     */
    private String modifyData(NedWorker worker, String data, String function)
        throws Exception {
        int i, n;
        String lines[];

        //
        // Modifications which require use of CDB transaction lookups
        //
        int fromTh = -1;
        int toTh = -1;
        try {
            // Attach to CDB
            try {
                fromTh = worker.getFromTransactionId();
                toTh = worker.getToTransactionId();
                int usid = worker.getUsid();
                mm.attach(fromTh, 0, usid);
                traceVerbose(worker, function+" attached to from-transaction "+fromTh+" usid "+usid);
                mm.attach(toTh, 0, usid);
                traceVerbose(worker, function+" attached to to-transaction "+toTh+" usid "+usid);
            } catch (Exception e) {
                throw new NedException("failed to attach to CDB", e);
            }

            //
            // Edit data
            //
            if (apiEditRoutePolicy) {
                data = editData(worker, data, function, toTh);
            }

            //
            // Scan meta-data and modify data
            //
            traceInfo(worker, function + " out-transforming - meta-data");
            data = metaData.modifyData(worker, data, mm, fromTh, toTh);

            //
            // modifySets - modify sets
            //
            if (isDevice()) {
                traceInfo(worker, function + " out-transforming - restoring sets");
                data = modifySets(worker, data, function, toTh);
            }
        } finally {
            try {
                // Detach from CDB
                if (fromTh != -1) {
                    mm.detach(fromTh);
                    traceVerbose(worker, function+" detached from from-transaction "+fromTh);
                }
                if (toTh != -1) {
                    mm.detach(toTh);
                    traceVerbose(worker, function+" detached from to-transaction "+toTh);
                }
            } catch (Exception e) {
                throw new NedException("failed to detach from CDB", e);
            }
        }

        //
        // Reorder data
        //
        traceInfo(worker, function + " out-transforming - reordering config");
        data = reorderData(worker, data);

        //
        // LINE-BY-LINE - applyConfig
        //
        traceInfo(worker, function + " out-transforming - lines");
        StringBuilder sb = new StringBuilder();
        String toptag = "";
        String[] group;
        String meta = "";
        lines = data.split("\n");
        data = null; // to avoid modifying data by accident
        for (n = 0; n < lines.length; n++) {
            String output = null;
            String line = lines[n];
            String trimmed = lines[n].trim();
            String cmd = trimmed.startsWith("no ") ? trimmed.substring(3) : trimmed;
            boolean traceChange = true;
            if (trimmed.isEmpty())
                continue;
            if (trimmed.startsWith("! meta-data :: ")) {
                meta = meta + lines[n] + "\n";
                sb.append(lines[n]+"\n");
                continue;
            }

            // Update toptag
            if (Character.isLetter(lines[n].charAt(0))) {
                toptag = trimmed;
            }

            //
            // policy-map * / class * / police rate
            // NSO bug, track #15958
            //
            if (toptag.startsWith("policy-map ") && trimmed.equals("police rate")) {
                output = "  no police";
            }

            //
            // cached-show
            //
            if (cmd.startsWith("cached-show ")) {
                output = "!" + line;
            }

            //
            // NETSIM
            //
            if (isNetsim()) {

                // description patch for netsim, quote text and escape "
                if (trimmed.startsWith("description ") && (i = line.indexOf("description ")) >= 0) {
                    String desc = line.substring(i+12).trim(); // Strip initial white spaces, added by NCS
                    if (desc.charAt(0) != '"') {
                        desc = desc.replaceAll("\\\"", "\\\\\\\""); // Convert " to \"
                        output = line.substring(0,i+12) + "\"" + desc + "\""; // Quote string, add ""
                    }
                }

                // no alias Java 'tailf:cli-no-value-on-delete' due to NCS bug
                else if (line.startsWith("no alias ") &&
                         (group = getMatches(worker, line, "(no alias(?: exec| config)? \\S+ ).*")) != null) {
                    output = group[1];
                }

                // alias patch
                else if (line.startsWith("alias ") &&
                         (group =  getMatches(worker, line, "(alias(?: exec| config)? \\S+ )(.*)")) != null) {
                    String alias = group[2].replaceAll("\\\"", "\\\\\\\""); // Convert " to \"
                    output = group[1] + "\"" + alias + "\""; // Quote string, add ""
                }

                // Fall through for adding line (transformed or not)
            }


            //
            // DEVICE
            //
            else {
                String match;

                //
                // route-policy
                //
                if (cmd.startsWith("route-policy ")) {
                    lines[n] = lines[n].replace("\"", ""); // Strip quotes around name
                }

                //
                // interface * / ipv4 address x.y.z.w /prefix
                //
                if (line.matches("^\\s*(no )?ipv4 address \\S+ /(\\d+).*$")) {
                    output = line.replaceFirst(" (\\S+) /(\\d+)", " $1/$2");
                }

                //
                // class-map * / match vlan [inner] *
                // class-map * / match traffic-class *
                //
                else if (toptag.startsWith("class-map ")
                         && (trimmed.startsWith("match vlan ") || trimmed.startsWith("match traffic-class "))) {
                    output = line.replace(",", " ");
                }

                //
                // snmp-server location|contact *
                //
                else if (trimmed.startsWith("snmp-server ")
                         && (group = getMatches(worker, line, "(snmp-server (?:location|contact) )\\\"(.*)\\\"")) != null) {
                    output = group[1] + passwordDequote(worker, group[2]);
                }

                //
                // aaa attribute format * / format-string
                //
                else if (toptag.startsWith("aaa attribute format ")
                         && trimmed.startsWith("format-string ")
                         && (match = getMatch(trimmed, "format-string(?: length \\d+)? (\\S+)")) != null) {
                    output = line.replace(match, "\""+match+"\"");
                }

                //
                // route-policy and group
                //
                else if ((lines[n].startsWith("route-policy ") && apiEditRoutePolicy == false)
                         || lines[n].startsWith("group ")) {
                    // Dequote and split single quoted string, example:
                    // route-policy <NAME>
                    //   "if (xxx) then \r\n statement(s) \r\n endif\r\n"
                    //  end-policy
                    traceVerbose(worker, "transformed => dequoted "+trimmed);
                    traceChange = false;
                    sb.append(lines[n++]+"\n");
                    if (lines[n].trim().startsWith("\"")) {
                        String value = stringDequote(worker, lines[n++].trim());
                        String values[] = value.split("\n");
                        for (int v = 0; v < values.length; v++)
                            sb.append(values[v].replace("\r", "")+"\n");
                    }
                    // note: end-policy will be added below
                }

                // Fall through for adding line (transformed or not)
            }

            //
            // Transform lines[n] -> XXX
            //
            if (output != null && !output.equals(lines[n])) {
                if (traceChange) {
                    if (output.isEmpty())
                        traceVerbose(worker, "transformed => stripped '"+trimmed+"'");
                    else
                        traceVerbose(worker, "transformed => '"+trimmed+"' to '"+output.trim()+"'");
                }
                lines[n] = output;
            }

            // Append to sb
            if (lines[n] != null && !lines[n].isEmpty()) {
                sb.append(lines[n]+"\n");
            }
            meta = "";
        }
        data = "\n" + sb.toString();

        //
        // Inject command(s)
        //
        traceInfo(worker, function + " out-transforming - injecting commands");
        for (n = 0; n < injectCommand.size(); n++) {
            String entry[]  = injectCommand.get(n);
            data = injectData(worker, data, entry, "=>");
        }

        traceVerbose(worker, function+"_AFTER:\n"+data);

        return data;
    }


    private void sftpUploadConfig(NedWorker worker, int cmd, String data)
        throws NedException, IOException, SSHSessionException, ApplyException {
        int i = 0;

        if (!remoteConnection.equals("none"))
            throw new NedException("SFTP apply ERROR: Using sftp to apply config is not supported via proxy");

        if (applyDeviceFile == null || applyDeviceFile.isEmpty())
            throw new NedException("SFTP apply ERROR: no file name configured");

        // Delete previous commit file (ignore errors)
        print_line_exec(worker, "do delete /noprompt "+applyDeviceFile);

        // Modify data
        data = stripLineAll(worker, data, "! meta-data", "=>", true);
        data = stripLineAll(worker, data, "!", "=>", false);
        //data = "!! IOS XR Configuration\n" + data + "end\n";

        traceVerbose(worker, "SFTP_APPLY=\n"+stringQuote(data));

        // Transfer file
        SFTPv3Client sftp = null;
        Connection sftpConn = null;
        long offset = 0;
        try {
            int max = this.sftpMaxSize;
            sftpConn = sftpConnect(worker);
            sftp = new SFTPv3Client(sftpConn);
            byte[] buffer = data.getBytes("UTF-8");
            SFTPv3FileHandle h = sftp.createFile(applyDeviceFile);
            traceInfo(worker, "SFTP transfering file: "+applyDeviceFile+" ("+data.length()+" bytes)");
            do {
                int writeSize = buffer.length - i < max ? Math.min(max, buffer.length - i) : max;
                sftp.write(h, offset, buffer, i, writeSize);
                i += writeSize;
                offset = i;
            } while (offset < buffer.length);
            sftp.closeFile(h);
        }
        catch (Exception e) {
            throw new NedException("SFTP apply ERROR : " + e.getMessage());
        }
        finally {
            if (sftp != null)
                sftp.close();
            if (sftpConn != null)
                sftpConn.close();
        }
        traceVerbose(worker, "SFTP transfer finished ("+offset+" bytes)");

        // Load config to candidate
        traceVerbose(worker, "Loading config to candidate");
        String res = print_line_exec(worker, "load "+applyDeviceFile);

        // Check for errors
        if (res.contains("Couldn't open file"))
            throw new ExtendedApplyException("SFTP load:", res, true, true);
        if (res.contains("Syntax/Authorization errors in one or more commands")) {
            res = print_line_exec(worker, "show configuration failed load");
            throw new ExtendedApplyException("SFTP apply:", res, true, true);
        }
    }

    static private String nedCmdFullName(int cmd) {
        if (cmd == NedCmd.ABORT_CLI)
            return "ABORT";
        if (cmd == NedCmd.REVERT_CLI)
            return "REVERT";
        return "APPLY-CONFIG";
    }

    //
    // sendConfig
    //
    private void sendConfig(NedWorker worker, int cmd, String data, boolean useSftp)
        throws Exception {
        String line;
        boolean isAtTop = true;
        long lastTime = System.currentTimeMillis();

        // Modify data and split to lines
        data = modifyData(worker, data, "APPLY-CONFIG");
        String[] lines = modifyLines(worker, data);

        // Enter config mode
        enterConfig(worker, cmd);

        // Check if should disable SFTP
        if (useSftp) {
            // Internal commit, can't use SFTP to apply config
            if (data.contains("\n"+commitCommand+"\n")) {
                traceInfo(worker, "Disabling SFTP transfer due to embedded commit command");
                useSftp = false;
            }
            // XR bug
            if (data.contains("no tacacs-server host ")) {
                traceInfo(worker, "Disabling SFTP transfer due to XR bug with 'tacacs-server host' config");
                useSftp = false;
            }
        }

        // Check if SFTP should be used
        long startSending = tick(0);
        if (useSftp && lines.length >= applySftpThreshold) {

            // Upload file with SFTP and load to candidate
            logInfo(worker, "BEGIN sending (SFTP) "+lines.length+" line(s)");
            sftpUploadConfig(worker, cmd, linesToString(lines));
            logInfo(worker, "DONE sending (SFTP) "+lines.length+" line(s) "+tickToString(startSending));
        }

        // Send config to device and check reply
        else {
            logInfo(worker, "BEGIN sending "+lines.length+" line(s)");
            try {
                // Send chunk of chunkSize
                for (int n = 0; n < lines.length;) {

                    // Copy in up to chunkSize config commands in chunk
                    StringBuilder sb = new StringBuilder();
                    int num = 0;
                    int start = n;
                    for (; n < lines.length && num < chunkSize; n++) {
                        line = lines[n];
                        if (line == null || line.isEmpty())
                            continue;
                        String trimmed = line.trim();
                        if (trimmed.startsWith("! meta-data :: "))
                            continue;
                        if (trimmed.equals("!"))
                            continue;

                        // XR åäö character twerk [RT30152]
                        if (trimmed.startsWith("description "))
                            line = trimmed;

                        // Append line
                        sb.append(line + "\n");
                        num++;
                    }
                    if (num == 0)
                        break;

                    // Send chunk of 'num' line(s) to the device
                    String chunk = sb.toString();
                    traceVerbose(worker, "SENDING line(s) "+(start+1)+"-"+(start+num)+"/"+lines.length);
                    traceVerbose2(worker, "CHUNK = "+stringQuote(chunk));
                    session.print(chunk);

                    // Check device reply, one line at the time
                    for (int i = start; i < n; i++) {
                        line = lines[i];
                        if (line == null || line.isEmpty())
                            continue;
                        String trimmed = line.trim();
                        if (trimmed.startsWith("! meta-data :: "))
                            continue;
                        if (trimmed.equals("!"))
                            continue;

                        // Wait for echo for all commands except:
                        textMode = false;
                        if (line.startsWith("banner " )) {
                            String textlines[] = line.split("\n");
                            traceInfo(worker, "Checking '"+textlines[0]+"' -> enabling text mode");
                            textMode = true;  // works because banner line(s) in one lines[]
                        }

                        // Reset timeout
                        long time = System.currentTimeMillis();
                        if ((time - lastTime) > (0.8 * writeTimeout)) {
                            lastTime = time;
                            worker.setTimeout(writeTimeout);
                        }

                        // Check device echo and possible input error (note: not commit)
                        isAtTop = noprint_line_wait(worker, cmd, line, 0);

                        // Successful CSCtk60033 patch commit, increase rollback counter
                        if (line.startsWith("commit comment CSCtk60033-patch"))
                            numCommit++;
                    }
                }
            }
            catch (ApplyException e) {
                try {
                    // Check if server side is open
                    if (session.serverSideClosed()) {
                        traceInfo(worker, "Detected: Server side closed");
                        inConfig = false;
                        throw e;
                    }

                    // Flush I/O buffer by sending cookie and waiting for echo
                    Pattern[] errorPrompt = new Pattern[] {
                        Pattern.compile("ned-trick-to-sync-io-not-an-error"),
                        Pattern.compile("\\A\\S*\\(config.*\\)#"),
                        Pattern.compile("\\A\\S*\\(cfg.*\\)#"),
                        Pattern.compile("\\A[^\\# ]+#[ ]?")
                    };

                    // Send cookie text
                    traceVerbose(worker, "Sending 'ned-trick-to-sync-io-not-an-error'");
                    session.print("ned-trick-to-sync-io-not-an-error\n");

                    // Parse all device replies until seen cookie or in exec mode
                    for (;;) {
                        // Check if server side is open
                        if (session.serverSideClosed()) {
                            traceInfo(worker, "Detected: Server side closed");
                            inConfig = false;
                            throw e;
                        }
                        traceVerbose(worker, "Waiting for 'ned-trick-to-sync-io-not-an-error echo'");
                        NedExpectResult res = session.expect(errorPrompt, worker);
                        if (res.getHit() == 0) {
                            traceInfo(worker, "Got ned-trick-to-sync-io-not-an-error");
                            traceVerbose(worker, "Waiting for prompt");
                            res = session.expect(exit_config_pattern);
                            traceVerbose(worker, "Got prompt, getHit()="+res.getHit());
                            break;
                        } else if (res.getHit() <= 2) {
                            traceVerbose(worker, "Got config prompt: '"+res.getText()+"'");
                        }
                        else {
                            traceInfo(worker, "Got exec prompt");
                            inConfig = false;
                            throw e;
                        }
                    }
                } catch (Exception e2) {
                    throw e;
                }
                // Exit config mode
                if (e.inConfigMode) {
                    exitConfig2(worker, "invalid configuration");
                }
                logInfo(worker, "DONE "+nedCmdFullName(cmd)+" - ERROR sending: "+stringQuote(e.getMessage()));
                throw e;
            }

            // Make sure we have exited from all submodes
            if (!isAtTop) {
                moveToTopConfig(worker);
            }
            logInfo(worker, "DONE sending "+lines.length+" line(s) "+tickToString(startSending));
        }

        if (failphase.equals("prepare")) {
            failphase = "";
            throw new NedException("PREPARE :: debug exception in prepare");
        }

        //
        // All commands accepted by device, prepare caching of secrets
        //
        secrets.prepare(worker, lines);
    }

    private String commitAdminConfig(NedWorker worker, int cmd, String data)
        throws Exception {

        data = "\n" + data;
        int start = data.indexOf("\nadmin\n");
        int end = data.indexOf("\n exit-admin-config");
        if (start >= 0 && end > start && isDevice()) {
            // Copy out admin data, strip from data
            String adminData = data.substring(start + 7, end + 1);
            data = data.substring(end + 19);
            traceInfo(worker, "Applying "+adminData.length()+" bytes of admin config");

            // Enter exec admin mode in order to enter admin config mode
            traceInfo(worker, "Entering admin exec mode");
            String res = print_line_exec(worker, "admin");
            if (res.indexOf("Invalid input detected") >= 0)
                throw new ExtendedApplyException("admin", "Failed to enter exec admin mode", false, false);
            if (res.indexOf("This command is not authorized") >= 0)
                throw new ExtendedApplyException("admin", "admin config is not authorized with this user", false, false);

            // Send and commit the admin config
            try {
                sendConfig(worker, cmd, adminData, false);
                print_line_wait_oper(worker, NedCmd.COMMIT, "commit");
                numAdminCommit++;
            }
            finally  {
                exitConfig2(worker, "admin");
            }
        }
        return data;
    }


    /**
     * applyConfig
     */
    @Override
    public void applyConfig(NedWorker worker, int cmd, String data)
        throws NedException, IOException, SSHSessionException, ApplyException {
        long start = tick(0);

        if (trace)
            session.setTracer(worker);
        logInfo(worker, "BEGIN APPLY-CONFIG");

        // Clear global config data
        lastGetConfig = null;
        numCommit = 0;
        numAdminCommit = 0;

        try {
            // Send and strip admin config
            data = commitAdminConfig(worker, cmd, data);

            // Send standard config
            if (data.trim().isEmpty() == false) {
                sendConfig(worker, cmd, data, true);
            }

        } catch (NedException e) {
            throw e;
        } catch (IOException e) {
            throw e;
        } catch (SSHSessionException e) {
            throw e;
        } catch (ApplyException e) {
            throw e;
        } catch (Exception e) {
            throw new NedException("applyConfig ERROR", e);
        } finally {
            logInfo(worker, "DONE APPLY-CONFIG "+tickToString(start));
        }
    }


    /**
     * commit
     */
    public void commit(NedWorker worker, int timeout)
        throws Exception {
        long start = tick(0);

        if (trace)
            session.setTracer(worker);
        logInfo(worker, "BEGIN COMMIT ("+commitMethod+") [num-commit "
                +numCommit+" "+numAdminCommit+"a]");

        if (!inConfig)
            throw new Exception("COMMIT :: internal ERROR: called in non-config mode");

        //
        // Commit
        //
        try {

            // Optional trial commit
            if (commitMethod.equals("confirmed")) {
                if (readTimeout < 30000)
                    worker.setTimeout(30000);
                String cmd = commitCommand + " confirmed 30";
                if (cmd.contains(" show-error")) {
                    // show-error must be last in line
                    cmd = cmd.replace(" show-error", "") + " show-error";
                }
                print_line_wait_oper(worker, NedCmd.COMMIT, cmd, minReadTimeout);
            }

            if (failphase.equals("before commit")) {
                failphase = "";
                throw new NedException("COMMIT :: debug exception at before commit");
            }

            // (confirm) commit
            print_line_wait_oper(worker, NedCmd.COMMIT, commitCommand);
            numCommit++;

            if (failphase.equals("after commit")) {
                failphase = "";
                throw new NedException("COMMIT :: debug exception at after commit");
            }

            // exit config mode
            exitConfig2(worker, "commit");

        } catch (Exception e) {
            logInfo(worker, "DONE COMMIT - ERROR: "+stringQuote(e.getMessage()));
            throw e;
        }

        //
        // Cache secrets
        //
        if (secrets.getNewEntries()) {
            traceInfo(worker, "SECRETS - caching encrypted secrets");
            lastGetConfig = getConfig(worker, false);
        }

        logInfo(worker, "DONE COMMIT "+tickToString(start));
        worker.commitResponse();
    }


    /**
     * persist
     */
    public void persist(NedWorker worker)
        throws Exception {
        // No-op, XR saves config in commit
        worker.persistResponse();
    }

    /**
     * prepareDry
     */
    public void prepareDry(NedWorker worker, String data)
        throws Exception {
        long start = tick(0);

        if (trace && session != null)
            session.setTracer(worker);

        String log = "BEGIN PREPARE-DRY (model="+iosmodel+" version="+iosversion+")";
        if (session == null)
            log += " [offline]";

        // ShowRaw used in debugging, to see cli commands before modification
        if (showRaw || data.contains("tailfned raw-run\n")) {
            logInfo(worker, log + " (raw)");
            showRaw = false;
            logInfo(worker, "DONE PREPARE-DRY (raw)"+tickToString(start));
            worker.prepareDryResponse(data);
            return;
        }

        logInfo(worker, log);

        // Modify data buffer
        data = modifyData(worker, data, "PREPARE-DRY");
        String[] lines = modifyLines(worker, data);

        StringBuilder sb = new StringBuilder();
        if (session == null && logVerbose)
            sb.append("! Generated offline\n");

        // Trim meta-data if not logVerbose
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            if (line == null)
                continue;
            if (!logVerbose && line.trim().startsWith("! meta-data :: "))
                continue;
            sb.append(line+"\n");
        }

        logInfo(worker, "DONE PREPARE-DRY"+tickToString(start));
        worker.prepareDryResponse(sb.toString());
    }

    private void doRollback(NedWorker worker, int cmd, String data)
        throws Exception {
        long start = tick(0);
        String name = nedCmdFullName(cmd);

        logInfo(worker, "BEGIN "+name+"("+commitMethod+") [in-config="
                +inConfig+"] [num-commit "+numCommit+" "+numAdminCommit+"a]");

        // If still in config mode, abort to drop the current commit
        if (inConfig) {
            if (readTimeout < 30000)
                worker.setTimeout(30000);
            print_line_wait_oper(worker, cmd, "abort", minReadTimeout);
            inConfig = false;
        }

        // Rollback admin config commited in prepare phase
        if (numAdminCommit > 0) {
            worker.setTimeout(readTimeout);
            try {
                print_line_wait_oper(worker, cmd, "admin rollback configuration last "+numAdminCommit);
            } catch (Exception e) {
                 // Not much we can do, all bets are off!
                LOGGER.error(name+" admin rollback failed ",  e);
                throw e;
            }
        }

        // If we have commited in this session, rollback
        if (numCommit > 0) {
            worker.setTimeout(readTimeout);
            try {
                print_line_wait_oper(worker, cmd, "rollback configuration last "+numCommit);
            } catch (Exception e) {
                 // Not much we can do, all bets are off!
                LOGGER.error(name+" rollback failed ",  e);
                throw e;
            }
        }

        logInfo(worker, "DONE "+name+" (rollbacked "+(numCommit+numAdminCommit)+" commit(s)) "+tickToString(start));
        numCommit = 0;
        numAdminCommit = 0;
    }

    /**
     * abort
     */
    public void abort(NedWorker worker, String data)
        throws Exception {

        if (trace)
            session.setTracer(worker);

        doRollback(worker, NedCmd.ABORT_CLI, data);

        worker.abortResponse();
    }


    /**
     * revert
     */
    public void revert(NedWorker worker, String data)
        throws Exception {

        if (trace)
            session.setTracer(worker);

        doRollback(worker, NedCmd.REVERT_CLI, data);

        worker.revertResponse();
    }


    private void cleanup() {
        try {
            if (cdbOper != null) {
                cdbOper.endSession();
                cdbOper = null;
            }
            ResourceManager.unregisterResources(this);
        } catch (Exception ignore) {
        }
    }

    /**
     * close
     */
    public void close(NedWorker worker)
        throws NedException, IOException {
        logInfo(worker, "BEGIN-DONE CLOSE(worker)");
        cleanup();
        super.close(worker);
    }

    public void close() {
        LOGGER.info("BEGIN CLOSE("+device_id+")");
        cleanup();
        LOGGER.info("DONE CLOSE("+device_id+")");
        super.close();
    }


    /**
     * trace
     */
    public void trace(NedWorker worker, String msg, String direction) {
        if (trace) {
            worker.trace("-- "+msg+" --\n", direction, device_id);
        }
    }

    /**
     * traceInfo
     */
    private void traceInfo(NedWorker worker, String info) {
        if (trace)
            worker.trace("-- " + info + "\n", "out", device_id);
    }

    /**
     * traceVerbose
     */
    private void traceVerbose(NedWorker worker, String info) {
        if (logVerbose && trace) {
            worker.trace("-- " + info + "\n", "out", device_id);
        }
    }

    /**
     * traceVerbose2
     */
    private void traceVerbose2(NedWorker worker, String info) {
        //traceVerbose(worker, info);
    }


    private String passwordQuote(String aText) {
        StringBuilder result = new StringBuilder();
        StringCharacterIterator iterator =
            new StringCharacterIterator(aText);
        char character =  iterator.current();
        while (character != CharacterIterator.DONE ){
            if (character == '"')
                result.append("\\\"");
            else if (character == '\\')
                result.append("\\\\");
            else {
                // The char is not a special one, add it to the result as is
                result.append(character);
            }
            character = iterator.next();
        }
        return result.toString();
    }

    private String passwordDequote(NedWorker worker, String aText) {
        if (aText.indexOf("\"") != 0) {
            traceVerbose(worker, "passwordDequote(ignored) : " + aText);
            return aText;
        }

        traceVerbose(worker, "passwordDequote(parse) : " + aText);

        aText = aText.substring(1,aText.length()-1); // strip ""

        StringBuilder result = new StringBuilder();
        StringCharacterIterator iterator =
            new StringCharacterIterator(aText);
        char c1 = iterator.current();
        while (c1 != CharacterIterator.DONE) {
            if (c1 == '\\') {
                char c2 = iterator.next();
                if (c2 == CharacterIterator.DONE )
                    result.append(c1);
                else if (c2 == '\\')
                    result.append('\\');
                else if (c2 == '\"')
                    result.append('\"');
                else {
                    result.append(c1);
                    result.append(c2);
                }
            }
            else {
                result.append(c1);
            }
            c1 = iterator.next();
        }
        traceVerbose(worker, "passwordDequote(parsed) : " + result.toString());
        //String data = result.toString();
        //for (int i = 0; i < data.length(); i++)
        //traceVerbose(worker, "PD-"+i+"= "+ data.charAt(i));

        return result.toString();
    }

    /**
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
                result.append("'\f");
            else if (character == '\t')
                result.append("\\t");
            else if (character == (char) 27) // \e
                result.append("\\e");
            else
                // The char is not a special one, add it to the result as is
                result.append(character);
            character = iterator.next();
        }
        result.append("\"");
        return result.toString();
    }


    /**
     * stringDequote
     */
    private String stringDequote(NedWorker worker, String aText) {
        if (aText.indexOf("\"") != 0) {
            traceVerbose(worker, "stringDequote(ignored) : " + aText);
            return aText;
        }

        //traceVerbose(worker, "stringDequote(parse) : " + aText);

        aText = aText.substring(1,aText.length()-1);

        StringBuilder result = new StringBuilder();
        StringCharacterIterator iterator =
            new StringCharacterIterator(aText);
        char c1 = iterator.current();
        while (c1 != CharacterIterator.DONE ) {
            if (c1 == '\\') {
                char c2 = iterator.next();
                if (c2 == CharacterIterator.DONE)
                    result.append(c1);
                else if (c2 == 'b')
                    result.append('\b');
                else if (c2 == 'n')
                    result.append('\n');
                else if (c2 == 'r')
                    result.append('\r');
                else if (c2 == 'v')
                    result.append((char) 11); // \v
                else if (c2 == 'f')
                    result.append('\f');
                else if (c2 == 't')
                    result.append('\t');
                else if (c2 == 'e')
                    result.append((char) 27); // \e
                else
                    result.append(c2);
            }
            else {
                // The char is not a special one, add it to the result as is
                result.append(c1);
            }
            c1 = iterator.next();
        }
        //traceVerbose(worker, "stringDequote(parsed) : " + result.toString());
        return result.toString();
    }


    /**
     * isTopExit
     */
    private boolean isTopExit(String line) {
        if (line.startsWith("exit"))
            return true;
        if (line.startsWith("!") && line.trim().equals("!"))
            return true;
        return false;
    }


    /**
     * fillGroups
     */
    private String[] fillGroups(Matcher matcher) {
        String[] groups = new String[matcher.groupCount()+1];
        for (int i = 0; i < matcher.groupCount()+1; i++) {
            groups[i] = matcher.group(i);
        }
        return groups;
    }

    /**
     * fillInjectLine
     */
    private String fillInjectLine(NedWorker worker, String insert, String where, String[] groups, String dir) {
        int i, offset = 0;

        // Replace $i with group value from match.
        // Note: hard coded to only support up to $9
        for (i = insert.indexOf("$"); i >= 0; i = insert.indexOf("$", i+offset)) {
            int num = (int)(insert.charAt(i+1) - '0');
            insert = insert.substring(0,i) + groups[num] + insert.substring(i+2);
            offset = offset + groups[num].length() - 2;
        }

        traceInfo(worker, "transformed "+dir+" injected "+stringQuote(insert)+" "+where+" "+stringQuote(groups[0]));

        return insert;
    }


    /**
     * linesToString
     */
    private String linesToString(String lines[]) {
        StringBuilder string = new StringBuilder();
        for (int n = 0; n < lines.length; n++) {
            if (lines[n].isEmpty())
                continue;
            string.append(lines[n]+"\n");
        }
        return string.toString();
    }


    /**
     * stripLineAll
     */
    private String stripLineAll(NedWorker worker, String res, String search, String dir, boolean trim) {
        StringBuilder buffer = new StringBuilder();
        String lines[] = res.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (trim)
                line = line.trim();
            if (line.startsWith(search)) {
                traceVerbose(worker, "transformed "+dir+" stripped '"+line.trim()+"'");
                continue;

            }
            buffer.append(lines[i]+"\n");
        }
        return buffer.toString();
    }


    /**
     * trimConfig
     */
    private String trimConfig(NedWorker worker, String res) {
        int i, nl;

        // Strip everything before:
        i = res.indexOf("Building configuration...");
        if (i >= 0) {
            nl = res.indexOf("\n", i);
            if (nl > 0)
                res = res.substring(nl+1);
        }
        i = res.indexOf("!! Last configuration change");
        if (i >= 0) {
            nl = res.indexOf("\n", i);
            if (nl > 0)
                res = res.substring(nl+1);
        }
        i = res.indexOf("No entries found.");
        if (i >= 0) {
            nl = res.indexOf("\n", i);
            if (nl > 0)
                res = res.substring(nl+1);
        }

        // Strip everything after 'end'
        i = res.lastIndexOf("\nend");
        if (i >= 0) {
            res = res.substring(0,i);
        }

        // Trim comments
        res = stripLineAll(worker, res, "!! ", "<=", false);
        res = stripLineAll(worker, res, "! ", "<=", false);

        return res + "\n";
    }


    /**
     * getAdminConfig
     */
    private String getAdminConfig(NedWorker worker)
        throws Exception {

        if (inConfig)
            throw new Exception("getAdminConfig() :: internal ERROR: called in config mode");

        // Show admin config
        String cmd = "admin show running-config";
        session.print(cmd + "\n");
        session.expect(new String[] { Pattern.quote(cmd) }, worker);
        String res = session.expect(prompt, worker);
        if (res.contains("Invalid input detected at")) {
            traceInfo(worker, "getAdminConfig() :: internal ERROR calling 'admin show running-config'");
            return "";
        }
        if (res.indexOf("Building configuration") < 0)
            return "";

        // Trim beginning and end, may result in empty buf
        res = trimConfig(worker, res);
        if (res.trim().isEmpty())
            return "";

        // Wrap in admin mode context
        res = "\nadmin\n" + res + "exit-admin-config\n\n";
        traceVerbose(worker, "SHOW_ADMIN:\n'"+res+"'");
        return res;
    }

    private Connection sftpConnect(NedWorker worker)
        throws Exception {

        // Connect
        traceInfo(worker, "SFTP connecting to " + ip.getHostAddress()+":"+port);
        Connection sftpConn = new Connection(ip.getHostAddress(), port);
        try {
            sftpConn.connect(null, 0, connectTimeout);
        } catch (Exception e) {
            throw new Exception("SFTP connect failed (check device SSH config or disable sftp in ned-settings) :: " + e.getMessage());
        }

        // Authenticate
        try {
            sftpConn.authenticateWithPassword(ruser, pass);
        } catch (Exception e) {
            throw new Exception("SFTP " + e.getMessage());
        }
        if (!sftpConn.isAuthenticationComplete())
            throw new NedException("SFTP authentication incomplete");

        traceInfo(worker, "SFTP logged in");
        return sftpConn;
    }


    private String sftpGetConfig(NedWorker worker)
        throws Exception {
        String res = "";

        // Connect using SSH
        Connection sftpConn = sftpConnect(worker);

        // Copy over running-config to file
        traceInfo(worker, "SFTP copying running-config to file: " + getDeviceFile);
        String cmd = "copy running-config " + getDeviceFile + " | prompts ENTER yes";
        String cmdpfx = inConfig ? "do " : "";
        String reply = run_command(worker, cmdpfx + cmd, inConfig, true);
        if (reply.startsWith(CMD_ERROR))
            throw new NedException("sftp-transfer ERROR: "+reply.replace(CMD_ERROR, ""));
        if (reply.contains("No such file or directory"))
            throw new NedException("sftp-transfer ERROR copying running-config to file, check 'get-device-config-settings file' ned-setting");

        //traceVerbose(worker, "COPY REPLY='"+reply+"'");

        // Get running-config file using Ganymed SFTP API
        traceInfo(worker, "SFTP fetching running-config copy: " + getDeviceFile);
        SFTPv3Client sftp = new SFTPv3Client(sftpConn);
        try {
            byte[] buffer = new byte[this.sftpMaxSize];
            int i = 0;
            long offset = 0;
            StringBuilder sb = new StringBuilder();
            SFTPv3FileHandle h = sftp.openFileRO(getDeviceFile);
            while ((i = sftp.read(h, offset, buffer, 0, buffer.length)) > 0) {
                sb.append(new String(buffer).substring(0, i));
                offset += i;
                if (i < buffer.length)
                    break;
            }
            res = sb.toString();
            traceInfo(worker, "SFTP got "+res.length()+" bytes");
        }
        catch (Exception e) {
            throw e;
        }
        finally {
            sftp.close();
        }
        sftpConn.close();

        // Delete the temporary running-config copy (ignore errors)
        traceInfo(worker, "SFTP deleting running-config copy: " + getDeviceFile);
        print_line_exec(worker, cmdpfx + "delete /noprompt " + getDeviceFile);

        res = res.replace("\n", "\r\n"); // to match terminal output
        return res;
    }


    /**
     * getConfig
     */
    private String getConfig(NedWorker worker, boolean convert)
        throws Exception {
        String res;
        long start = tick(0);

        //
        // Get config from device
        //
        if (convert && syncFile != null) {
            logInfo(worker, "BEGIN reading config (file = "+syncFile+")");
            res = print_line_exec(worker, "file show " + syncFile);
            if (res.indexOf("Error: failed to open file") >= 0)
                throw new NedException("failed to sync from file " + syncFile);
        }

        // sftp-transfer
        else if (getDeviceMethod.equals("sftp-transfer")) {
            logInfo(worker, "BEGIN reading config (sftp-transfer)");
            if (!remoteConnection.equals("none"))
                throw new NedException("sftp-transfer is not supported with proxy mode");
            if (getDeviceFile == null)
                throw new NedException("No SFTP file name configured");
            res = sftpGetConfig(worker);
        }

        // show running-config
        else {
            String cmd = "show running-config";
            logInfo(worker, "BEGIN reading config ("+cmd+") [in-config="+inConfig+"]");
            if (inConfig)
                cmd = "do " + cmd;
            session.print(cmd+"\n");
            session.expect(cmd, worker);
            res = session.expect(prompt, worker);
        }
        worker.setTimeout(readTimeout);

        //
        // Trim beginning and end
        //
        res = trimConfig(worker, res);

        //
        // Add admin config first
        //
        if (isDevice()) {
            traceInfo(worker, "reading config - admin show running-config");
            res = getAdminConfig(worker) + res;
        }

        logInfo(worker, "DONE reading config ("+res.length()+" bytes) "+tickToString(start));

        //
        // TRANSFORM CONFIG
        //
        res = transformConfig(worker, convert, false, res);

        if (syncFile != null) {
            traceVerbose(worker, "\nSHOW_AFTER_FILE:\n"+res);
            syncFile = null;
        } else {
            traceVerbose(worker, "SHOW_AFTER=\n"+res);
        }

        // Respond with updated show buffer
        return res;
    }


    /**
     * transformConfig
     */
    private String transformConfig(NedWorker worker, boolean convert, boolean partial, String res)
        throws Exception {
        int n, i, nl;
        String match;
        String[] group;
        long start = tick(0);

        logInfo(worker, "BEGIN in-transforming");

        if (apiEditRoutePolicy) {
            traceInfo(worker, "transformed <= inserted tailfned api edit-route-policy");
            res = "\ntailfned api edit-route-policy\n" + res;
        }

        if (includeCachedShowVersion) {
            // Add cached-show info to config
            res = res + "cached-show version version " + iosversion + "\n";
            res = res + "cached-show version model " + iosmodel.replace(" ", "-") + "\n";
            res = res + "cached-show version serial-number " + iosserial + "\n";
        }

        //
        // Update secrets - replace encrypted secrets with cleartext if not changed
        //
        if (isDevice()) {
            traceInfo(worker, "in-transforming - updating secrets [convert="+convert+" partial="+partial+"]");
            res = secrets.update(worker, res, convert, partial);
        }

        //
        // STRING QUOTE THE FOLLOWING ENTRIES
        //
        traceInfo(worker, "in-transforming - quoting strings ");
        String[] quoteStrings = {
            "alias (?:exec \\S+|config \\S+|\\S+) (.*)"
        };
        for (n = 0; n < quoteStrings.length; n++) {
            Pattern pattern = Pattern.compile(quoteStrings[n]);
            Matcher matcher = pattern.matcher(res);
            int offset = 0;
            while (matcher.find()) {
                String quoted = stringQuote(matcher.group(1));
                traceVerbose(worker, "transformed <= quoted string '"+matcher.group(0)+"' to '"+quoted+"'");
                res = res.substring(0, matcher.start(1) + offset)
                    + quoted
                    + res.substring(matcher.start(1) + matcher.group(1).length() + offset);
                offset = offset + quoted.length() - matcher.group(1).length();
            }
        }

        //
        // Quote ' description ' strings
        //
        traceInfo(worker, "in-transforming - quoting descriptions");
        String lines[] = res.split("\n");
        res = null; // to provoke crash if used below
        String toptag = "";
        for (n = 0; n < lines.length; n++) {
            if (lines[n].isEmpty())
                continue;
            if (isTopExit(lines[n])) {
                toptag = "";
            } else if (Character.isLetter(lines[n].charAt(0))) {
                toptag = lines[n].trim();
            }
            i = lines[n].indexOf(" description ");
            if (i < 0)
                continue;
            if (toptag.startsWith("l2vpn"))
                continue;
            if (toptag.startsWith("router static"))
                continue;
            if (i > 1 && lines[n].charAt(i-1) == '#')
                continue; // Ignore '# description' entries in e.g. route-policy or sets

            // Quote description string
            String desc = stringQuote(lines[n].substring(i+13).trim());
            //traceVerbose(worker, "transformed <= description "+stringQuote(desc));
            lines[n] = lines[n].substring(0,i+13) + desc;
        }


        //
        // NETSIM - leave early
        //
        if (isNetsim() && syncFile == null) {
            res = linesToString(lines);
            logInfo(worker, "DONE in-transforming "+tickToString(start));
            return res;
        }


        //
        // REAL DEVICES BELOW:
        //

        //
        // MAIN LINE-BY-LINE LOOP
        //
        traceInfo(worker, "in-transforming - line-by-line patches");
        for (n = 0, toptag = ""; n < lines.length; n++) {
            String input = null;
            String trimmed = lines[n].trim();
            if (trimmed.isEmpty())
                continue;

            // Update toptag
            if (isTopExit(lines[n])) {
                toptag = "";
            } else if (Character.isLetter(lines[n].charAt(0))) {
                toptag = trimmed;
            }

            //
            // tailf:cli-range-list-syntax
            //   class-map * / match vlan *
            //   class-map * / match traffic-class *
            //
            if (toptag.startsWith("class-map ")
                && (trimmed.startsWith("match vlan ") || trimmed.startsWith("match traffic-class"))) {
                input = lines[n].replaceAll("([0-9])( )([0-9])", "$1,$3");
            }

            //
            // interface * / encapsulation dot1ad|dot1ad
            //
            else if (toptag.startsWith("interface ")
                     && (match = getMatch(trimmed, "encapsulation(?: ambiguous)? dot1(?:ad|q) (.*)")) != null) {
                input = lines[n].replace(" , ", ",");
            }

            //
            // interface * / ipv4 address
            //
            else if (toptag.startsWith("interface ") && trimmed.startsWith("ipv4 address ")) {
                input = lines[n].replaceAll("ipv4 address ([0-9.]+)/(\\d+)", "ipv4 address $1 /$2");
            }

            //
            // snmp-server contact|location
            //
            else if (toptag.startsWith("snmp-server ")
                     && (match = getMatch(trimmed, "snmp-server (?:contact|location) (.*)")) != null) {
                String quoted = stringQuote(match);
                input = lines[n].replace(match, quoted);
            }

            // XR bugpatch: can't read it's own config
            //   lmp / gmpls optical-uni / controller *
            //    mpls ldp / nsr
            //
            else if (toptag.equals("lmp") && lines[n].equals("  !\r")) {
                input = "  exit\r";
            }
            else if (toptag.equals("mpls ldp") && lines[n].equals(" !\r")) {
                input = " exit\r";
            }

            //
            // route-policy *
            // route-policy * in|out
            //
            else if (toptag.startsWith("route-policy ")
                     && (match = getMatch(trimmed, "route-policy (.+)$")) != null
                     && match.contains(" ")) {
                input = lines[n].replace(match, "\""+match+"\"");
            }
            else if (trimmed.startsWith("route-policy ")
                     && (match = getMatch(trimmed, "route-policy (.+) (?:in|out)$")) != null
                     && match.contains(" ")) {
                input = lines[n].replace(match, "\""+match+"\"");
            }

            //
            // !<comment>
            //
            else if (trimmed.startsWith("!") && trimmed.length() > 1) {
                input = "";
            }

            //
            // Transform lines[n] -> XXX
            //
            if (input != null && !input.equals(lines[n])) {
                if (input.isEmpty())
                    traceVerbose(worker, "transformed <= stripped '"+trimmed+"'");
                else
                    traceVerbose(worker, "transformed <= '"+trimmed+"' to '"+input.trim()+"'");
                lines[n] = input;
            }

        } // for (line-by-line)
        res = linesToString(lines);


        //
        // APPEND TRANSFORMATIONS (may add, delete or reorder lines)
        //
        traceInfo(worker, "in-transforming - appending config");
        lines = res.split("\n");
        String[] sets = {
            "extcommunity-set rt","extcommunity-set soo", "extcommunity-set opaque",
            "rd-set","prefix-set","as-path-set","community-set"
        };
        StringBuilder buffer = new StringBuilder();
        String trimmed = "";
        for (n = 0; n < lines.length; n++) {
            String prevcmd = trimmed;
            trimmed = lines[n].trim();
            if (trimmed.isEmpty())
                continue;

            // Update toptag
            if (isTopExit(lines[n])) {
                toptag = "";
            } else if (Character.isLetter(lines[n].charAt(0))) {
                toptag = trimmed;
            }

            //
            // route-policy
            //
            if (toptag.startsWith("route-policy ")) {
                buffer.append(lines[n]+"\n");
                // New line list API - prepend line numbers to lines
                if (apiEditRoutePolicy) {
                    String name = toptag.replace("\"", "");
                    ConfPath cp = new ConfPath(OPER_PATH+"/edit-list{\""+name+"\"}");
                    String lineno[] = null;
                    if (cdbOper.exists(cp)) {
                        String val = ConfValue.getStringByValue(cp, cdbOper.getElem(cp.append("/lineno")));
                        lineno = val.split(" ");
                    }
                    int indx = 0;
                    int num = 0;
                    for (n = n + 1; n < lines.length; n++) {
                        if (lines[n].trim().equals("end-policy"))
                            break;
                        if (lineno != null && indx < lineno.length)
                            num = Integer.parseInt(lineno[indx++]);
                        else
                            num += 10;
                        buffer.append(""+num+" "+lines[n].trim()+"\n");
                    }
                }
                // Single buffer API - make contents into a single quoted string
                else {
                    String policy = "";
                    for (n = n + 1; n < lines.length; n++) {
                        if (lines[n].trim().equals("end-policy"))
                            break;
                        policy += lines[n] + "\n";
                    }
                    if (!policy.isEmpty()) {
                        traceVerbose(worker, "transformed <= quoted '"+toptag+"'");
                        buffer.append(stringQuote(policy)+"\n");
                    }
                }
            }

            //
            // group - make contents into a single quoted string
            //
            else if (toptag.startsWith("group ")) {
                buffer.append(lines[n]+"\n");
                String policy = "";
                for (n = n + 1; n < lines.length; n++) {
                    if (lines[n].trim().equals("end-group"))
                        break;
                    policy += lines[n] + "\n";
                }
                if (!policy.isEmpty()) {
                    traceVerbose(worker, "transformed <= quoted '"+toptag+"'");
                    buffer.append(stringQuote(policy)+"\n");
                }
            }

            //
            // mpls traffic-eng / auto-tunnel backup
            //
            else if (toptag.equals("mpls traffic-eng")
                     && !prevcmd.equals("mpls traffic-eng")
                     && lines[n].equals(" auto-tunnel backup\r")) {
                // XR bugpatch: can't read it's own config
                traceVerbose(worker, "transformed <= injecting 'mpls traffic-eng' for XR bad ordering");
                buffer.append("mpls traffic-eng\n");
            }

            //
            // policy-map * / class * / random-detect discard-class *
            //
            else if (toptag.startsWith("policy-map ")
                     && trimmed.startsWith("random-detect discard-class ")
                     && trimmed.contains(",")
                     && (group = getMatches(worker, trimmed, "random-detect discard-class (\\S+)( .*)")) != null) {
                // XR concats discard-class entries with a comma between, i.e: x,y,z
                String vals[] = group[1].split(",");
                traceVerbose(worker, "transformed <= splitting '"+trimmed+"' in "+vals.length+" entries");
                for (int v = 0; v < vals.length; v++)
                    buffer.append("random-detect discard-class "+vals[v]+group[2]+"\n");
                continue;
            }

            //
            // sets - strip commas
            //
            for (int s = 0; s < sets.length; s++) {
                if (lines[n].startsWith(sets[s]+" ")) {
                    traceVerbose(worker, "transformed <= stripped commas from set: "+lines[n]);
                    buffer.append(lines[n]+"\n");
                    for (n = n + 1; n < lines.length; n++) {
                        if (lines[n].trim().equals("end-set"))
                            break;
                        buffer.append(lines[n].replace(",","")+"\n");
                    }
                    break;
                    // fall through for appending end-set line
                }
            }

            //
            // Add line
            //
            if (n < lines.length) {
                buffer.append(lines[n]+"\n");
            }
        }
        res = buffer.toString();

        //
        // Assemble banner into a single quoted string with start and end marker
        //
        traceInfo(worker, "in-transforming - banners");
        Pattern p = Pattern.compile("\nbanner (\\S+) (\\S)(.*?)\\2\\S*?\r", Pattern.DOTALL);
        Matcher m = p.matcher(res);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String name = m.group(1);
            String marker = passwordQuote(m.group(2));
            String message = stringQuote(m.group(3));
            traceVerbose(worker, "transformed <= quoted banner "+name);
            m.appendReplacement(sb, Matcher.quoteReplacement("\nbanner "+name+" "+marker+" "+message+" "+marker));
        }
        m.appendTail(sb);
        res = sb.toString();

        //
        // ned-setting cisco-iosxr-show-running-strict-mode
        //
        if (showRunningStrictMode) {
            traceInfo(worker, "in-transforming - strict mode");
            lines = res.split("\n");
            for (n = 0; n < lines.length; n++) {
                trimmed = lines[n];
                if (lines[n].startsWith("!") && trimmed.equals("!"))
                    lines[n].replace("!", "xyzroot 0");
                else if (trimmed.equals("!"))
                    lines[n].replace("!", "exit");
            }
            res = linesToString(lines);
        }
        // Mode-sensitive interface fix.
        else {
            traceInfo(worker, "in-transforming - injecting 'xyzroot 0' top-root markers");
            res = res.replaceAll("\ninterface ", "\nxyzroot 0\ninterface ");
            res = res.replaceAll("\nvrf (\\S+)\r", "\nxyzroot 0\r\nvrf $1\r");
        }

        logInfo(worker, "DONE in-transforming "+tickToString(start));
        return res;
    }

    private String getCommitId(NedWorker worker)
        throws NedException, IOException, SSHSessionException, ApplyException {
        String res = print_line_exec(worker, "show config commit list 1 detail");
        res = getMatch(res, "CommitId:\\s+(\\d+)");
        if (res == null)
            throw new Error("failed to get commitId, reply="+res);
        return res;
    }

    /**
     * getTransId
     */
    public void getTransId(NedWorker worker)
        throws Exception {
        long start = tick(0);
        String res;

        if (trace)
            session.setTracer(worker);

        // Use commit list ID for string data
        if (transActionIdMethod.equals("commit-list")) {
            logInfo(worker, "BEGIN GET-TRANS-ID (commit-list)");
            res = getCommitId(worker);
            logInfo(worker, "DONE GET-TRANS-ID ("+res+") "+tickToString(start));
            worker.getTransIdResponse(res);
            return;
        }

        // Use last cached transformed config from applyConfig() secret code
        if (lastGetConfig != null) {
            logInfo(worker, "BEGIN GET-TRANS-ID (config-hash secrets)");
            res = lastGetConfig;
            lastGetConfig = null;
        }

        // Use running-config for string data
        else {
            logInfo(worker, "BEGIN GET-TRANS-ID (config-hash)");
            res = getConfig(worker, false);
        }
        worker.setTimeout(readTimeout);

        traceVerbose(worker, "TRANS-ID-BUF=\n+++ begin\n"+res+"\n+++ end");

        // Calculate checksum of running-config
        byte[] bytes = res.getBytes("UTF-8");
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] thedigest = md.digest(bytes);
        BigInteger md5Number = new BigInteger(1, thedigest);
        String md5String = md5Number.toString(16);

        logInfo(worker, "DONE GET-TRANS-ID ("+md5String+") "+tickToString(start));
        worker.getTransIdResponse(md5String);
    }

    /**
     * show
     */
    public void show(NedWorker worker, String toptag)
        throws Exception {
        long start = tick(0);

        if (trace)
            session.setTracer(worker);
        logInfo(worker, "BEGIN SHOW");

        // Only respond to the first toptag
        if (toptag.equals("interface") == false) {
            worker.showCliResponse("");
            return;
        }

        // Get config from device
        lastGetConfig = null;
        String res = getConfig(worker, true);

        // cisco-iosxr extended-parser
        if (turboParserEnable) {
            traceInfo(worker, "Parsing config using turbo-mode");
            if (parseAndLoadXMLConfigStream(mm, worker, schema, res)) {
                // Turbo-parser present/succeeded, clear config to bypass CLI
                res = "";
            }
        } else if (robustParserMode) {
            res = filterConfig(res, schema, mm, worker, null, false).toString();
        }

        logInfo(worker, "DONE SHOW "+tickToString(start));
        worker.showCliResponse(res);
    }

    private boolean partialPathIn(String longp, String shortp) {
        String pl[] = longp.split(" \\\\ ");
        String ps[] = shortp.split(" \\\\ ");
        for (int i = 0; i < ps.length; i++)
            if (!ps[i].trim().equals(pl[i].trim()))
                return false;
        return true;
    }

    /**
     * run_showPartial
     */
    private String run_showPartial(NedWorker worker, String cmdPath)
        throws Exception {
        String dump;

        if (cmdPath.trim().equals("admin")) {
            dump = getAdminConfig(worker);
        } else {
            String show = "show running-config " + cmdPath.replace("\\", "");
            dump = print_line_exec(worker, show);
        }

        if (dump.contains("% No such configuration item")) {
            traceInfo(worker, "showPartial() WARNING: '"+cmdPath+"' not found");
            return "";
        }
        if (dump.contains("Invalid input detected at")) {
            traceInfo(worker, "showPartial() ERROR: failed to show '"+cmdPath+"'");
            int i = cmdPath.lastIndexOf("\\");
            if (i > 0)
                return run_showPartial(worker, cmdPath.substring(0, i));
            return null;
        }

        return dump;
    }

    /**
     * Handler called when "commit no-overwrite" is used in NSO.
     * Dumps config from one or many specified locations in the
     * tree instead of dumping everything.
     *
     * @param worker  - The NED worker
     * @param cp      - Paths to dump
     * @throws Exception
     *
     * commit no-overwrite
     * devices partial-sync-from [ <xpath> <xpath>]
     */
    public void
    showPartial(NedWorker worker, String[] cp)
        throws Exception {
        StringBuilder results = new StringBuilder();
        long start = tick(0);

        if (trace)
            session.setTracer(worker);
        lastGetConfig = null;

        logInfo(worker, "BEGIN SHOW PARTIAL");
        traceVerbose(worker, Arrays.toString(cp));

        //
        // NETSIM - execute a plain show running and dump the result
        //
        if (isNetsim()) {
            ArrayList<String> cmdPaths = new ArrayList<String>(Arrays.asList(cp));
            for (String cmdPath : cmdPaths) {
                String show = "show running-config " + cmdPath.replace("\\", "");
                String dump = print_line_exec(worker, show);
                if (dump.contains("% No entries found.")) {
                    traceInfo(worker, "showPartial() WARNING: '"+cmdPath+"' not found");
                } else {
                    results.append(dump);
                }
                worker.setTimeout(readTimeout);
            }
        }

        //
        // Real XR device
        //
        else {
            ArrayList<String> cmdPaths = new ArrayList<String>();
            ArrayList<String> pathsToDump = new ArrayList<String>();

            // Scan protectedPaths and trim matches with too deep show
            for (int i = 0; i < cp.length; i++) {
                boolean isProtected = false;
                String path = cp[i];
                // Trim quotes around route-policy name
                String match = getMatch(path, "^route-policy \\\"(.*)\\\"$");
                if (match != null && match.contains(" ")) {
                    path = path.replace("\""+match+"\"", match);
                }
                // Scan paths and trim too deep shows
                for (Pattern pattern : protectedPaths) {
                    Matcher matcher = pattern.matcher(path);
                    if (matcher.matches()) {
                        String group = matcher.group(1).trim();
                        String trimmed = path.substring(0, path.indexOf(group));
                        traceVerbose(worker, "partial: trimmed '"+path+"' to '"+trimmed+"'");
                        cmdPaths.add(trimmed);
                        isProtected = true;
                        break;
                    }
                }
                if (isProtected == false) {
                    cmdPaths.add(path);
                }
            }

            // Sort the path list such that shortest comes first
            class PathComp implements Comparator<String> {
                public int compare(String o1, String o2) {
                    int x = o1.length();
                    int y = o2.length();
                    return (x < y) ? -1 : ((x == y) ? 0 : 1);
                }
            }
            Collections.sort(cmdPaths, new PathComp());

            // Filter out any overlapping paths
            for (String cmdPath : cmdPaths) {
                boolean isUniquePath = true;
                for (String p : pathsToDump) {
                    if (partialPathIn(cmdPath, p)) {
                        isUniquePath = false;
                        break;
                    }
                }
                if (isUniquePath) {
                    pathsToDump.add(cmdPath); // New path to dump
                } else {
                    traceVerbose(worker, "partial: stripped '"+cmdPath+"'");
                }
            }

            // Call all partial show commands
            for (String cmdPath : pathsToDump) {
                String dump = run_showPartial(worker, cmdPath);
                if (dump == null)
                    throw new Error("showPartial() :: failed to show '"+cmdPath+"'");
                if (!dump.isEmpty()) {
                    // Trim show date stamp
                    Pattern p = Pattern.compile("\r(?:Tue|Mon|Wed|Thu|Fri|Sat|Sun) .*UTC");
                    Matcher m = p.matcher(dump);
                    if (m.find())
                        dump = dump.replace(m.group(0), "");
                    // Append output
                    results.append(dump);
                    worker.setTimeout(readTimeout);
                }
            }
        }

        //
        // Transform config
        //
        String config = results.toString();
        if (!config.trim().isEmpty()) {
            config = transformConfig(worker, true, true, config); // convert=true & partial=true
        }
        //traceVerbose(worker, "SHOW_PARTIAL_AFTER=\n"+config);

        logInfo(worker, "DONE SHOW PARTIAL "+tickToString(start));
        worker.showCliResponse(config);
    }


    /**
     * isConnection
     */
    public boolean isConnection(String device_id,
                                InetAddress ip,
                                int port,
                                String proto,  // ssh or telnet
                                String ruser,
                                String pass,
                                String secpass,
                                String keydir,
                                boolean trace,
                                int connectTimeout, // msec
                                int readTimeout,
                                int writeTimeout) {
        return ((this.device_id.equals(device_id)) &&
                (this.ip.equals(ip)) &&
                (this.port == port) &&
                (this.proto.equals(proto)) &&
                (this.ruser.equals(ruser)) &&
                (this.pass.equals(pass)) &&
                (this.secpass.equals(secpass)) &&
                (this.trace == trace) &&
                (this.connectTimeout == connectTimeout) &&
                (this.readTimeout == readTimeout) &&
                (this.writeTimeout == writeTimeout));
    }


    /**
     * commandWash
     */
    private String commandWash(String cmd) {
        byte[] bytes = cmd.getBytes();
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < cmd.length(); ++i) {
            if (bytes[i] == 9)
                continue;
            if (bytes[i] == -61)
                continue;
            result.append(cmd.charAt(i));
        }
        return result.toString();
    }


    /**
     * run_command
     */
    private String run_command(NedWorker worker, String cmd, boolean configMode, boolean single)
        throws Exception {
        String promptv[] = null;
        int i;
        String reply = single ? "" : ("\n> " + cmd);

        traceInfo(worker, "run_command = '"+cmd+"'");

        // Enable noprompts or extract answer(s) to prompting questions
        boolean noprompts = false;
        if (cmd.matches("^.*\\s*\\|\\s*noprompts\\s*$")) {
            noprompts = true;
            cmd = cmd.substring(0,cmd.lastIndexOf("|")).trim();
        } else {
            Pattern pattern = Pattern.compile("(.*)\\|\\s*prompts\\s+(.*)");
            Matcher matcher = pattern.matcher(cmd);
            if (matcher.find()) {
                cmd = matcher.group(1).trim();
                traceVerbose(worker, "   cmd = '"+cmd+"'");
                promptv = matcher.group(2).trim().split(" +");
                for (i = 0; i < promptv.length; i++)
                    traceVerbose(worker, "   promptv["+i+"] = '"+promptv[i]+"'");
            }
        }

        // Send command or help (ending with ?) to device
        boolean help = cmd.charAt(cmd.length() - 1) == '?';
        String helpPrompt;
        traceVerbose(worker, "SENDING_CMD [in-config="+configMode+"]: "+stringQuote(cmd));
        if (help) {
            session.print(cmd);
            helpPrompt = "\\A[^\\# ]+#[ ]*" + cmd.substring(0, cmd.length()-1) + "[ ]*";
            traceVerbose(worker, "help-prompt = '" + helpPrompt + "'");
            noprompts = true;
        }
        else {
            session.print(cmd+"\n");
            helpPrompt = prompt;
        }

        // Wait for command echo from device
        traceVerbose(worker, "Waiting for command echo '"+cmd+"'");
        session.expect(new String[] { Pattern.quote(cmd) }, worker);

        // Prompt patterns
        Pattern[] cmdPrompt = new Pattern[] {
            // 0 - Prompt patterns:
            Pattern.compile(config_prompt),
            Pattern.compile(prompt),
            Pattern.compile(helpPrompt),
            // 3 - Ignore patterns:
            Pattern.compile("\\[OK\\]"),
            Pattern.compile("\\[Done\\]"),
            Pattern.compile("timeout is \\d+ seconds:"),  // ping
            Pattern.compile("Suggested steps to resolve this:"), // admin install
            Pattern.compile("Warning: .*:"), // admin install
            // 8 - Question patterns:
            Pattern.compile(":\\s*$"),
            Pattern.compile("\\][\\?]?\\s*$")
        };

        // Wait for prompt, answer prompting questions with | prompts info
        worker.setTimeout(readTimeout);
        int promptc = 0;
        while (true) {
            traceVerbose(worker, "Waiting for command prompt (read-timeout "+readTimeout+")");
            NedExpectResult res = session.expect(cmdPrompt, true, readTimeout, worker);
            String output = res.getText();
            String answer = null;
            reply += output;
            if (res.getHit() < 3) {
                traceVerbose(worker, "Got prompt["+res.getHit()+"] "+stringQuote(output));
                if (help) {
                    sendBackspaces(worker, cmd);
                }
                if (configMode) {
                    // Check if command exited config mode:
                    if (res.getHit() == 1) {
                        traceInfo(worker, "command ERROR: exited config mode calling '"+cmd+"'");
                        enterConfig(worker, NedCmd.CMD);
                        return CMD_ERROR + reply + "\nERROR: Aborted, last command left config mode";
                    }
                    // WARNING: No command error checks are performed, command is 100% raw.
                }
                if (promptv != null && promptc < promptv.length) {
                    reply += "\n(unused prompts:";
                    for (i = promptc; i < promptv.length; i++)
                        reply += " "+promptv[i];
                    reply += ")";
                }
                break;
            } else if (res.getHit() < 8 // Ignore patterns
                       || noprompts // '| noprompts' option
                       || help
                       || cmd.startsWith("show ")) {
                if (promptv == null || promptv.length == 0) {
                    traceVerbose(worker, "Ignoring output ["+res.getHit()+"] "+stringQuote(output));
                    continue;
                }
            }

            traceVerbose(worker, "Got question "+stringQuote(output));

            // Get answer from command line, i.e. '| prompts <val>'
            if (promptv != null && promptc < promptv.length) {
                answer = promptv[promptc++];
            }

            // Look for answer in auto-prompts ned-settings
            else {
                for (int n = autoPrompts.size()-1; n >= 0; n--) {
                    String entry[] = autoPrompts.get(n);
                    if (findString(entry[1], output) >= 0) {
                        traceInfo(worker, "Matched auto-prompt["+entry[0]+"]");
                        answer = entry[2];
                        reply += "(auto-prompt "+answer+") -> ";
                        break;
                    }
                }
            }

            // Send answer to device. Check if rebooting
            if (answer != null) {
                traceVerbose(worker, "SENDING_CMD_ANSWER: "+answer);
                if (answer.equals("ENTER"))
                    session.print("\n");
                else if (answer.equals("IGNORE"))
                    continue; // used to avoid blocked on bad prompts
                else if (answer.length() == 1)
                    session.print(answer);
                else
                    session.print(answer+"\n");
                if (cmd.startsWith("reload")
                    && output.contains("Proceed with reload")
                    && answer.charAt(0) != 'n') {
                    rebooting = true;
                    break;
                }
                continue;
            }

            // Missing answer to a question prompt:
            reply = "\nMissing answer to a device question:\n+++" + reply;
            reply +="\n+++\nSet auto-prompts ned-setting or add '| prompts <answer>', e.g.:\n";
            if (configMode)
                reply += "exec \"<command> | prompts yes\"";
            else
                reply += "devices device <devname> live-status exec any \"reload | prompts yes\"";
            reply += "\nNote: Single letter is sent without LF. Use 'ENTER' for LF only.";
            reply += "\n      Add '| noprompts' in order to ignore all prompts.";
            exitPrompting(worker);
            return CMD_ERROR + reply;
        }

        return reply;
    }


    /**
     * command
     */
    @Override
    public void command(NedWorker worker, String cmdName, ConfXMLParam[] p)
        throws Exception {
        String cmd  = cmdName;
        String replies = "";
        boolean configMode = false;
        int i;
        rebooting = false;
        long start = tick(0);

        if (trace)
            session.setTracer(worker);

        // Add command arguments and clean out special characters (e.g. caused by editing)
        for (i = 0; i < p.length; ++i) {
            ConfObject val = p[i].getValue();
            if (val != null)
                cmd = cmd + " " + val.toString();
        }
        logInfo(worker, "BEGIN COMMAND("+cmd+") [in-config="+inConfig+"]");
        cmd = commandWash(cmd);

        // Patch for service node bug which erronously quoted command string
        if (cmd.charAt(cmd.length() - 1) == '"') {
            traceInfo(worker, "NCSPATCH: removing quotes inserted by bug in NCS");
            cmd = cmd.substring(0, cmd.length() -1 );
            cmd = cmd.replaceFirst("\"", "");
        }

        // Config mode exec command(s)
        boolean wasInConfig = inConfig;
        if (cmd.startsWith("exec ")) {
            configMode = true;
            if (cmd.startsWith("exec "))
                cmd = cmd.substring(5);
            if (!wasInConfig)
                enterConfig(worker, NedCmd.CMD);
        }
        // Exec mode command(s)
        else if (cmd.startsWith("any ")) {
            cmd = cmd.substring(4);
        }


        // show outformat raw [internal command]
        if (cmd.equals("show outformat raw")) {
            replies = "\nNext dry-run will show raw (unmodified) format.\n";
            traceInfo(worker, replies);
            showRaw = true;
        }

        // failphase <phase> [internal command]
        else if (cmd.startsWith("fail ")) {
            failphase = cmd.substring(5).trim();
            replies = "\nfailphase set to: '"+failphase+"'\n";
            traceInfo(worker, replies);
        }

        // show outformat raw [internal command]
        else if (cmd.equals("secrets resync")) {
            secrets.enableReSync();
            getConfig(worker, false);
            replies = "\nRe-synced all cached secrets.\n";
            traceInfo(worker, replies);
        }

        // secrets delete [internal command]
        else if (cmd.equals("secrets delete")) {
            int found = secrets.delete(worker);
            replies = "\nDeleted "+found+" cached secrets.\n";
            traceInfo(worker, replies);
        }

        // sync-from-file <FILE>
        else if (isNetsim() && cmd.startsWith("sync-from-file ")) {
            syncFile = cmd.trim().substring(15).trim();
            replies = "\nNext sync-from will use file = " + syncFile + "\n";
            traceInfo(worker, replies);
        }

        //
        // Run command(s) on device:
        //
        else {
            String[] cmds = cmd.split(" ; ");
            for(i = 0 ; i < cmds.length ; i++) {
                String reply = run_command(worker, cmds[i], configMode, cmds.length == 1);
                if (reply.startsWith(CMD_ERROR)) {
                    replies += reply.substring(CMD_ERROR.length());
                    if (configMode && !wasInConfig)
                        exitConfig2(worker, "command");
                    logInfo(worker, "DONE COMMAND "+tickToString(start));
                    worker.error(NedCmd.CMD, replies);
                    return;
                }
                replies += reply;
            }
        }

        //
        // Report device output 'replies'
        //
        logInfo(worker, "DONE COMMAND "+tickToString(start));
        if (configMode) {
            if (!wasInConfig)
                exitConfig2(worker, "command");
            worker.commandResponse(new ConfXMLParam[] {
                    new ConfXMLParamValue("cisco-ios-xr", "result", new ConfBuf(replies))});
        } else {
            worker.commandResponse(new ConfXMLParam[] {
                    new ConfXMLParamValue("cisco-ios-xr-stats", "result",new ConfBuf(replies))});
        }

        // Rebooting
        if (rebooting) {
            logInfo(worker, "Rebooting device...");
            worker.setTimeout(10*60*1000);
            sleep(worker, 30 * 1000, true); // Sleep 30 seconds
        }
    }

    /**
     * showStats
     */
    public void showStats(NedWorker worker, int th, ConfPath path)
        throws Exception {

        // Call NedLiveStatus and return results
        ArrayList<NedTTL> ttls = nedLiveStatus.showStats(worker, mm, th, path);
        worker.showStatsResponse(ttls != null ? ttls.toArray(new NedTTL[ttls.size()]) : null);

    }


    /**
     * showStatsList
     */
    public void showStatsList(NedWorker worker, int th, ConfPath path)
        throws Exception {

        // Call NedLiveStatus and return results
        ArrayList<NedTTL> ttls = nedLiveStatus.showStatsList(worker, mm, th, path);
        worker.showStatsListResponse(60, ttls != null ? ttls.toArray(new NedTTL[ttls.size()]) : null);

    }


    /**
     * newConnection
     */
    public NedCliBase newConnection(String device_id,
                                InetAddress ip,
                                int port,
                                String proto,  // ssh or telnet
                                String ruser,
                                String pass,
                                String secpass,
                                String publicKeyDir,
                                boolean trace,
                                int connectTimeout, // msec
                                int readTimeout,    // msec
                                int writeTimeout,   // msecs
                                NedMux mux,
                                NedWorker worker) {
        return new IosxrNedCli(device_id,
                               ip, port, proto, ruser, pass, secpass, trace,
                               connectTimeout, readTimeout, writeTimeout,
                               mux, worker);
    }


    public NedCliBase initNoConnect(String device_id,
                                    NedMux mux,
                                    NedWorker worker) {
        logDebug(null, "initNoConnect("+device_id+")");
        return new IosxrNedCli(device_id, mux, worker);
    }

    /**
     * toString
     */
    public String toString() {
        if (ip == null)
            return device_id+"-<ip>:"+Integer.toString(port)+"-"+proto;
        return device_id+"-"+ip.toString()+":"+
            Integer.toString(port)+"-"+proto;
    }
}
