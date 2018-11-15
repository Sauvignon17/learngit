package com.cisco.cmb.sdwan.util.Device;

import java.util.List;

public interface CliDevice {
	boolean exists();

	String runShowInterfaceBriefCmd();

	String runCommand(String command);

	List<InterfaceStatus> parseShowIpInterfaceBriefResult(String result);

	List<InterfaceStatus> getAllInterfacesStatus();
}
