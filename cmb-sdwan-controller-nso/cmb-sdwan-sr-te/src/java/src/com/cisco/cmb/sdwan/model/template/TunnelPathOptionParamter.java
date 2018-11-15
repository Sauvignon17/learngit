package com.cisco.cmb.sdwan.model.template;

import com.cisco.cmb.sdwan.util.TemplateParameterBase;

public class TunnelPathOptionParamter extends TemplateParameterBase {
	private String deviceName = "";
	private String tunnelId = "";
	private String tunnelPathOptionIndex = "";
	private String explicitPathName = "";

	public TunnelPathOptionParamter(String templateName) {
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

	public String getTunnelPathOptionIndex() {
		return tunnelPathOptionIndex;
	}

	public void setTunnelPathOptionIndex(String tunnelPathOptionIndex) {
		this.tunnelPathOptionIndex = tunnelPathOptionIndex;
	}

	public String getExplicitPathName() {
		return explicitPathName;
	}

	public void setExplicitPathName(String explicitPathName) {
		this.explicitPathName = explicitPathName;
	}

	@Override
	public void populate() {
		variables.putQuoted("DEVICE_NAME", deviceName);
		variables.putQuoted("TUNNEL_ID", tunnelId);
		variables.putQuoted("TUNNEL_PATH_OPTION_INDEX", tunnelPathOptionIndex);
		variables.putQuoted("EXPLICIT_PATH_NAME", explicitPathName);
	}
}
