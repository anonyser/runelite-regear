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
	static final int FULL_INVENTORY = 28; // slots a full-inventory setup fills (for the rotating demo step)

	static final class Step
	{
		final String title;
		final String instruction;
		final PatternPreset pattern; // null = the closing notes step
		final int count;             // boxes shown per round (the visible window)
		final int total;             // items cleared across all rounds; == count for single-round steps

		Step(String title, String instruction, PatternPreset pattern, int count, int total)
		{
			this.title = title;
			this.instruction = instruction;
			this.pattern = pattern;
			this.count = count;
			this.total = total;
		}
	}

	private final List<Step> steps = new ArrayList<>();
	private List<Integer> items = new ArrayList<>();
	private boolean gearMode;
	private int stepIndex;
	private int clicked; // green boxes clicked so far in the current round
	private int cleared; // items cleared in earlier rounds of the current (rotating) step

	/** Build the step list from the example items. Needs at least 2 items to show anything useful. */
	void start(boolean gearMode, List<Integer> exampleItems)
	{
		this.gearMode = gearMode;
		this.items = new ArrayList<>(exampleItems);
		steps.clear();
		stepIndex = 0;
		clicked = 0;
		cleared = 0;
		final int n = items.size();
		if (n < 2)
		{
			return; // nothing to demonstrate; the overlay shows an "add a few items" note
		}
		final String kind = gearMode ? "Gear setup" : "Inventory setup";
		if (n >= 3)
		{
			final int z = Math.min(4, n);
			steps.add(new Step(kind + " — Z pattern",
				"Click the green boxes 1 to " + z + " in order.", PatternPreset.Z, z, z));
			steps.add(new Step("Single spot",
				"One slot: you click the same spot and it advances to the next item.", PatternPreset.SINGLE, 1, 1));
			final int v = Math.min(3, n);
			steps.add(new Step("Vertical line",
				"A straight column, top to bottom.", PatternPreset.VERTICAL, v, v));
		}
		// Full inventory: a 6-slot window keeps rotating until all 28 inventory slots are filled, exactly
		// like a real full-inventory setup. The example items cycle to stand in for a whole inventory.
		final int fullWindow = Math.min(6, n);
		steps.add(new Step("Full inventory — Z pattern",
			"Click 1 to " + fullWindow + " in order, then keep going until it's full.",
			PatternPreset.Z, fullWindow, FULL_INVENTORY));
		steps.add(new Step("Done!", null, null, 0, 0));
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

	/** Progress within a rotating step: items cleared so far, including this round's clicks. */
	int stepProgress()
	{
		return cleared + clicked;
	}

	/** Total items the current step clears; equals the visible window for single-round steps. */
	int stepTotal()
	{
		final Step s = current();
		return s == null ? 0 : s.total;
	}

	/** True when the current step rotates through more items than fit in its visible window. */
	boolean isRotatingStep()
	{
		final Step s = current();
		return s != null && s.pattern != null && s.total > offsets().size();
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
		// Tutorial demos use at most 6 items from a roomy anchor, so the full bank width never wraps.
		return s.pattern.offsetsFor(s.count, RegearList.BANK_COLUMNS);
	}

	int boxCount()
	{
		return Math.min(offsets().size(), roundRemaining());
	}

	/** Items still to clear in the current step (its rotation); 0 on the notes step. */
	private int roundRemaining()
	{
		final Step s = current();
		if (s == null || s.pattern == null)
		{
			return 0;
		}
		return s.total - cleared;
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
		return items.isEmpty() ? -1 : items.get((cleared + k) % items.size());
	}

	/** The next box the player is expected to click (0-based), or -1 when the round is complete. */
	int expectedBox()
	{
		return clicked < boxCount() ? clicked : -1;
	}

	/** Register a click on box k. In-order clicks advance the round; the notes step finishes on any click. */
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
			final int boxes = boxCount();
			if (clicked >= boxes)
			{
				// Round done: clear this window, then roll to the next window or finish the step.
				cleared += boxes;
				clicked = 0;
				if (cleared >= s.total)
				{
					stepIndex++;
					cleared = 0;
				}
			}
		}
	}

	void reset()
	{
		steps.clear();
		items.clear();
		stepIndex = 0;
		clicked = 0;
		cleared = 0;
	}
}
