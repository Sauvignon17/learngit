package com.cisco.cmb.sdwan.util.Device;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;

import com.cisco.cmb.sdwan.util.MaapiWrapper;
import com.tailf.conf.ConfBuf;
import com.tailf.conf.ConfList;
import com.tailf.conf.ConfObject;
import com.tailf.conf.ConfPath;
import com.tailf.conf.ConfXMLParam;
import com.tailf.conf.ConfXMLParamValue;
import com.tailf.navu.NavuAction;
import com.tailf.navu.NavuContainer;
import com.tailf.navu.NavuContext;
import com.tailf.navu.NavuException;
import com.tailf.navu.NavuNode;
import com.tailf.ncs.ns.Ncs;

public class IosDevice implements CliDevice {
	private static final Logger LOGGER = Logger.getLogger(IosDevice.class);

	private static final String STATUS_PREFIX = "ios-stats";
	private static final String CHECK_INTERFACE_STATUS_CMD = "show ip int bri";
	private static final Pattern CHECK_INTERFACE_STATUS_PATTERN = Pattern
			.compile("(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)");
	private String name;

	public IosDevice(String name) {
		super();
		this.name = name;
	}

	@Override
	public boolean exists() {
		try (MaapiWrapper wrapper = new MaapiWrapper()) {
			wrapper.startReadTransaction();
			NavuContainer root = new NavuContainer(new NavuContext(wrapper.getMaapi(), wrapper.getReadTransactionId()));
			try {
				root.getNavuNode(new ConfPath("/ncs:devices/device{%s}", name));
			} catch (NavuException e) {
				return false;
			}
			return true;
		} catch (Exception e) {
			throw new IllegalStateException(e.getMessage(), e);
		}
	}

	@Override
	public String runShowInterfaceBriefCmd() {
		return runCommand(CHECK_INTERFACE_STATUS_CMD);
	}

	@Override
	public List<InterfaceStatus> parseShowIpInterfaceBriefResult(String result) {
		List<InterfaceStatus> is = new ArrayList<InterfaceStatus>();
		for (String line : result.split(System.lineSeparator())) {
			Matcher matcher = CHECK_INTERFACE_STATUS_PATTERN.matcher(line);
			if (matcher.find()) {
				InterfaceStatus i = new InterfaceStatus(matcher.group(1), matcher.group(2), matcher.group(5),
						matcher.group(6));
				is.add(i);
			}
		}

		return is;
	}

	@Override
	public List<InterfaceStatus> getAllInterfacesStatus() {
		String result = runShowInterfaceBriefCmd();
		LOGGER.debug(String.format("Started parse result from device %s,%sresult:%s ", name, System.lineSeparator(),
				result));
		return parseShowIpInterfaceBriefResult(result);
	}

	@Override
	public String runCommand(String command) {
		LOGGER.debug(String.format("Started run command '%s' on device %s", command, name));
		try (MaapiWrapper wrapper = new MaapiWrapper()) {
			wrapper.startReadTransaction();
			NavuContainer root = new NavuContainer(new NavuContext(wrapper.getMaapi(), wrapper.getReadTransactionId()));
			NavuNode device = root.getNavuNode(new ConfPath("/ncs:devices/device{%s}", name));
			NavuAction anyPath = device.container(Ncs._live_status_).container(STATUS_PREFIX, "exec").action("any");
			ConfXMLParam[] params = new ConfXMLParam[] { new ConfXMLParamValue(STATUS_PREFIX, "args",
					new ConfList(new ConfObject[] { new ConfBuf(command) })) };
			ConfXMLParam[] response = anyPath.call(params);
			String result = response[0].getValue().toString();
			LOGGER.debug(String.format("Finished run command '%s' on device %s", command, name));
			return result;
		} catch (Exception e) {
			LOGGER.error(String.format("Failed run command '%s' on device %s", command, name), e);
			throw new IllegalStateException(e.getMessage(), e);
		}
	}

}
