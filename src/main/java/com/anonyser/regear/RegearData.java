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

	/** Repair nulls and clamp ranges across every list after a load. */
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
	}
}
