package com.tailf.packages.ned.iosxr;

import com.tailf.packages.ned.nedcom.NedComCliBase;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.net.InetAddress;
import java.text.StringCharacterIterator;
import java.text.CharacterIterator;
import java.io.IOException;

import com.tailf.conf.Conf;
import com.tailf.conf.ConfKey;
import com.tailf.conf.ConfBuf;
import com.tailf.conf.ConfValue;

import com.tailf.maapi.Maapi;
import com.tailf.maapi.MaapiException;

import com.tailf.ncs.ns.Ncs;

import com.tailf.ned.NedWorker;
import com.tailf.ned.NedException;
import com.tailf.ned.NedExpectResult;
import com.tailf.ned.CliSession;
import com.tailf.ned.TelnetSession;
import com.tailf.ned.SSHSession;
import com.tailf.ned.SSHConnection;

import com.tailf.navu.NavuContext;
import com.tailf.navu.NavuContainer;
import com.tailf.navu.NavuChoice;
import com.tailf.navu.NavuList;
import com.tailf.navu.NavuLeaf;
import com.tailf.navu.NavuNode;

import org.apache.log4j.Logger;


/**
 * Login on device
 *
 *
 * ned-settings cisco-iosxr-connection-settings loginscript <name>
 *
 * ned-settings loginscripts script {name} alias {name} <value>
 * ned-settings loginscripts script {name} id {id} state <num> [expect <regexp>] send <command>] [next-state <num>]
 * ned-settings loginscripts script {name} id {id} state <num> [expect <regexp>] send-noecho <command>] [next-state <num>]
 * ned-settings loginscripts script {name} id {id} state <num> [expect <regexp>] connect [next-state <num>]
 * ned-settings loginscripts script {name} id {id} state <num> [expect <regexp>] fail [message]
 * ned-settings loginscripts script {name} id {id} state <num> [expect <regexp>] end
 *
 * notes: alias (use: $name) are inserted in both expect and send.
 * built-in aliases: $address, $port, $remote-name, $remote-password
 * and $remote-secondary-password
 *
 *
 * @author lbang
 * @version v2.0 2018-01-30
 */
@SuppressWarnings("deprecation")
public class NedLoginScripts {

    /*
     * Constructor data
     */
    private NedComCliBase owner;
    private CliSession session;
    private String device_id;
    private boolean trace;
    private boolean logVerbose;
    private int connRetries;
    private int connRetryWait;

    /*
     * Internal data
     */
    private NedWorker worker = null;
    private HashMap<String, String> aliases = new HashMap<String, String>();
    private HashMap<String, ArrayList<LoginAction>> actions = new HashMap<String, ArrayList<LoginAction>>();
    private int state = -1;

    /**
     * Constructor
     * @param
     */
    NedLoginScripts(NedComCliBase owner,
             CliSession session,
             String device_id,
             boolean trace,
             boolean logVerbose,
             int connRetries,
             int connRetryWait)
    {
        this.owner         = owner;
        this.session       = session;
        this.device_id     = device_id;
        this.trace         = trace;
        this.logVerbose    = logVerbose;
        this.connRetries   = connRetries;
        this.connRetryWait = connRetryWait;
    }


    /**
     * Get system time in ticks
     * @param
     * @return
     * @throws Exception
     */
    private long tick(long t) {
        return System.currentTimeMillis() - t;
    }


    /**
     * Format string for output
     * @param
     * @return
     * @throws Exception
     */
    private String tickToString(long start) {
        long stop = tick(start);
        return String.format("[%d ms]", stop);
    }


    /**
     * Write verbose info in trace
     * @param
     * @return
     * @throws Exception
     */
    public void traceVerbose(String msg) {
        if (logVerbose) {
            traceInfo(msg);
        }
    }


    /**
     * Write info in trace
     * @param
     * @return
     * @throws Exception
     */
    public void traceInfo(String msg) {
        if (worker != null && trace) {
            String prefix = "LOGIN";
            if (this.state != -1)
                prefix += ("["+this.state+"]");

            worker.trace("-- "+prefix+": " + msg + "\n", "out", device_id);
        }
    }


    /**
     * Log and trace an error
     * @param
     * @return
     * @throws Exception
     */
    public void logError(String text, Exception e) {
        text = "NedLoginScripts ERROR: " + text;
        owner.LOGGER.error(device_id + " " + text, e);
        if (trace && worker != null) {
            if (e != null)
                worker.trace("-- " + text + ": " + e.getMessage() + "\n", "out", device_id);
            else
                worker.trace("-- " + text + ": unknown\n", "out", device_id);
        }
    }


