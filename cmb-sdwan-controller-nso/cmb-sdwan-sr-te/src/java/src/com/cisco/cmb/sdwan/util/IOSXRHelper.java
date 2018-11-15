package com.cisco.cmb.sdwan.util;

import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.log4j.Logger;

import com.tailf.navu.NavuContainer;
import com.tailf.navu.NavuException;
import com.tailf.navu.NavuList;
import com.tailf.navu.NavuNode;

public class IOSXRHelper {
	private static final Logger LOGGER = Logger.getLogger(IOSXRHelper.class);
	private static final String NS = "cisco-ios-xr";
	private NavuNode root;

	private NavuContainer device;
	private String name;

	public IOSXRHelper(NavuNode root, String name) throws NavuException {
		super();
		this.root = root;
		this.name = name;
		this.device = root.container("devices").list("device").elem(name);
	}

	public boolean exists() throws NavuException {
		return device.exists();
	}

	public NavuList loopbacks() throws NavuException {
		return device.container("config").container(NS, "interface").list("Loopback");
	}

	public String minLoopbackId() throws NavuException {
		if (!exists()) {
			LOGGER.warn(String.format("Path not exists:{}", device.getKeyPath()));
		}
		NavuList loopbacks = device.container("config").container(NS, "interface").list("Loopback");
		Optional<Integer> minIdOpt = loopbacks.elements().stream().map(loopback -> {
			try {
				return Integer.valueOf(loopback.leaf("id").valueAsString());
			} catch (NumberFormatException | NavuException e) {
				LOGGER.warn(e);
				return null;
			}
		}).filter(id -> id != null).collect(Collectors.minBy(Integer::compare));

		if (minIdOpt.isPresent()) {
			return minIdOpt.get().toString();
		}
		return null;
	}

	public String minLocalDiscriminatorId() throws NavuException {
		if (!exists()) {
			LOGGER.warn(String.format("Path not exists:{}", device.getKeyPath()));
		}

		NavuList idList = device.container("config").container(NS, "sbfd").container("local-discriminator")
				.list("id-list");
		Optional<Long> minIdOpt = idList.elements().stream().map(id -> {
			try {
				return Long.valueOf(id.leaf("id").valueAsString());
			} catch (NumberFormatException | NavuException e) {
				LOGGER.warn(e);
				return null;
			}
		}).filter(id -> id != null).collect(Collectors.minBy(Long::compare));

		if (minIdOpt.isPresent()) {
			return minIdOpt.get().toString();
		}
		return null;
	}
}
