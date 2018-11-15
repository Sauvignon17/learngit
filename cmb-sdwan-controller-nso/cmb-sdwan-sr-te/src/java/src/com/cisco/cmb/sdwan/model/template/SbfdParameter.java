package com.cisco.cmb.sdwan.model.template;

import com.cisco.cmb.sdwan.util.TemplateParameterBase;

public class SbfdParameter extends TemplateParameterBase {

	private String deviceName = "";
	private String sbfdRemoteTarget = "";
	private String sbfdRemoteDiscriminator = "";

	public SbfdParameter(String templateName) {
		super(templateName);
	}

	public String getDeviceName() {
		return deviceName;
	}

	public void setDeviceName(String deviceName) {
		this.deviceName = deviceName;
	}

	public String getSbfdRemoteTarget() {
		return sbfdRemoteTarget;
	}

	public void setSbfdRemoteTarget(String sbfdRemoteTarget) {
		this.sbfdRemoteTarget = sbfdRemoteTarget;
	}

	public String getSbfdRemoteDiscriminator() {
		return sbfdRemoteDiscriminator;
	}

	public void setSbfdRemoteDiscriminator(String sbfdRemoteDiscriminator) {
		this.sbfdRemoteDiscriminator = sbfdRemoteDiscriminator;
	}

	@Override
	public void populate() {
		variables.putQuoted("DEVICE_NAME", deviceName);
		variables.putQuoted("SBFD_REMOTE_TARGET", sbfdRemoteTarget);
		variables.putQuoted("SBFD_REMOTE_DISCRIMINATOR", sbfdRemoteDiscriminator);
	}
}
