package com.anonyser.regear;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Serialises Regear setups to and from a single line of shareable text, so a whole set (or one list)
 * can be copied to the clipboard and pasted by someone else to mirror it exactly. The text is a short
 * prefix plus Base64 of the setups' JSON, which survives being pasted through chat apps on one line.
 * Kept deliberately client-free so it can be unit-tested without a running game.
 */
final class RegearShare
{
	/** Marks Regear share text and carries a format version for future migrations. */
	static final String PREFIX = "regear1:";

	private RegearShare()
	{
	}

	/**
	 * Encode the given lists into one shareable token. Sequence progress is dropped so the imported
	 * copy always starts fresh; everything the recipient needs to mirror the setup is kept.
	 */
	static String export(List<RegearList> lists, Gson gson)
	{
		final RegearData out = new RegearData();
		if (lists != null)
		{
			for (RegearList list : lists)
			{
				if (list == null)
				{
					continue;
				}
				// Deep-copy via a JSON round-trip, then drop the persisted sequence progress.
				final RegearList copy = gson.fromJson(gson.toJson(list), RegearList.class);
				copy.laneCursors = null;
				out.lists.add(copy);
			}
		}
		final String json = gson.toJson(out);
		return PREFIX + Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Decode share text back into setups, tolerant of surrounding whitespace, a missing prefix, or
	 * raw (un-encoded) JSON. Each returned list is normalised and reset to its start. Returns an empty
	 * list if nothing valid could be read.
	 */
	static List<RegearList> parse(String text, Gson gson)
	{
		final List<RegearList> result = new ArrayList<>();
		if (text == null)
		{
			return result;
		}
		String body = text.trim();
		if (body.isEmpty())
		{
			return result;
		}
		if (body.startsWith(PREFIX))
		{
			body = body.substring(PREFIX.length()).trim();
		}
		String json;
		try
		{
			json = new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8);
		}
		catch (IllegalArgumentException notBase64)
		{
			// Someone may have pasted raw JSON: fall back to using the text as-is.
			json = body;
		}
		final RegearData data;
		try
		{
			data = gson.fromJson(json, RegearData.class);
		}
		catch (Exception badJson)
		{
			return result;
		}
		if (data == null || data.lists == null)
		{
			return result;
		}
		data.normalize();
		for (RegearList list : data.lists)
		{
			if (list != null && list.name != null && !list.name.trim().isEmpty())
			{
				list.resetLanes();
				result.add(list);
			}
		}
		return result;
	}

	/**
	 * A name based on {@code base} that is not already taken (case-insensitive): returns {@code base}
	 * unchanged if free, otherwise appends " (2)", " (3)", ... The provided set is not modified.
	 */
	static String uniqueName(String base, Set<String> existingLower)
	{
		final String name = base == null || base.trim().isEmpty() ? "Regear" : base.trim();
		if (existingLower == null || !existingLower.contains(name.toLowerCase()))
		{
			return name;
		}
		for (int n = 2; n < 10000; n++)
		{
			final String candidate = name + " (" + n + ")";
			if (!existingLower.contains(candidate.toLowerCase()))
			{
				return candidate;
			}
		}
		return name;
	}
}
