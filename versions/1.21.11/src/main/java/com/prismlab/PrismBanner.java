package com.prismlab;

/**
 * A main-source string that the 1.21.11 screen draws, so a main-source edit has something
 * the screenshot can show. Main classes live on the server side of the mod and are already
 * loaded by the time the client is up; editing {@link #label()} proves that {@code HotUi}
 * redefines loaded main classes on this node, not just the fresh-loaded client classes.
 */
public final class PrismBanner {
	public static final String DEFAULT = "Prism node online";

	private PrismBanner() {
	}

	public static String label() {
		return DEFAULT;
	}
}
