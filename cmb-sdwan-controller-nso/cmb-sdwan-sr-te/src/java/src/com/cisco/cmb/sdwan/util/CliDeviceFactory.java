package com.cisco.cmb.sdwan.util;

import java.util.HashSet;

import org.apache.log4j.Logger;

import com.cisco.cmb.sdwan.util.Device.CliDevice;
import com.cisco.cmb.sdwan.util.Device.IosDevice;
import com.cisco.cmb.sdwan.util.Device.IosxrDevice;
import com.tailf.conf.ConfPath;
import com.tailf.navu.NavuContainer;
import com.tailf.navu.NavuContext;
import com.tailf.navu.NavuException;
import com.tailf.navu.NavuLeaf;

public class CliDeviceFactory {
	private static final Logger LOGGER = Logger.getLogger(CliDeviceFactory.class);

	private static final String iosxrNedId = "cisco-ios-xr-id:cisco-ios-xr";
	private static final String iosNedId = "ios-id:cisco-ios";
	private static HashSet<String> supportNedType = new HashSet<String>();

	static {
		supportNedType.add(iosxrNedId);
		supportNedType.add(iosNedId);
	}

	public static String getDeviceNedId(String deviceName) {
		LOGGER.debug(String.format("Started get device %s information", deviceName));
		try (MaapiWrapper wrapper = new MaapiWrapper()) {
			wrapper.startReadTransaction();
			NavuContainer root = new NavuContainer(new NavuContext(wrapper.getMaapi(), wrapper.getReadTransactionId()));
			try {
				root.getNavuNode(new ConfPath("/ncs:devices/device{%s}", deviceName));
			} catch (NavuException e) {
				throw new IllegalStateException(String.format("Device %s not exists in nso.", deviceName));
			}

			String nedIdStr = ((NavuLeaf) root
					.getNavuNode(new ConfPath("/ncs:devices/device{%s}/device-type/cli/ned-id", deviceName)))
							.valueAsString();
			LOGGER.debug(String.format("Finished get device %s information", deviceName));
			return nedIdStr;
		} catch (Exception e) {
			LOGGER.error(String.format("Failed validate device %s information", deviceName), e);
			throw new IllegalStateException(e.getMessage(), e);
		}
	}

	public static CliDevice getCliDevice(String deviceName) {
		String nedId = getDeviceNedId(deviceName);
		if (nedId == null || !supportNedType.contains(nedId)) {
			throw new IllegalStateException("Not supported for device type.");
		}
		if (iosxrNedId.equals(nedId)) {
			return new IosxrDevice(deviceName);
		} else {
			return new IosDevice(deviceName);
		}
	}

}
