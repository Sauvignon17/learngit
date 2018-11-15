package com.cisco.cmb.sdwan.model.template;

import com.cisco.cmb.sdwan.util.TemplateParameterBase;

public class TunnelBfdParamter extends TemplateParameterBase {

	private String deviceName = "";
	private String tunnelId = "";
	private String tunnelBfdMutiplier = "";
	private String tunnelBfdMinimumInterval = "";

	public TunnelBfdParamter(String templateName) {
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

	public String getTunnelBfdMutiplier() {
		return tunnelBfdMutiplier;
	}

	public void setTunnelBfdMutiplier(String tunnelBfdMutiplier) {
		this.tunnelBfdMutiplier = tunnelBfdMutiplier;
	}

	public String getTunnelBfdMinimumInterval() {
		return tunnelBfdMinimumInterval;
	}

	public void setTunnelBfdMinimumInterval(String tunnelBfdMinimumInterval) {
		this.tunnelBfdMinimumInterval = tunnelBfdMinimumInterval;
	}

	@Override
	public void populate() {
		variables.putQuoted("DEVICE_NAME", deviceName);
		variables.putQuoted("TUNNEL_ID", tunnelId);
		variables.putQuoted("TUNNEL_BFD_MUTIPLIER", tunnelBfdMutiplier);
		variables.putQuoted("TUNNEL_BFD_MINIMUM_INTERVAL", tunnelBfdMinimumInterval);
	}
}
