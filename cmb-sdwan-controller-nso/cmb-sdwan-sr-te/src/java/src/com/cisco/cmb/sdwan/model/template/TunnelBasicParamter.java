package com.cisco.cmb.sdwan.model.template;

import com.cisco.cmb.sdwan.util.TemplateParameterBase;

public class TunnelBasicParamter extends TemplateParameterBase {
	private String deviceName = "";
	private String tunnelId = "";
	private String tunnelLoopbackId = "";
	private String tunnelDestination = "";
	private String forwardClass = "";

	public TunnelBasicParamter(String templateName) {
		super(templateName);
	}

	public String getDeviceName() {
		return deviceName;
	}

	public void setDeviceName(String deviceName) {
		this.deviceName = deviceName;
	}

	public String getTunnelId() {
		return tunnelId;
	}

	public void setTunnelId(String tunnelId) {
		this.tunnelId = tunnelId;
	}

	public String getTunnelLoopbackId() {
		return tunnelLoopbackId;
	}

	public void setTunnelLoopbackId(String tunnelLoopbackId) {
		this.tunnelLoopbackId = tunnelLoopbackId;
	}

	public String getTunnelDestination() {
		return tunnelDestination;
	}

	public void setTunnelDestination(String tunnelDestination) {
		this.tunnelDestination = tunnelDestination;
	}

	public String getForwardClass() {
		return forwardClass;
	}

	public void setForwardClass(String forwardClass) {
		this.forwardClass = forwardClass;
	}

	@Override
	public void populate() {
		variables.putQuoted("DEVICE_NAME", deviceName);
		variables.putQuoted("TUNNEL_ID", tunnelId);
		variables.putQuoted("TUNNEL_LOOPBACK_ID", tunnelLoopbackId);
		variables.putQuoted("TUNNEL_DESTINATION", tunnelDestination);
		variables.putQuoted("FORWARD_CLASS", forwardClass);
	}
}
