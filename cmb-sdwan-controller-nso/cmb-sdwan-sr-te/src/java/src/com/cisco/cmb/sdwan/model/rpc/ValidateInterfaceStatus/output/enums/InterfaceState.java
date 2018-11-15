package com.cisco.cmb.sdwan.model.rpc.ValidateInterfaceStatus.output.enums;

import com.tailf.conf.ConfEnumeration;
import com.tailf.conf.ConfException;

public enum InterfaceState {
	OK("ok"), NG("ng"), UNKNOWN("unknown");

	private final String str;

	InterfaceState(final String str) {
		this.str = str;
	}

	static public InterfaceState fromString(final String text) throws IllegalArgumentException {
		if (text != null) {
			for (InterfaceState b : InterfaceState.values()) {
				if (text.equalsIgnoreCase(b.toString())) {
					return b;
				}
			}
		}
		throw new IllegalArgumentException(text + " is not a valid argument");
	}

	@Override
	public String toString() {
		return str;
	}

	public ConfEnumeration confEnumeration() throws ConfException {
		return new ConfEnumeration(this.ordinal());
	}
}
