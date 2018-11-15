package com.cisco.cmb.sdwan.util;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Map;

import org.apache.log4j.Logger;

import com.tailf.conf.Conf;
import com.tailf.conf.ConfException;
import com.tailf.conf.ConfIP;
import com.tailf.dp.DpUserInfo;
import com.tailf.maapi.Maapi;
import com.tailf.maapi.MaapiUserSessionFlag;
import com.tailf.ncs.NcsMain;

public class MaapiWrapper implements Closeable {
	private static final Logger logger = Logger.getLogger(MaapiWrapper.class);
	private Maapi maapi;
	private int readTransactionId = -1;
	private int writeTransactionId = -1;

	public MaapiWrapper() {
		maapi = newSystemMaapi();
	}

	public MaapiWrapper(DpUserInfo userInfo) {
		maapi = newUserSessionMaapi(userInfo);
	}

	public MaapiWrapper startReadTransaction() {
		if (readTransactionId == -1) {
			try {
				readTransactionId = maapi.startTrans(Conf.DB_RUNNING, Conf.MODE_READ);

				if (logger.isDebugEnabled())
					logger.debug("started read transaction " + readTransactionId + " for wrapper: " + this);
			} catch (IOException | ConfException e) {
				throw new IllegalStateException("Unable to start read transaction", e);
			}
		}
		return this;
	}

	public MaapiWrapper startWriteTransaction() {
		if (writeTransactionId == -1) {
			try {
				writeTransactionId = maapi.startTrans(Conf.DB_RUNNING, Conf.MODE_READ_WRITE);

				if (logger.isDebugEnabled())
					logger.debug("started write transaction " + writeTransactionId + " for wrapper: " + this);
			} catch (IOException | ConfException e) {
				throw new IllegalStateException("Unable to start write transaction", e);
			}
		}

		return this;
	}

	public void close() throws IOException {
		finishTransaction();
		maapi.getSocket().close();
	}

	/**
	 * Finish all started transactions for this wrapper.
	 * </p>
	 * It will also detach an external transaction when an internal Maapi is used.
	 *
	 * @return {@code this} for method chaining
	 */
	public MaapiWrapper finishTransaction() {
		finishReadTransaction();
		finishWriteTransaction();
		return this;
	}

	public int getReadTransactionId() {
		return readTransactionId;
	}

	public int getWriteTransactionId() {
		return writeTransactionId;
	}

	/**
	 * Calls {@link Maapi#finishTrans(int)} and resets the read transaction in the
	 * wrapper
	 */
	private void finishReadTransaction() {
		if (readTransactionId != -1) {
			try {
				maapi.finishTrans(readTransactionId);
			} catch (Exception e) {
				logger.error("Unable to finish read transaction transaction", e);
			}
			readTransactionId = -1;
		}
	}

	/**
	 * Calls {@link Maapi#finishTrans(int)} and resets the write transaction in the
	 * wrapper
	 */
	private void finishWriteTransaction() {
		if (writeTransactionId != -1) {
			try {
				maapi.finishTrans(writeTransactionId);
			} catch (Exception e) {
				logger.error("Unable to finish write transaction transaction", e);
			}
			writeTransactionId = -1;
		}
	}

	/**
	 * Get the underlying {@link Maapi} connection.
	 *
	 * @return {@link Maapi} connection
	 */
	public Maapi getMaapi() {
		return maapi;
	}

	public static Maapi newSystemMaapi() {
		String host = getNcsHost();
		int port = getNcsPort();

		Socket socket = null;
		try {
			socket = new Socket(host, port);
			Maapi maapi = new Maapi(socket);
			maapi.startUserSession("system", InetAddress.getByName(host), "system", new String[] { "system" },
					MaapiUserSessionFlag.PROTO_TCP);
			return maapi;
		} catch (Exception e) {
			CloseUtil.close(socket);
			throw new RuntimeException("Unable to create system maapi to host: " + host + ":" + port, e);
		}
	}

	public static Maapi newUserSessionMaapi(DpUserInfo userInfo) {
		String host = getNcsHost();
		int port = getNcsPort();
		Socket socket = null;
		try {
			socket = new Socket(host, port);
			Maapi maapi = new Maapi(socket);
			ConfIP ipAddress = (ConfIP) userInfo.getIPAddress();
			maapi.startUserSession(userInfo.getUserName(), ipAddress.getAddress(), userInfo.getContext(), new String[0],
					MaapiUserSessionFlag.valueOf(userInfo.getProtocol()));
			return maapi;
		} catch (Exception e) {
			CloseUtil.close(socket);
			throw new RuntimeException("Unable to create system maapi to host: " + host + ":" + port, e);
		}
	}

	public static String getNcsHost() {
		Map<String, String> environment = System.getenv();
		String host = null;
		if (environment.containsKey("NCS_HOST")) {
			host = environment.get("NCS_HOST");
		}
		if (!environment.containsKey("NCS_HOST") && System.getProperty("NCS_HOST") != null) {
			host = System.getProperty("NCS_HOST");
		}
		if (host == null) {
			NcsMain.getInstance().getNcsHost();
		}
		return host;
	}

	public static int getNcsPort() {
		Map<String, String> environment = System.getenv();
		int port = Conf.NCS_PORT;

		if (environment.containsKey("NCS_PORT")) {
			port = Integer.parseInt(environment.get("NCS_PORT"));
		}
		if (!environment.containsKey("NCS_PORT") && System.getProperty("NCS_PORT") != null) {
			port = Integer.parseInt(System.getProperty("NCS_PORT"));
		}

		if (port != NcsMain.getInstance().getNcsPort()) {
			port = NcsMain.getInstance().getNcsPort();
		}

		return port;
	}
}
