package com.anonyser.regear;

/**
 * What dropping one item onto another does in the setup editor grid — the same two modes the
 * game's bank offers. Swap trades the two slots; Insert pulls the item out and pushes everything
 * between over by one.
 */
public enum DragMode
{
	SWAP("Swap"),
	INSERT("Insert");

	private final String label;

	DragMode(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
