package com.anonyser.regear;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pure rotation / pattern / slot maths, exercised without a running client. These lock in the lane
 * behaviour described in the spec (1-, 2-, 3- and 4-item rotations, Z pattern, completion, overlap).
 */
public class RegearRotationTest
{
	private static RegearList list(PatternPreset pattern, int visible, int itemCount)
	{
		final RegearList l = new RegearList("t");
		l.pattern = pattern;
		l.visibleCount = visible;
		for (int i = 1; i <= itemCount; i++)
		{
			l.items.add(new RegearItem(i));
		}
		l.resetLanes();
		return l;
	}

	private static int activeId(RegearList l, int lane)
	{
		final RegearItem it = l.activeItem(lane);
		return it == null ? -1 : it.id;
	}

	@Test
	public void threeLaneVerticalRotation()
	{
		final RegearList l = list(PatternPreset.VERTICAL, 3, 12);
		assertEquals(3, l.laneCount());
		assertEquals(1, activeId(l, 0));
		assertEquals(2, activeId(l, 1));
		assertEquals(3, activeId(l, 2));

		l.advanceLane(0, CompletionBehavior.STOP);
		assertEquals(4, activeId(l, 0));
		l.advanceLane(1, CompletionBehavior.STOP);
		assertEquals(5, activeId(l, 1));
		l.advanceLane(2, CompletionBehavior.STOP);
		assertEquals(6, activeId(l, 2));

		// Lane 0 walks 1,4,7,10 then empties.
		l.advanceLane(0, CompletionBehavior.STOP); // 7
		l.advanceLane(0, CompletionBehavior.STOP); // 10
		assertEquals(10, activeId(l, 0));
		l.advanceLane(0, CompletionBehavior.STOP); // past end
		assertNull(l.activeItem(0));
	}

	@Test
	public void singleSpotRotation()
	{
		final RegearList l = list(PatternPreset.SINGLE, 1, 4);
		assertEquals(1, l.laneCount());
		assertEquals(1, activeId(l, 0));
		l.advanceLane(0, CompletionBehavior.STOP);
		assertEquals(2, activeId(l, 0));
		l.advanceLane(0, CompletionBehavior.STOP);
		assertEquals(3, activeId(l, 0));
		l.advanceLane(0, CompletionBehavior.STOP);
		assertEquals(4, activeId(l, 0));
		l.advanceLane(0, CompletionBehavior.STOP);
		assertNull(l.activeItem(0));
	}

	@Test
	public void twoLaneRotation()
	{
		final RegearList l = list(PatternPreset.VERTICAL, 2, 8);
		assertEquals(1, activeId(l, 0));
		assertEquals(2, activeId(l, 1));
		l.advanceLane(0, CompletionBehavior.STOP);
		assertEquals(3, activeId(l, 0));
		l.advanceLane(1, CompletionBehavior.STOP);
		assertEquals(4, activeId(l, 1));
	}

	@Test
	public void fourLaneRotation()
	{
		final RegearList l = list(PatternPreset.VERTICAL, 4, 16);
		assertEquals(4, l.laneCount());
		assertEquals(1, activeId(l, 0));
		assertEquals(4, activeId(l, 3));
		l.advanceLane(3, CompletionBehavior.STOP);
		assertEquals(8, activeId(l, 3));
	}

	@Test
	public void zPatternOffsets()
	{
		final RegearList l = list(PatternPreset.Z, 4, 4);
		final List<PatternOffset> offs = l.effectiveOffsets();
		assertEquals(4, offs.size());
		assertEquals(new PatternOffset(0, 0), offs.get(0));
		assertEquals(new PatternOffset(0, 1), offs.get(1));
		assertEquals(new PatternOffset(1, 0), offs.get(2));
		assertEquals(new PatternOffset(1, 1), offs.get(3));
	}

	@Test
	public void customOffsetsDriveLanes()
	{
		final RegearList l = list(PatternPreset.CUSTOM, 3, 6);
		l.customOffsets.add(new PatternOffset(0, 0));
		l.customOffsets.add(new PatternOffset(0, 1));
		l.customOffsets.add(new PatternOffset(0, 2));
		l.resetLanes();
		assertEquals(3, l.laneCount());
		assertEquals(1, activeId(l, 0));
		assertEquals(3, activeId(l, 2));
	}

	@Test
	public void absoluteSlotsAndGridFit()
	{
		assertEquals(39, RegearList.defaultAnchorSlot(0));
		assertEquals(37, RegearList.defaultAnchorSlot(1));

		final RegearList v = list(PatternPreset.VERTICAL, 3, 6);
		v.anchorSlot = 39; // col 7, row 4
		assertEquals(39, v.absoluteSlot(0));
		assertEquals(47, v.absoluteSlot(1));
		assertEquals(55, v.absoluteSlot(2));
		assertTrue(v.fitsGrid());

		final RegearList z = list(PatternPreset.Z, 4, 4);
		z.anchorSlot = 39; // col 7 -> the second column would fall off the grid
		assertFalse(z.fitsGrid());
		z.anchorSlot = 38; // col 6 -> now the two columns fit
		assertTrue(z.fitsGrid());
	}

	@Test
	public void loopWrapsLaneToStart()
	{
		final RegearList l = list(PatternPreset.SINGLE, 1, 4);
		l.advanceLane(0, CompletionBehavior.LOOP); // 2
		l.advanceLane(0, CompletionBehavior.LOOP); // 3
		l.advanceLane(0, CompletionBehavior.LOOP); // 4
		l.advanceLane(0, CompletionBehavior.LOOP); // wraps back to item 1
		assertEquals(1, activeId(l, 0));
	}

	@Test
	public void stopParksLaneAndReportsComplete()
	{
		final RegearList l = list(PatternPreset.SINGLE, 1, 2);
		assertFalse(l.isComplete());
		l.advanceLane(0, CompletionBehavior.STOP); // 2
		l.advanceLane(0, CompletionBehavior.STOP); // past end
		assertTrue(l.isComplete());
	}

	@Test
	public void withdrawnAccumulatesAndResetsOnAdvance()
	{
		final RegearList l = list(PatternPreset.SINGLE, 1, 3);
		l.items.get(0).quantity = 30; // this item requires 30 withdrawn before advancing
		assertEquals(0, l.getWithdrawn(0));
		l.addWithdrawn(0, 10);
		l.addWithdrawn(0, 10);
		assertEquals(20, l.getWithdrawn(0));
		assertEquals(1, activeId(l, 0)); // still on item 1 at 20/30
		l.addWithdrawn(0, 10);
		assertEquals(30, l.getWithdrawn(0));
		l.advanceLane(0, CompletionBehavior.STOP);
		assertEquals(2, activeId(l, 0));
		assertEquals(0, l.getWithdrawn(0)); // resets for the next item
	}

	@Test
	public void alternativesCountAsMatches()
	{
		final RegearItem it = new RegearItem(4587);
		assertFalse(it.matches(4585));
		it.alts.add(4585);
		assertTrue(it.matches(4587)); // the primary id
		assertTrue(it.matches(4585)); // an alternative ("or")
		assertFalse(it.matches(1234));
	}

	@Test
	public void footprintSlotsCoverEveryLane()
	{
		final RegearList l = list(PatternPreset.VERTICAL, 3, 6);
		l.anchorSlot = 39;
		assertEquals(3, l.footprintSlots().size());
		assertTrue(l.footprintSlots().contains(39));
		assertTrue(l.footprintSlots().contains(47));
		assertTrue(l.footprintSlots().contains(55));
	}
}
