package com.anonyser.regear;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Round-trips the shareable export/import format and the name-collision helper, all without a running
 * client, so the "send someone your setup" path is locked in.
 */
public class RegearShareTest
{
	private final Gson gson = new Gson();

	private static RegearList sample(String name)
	{
		final RegearList l = new RegearList(name);
		l.enabled = true;
		l.visibleCount = 4;
		l.pattern = PatternPreset.Z;
		l.anchorSlot = 30;
		l.completion = CompletionBehavior.LOOP;
		final RegearItem whip = new RegearItem(4151);
		whip.quantity = 1;
		whip.note = "whip";
		whip.skipIfWorn = true;
		whip.alts.add(12006);
		l.items.add(whip);
		l.items.add(new RegearItem(2434, 3, null));
		l.resetLanes();
		return l;
	}

	@Test
	public void roundTripsEveryField()
	{
		final RegearList src = sample("Main");
		final List<RegearList> out = RegearShare.parse(RegearShare.export(Arrays.asList(src), gson), gson);
		assertEquals(1, out.size());
		final RegearList got = out.get(0);
		assertEquals("Main", got.name);
		assertTrue(got.enabled);
		assertEquals(4, got.visibleCount);
		assertEquals(PatternPreset.Z, got.pattern);
		assertEquals(30, got.anchorSlot);
		assertEquals(CompletionBehavior.LOOP, got.completion);
		assertEquals(2, got.items.size());
		assertEquals(4151, got.items.get(0).id);
		assertEquals("whip", got.items.get(0).note);
		assertTrue(got.items.get(0).skipIfWorn);
		assertEquals(Integer.valueOf(12006), got.items.get(0).alts.get(0));
		assertEquals(3, got.items.get(1).quantity);
	}

	@Test
	public void exportAllRoundTripsEveryList()
	{
		final List<RegearList> src = Arrays.asList(sample("A"), sample("B"), sample("C"));
		final List<RegearList> out = RegearShare.parse(RegearShare.export(src, gson), gson);
		assertEquals(3, out.size());
	}

	@Test
	public void tokenHasPrefixAndIsSingleLine()
	{
		final String token = RegearShare.export(Arrays.asList(sample("A")), gson);
		assertTrue(token.startsWith(RegearShare.PREFIX));
		assertFalse(token.contains("\n"));
	}

	@Test
	public void importedSetupStartsFresh()
	{
		final RegearList src = sample("Main");
		src.advanceLane(0, CompletionBehavior.STOP); // push progress off the start before exporting
		final RegearList got = RegearShare.parse(RegearShare.export(Arrays.asList(src), gson), gson).get(0);
		assertEquals(0, got.activeIndex(0));
	}

	@Test
	public void parseRejectsGarbage()
	{
		assertTrue(RegearShare.parse("not a real token", gson).isEmpty());
		assertTrue(RegearShare.parse("", gson).isEmpty());
		assertTrue(RegearShare.parse(null, gson).isEmpty());
	}

	@Test
	public void uniqueNameAvoidsCollisions()
	{
		final Set<String> taken = new HashSet<>(Arrays.asList("main", "main (2)"));
		assertEquals("Fresh", RegearShare.uniqueName("Fresh", taken));
		assertEquals("Main (3)", RegearShare.uniqueName("Main", taken));
	}
}
