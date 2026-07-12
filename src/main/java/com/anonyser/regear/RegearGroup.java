package com.anonyser.regear;

import java.util.ArrayList;
import java.util.List;

/**
 * A named batch of setups you can turn on or off in one action. Members are stored by each list's
 * stable {@link RegearList#id} rather than by name, so renaming a setup keeps it in its groups and
 * deleting one cleanly drops it. A setup may belong to more than one group.
 *
 * <p>Enabling a group is an exclusive switch: its member lists become the only enabled ones.
 * Disabling a group just turns its members off and leaves everything else as it was.</p>
 */
class RegearGroup
{
	String name;
	List<String> memberIds = new ArrayList<>();

	// Required by Gson for deserialization.
	RegearGroup()
	{
	}

	RegearGroup(String name)
	{
		this.name = name;
	}

	boolean contains(String listId)
	{
		return listId != null && memberIds.contains(listId);
	}
}
