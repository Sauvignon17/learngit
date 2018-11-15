package com.cisco.cmb.sdwan.model;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import com.cisco.cmb.sdwan.namespaces.ciscoNsoCmbSdwanSrTe;
import com.tailf.conf.ConfBuf;
import com.tailf.conf.ConfKey;
import com.tailf.conf.ConfObject;
import com.tailf.navu.NavuException;
import com.tailf.navu.NavuListEntry;

public class ExplictPath {
	private NavuListEntry context;

	public ExplictPath(NavuListEntry context) {
		super();
		this.context = context;
	}

	public String name() throws NavuException {
		return context.leaf(ciscoNsoCmbSdwanSrTe._name_).valueAsString();
	}

	public String pathOptionIndex() throws NavuException {
		return context.leaf(ciscoNsoCmbSdwanSrTe._path_option_index_).valueAsString();
	}

	protected Map<String, Hop> listHop = new HashMap<>();

	public Hop hop(ConfKey key) throws NavuException {
		fillExplictPathlistCache();
		return listHop.get(key.toString());
	}

	public Hop hop(String key) throws NavuException {
		return hop(new ConfKey(new ConfObject[] { new ConfBuf(key) }));
	}

	public Collection<Hop> hopList() throws NavuException {
		fillExplictPathlistCache();
		return listHop.values();
	}

	public void resetExplictPathListCache() {
		listHop.clear();
	}

	private void fillExplictPathlistCache() throws NavuException {
		if (listHop.isEmpty()) {
			for (Entry<ConfKey, NavuListEntry> hopEntry : context.container(ciscoNsoCmbSdwanSrTe._hops_)
					.list(ciscoNsoCmbSdwanSrTe._hop_).entrySet()) {
				listHop.put(hopEntry.getKey().toString(), new Hop(hopEntry.getValue()));

			}
		}
	}
}
