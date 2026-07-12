package com.anonyser.regear;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The client-free item flags behind Omit and Add blank space: an always-skipped entry is dropped from
 * the withdrawal cycle, and copying an entry keeps its flags.
 */
public class RegearItemTest
{
	@Test
	public void alwaysSkippedCoversBlankAndOmittedOnly()
	{
		assertFalse(new RegearItem(4151).alwaysSkipped());

		final RegearItem omitted = new RegearItem(4151);
		omitted.omitted = true;
		assertTrue(omitted.alwaysSkipped());

		final RegearItem blank = new RegearItem();
		blank.blank = true;
		assertTrue(blank.alwaysSkipped());

		// Skip-if-worn is conditional on the worn set, so it is NOT an unconditional skip.
		final RegearItem worn = new RegearItem(4151);
		worn.skipIfWorn = true;
		assertFalse(worn.alwaysSkipped());
	}

	@Test
	public void copyPreservesEveryFlag()
	{
		final RegearItem it = new RegearItem(4151, 3, "spec weapon");
		it.skipIfWorn = true;
		it.omitted = true;
		it.alts.add(4153);

		final RegearItem c = it.copy();
		assertEquals(4151, c.id);
		assertEquals(3, c.quantity);
		assertEquals("spec weapon", c.note);
		assertTrue(c.skipIfWorn);
		assertTrue(c.omitted);
		assertFalse(c.blank);
		assertTrue(c.alts.contains(4153));

		final RegearItem blank = new RegearItem();
		blank.blank = true;
		assertTrue(blank.copy().blank);
	}
}
