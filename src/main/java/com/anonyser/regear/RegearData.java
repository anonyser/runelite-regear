package com.anonyser.regear;

import java.util.ArrayList;
import java.util.List;

/**
 * Root of the persisted Regear state: a schema version (for future migrations) and the ordered
 * lists. Serialized to and from a single config value as JSON by the plugin.
 */
class RegearData
{
	int version = 1;
	List<RegearList> lists = new ArrayList<>();
	/** Named batches of setups (see {@link RegearGroup}); empty on legacy saves. */
	List<RegearGroup> groups = new ArrayList<>();

	/** Repair nulls and clamp ranges across every list after a load, and tidy the groups. */
	void normalize()
	{
		if (lists == null)
		{
			lists = new ArrayList<>();
		}
		for (RegearList list : lists)
		{
			if (list != null)
			{
				list.normalize();
			}
		}
		if (groups == null)
		{
			groups = new ArrayList<>();
		}
		final java.util.Set<String> knownIds = new java.util.HashSet<>();
		for (RegearList list : lists)
		{
			if (list != null && list.id != null)
			{
				knownIds.add(list.id);
			}
		}
		groups.removeIf(g -> g == null);
		for (RegearGroup group : groups)
		{
			if (group.memberIds == null)
			{
				group.memberIds = new ArrayList<>();
			}
			// Drop references to lists that no longer exist, so a group can't point at nothing.
			group.memberIds.removeIf(id -> !knownIds.contains(id));
		}
	}
}
