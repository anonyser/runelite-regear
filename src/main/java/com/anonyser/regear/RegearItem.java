package com.anonyser.regear;

/**
 * One entry in a Regear list: an item id, an optional quantity hint, and an optional user note.
 * Duplicates of the same id are allowed and expected (multiple potion doses, several food, repeats
 * in a sequence), so equality is identity-based, not value-based. Package-private mutable fields keep
 * the persisted JSON small and let the panel edit entries in place.
 */
class RegearItem
{
	int id;
	/** Informational only (e.g. dose or amount); the plugin never withdraws, so this is a display hint. */
	int quantity;
	/** Optional short label shown under the slot; may be null. */
	String note;

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

	RegearItem copy()
	{
		return new RegearItem(id, quantity, note);
	}
}
