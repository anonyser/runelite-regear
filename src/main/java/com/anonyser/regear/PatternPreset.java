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
	/** A straight column: (0,0),(0,1),(0,2),... */
	VERTICAL("Vertical line"),
	/**
	 * A compact block of two-tall column pairs marching right; when the next pair would run off the
	 * bank's right edge it wraps to a fresh two-row band below, so any count up to 28 fits.
	 */
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
	 * The lane offsets for a preset at the given visible item count (1..28). CUSTOM returns an empty
	 * list here — callers must use the list's stored custom offsets instead.
	 *
	 * @param availableCols columns from the anchor to the bank's right edge; Z wraps to a new
	 *                      two-row band instead of marching past it. Pass the full bank width when
	 *                      the anchor is at column 0.
	 */
	List<PatternOffset> offsetsFor(int visibleCount, int availableCols)
	{
		final int n = Math.max(1, Math.min(28, visibleCount));
		final int cols = Math.max(1, availableCols);
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
				// Two-tall column pairs marching right: (0,0),(0,1),(1,0),(1,1),... — and when a
				// pair would pass the bank's right edge, wrap to a fresh two-row band underneath.
				for (int i = 0; i < n; i++)
				{
					final int pair = i / 2;
					final int band = pair / cols;
					out.add(new PatternOffset(pair % cols, band * 2 + i % 2));
				}
				break;
			case CUSTOM:
			default:
				break;
		}
		return out;
	}
}
