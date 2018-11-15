package com.cisco.cmb.sdwan.model.rpc.ValidateInterfaceStatus.output;

import com.cisco.cmb.sdwan.util.Device.InterfaceStatus;

public class Interfaze {

	private String name;
	private String ipAddress;
	private String status;
	private String protocol;
	private boolean ok;

	public Interfaze(InterfaceStatus interfaceStatus) {
		super();
		this.name = interfaceStatus.getName();
		this.ipAddress = interfaceStatus.getIpAddress();
		this.status = interfaceStatus.getStatus();
		this.protocol = interfaceStatus.getProtocol();
		this.ok = interfaceStatus.isOk();
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getIpAddress() {
		return ipAddress;
	}

	public void setIpAddress(String ipAddress) {
		this.ipAddress = ipAddress;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public String getProtocol() {
		return protocol;
	}

	public void setProtocol(String protocol) {
		this.protocol = protocol;
	}

	public boolean isOk() {
		return ok;
	}

	public void setOk(boolean ok) {
		this.ok = ok;
	}
}
