package com.anonyser.regear;

/**
 * A relative bank-slot offset for one visible lane, measured from a list's anchor slot.
 * {@code x} is columns to the right, {@code y} is rows down. Serialized as part of a custom pattern.
 * Package-private mutable fields keep the JSON compact and let the panel edit offsets in place.
 */
class PatternOffset
{
	int x;
	int y;

	// Required by Gson for deserialization.
	PatternOffset()
	{
	}

	PatternOffset(int x, int y)
	{
		this.x = x;
		this.y = y;
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof PatternOffset))
		{
			return false;
		}
		final PatternOffset p = (PatternOffset) o;
		return x == p.x && y == p.y;
	}

	@Override
	public int hashCode()
	{
		return 31 * x + y;
	}

	@Override
	public String toString()
	{
		return "(" + x + "," + y + ")";
	}
}
