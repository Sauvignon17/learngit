package com.cisco.cmb.sdwan.exception;

public class DataInvalidationException extends RuntimeException {

	private static final long serialVersionUID = 8366812658032652870L;

	public DataInvalidationException() {
		super();
	}

	public DataInvalidationException(String message, Throwable cause, boolean enableSuppression,
			boolean writableStackTrace) {
		super(message, cause, enableSuppression, writableStackTrace);
	}

	public DataInvalidationException(String message, Throwable cause) {
		super(message, cause);
	}

	public DataInvalidationException(String message) {
		super(message);
	}

	public DataInvalidationException(Throwable cause) {
		super(cause);
	}

}
