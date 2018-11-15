package com.cisco.cmb.sdwan.util.Device;

public class InterfaceStatus {
	protected String name;
	protected String ipAddress;
	protected String status;
	protected String protocol;
	protected boolean ok;

	public InterfaceStatus(String name, String ipAddress, String status, String protocol) {
		super();
		this.name = name;
		this.ipAddress = ipAddress;
		this.status = status;
		this.protocol = protocol;
		this.ok = "up".equalsIgnoreCase(status) && "up".equalsIgnoreCase(protocol) ? true : false;
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