    /**
     * Regexp match one
     * @param
     * @return
     */
    private String getMatch(String text, String regexp) {
        Pattern p = Pattern.compile(regexp);
        Matcher m = p.matcher(text);
        if (m.find() == false)
            return null;
        return m.group(1);
    }


    /**
     * Quote a string
     * @param
     * @return
     */
    private static String stringQuote(String aText) {
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
     * Login action class
     * @param
     * @return
     */
    private class LoginAction {
        int id;
        int state;
        String expect;
        String action;
        String actionValue;
        int nextState;

        public LoginAction(int id, int state, String expect, String action, String actionValue, int nextState) {
            this.id = id;
            this.state = state;
            this.expect = expect;
            this.action = action;
            this.actionValue = actionValue;
            this.nextState = nextState;
        }
    }


    /**
     * Setup SSH for action conncet
     * @param
     * @return
     * @throws Exception
     */
    private void setupSSH()
        throws Exception {
        owner.connection = new SSHConnection(this.worker);
        owner.connection.connect(null, 0, owner.connectTimeout);
        if (owner.connection.isAuthenticationComplete() == false)
            throw new NedException("LOGIN: SSH authentication failed");
        NedWorker myWorker = trace ? this.worker : null;
        this.session = new SSHSession(owner.connection, owner.readTimeout, myWorker, owner, 200, 24);
    }


    /**
     * Setup TELNET for action connect
     * @param
     * @return
     * @throws Exception
     */
    private void setupTelnet()
        throws Exception {
        NedWorker myWorker = trace ? this.worker : null;
        this.session = new TelnetSession(this.worker,owner.ruser,owner.readTimeout,myWorker,owner);
    }


    /**
     * Sleep ms milliseconds
     * @param
     * @return
     * @throws Exception
     */
    private void sleep(long ms) {
        traceVerbose("Sleeping "+ms+" milliseconds");
        try {
            Thread.sleep(ms);
            traceVerbose("Woke up from sleep");
        } catch (InterruptedException ignore) { }
    }


    /**
     * Connect to device using NSO NED connect
     * @param
     * @return
     * @throws Exception
     */
    private void connectDevice()
        throws Exception {

        for (int i = this.connRetries; i >= 0; i--) {
            traceInfo(owner.proto.toUpperCase()+" connecting to host: "
                      +owner.ip.getHostAddress()+":"+owner.port
                      +" ["+(1+this.connRetries-i)+"/"+(this.connRetries+1)+"]");
            try {
                if (owner.proto.equals("ssh")) {
                    setupSSH();
                } else {
                    setupTelnet();
                }
                traceInfo(owner.proto.toUpperCase()+" connected");
                break;
            }
            catch (Exception e) {
                String failmsg = "connect :: "+e.getMessage();
                this.session = null;
                if (i > 0) {
                    // Got more retries -> sleep and retry
                    traceInfo(failmsg + " - retrying");
                    worker.setTimeout(owner.connectTimeout + (this.connRetryWait * 1000));
                    sleep(this.connRetryWait * 1000);
                }
                else {
                    // Out of retries -> throw exception
                    throw new Exception("LOGIN: "+failmsg);
                }
            }
        }
    }


    /**
     * Get integer from navu leaf
     * @param
     * @return
     * @throws Exception
     */
    private int navuLeafGetInt(NavuLeaf leaf, int defaultValue)
        throws Exception {
        if (leaf == null)
            return defaultValue;
        String value = leaf.valueAsString();
        if (value == null)
            return defaultValue;
        return Integer.parseInt(value);
    }


    /**
     * Insert alias in send or expect string
     * @param
     * @return
     * @throws Exception
     */
    private String insertAlias(String scriptname, String line)
        throws Exception {
        Pattern p = Pattern.compile("(\\$([_a-zA-Z-]+))");
        Matcher m = p.matcher(line);
        while (m.find()) {
            String alias = aliases.get(scriptname+m.group(2));
            if (alias == null) {
                traceInfo("WARNING: missing alias "+m.group(2)+" in script "+scriptname);
                return line;
            }
            line = line.replace(m.group(1), alias);
        }
        return line;
    }


    /**
     * Action to string
     * @param
     * @return Action in string format
     */
    private String actionToString(LoginAction action) {
        String string = "action id="+action.id+" state="+action.state;
        if (action.expect != null)
            string += (" expect="+stringQuote(action.expect));
        if (action.action != null)
            string += (" action="+action.action);
        if (action.actionValue != null)
            string += (" "+action.actionValue);
        if (action.state != action.nextState)
            string += (" next-state="+action.nextState);
        return string.replace("\n", "\\n");
    }


    /**
     * Script to string
     * @param
     * @return
     * @throws Exception
     */
    public String scriptToString(String scriptname)
        throws Exception {
        StringBuilder sb = new StringBuilder();

        // Get actions from hashmap
        for (Map.Entry<String, ArrayList<LoginAction>> entry : actions.entrySet()) {
            ArrayList<LoginAction> actionList = entry.getValue();
            for (int a = 0; a < actionList.size(); a++) {
                LoginAction action = actionList.get(a);
                sb.append(actionToString(action)+"\n");
            }
        }

        // Get aliases from hashmap
        for (Map.Entry<String, String> entry : aliases.entrySet()) {
            String name = entry.getKey().substring(scriptname.length());
            String value = entry.getValue();
            if (name.contains("password"))
                value = "*HIDDEN PASSWORD*";
            sb.insert(0, "alias "+name+" = "+value+"\n");
        }

        // Sort entries
        String lines[] = sb.toString().split("\n");
        Arrays.sort(lines);
        sb = new StringBuilder();
        for (int n = 0; n < lines.length; n++) {
            sb.append(lines[n]+"\n");
        }

        return sb.toString();
    }


    /**
     * Connect to device using login script
     * @param
     * @return
     * @throws Exception
     */
    public boolean hasScript(NedWorker worker, String scriptname) {
        ArrayList<LoginAction> actionList = actions.get(scriptname+"0");
        if (actionList != null)
            return true;
        return false;
    }


    /**
     * Connect to device using login script
     * @param
     * @return
     * @throws Exception
     */


    /**
     * Read scriptname from ned-settings
     * @param
     * @return
     * @throws Exception
     */
    public boolean readFromNedSettings(NedWorker worker, String scriptname, String nedname, Maapi maapi)
        throws Exception {
        boolean found = false;
        try {
            maapi.getMyUserSession();
        } catch (Exception ignore) {
            maapi.setUserSession(1);
        }
        int th = maapi.startTrans(Conf.DB_RUNNING, Conf.MODE_READ);
        NavuContext context = new NavuContext(maapi, th);

        // Get device profile
        String deviceProfile = nedname;
        String p = "/ncs:devices/device{"+this.device_id+"}/device-profile";
        try {
            if (maapi.exists(th, p)) {
                deviceProfile = ConfValue.getStringByValue(p, maapi.getElem(th, p));
            }
        } catch (MaapiException ignore) { }
        traceInfo("device-profile = " + deviceProfile);

        // Base roots
        NavuContainer global = new NavuContainer(context)
            .container(Ncs.hash)
            .container(Ncs._devices_)
            .container("ncs", "global-settings");
        NavuContainer profile = new NavuContainer(context)
            .container(Ncs.hash)
            .container(Ncs._devices_)
            .container("ncs", "profiles")
            .list(Ncs._profile_)
            .elem(new ConfKey(new ConfBuf(deviceProfile)));
        NavuContainer device= new NavuContainer(context)
            .container(Ncs.hash)
            .container(Ncs._devices_)
            .list(Ncs._device_)
            .elem(new ConfKey(new ConfBuf(this.device_id)));
        NavuContainer[] settings = {global, profile, device };

        // Read aliases and script with name 'scriptname'
        NavuContainer script = null;
        for (NavuContainer s : settings ) {
            if (s == null)
                continue;
            script = s.container("ncs", "ned-settings")
                .container("loginscripts", "loginscripts")
                .list("loginscripts", "script").elem(scriptname);
            if (script == null)
                continue;

            traceVerbose("loading script: "+scriptname);
            found = true;

            // Read aliases
            NavuList navualias = script.list("loginscripts", "alias");
            for (NavuContainer a : navualias.elements()) {
                String name = a.leaf("name").valueAsString();
                String value = a.leaf("value").valueAsString();
                addAlias(worker, scriptname, name, value);
            }

            // Read script
            NavuList navuaction = script.list("loginscripts", "id");
            for (NavuContainer a : navuaction.elements()) {
                // id and state
                int id = navuLeafGetInt(a.leaf("id"), 0);
                int state = navuLeafGetInt(a.leaf("state"), 0);
                // expect
                String expect = a.leaf("expect").valueAsString();
                // action
                String action = null;
                String actionValue = null;
                List<NavuNode> actionChoice = a.getSelectCaseAsNavuNode("action-choice");
                if (actionChoice != null) {
                    action = actionChoice.get(0).getName();
                    if (action.contains("send") || action.contains("fail"))
                        actionValue = ((NavuLeaf)(actionChoice.get(0))).valueAsString();
                }
                // next-state
                int nextState = navuLeafGetInt(a.leaf("next-state"), state);

                // Add to cache
                addAction(worker,scriptname,id,state,expect,action,actionValue,nextState);
            }
        }
        return found;
    }


    /**
     * Add alias
     * @param
     * @return
     * @throws Exception
     */
    public void addAlias(NedWorker worker, String scriptname,
                         String name, String value) {
        if (value == null)
            return;
        this.worker = worker;
        aliases.put(scriptname+name, value);
        if (name.contains("password"))
            value = "*HIDDEN PASSWORD*";
        traceVerbose("["+scriptname+"] adding alias "+name+" = "+stringQuote(value));
    }


    /**
     * Add action
     * @param
     * @return
     * @throws Exception
     */
    public void addAction(NedWorker worker, String scriptname,
                          int id, int state,
                          String expect,
                          String action, String actionValue,
                          int nextState)
        throws Exception {
        this.worker = worker;

        // Must have expect or action
        if (expect == null && action == null)
            throw new Exception("LOGIN: missing expect or action in script="+scriptname+" id="+id);

        if (expect != null && action == null && state == nextState)
            throw new Exception("LOGIN: expect must result in an action or new state");

        // Verify action
        if (action != null) {
            action = action.trim();
            if (action.startsWith("send")) {
                if (actionValue == null)
                    throw new Exception("LOGIN: send must have a string to send");
            } else if (action.equals("end") || action.equals("connect")) {
                if (actionValue != null)
                    throw new Exception("LOGIN: end|connect must not have actionValue");
            } else if (action.equals("fail")) {
            } else {
                throw new Exception("LOGIN: unknown action: "+action);
            }
        }

        ArrayList<LoginAction> actionList = actions.get(scriptname+""+state);
        if (actionList == null) {
            actionList = new ArrayList<LoginAction>();
        }

        LoginAction act = new LoginAction(id,state,expect,action,actionValue,nextState);
        traceInfo("["+scriptname+"] adding "+actionToString(act));
        actionList.add(act);
        actions.put(scriptname+""+state, actionList);
    }


    /**
     * Add entry in loginscript
     * @param
     * @return
     * @throws Exception
     */
    public void add(NedWorker worker, String scriptname, String line)
        throws Exception {
        String orgline = line;

        //
        // alias
        //
        if (line.trim().startsWith("alias ")) {
            Pattern p = Pattern.compile("alias (\\S+) (.+)");
            Matcher m = p.matcher(line);
            if (m.find() == false)
                throw new Exception("LOGIN: malformed add("+scriptname+"): "+stringQuote(orgline));
            String name = m.group(1);
            String value = m.group(2);
            addAlias(worker,scriptname,name,value);
            return;
        }

        //
        // action
        //

        // id and state
        Pattern p = Pattern.compile("id[ ]+(\\d+)[ ]+state[ ]+(\\d+)");
        Matcher m = p.matcher(line);
        if (m.find() == false)
            throw new Exception("LOGIN: malformed add("+scriptname+"): "+stringQuote(orgline));
        int id = Integer.parseInt(m.group(1));
        int state = Integer.parseInt(m.group(2));
        line = line.substring(m.end(2));

        // Note: after initial mandatory id and state, parse backwards

        // next-state
        int nextState = state;
        p = Pattern.compile("( next-state[ ]+(\\d+))");
        m = p.matcher(line);
        if (m.find()) {
            nextState = Integer.parseInt(m.group(2));
            line = line.substring(0, m.start(1));
        }

        // action & actionValue
        String action = null;
        String actionValue = null;
        p = Pattern.compile(" (send(?:\\-noecho)?|fail|connect|end)[ ]?(.+)?", Pattern.DOTALL);
        m = p.matcher(line);
        if (m.find()) {
            action = m.group(1);
            actionValue = m.group(2);
            line = line.substring(0, m.start(1)-1);
        }

        // expect
        String expect = null;
        p = Pattern.compile("(expect (.+))");
        m = p.matcher(line);
        if (m.find()) {
            expect = m.group(2);
            line = line.substring(0, m.start(1));
        }

        if (line.trim().isEmpty() == false)
            throw new Exception("LOGIN: malformed add("+scriptname+"): "+stringQuote(orgline));

        // Add action
        addAction(worker,scriptname,id,state,expect,action,actionValue,nextState);
    }


    /**
     * Connect to device using loginscript
     * @param
     * @return
     * @throws Exception
     */
    public CliSession connect(NedWorker worker, String scriptname, int expectTimeout)
        throws Exception {
        this.worker = worker;

        // Add device data as aliases
        addAlias(worker,scriptname,"remote-name", owner.ruser);
        addAlias(worker,scriptname,"remote-password", owner.pass);
        if (owner.secpass != null && owner.secpass.isEmpty() == false)
            addAlias(worker,scriptname,"remote-secondary-password", owner.secpass);

        traceInfo("Connecting using '"+scriptname+"' script:\n"+scriptToString(scriptname));

        this.state = 0;
        for (;;) {

            // Get action list
            ArrayList<LoginAction> actionList = actions.get(scriptname+""+this.state);
            if (actionList == null || actions.size() == 0)
                throw new Exception("LOGIN: missing script or state "+this.state+" in script: "+scriptname);

            // Check if we have an expect in this state
            int numexpect = 0;
            for (int a = 0; a < actionList.size(); a++) {
                LoginAction action = actionList.get(a);
                if (action.expect != null)
                    numexpect++;
            }

            // expect action
            LoginAction action = null;
            String match = "ERROR";
            if (numexpect > 0) {
                Pattern[] patterns = new Pattern[numexpect];
                numexpect = 0;
                String patbuf = "";
                for (int a = 0; a < actionList.size(); a++) {
                    action = actionList.get(a);
                    if (action.expect == null)
                        continue;
                    String expect = insertAlias(scriptname, action.expect);
                    patbuf += (" "+stringQuote(expect));
                    patterns[numexpect++] = Pattern.compile(expect);
                }

                // Wait for input from device
                traceInfo("waiting for input from device, "+numexpect+" pattern(s):"+patbuf);
                NedExpectResult res;
                try {
                    res = session.expect(patterns, false, expectTimeout, worker);
                } catch (Exception e) {
                    // Possibly a timeout, try return the input data from expect
                    res = session.expect(new Pattern[] { Pattern.compile(".*", Pattern.DOTALL) }, true, 0);
                    if (res.getMatch().trim().isEmpty())
                        throw new NedException("LOGIN: "+e.getMessage()+", no response from device");
                    else
                        throw new NedException("LOGIN: "+e.getMessage()+", blocked on "+stringQuote(res.getMatch()));
                }

                // Matched, find action
                int index = res.getHit();
                match = res.getMatch();
                if (res.getText().trim().isEmpty() == false)
                    traceVerbose("expect ignored("+index+"): "+stringQuote(res.getText()));
                traceVerbose("expect matched("+index+"): "+stringQuote(match));
                for (int a = 0; a < actionList.size(); a++) {
                    action = actionList.get(a);
                    if (action.expect == null)
                        continue;
                    if (index-- == 0) {
                        String expect = insertAlias(scriptname, action.expect);
                        //traceVerbose("matched expect = "+stringQuote(expect));
                        break;
                    }
                }
            }

            // expect == null
            else {
                action = actionList.get(0);
            }

            traceInfo(actionToString(action));

            // action
            if (action.action != null) {

                // end action
                if (action.action.equals("end")) {
                    traceInfo("successfully logged in");
                    return this.session;
                }

                // connect action
                else if (action.action.equals("connect")) {
                    connectDevice();
                }

                // send action
                else if (action.action.startsWith("send")) {
                    String cmd = insertAlias(scriptname, action.actionValue);
                    traceVerbose("SENDING " + stringQuote(cmd));
                    session.print(cmd);
                    String echo = cmd.trim();
                    if (action.action.equals("send") && echo.isEmpty() == false) {
                        traceVerbose("Waiting for echo");
                        session.expect(new String[] { Pattern.quote(echo) }, worker);
                    }
                }

                // fail action
                if (action.action.equals("fail")) {
                    if (action.actionValue != null && action.actionValue.isEmpty() == false)
                        throw new Exception("LOGIN: "+action.actionValue);
                    else
                        throw new Exception("LOGIN: "+stringQuote(match));
                }

            }

            // update state
            if (action.nextState != this.state) {
                traceVerbose("new state = "+action.nextState);
                this.state = action.nextState;
            }
        }
    }
}
