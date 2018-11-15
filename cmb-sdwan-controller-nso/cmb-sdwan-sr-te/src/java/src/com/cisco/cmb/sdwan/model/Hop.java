package com.cisco.cmb.sdwan.model;

import com.cisco.cmb.sdwan.namespaces.ciscoNsoCmbSdwanSrTe;
import com.tailf.navu.NavuException;
import com.tailf.navu.NavuListEntry;

public class Hop {
	private NavuListEntry hop;

	public Hop(NavuListEntry hop) {
		super();
		this.hop = hop;
	}

	public String index() throws NavuException {
		return hop.leaf(ciscoNsoCmbSdwanSrTe._index_).valueAsString();

	}

	public String ip() throws NavuException {
		return hop.leaf(ciscoNsoCmbSdwanSrTe._ip_).valueAsString();

	}
}
