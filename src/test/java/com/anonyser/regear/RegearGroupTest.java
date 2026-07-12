package com.anonyser.regear;

import com.google.gson.Gson;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Covers the group data model without a running client: every list gets a stable unique id, groups
 * reference lists by that id, and normalize repairs a loaded blob (backfilling missing ids and
 * dropping group members whose list is gone).
 */
public class RegearGroupTest
{
	private final Gson gson = new Gson();

	@Test
	public void everyListGetsAUniqueNonNullId()
	{
		final RegearList a = new RegearList("a");
		final RegearList b = new RegearList("b");
		assertNotNull(a.id);
		assertNotNull(b.id);
		assertFalse(a.id.isEmpty());
		assertNotEquals(a.id, b.id);
	}

	@Test
	public void normalizeBackfillsAMissingListId()
	{
		final RegearList l = new RegearList("legacy");
		l.id = null; // as a pre-id save would deserialize
		final RegearData data = new RegearData();
		data.lists.add(l);
		data.normalize();
		assertNotNull(l.id);
		assertFalse(l.id.isEmpty());
	}

	@Test
	public void normalizeDropsGroupMembersWhoseListIsGone()
	{
		final RegearList kept = new RegearList("kept");
		final RegearData data = new RegearData();
		data.lists.add(kept);
		final RegearGroup g = new RegearGroup("style");
		g.memberIds = new java.util.ArrayList<>(Arrays.asList(kept.id, "ghost-id"));
		data.groups.add(g);

		data.normalize();

		assertEquals(1, g.memberIds.size());
		assertTrue(g.contains(kept.id));
		assertFalse(g.contains("ghost-id"));
	}

	@Test
	public void normalizeRepairsNullGroupsAndMemberLists()
	{
		final RegearData data = new RegearData();
		data.groups = null; // a pre-groups save
		data.normalize();
		assertNotNull(data.groups);
		assertTrue(data.groups.isEmpty());

		final RegearGroup g = new RegearGroup("x");
		g.memberIds = null;
		data.groups.add(g);
		data.normalize();
		assertNotNull(g.memberIds);
	}

	@Test
	public void groupsSurviveAConfigRoundTrip()
	{
		final RegearData data = new RegearData();
		final RegearList a = new RegearList("main");
		final RegearList b = new RegearList("alt");
		data.lists.add(a);
		data.lists.add(b);
		final RegearGroup g = new RegearGroup("tribrid");
		g.memberIds.add(a.id);
		data.groups.add(g);

		final RegearData back = gson.fromJson(gson.toJson(data), RegearData.class);
		back.normalize();

		assertEquals(1, back.groups.size());
		assertEquals("tribrid", back.groups.get(0).name);
		// The member id still points at the first list after the round trip.
		assertTrue(back.groups.get(0).contains(back.lists.get(0).id));
		assertFalse(back.groups.get(0).contains(back.lists.get(1).id));
	}
}
