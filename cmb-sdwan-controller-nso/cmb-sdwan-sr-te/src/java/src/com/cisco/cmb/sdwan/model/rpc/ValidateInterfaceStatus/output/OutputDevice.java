package com.cisco.cmb.sdwan.model.rpc.ValidateInterfaceStatus.output;

import java.util.ArrayList;
import java.util.List;

public class OutputDevice {
	private String name;
	private String info;

	private List<Interfaze> interfaces = new ArrayList<Interfaze>();

	public OutputDevice() {
		super();
	}

	public OutputDevice(String name) {
		super();
		this.name = name;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public List<Interfaze> getInterfaces() {
		return interfaces;
	}

	public void setInterfaces(List<Interfaze> interfaces) {
		this.interfaces = interfaces;
	}

	public String getInfo() {
		return info;
	}

	public void setInfo(String info) {
		this.info = info;
	}

}
