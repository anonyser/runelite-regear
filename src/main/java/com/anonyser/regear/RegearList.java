package com.anonyser.regear;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One ordered Regear withdrawal sequence and its display settings. The bank shows only a small
 * rotating window of this list at a time: {@code visibleCount} lanes, each showing one item. Lane
 * {@code k} walks the list as {@code k, k+L, k+2L, ...} where {@code L} is the number of lanes, so
 * the lanes together cover the list in order (top-to-bottom, left-to-right in panel order).
 *
 * <p>All rotation and slot math lives here and is deliberately client-free so it can be unit-tested
 * without a running game. The plugin owns detection of a real withdrawal; this class only advances a
 * lane when told to.</p>
 */
class RegearList
{
	static final int BANK_COLUMNS = 8;

	String name;
	boolean enabled;
	List<RegearItem> items = new ArrayList<>();
	int visibleCount = 3;
	PatternPreset pattern = PatternPreset.VERTICAL;
	List<PatternOffset> customOffsets = new ArrayList<>();
	/** Base bank slot (0-based, 8-wide grid) that lane offset (0,0) maps to. */
	int anchorSlot = defaultAnchorSlot(0);
	/** Per-list override of the global completion behaviour; null means "use the global setting". */
	CompletionBehavior completion;
	/** Persisted progress: {@code laneCursors[k]} is the item index currently shown in lane k. */
	int[] laneCursors;
	/**
	 * Runtime-only (not persisted): the game tick at which each lane's post-withdrawal hold ends. A
	 * lane is held (its slot shown empty) while the current tick is below this value, so mashing a
	 * slot pulls only one item per hold window. Set on advance; expires by tick.
	 */
	transient int[] laneHoldUntilTick;
	/**
	 * Runtime-only (not persisted): how much of the current active item each lane has seen withdrawn
	 * so far. A lane only advances once this reaches the item's required quantity, so an item stays
	 * put while you withdraw it in chunks (e.g. Withdraw-10 three times for 30 runes).
	 */
	transient int[] laneWithdrawn;

	// Required by Gson for deserialization.
	RegearList()
	{
	}

	RegearList(String name)
	{
		this.name = name;
	}

	/**
	 * The default anchor slot for the Nth enabled list: first sits at the far right ~4 rows down,
	 * each later list steps two columns left so patterns up to two columns wide do not collide.
	 */
	static int defaultAnchorSlot(int order)
	{
		final int col = Math.max(0, 7 - order * 2);
		final int row = 4;
		return row * BANK_COLUMNS + col;
	}

	/** Repairs nulls and clamps ranges after a config load, where Gson may have left gaps. */
	void normalize()
	{
		if (items == null)
		{
			items = new ArrayList<>();
		}
		if (customOffsets == null)
		{
			customOffsets = new ArrayList<>();
		}
		if (pattern == null)
		{
			pattern = PatternPreset.VERTICAL;
		}
		visibleCount = Math.max(1, Math.min(4, visibleCount));
		ensureLanes();
	}

	/** The lane offsets actually in effect: a preset derives them from the count, CUSTOM stores them. */
	List<PatternOffset> effectiveOffsets()
	{
		if (pattern == PatternPreset.CUSTOM)
		{
			if (customOffsets.isEmpty())
			{
				// Nothing configured yet: fall back to a single spot so the list still shows something.
				final List<PatternOffset> one = new ArrayList<>();
				one.add(new PatternOffset(0, 0));
				return one;
			}
			return customOffsets.size() > 4 ? customOffsets.subList(0, 4) : customOffsets;
		}
		return pattern.offsetsFor(visibleCount);
	}

	/** Number of visible lanes, i.e. the rotation step. */
	int laneCount()
	{
		return effectiveOffsets().size();
	}

	/** (Re)initialise lane cursors if missing or the lane count changed; each lane starts at its index. */
	void ensureLanes()
	{
		final int lanes = laneCount();
		if (laneCursors == null || laneCursors.length != lanes)
		{
			resetLanes();
		}
	}

	/** Restart the whole sequence: lane k shows item k again. */
	void resetLanes()
	{
		final int lanes = laneCount();
		laneCursors = new int[lanes];
		for (int k = 0; k < lanes; k++)
		{
			laneCursors[k] = k;
		}
		laneHoldUntilTick = null;
		laneWithdrawn = null;
	}

	/** Hold a lane empty until the given game tick (anti-spam limiter). */
	void holdLane(int lane, int releaseTick)
	{
		final int lanes = laneCount();
		if (laneHoldUntilTick == null || laneHoldUntilTick.length != lanes)
		{
			laneHoldUntilTick = new int[lanes];
		}
		if (lane >= 0 && lane < laneHoldUntilTick.length)
		{
			laneHoldUntilTick[lane] = releaseTick;
		}
	}

