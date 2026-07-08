package com.anonyser.regear;

import java.util.ArrayList;
import java.util.List;

/**
 * State for the interactive on-bank tutorial. It is client-free: the plugin fills it with example
 * item ids read from whatever bank is open, the overlay draws it, and the plugin's mouse handler
 * advances it. Example items are chosen per session, so it works on any account -- gear items when
 * the bank has at least three equippable items, otherwise it falls back to a plain inventory demo.
 */
class RegearTutorial
{
	static final int ANCHOR_SLOT = 9; // col 1, row 1 -- leaves room for the widest pattern

	static final class Step
	{
		final String title;
		final String instruction;
		final PatternPreset pattern; // null = the closing notes step
		final int count;

		Step(String title, String instruction, PatternPreset pattern, int count)
		{
			this.title = title;
			this.instruction = instruction;
			this.pattern = pattern;
			this.count = count;
		}
	}

	private final List<Step> steps = new ArrayList<>();
	private List<Integer> items = new ArrayList<>();
	private boolean gearMode;
	private int stepIndex;
	private int clicked; // green boxes clicked so far in the current step

	/** Build the step list from the example items. Needs at least 2 items to show anything useful. */
	void start(boolean gearMode, List<Integer> exampleItems)
	{
		this.gearMode = gearMode;
		this.items = new ArrayList<>(exampleItems);
		steps.clear();
		stepIndex = 0;
		clicked = 0;
		final int n = items.size();
		if (n < 2)
		{
			return; // nothing to demonstrate; the overlay shows an "add a few items" note
		}
		final String kind = gearMode ? "Gear setup" : "Inventory setup";
		if (n >= 3)
		{
			steps.add(new Step(kind + " — Z pattern",
				"Click the green boxes 1 to " + Math.min(4, n) + " in order.", PatternPreset.Z, Math.min(4, n)));
			steps.add(new Step("Single spot",
				"One slot: you click the same spot and it advances to the next item.", PatternPreset.SINGLE, 1));
			steps.add(new Step("Vertical line",
				"A straight column, top to bottom.", PatternPreset.VERTICAL, Math.min(3, n)));
		}
		steps.add(new Step("Full inventory — Z pattern",
			"A whole setup rotates through these slots. Click the green boxes in order.",
			PatternPreset.Z, Math.min(6, n)));
		steps.add(new Step("Done!", null, null, 0));
	}

	boolean hasSteps()
	{
		return !steps.isEmpty();
	}

	boolean isFinished()
	{
		return steps.isEmpty() || stepIndex >= steps.size();
	}

	Step current()
	{
		return isFinished() ? null : steps.get(stepIndex);
	}

	int clicked()
	{
		return clicked;
	}

	int stepNumber()
	{
		return stepIndex + 1;
	}

	int stepCount()
	{
		return steps.size();
	}

	boolean isNotesStep()
	{
		final Step s = current();
		return s != null && s.pattern == null;
	}

	List<PatternOffset> offsets()
	{
		final Step s = current();
		if (s == null || s.pattern == null)
		{
			return new ArrayList<>();
		}
		return s.pattern.offsetsFor(s.count);
	}

	int boxCount()
	{
		return offsets().size();
	}

	int slotForBox(int k)
	{
		final List<PatternOffset> offs = offsets();
		if (k < 0 || k >= offs.size())
		{
			return -1;
		}
		final PatternOffset o = offs.get(k);
		final int col = ANCHOR_SLOT % RegearList.BANK_COLUMNS + o.x;
		final int row = ANCHOR_SLOT / RegearList.BANK_COLUMNS + o.y;
		return row * RegearList.BANK_COLUMNS + col;
	}

	int itemForBox(int k)
	{
		return items.isEmpty() ? -1 : items.get(k % items.size());
	}

	/** The next box the player is expected to click (0-based), or -1 when the step is complete. */
	int expectedBox()
	{
		return clicked < boxCount() ? clicked : -1;
	}

	/** Register a click on box k. In-order clicks advance the step; the notes step finishes on any click. */
	void clickBox(int k)
	{
		final Step s = current();
		if (s == null)
		{
			return;
		}
		if (s.pattern == null)
		{
			stepIndex++; // notes step: any click finishes the tutorial
			return;
		}
		if (k == clicked)
		{
			clicked++;
			if (clicked >= boxCount())
			{
				stepIndex++;
				clicked = 0;
			}
		}
	}

	void reset()
	{
		steps.clear();
		items.clear();
		stepIndex = 0;
		clicked = 0;
	}
}
