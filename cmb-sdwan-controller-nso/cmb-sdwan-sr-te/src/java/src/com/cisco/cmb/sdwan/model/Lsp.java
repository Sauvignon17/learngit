package com.cisco.cmb.sdwan.model;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.log4j.Logger;

import com.cisco.cmb.sdwan.namespaces.ciscoNsoCmbSdwanSrTe;
import com.tailf.conf.ConfBuf;
import com.tailf.conf.ConfKey;
import com.tailf.conf.ConfObject;
import com.tailf.navu.NavuException;
import com.tailf.navu.NavuListEntry;
import com.tailf.navu.NavuNode;

public class Lsp {
	private static final Logger LOGGER = Logger.getLogger(Lsp.class);

	private NavuNode service;

	public Lsp(NavuNode service) {
		super();
		this.service = service;
	}

	public String id() throws NavuException {
		return service.leaf(ciscoNsoCmbSdwanSrTe._id_).valueAsString();
	}

	public String source() throws NavuException {
		return service.getParent().getParent().getParent().leaf("name").valueAsString();
	}

	public String sourceLoopbackId() throws NavuException {
		return service.container(ciscoNsoCmbSdwanSrTe._loopback_ids_).leaf(ciscoNsoCmbSdwanSrTe._source_loopback_id_)
				.valueAsString();
	}

	public String forwardClass() throws NavuException {
		return service.leaf(ciscoNsoCmbSdwanSrTe._forward_class_).valueAsString();
	}

	public Bfd bfd() throws NavuException {
		return new Bfd(service.container(ciscoNsoCmbSdwanSrTe._bfd_));
	}

	public String destination() throws NavuException {
		return service.leaf(ciscoNsoCmbSdwanSrTe._destination_).valueAsString();
	}

	public String destinationLoopbackId() throws NavuException {
		return service.container(ciscoNsoCmbSdwanSrTe._loopback_ids_)
				.leaf(ciscoNsoCmbSdwanSrTe._destination_loopback_id_).valueAsString();
	}

	protected Map<String, ExplictPath> listExplictPath = new HashMap<>();

	public ExplictPath explictPath(ConfKey key) throws NavuException {
		fillExplictPathlistCache();
		return listExplictPath.get(key.toString());
	}

	public ExplictPath explictPath(String key) throws NavuException {
		return explictPath(new ConfKey(new ConfObject[] { new ConfBuf(key) }));
	}

	public Collection<ExplictPath> explicitPathList() throws NavuException {
		fillExplictPathlistCache();
		return listExplictPath.values();
	}

	public void resetExplictPathListCache() {
		listExplictPath.clear();
	}

	private void fillExplictPathlistCache() throws NavuException {
		if (listExplictPath.isEmpty()) {
			for (Entry<ConfKey, NavuListEntry> explicitPathEntry : service
					.container(ciscoNsoCmbSdwanSrTe._explicit_paths_).list(ciscoNsoCmbSdwanSrTe._explict_path_)
					.entrySet()) {
				listExplictPath.put(explicitPathEntry.getKey().toString(),
						new ExplictPath(explicitPathEntry.getValue()));

			}
		}
	}
}
