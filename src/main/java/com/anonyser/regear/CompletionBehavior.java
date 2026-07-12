package com.anonyser.regear;

/**
 * What a Regear lane does once it has advanced past the end of its list. Set once in the plugin
 * options and applied to every list; {@link #STOP} is the default.
 */
public enum CompletionBehavior
{
	/** Leave finished lanes empty; nothing more is shown. */
	STOP("Stop at end"),
	/** Wrap each finished lane back to its starting item and keep going. */
	LOOP("Loop to start"),
	/** Reset the whole sequence to the start whenever the bank closes. */
	RESET_ON_BANK_CLOSE("Reset on bank close"),
	/** Reset the whole sequence to the start on the next inventory change. */
	RESET_ON_INVENTORY_CHANGE("Reset on inv change"),
	/** Never auto-reset; only the panel's Reset button restarts the sequence. */
	MANUAL("Manual reset");

	private final String label;

	CompletionBehavior(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
