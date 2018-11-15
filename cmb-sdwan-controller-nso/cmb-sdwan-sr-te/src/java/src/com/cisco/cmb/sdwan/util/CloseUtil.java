package com.cisco.cmb.sdwan.util;

import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;

import org.apache.log4j.Logger;

import com.tailf.maapi.Maapi;

/**
 * Static utility methods for closing resources such as sockets that suppresses
 * and logs exceptions.
 * <p/>
 * <p/>
 * Primary used in JDK6.
 *
 * @author jesper.sigarden@dataductus.se
 * @version 1.0
 */
public class CloseUtil {
	private final static Logger logger = Logger.getLogger(CloseUtil.class);

	/**
	 * Close the maapi socket
	 *
	 * @param maapi
	 *            maapi's socket to close
	 */
	public static void close(Maapi maapi) {
		if (maapi != null)
			close(maapi.getSocket());
	}

	/**
	 * Close the object but suppress and log any exceptions.
	 *
	 * @param closeable
	 *            object to close
	 */
	public static void close(Closeable closeable) {
		if (closeable == null) {
			return;
		}

		try {
			closeable.close();
		} catch (IOException e) {
			String msg = "Suppressed exception while closing " + closeable;
			logger.error(msg, e);
		}
	}

	/**
	 * Close the socket but suppress and log any exceptions.
	 *
	 * @param socket
	 *            socket to close
	 */
	public static void close(Socket socket) {
		if (socket == null) {
			return;
		}

		try {
			socket.close();
		} catch (IOException e) {
			String msg = "Suppressed exception while closing socket: " + socket;
			logger.error(msg, e);
		}
	}
}
