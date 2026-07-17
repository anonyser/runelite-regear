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

	@ConfigSection(
		name = "Equipment panel",
		description = "A movable panel that mirrors your worn equipment while the bank is open",
		position = 3
	)
	String equipmentSection = "equipment";

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

	@Range(min = 1, max = 28)
	@ConfigItem(
		keyName = "defaultVisibleCount",
		name = "Default visible items",
		description = "How many lanes a newly created list starts with (1-28; 28 = a full inventory).",
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
		// Fresh key (was activeColor): the meaning changed from "the one highlight" to "the boxes
		// you are NOT on yet", so an old saved colour must not carry over and drown the new scheme.
		keyName = "slotColor",
		name = "Slot boxes",
		description = "Colour of the box around the active item slots you are not on yet.",
		position = 3,
		section = bankSection
	)
	default Color slotColor()
	{
		return new Color(0, 110, 45, 200);
	}

	@Alpha
	@ConfigItem(
		keyName = "currentColor",
		name = "Current click",
		description = "Colour of the box around the slot to click next; it moves through the<br>"
			+ "withdraw order in real time as you take items out.",
		position = 4,
		section = bankSection
	)
	default Color currentColor()
	{
		return new Color(70, 255, 70, 255);
	}

	@ConfigItem(
		keyName = "showLaneNumbers",
		name = "Show lane numbers",
		description = "Number each active slot so the intended click order is obvious.",
		position = 5,
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
		position = 6,
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
		keyName = "overlayHidden",
		name = "Hide bank guide",
		description = "Hide the whole Regear guide in the bank so you can browse it freely. This is the<br>"
			+ "same as the Hide overlay button in the panel; turn it off (or click Show overlay) to<br>"
			+ "bring the guide back.",
		position = 6,
		section = overlaySection
	)
	default boolean overlayHidden()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showEquipmentOverlay",
		name = "Show equipment while banking",
		description = "Show a movable panel of your current worn equipment while the bank is open, in the<br>"
			+ "game's equipment layout. It updates live as you gear up and hides when you close the<br>"
			+ "bank. Drag it anywhere like any other overlay.",
		position = 0,
		section = equipmentSection
	)
	default boolean showEquipmentOverlay()
	{
		return false;
	}

	@ConfigItem(
		keyName = "defaultCompletion",
		name = "When a list finishes",
		description = "What happens once every lane has run past the end of a list.<br>"
			+ "Applies to every saved list.",
		position = 0,
		section = behaviourSection
	)
	default CompletionBehavior defaultCompletion()
	{
		return CompletionBehavior.STOP;
	}

	@Range(min = 0, max = 5)
	@ConfigItem(
		keyName = "holdTicks",
		name = "Hold after withdraw (ticks)",
		description = "Anti-spam limiter: after you withdraw an active item, keep that slot empty for<br>"
			+ "this many game ticks (~0.6s each) before the next item appears, so mashing a slot only<br>"
			+ "pulls one item at a time, in order. 0 = fastest (no hold); higher = firmer limit.",
		position = 1,
		section = behaviourSection
	)
	default int holdTicks()
	{
		return 1;
	}

	@ConfigItem(
		keyName = "skipMissing",
		name = "Skip missing items",
		description = "If the active item is not in the bank, advance the lane past it instead of<br>"
			+ "waiting. Off means the lane holds on the missing item until it is available.",
		position = 2,
		section = behaviourSection
	)
	default boolean skipMissing()
	{
		return false;
	}

	@Range(min = 0, max = 250)
	@ConfigItem(
		keyName = "bankDragDelay",
		name = "Prevent bank item drag",
		description = "While the bank is open, raise the item drag delay (in client ticks, ~20ms each)<br>"
			+ "so fast withdraw clicks are not misread as drags. 0 leaves the client default (5).<br>"
			+ "This is the same setting the Anti-Drag plugin uses.",
		position = 3,
		section = behaviourSection
	)
	default int bankDragDelay()
	{
		return 50;
	}

	@ConfigItem(
		keyName = "dragMode",
		name = "Item drag",
		description = "What dropping one item onto another does in the setup editor —<br>"
			+ "the same two modes the bank has. Swap trades the two slots;<br>"
			+ "Insert pushes everything between over by one.<br>"
			+ "Also a dropdown above the items grid in the panel.",
		position = 4,
		section = behaviourSection
	)
	default DragMode dragMode()
	{
		return DragMode.SWAP;
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

	@ConfigItem(
		keyName = "hideTutorial",
		name = "Hide tutorial button",
		description = "Hide the flashing Tutorial button in the side panel (the checkbox next to it does the same).",
		position = 11
	)
	default boolean hideTutorial()
	{
		return false;
	}
}