	boolean isLaneHeld(int lane, int currentTick)
	{
		return laneHoldUntilTick != null && lane >= 0 && lane < laneHoldUntilTick.length
			&& currentTick < laneHoldUntilTick[lane];
	}

	/** True if any lane's hold ends exactly on this tick (so the next item should now be shown). */
	boolean releaseDue(int currentTick)
	{
		if (laneHoldUntilTick == null)
		{
			return false;
		}
		for (int until : laneHoldUntilTick)
		{
			if (until == currentTick)
			{
				return true;
			}
		}
		return false;
	}

	void clearHolds()
	{
		laneHoldUntilTick = null;
	}

	/** Add to the amount withdrawn of a lane's current item. */
	void addWithdrawn(int lane, int amount)
	{
		final int lanes = laneCount();
		if (laneWithdrawn == null || laneWithdrawn.length != lanes)
		{
			laneWithdrawn = new int[lanes];
		}
		if (lane >= 0 && lane < laneWithdrawn.length)
		{
			laneWithdrawn[lane] += amount;
		}
	}

	int getWithdrawn(int lane)
	{
		return laneWithdrawn != null && lane >= 0 && lane < laneWithdrawn.length ? laneWithdrawn[lane] : 0;
	}

	void clearWithdrawn()
	{
		laneWithdrawn = null;
	}

	/** Absolute bank slot for a lane = anchor + offset, or -1 if it would fall outside the grid. */
	int absoluteSlot(int lane)
	{
		final List<PatternOffset> offs = effectiveOffsets();
		if (lane < 0 || lane >= offs.size())
		{
			return -1;
		}
		final PatternOffset o = offs.get(lane);
		final int col = anchorSlot % BANK_COLUMNS + o.x;
		final int row = anchorSlot / BANK_COLUMNS + o.y;
		if (col < 0 || col >= BANK_COLUMNS || row < 0)
		{
			return -1;
		}
		return row * BANK_COLUMNS + col;
	}

	/** The item index currently shown in a lane (may be past the end of the list). */
	int activeIndex(int lane)
	{
		ensureLanes();
		if (lane < 0 || lane >= laneCursors.length)
		{
			return -1;
		}
		return laneCursors[lane];
	}

	/** The item currently shown in a lane, or null if that lane has run past the end of the list. */
	RegearItem activeItem(int lane)
	{
		final int idx = activeIndex(lane);
		if (idx < 0 || idx >= items.size())
		{
			return null;
		}
		return items.get(idx);
	}

	/** The item a lane will show after its current one, for the optional "next" preview; may be null. */
	RegearItem nextItem(int lane)
	{
		final int idx = activeIndex(lane);
		final int next = idx + laneCount();
		if (idx < 0 || next >= items.size())
		{
			return null;
		}
		return items.get(next);
	}

	/**
	 * Advance one lane to its next item, applying the completion behaviour when it runs off the end.
	 * LOOP restarts just that lane at its base index; every other behaviour parks the lane past the
	 * end (so it shows nothing) and lets the plugin handle any whole-sequence reset out of band.
	 */
	void advanceLane(int lane, CompletionBehavior effective)
	{
		ensureLanes();
		if (lane < 0 || lane >= laneCursors.length)
		{
			return;
		}
		final int step = laneCount();
		int next = laneCursors[lane] + step;
		if (next >= items.size())
		{
			if (effective == CompletionBehavior.LOOP)
			{
				next = lane;
			}
			else
			{
				next = items.size();
			}
		}
		laneCursors[lane] = next;
		if (laneWithdrawn != null && lane < laneWithdrawn.length)
		{
			laneWithdrawn[lane] = 0;
		}
	}

	/** True once every lane has run past the end of the list. */
	boolean isComplete()
	{
		ensureLanes();
		for (int k = 0; k < laneCursors.length; k++)
		{
			if (laneCursors[k] < items.size())
			{
				return false;
			}
		}
		return true;
	}

	/** All bank slots this list's pattern occupies (its footprint), for cross-list overlap checks. */
	Set<Integer> footprintSlots()
	{
		final Set<Integer> slots = new LinkedHashSet<>();
		final int lanes = laneCount();
		for (int k = 0; k < lanes; k++)
		{
			final int slot = absoluteSlot(k);
			if (slot >= 0)
			{
				slots.add(slot);
			}
		}
		return slots;
	}

	/** True if the pattern stays within the 8-column grid from the current anchor (no row wrap). */
	boolean fitsGrid()
	{
		for (int k = 0; k < laneCount(); k++)
		{
			if (absoluteSlot(k) < 0)
			{
				return false;
			}
		}
		return true;
	}

	CompletionBehavior effectiveCompletion(CompletionBehavior globalDefault)
	{
		return completion != null ? completion : globalDefault;
	}
}
