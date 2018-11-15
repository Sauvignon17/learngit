package com.cisco.cmb.sdwan.model.template;

import com.cisco.cmb.sdwan.util.TemplateParameterBase;

public class ExplictPathParameter extends TemplateParameterBase {

	private String deviceName = "";
	private String explicitPathName = "";
	private String explicitPathIndex = "";
	private String explicitPathHopIp = "";

	public ExplictPathParameter(String templateName) {
		super(templateName);
	}

	public String getDeviceName() {
		return deviceName;
	}

	public void setDeviceName(String deviceName) {
		this.deviceName = deviceName;
	}

	public String getExplicitPathName() {
		return explicitPathName;
	}

	public void setExplicitPathName(String explicitPathName) {
		this.explicitPathName = explicitPathName;
	}

	public String getExplicitPathIndex() {
		return explicitPathIndex;
	}

	public void setExplicitPathIndex(String explicitPathIndex) {
		this.explicitPathIndex = explicitPathIndex;
	}

	public String getExplicitPathHopIp() {
		return explicitPathHopIp;
	}

	public void setExplicitPathHopIp(String explicitPathHopIp) {
		this.explicitPathHopIp = explicitPathHopIp;
	}

	@Override
	public void populate() {
		variables.putQuoted("DEVICE_NAME", deviceName);
		variables.putQuoted("EXPLICIT_PATH_NAME", explicitPathName);
		variables.putQuoted("EXPLICIT_PATH_INDEX", explicitPathIndex);
		variables.putQuoted("EXPLICIT_PATH_HOP_IP", explicitPathHopIp);
	}
}
