package com.cisco.cmb.sdwan.model.rpc.ValidateInterfaceStatus.input;

import java.util.ArrayList;
import java.util.List;

public class InputDevice {

	private String name;
	private List<String> interfaces = new ArrayList<String>();

	public InputDevice() {
		super();
	}

	public InputDevice(String name, List<String> interfaces) {
		super();
		this.name = name;
		this.interfaces = interfaces;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public List<String> getInterfaces() {
		return interfaces;
	}

	public void setInterfaces(List<String> interfaces) {
		this.interfaces = interfaces;
	}
}
