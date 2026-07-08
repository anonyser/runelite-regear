package com.anonyser.regear;

import java.util.ArrayList;
import java.util.List;

/**
 * One entry in a Regear list: an item id, an optional quantity, an optional note, and optional
 * "or" alternatives -- other item ids that satisfy the same slot (e.g. a fresh vs a used/degraded
 * variant). Any of them counts as this item for matching a withdrawal, and the bank shows whichever
 * one you actually own. Duplicates of the same id across a list are allowed and expected, so equality
 * is identity-based, not value-based.
 */
class RegearItem
{
	int id;
	/** How many of this item to withdraw before the lane advances (0/1 = advance after one). */
	int quantity;
	/** Optional short label shown under the slot; may be null. */
	String note;
	/** Alternative item ids that also satisfy this slot ("or"); any of them counts as a match. */
	List<Integer> alts = new ArrayList<>();
	/** If set, this item counts as already satisfied while you are wearing it, so it is not shown in
	 *  the regear (e.g. a Ring of Recoil you still have on -- no point pulling another). Per list. */
	boolean skipIfWorn;

	// Required by Gson for deserialization.
	RegearItem()
	{
	}

	RegearItem(int id)
	{
		this.id = id;
	}

	RegearItem(int id, int quantity, String note)
	{
		this.id = id;
		this.quantity = quantity;
		this.note = note;
	}

	/** True if the given item id is this item or one of its alternatives. */
	boolean matches(int otherId)
	{
		return otherId == id || (alts != null && alts.contains(otherId));
	}

	RegearItem copy()
	{
		final RegearItem c = new RegearItem(id, quantity, note);
		if (alts != null)
		{
			c.alts = new ArrayList<>(alts);
		}
		return c;
	}
}
