package com.anonyser.regear;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

/**
 * Global Regear settings. Per-list settings (enabled, visible count, pattern, custom offsets, bank
 * anchor, completion override) live on each {@link RegearList} and are edited from the side panel;
 * the values here are the plugin-wide defaults and toggles.
 */
@ConfigGroup(RegearConfig.GROUP)
public interface RegearConfig extends Config
{
	String GROUP = "regear";

	@ConfigSection(
		name = "Bank display",
		description = "How Regear arranges the active items in the bank",
		position = 0
	)
	String bankSection = "bank";

	@ConfigSection(
		name = "Overlays",
		description = "Item id text and the guidance drawn over the bank",
		position = 1
	)
	String overlaySection = "overlay";

	@ConfigSection(
		name = "Behaviour",
		description = "Rotation, resets and missing-item handling",
		position = 2
	)
	String behaviourSection = "behaviour";

	@ConfigItem(
		keyName = "applyInBank",
		name = "Show setups in bank",
		description = "When the bank is open, filter it to the active items of every enabled list<br>"
			+ "and move them into their configured positions. Turn off to keep your lists but<br>"
			+ "leave the bank untouched.",
		position = 0,
		section = bankSection
	)
	default boolean applyInBank()
	{
		return true;
	}

	@Range(min = 1, max = 4)
	@ConfigItem(
		keyName = "defaultVisibleCount",
		name = "Default visible items",
		description = "How many lanes a newly created list starts with (1-4).",
		position = 1,
		section = bankSection
	)
	default int defaultVisibleCount()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "highlightActive",
		name = "Highlight active slots",
		description = "Draw a coloured box around the bank slots Regear is currently pointing you at.",
		position = 2,
		section = bankSection
	)
	default boolean highlightActive()
	{
		return true;
	}

	@Alpha
	@ConfigItem(
		keyName = "activeColor",
		name = "Active highlight",
		description = "Colour of the box around the current active item slots.",
		position = 3,
		section = bankSection
	)
	default Color activeColor()
	{
		return new Color(0, 200, 83, 180);
	}

	@ConfigItem(
		keyName = "showLaneNumbers",
		name = "Show lane numbers",
		description = "Number each active slot so the intended click order is obvious.",
		position = 4,
		section = bankSection
	)
	default boolean showLaneNumbers()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showNextPreview",
		name = "Show next item",
		description = "Draw a small preview of the item each lane will advance to next.",
		position = 5,
		section = bankSection
	)
	default boolean showNextPreview()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showItemIds",
		name = "Item id overlay",
		description = "Draw each item's id in small white text over items in the bank,<br>"
			+ "inventory and equipment. Handy while building a list.",
		position = 0,
		section = overlaySection
	)
	default boolean showItemIds()
	{
		return false;
	}

	@ConfigItem(
		keyName = "highlightMissing",
		name = "Mark missing items",
		description = "Draw a marker where an active item should be but is not in the bank.",
		position = 4,
		section = overlaySection
	)
	default boolean highlightMissing()
	{
		return true;
	}

	@Alpha
	@ConfigItem(
		keyName = "missingColor",
		name = "Missing marker",
		description = "Colour of the missing-item marker.",
		position = 5,
		section = overlaySection
	)
	default Color missingColor()
	{
		return new Color(220, 40, 40, 200);
	}

	@ConfigItem(
		keyName = "defaultCompletion",
		name = "When a list finishes",
		description = "Default behaviour once every lane has run past the end of a list.<br>"
			+ "A list can override this from the side panel.",
		position = 0,
		section = behaviourSection
	)
	default CompletionBehavior defaultCompletion()
	{
		return CompletionBehavior.STOP;
	}

	@ConfigItem(
		keyName = "skipMissing",
		name = "Skip missing items",
		description = "If the active item is not in the bank, advance the lane past it instead of<br>"
			+ "waiting. Off means the lane holds on the missing item until it is available.",
		position = 1,
		section = behaviourSection
	)
	default boolean skipMissing()
	{
		return false;
	}

	@ConfigItem(
		keyName = "openPanel",
		name = "Open Regear panel",
		description = "Tick to open the Regear side panel; it opens the panel then unticks itself.",
		position = 10
	)
	default boolean openPanel()
	{
		return false;
	}
}
