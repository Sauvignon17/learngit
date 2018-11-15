package com.cisco.cmb.sdwan.model;

import com.cisco.cmb.sdwan.namespaces.ciscoNsoCmbSdwanSrTe;
import com.tailf.navu.NavuContainer;
import com.tailf.navu.NavuException;

public class Bfd {
	private NavuContainer bfd;

	public Bfd(NavuContainer bfd) {
		super();
		this.bfd = bfd;
	}

	public boolean exists() throws NavuException {
		return bfd.exists();
	}

	public String multiplier() throws NavuException {
		return bfd.leaf(ciscoNsoCmbSdwanSrTe._multiplier_).valueAsString();

	}

	public String minimumInterval() throws NavuException {
		return bfd.leaf(ciscoNsoCmbSdwanSrTe._minimum_interval_).valueAsString();
	}
}
