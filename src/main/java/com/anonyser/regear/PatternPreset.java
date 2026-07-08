package com.anonyser.regear;

import java.util.ArrayList;
import java.util.List;

/**
 * How the visible lanes of a list are laid out in the bank, relative to the list's anchor slot.
 * Every preset except {@link #CUSTOM} derives its lane offsets from the visible item count; CUSTOM
 * reads them from the list's own {@code customOffsets}. Offsets are pure data (columns/rows), which
 * keeps this class client-free and unit-testable.
 */
enum PatternPreset
{
	/** One position; the single visible item advances through the whole list in place. */
	SINGLE("Single spot"),
	/** A straight column: (0,0),(0,1),(0,2),(0,3). */
	VERTICAL("Vertical line"),
	/** A compact block filling column 0 top-to-bottom then column 1: good for 4-way switches. */
	Z("Z pattern"),
	/** Lane offsets come from the list's custom pattern editor. */
	CUSTOM("Custom");

	private final String label;

	PatternPreset(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}

	/**
	 * The lane offsets for a preset at the given visible item count (1..4). CUSTOM returns an empty
	 * list here — callers must use the list's stored custom offsets instead.
	 */
	List<PatternOffset> offsetsFor(int visibleCount)
	{
		final int n = Math.max(1, Math.min(6, visibleCount));
		final List<PatternOffset> out = new ArrayList<>();
		switch (this)
		{
			case SINGLE:
				out.add(new PatternOffset(0, 0));
				break;
			case VERTICAL:
				for (int i = 0; i < n; i++)
				{
					out.add(new PatternOffset(0, i));
				}
				break;
			case Z:
				// Fill column 0 top-to-bottom, then column 1 top-to-bottom:
				// 1 item -> (0,0); 2 -> +(0,1); 3 -> +(1,0); 4 -> +(1,1).
				for (int i = 0; i < n; i++)
				{
					out.add(new PatternOffset(i / 2, i % 2));
				}
				break;
			case CUSTOM:
			default:
				break;
		}
		return out;
	}
}
