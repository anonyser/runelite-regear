package com.anonyser.regear;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.widgets.Widget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bank layout engine. When the bank finishes (re)building its item icons, this hides every item
 * and then moves the active target of each enabled list into that list's configured slot, so the
 * bank shows only the small set of items Regear is guiding you toward, in fixed positions. This is
 * the same widget-repositioning technique RuneLite's own Bank Tag Layouts use, reimplemented here so
 * Regear is fully self-contained and needs no other plugin. It is display-only: it moves and hides
 * icons, it never clicks or withdraws.
 */
@Singleton
class RegearBankController
{
	private static final Logger log = LoggerFactory.getLogger(RegearBankController.class);

	// Bank grid geometry, matching RuneLite core (BankTagsPlugin): 8 columns of 36x32 icons.
	static final int COLUMNS = 8;
	static final int ITEM_WIDTH = 36;
	static final int ITEM_HEIGHT = 32;
	static final int X_PADDING = 12;
	static final int Y_PADDING = 4;
	static final int START_X = 51;
	static final int START_Y = 0;

	/** One positioned (or missing) active item, recorded for the overlay to annotate. */
	static final class Placement
	{
		final String listName;
		final int lane;      // 0-based lane index within its list
		final int slot;      // absolute bank slot
		final RegearItem item;
		RegearItem next;     // what this lane advances to next, for the preview; may be null
		Widget widget;       // the bank item widget moved here, or null if missing/duplicate
		boolean missing;
		boolean duplicate;   // a second lane wanting an item already shown elsewhere

		Placement(String listName, int lane, int slot, RegearItem item)
		{
			this.listName = listName;
			this.lane = lane;
			this.slot = slot;
			this.item = item;
		}
	}

	private final Client client;
	private final RegearConfig config;

	private RegearData data;
	private final List<Placement> placements = new ArrayList<>();
	private final List<String> missingLabels = new ArrayList<>();
	private boolean overlapDetected;

	@Inject
	RegearBankController(Client client, RegearConfig config)
	{
		this.client = client;
		this.config = config;
	}

	void setData(RegearData data)
	{
		this.data = data;
	}

	List<Placement> getPlacements()
	{
		return placements;
	}

	List<String> getMissingLabels()
	{
		return missingLabels;
	}

	boolean isOverlapDetected()
	{
		return overlapDetected;
	}

	static int slotToX(int slot)
	{
		return slot % COLUMNS * (ITEM_WIDTH + X_PADDING) + START_X;
	}

	static int slotToY(int slot)
	{
		return slot / COLUMNS * (ITEM_HEIGHT + Y_PADDING) + START_Y;
	}

	/**
	 * Rebuild the bank display for the current lists. Runs on the client thread. Hides only the items
	 * that are not current targets, and moves a target only when its slot actually changes, so
	 * repeated calls on an unchanged bank do no work and never flicker. Leaves the bank untouched when
	 * Regear has nothing to manage.
	 *
	 * @param currentTick current game tick, used to evaluate each lane's anti-spam hold
	 */
	void applyLayout(int currentTick)
	{
		placements.clear();
		missingLabels.clear();
		overlapDetected = false;

		if (data == null || !config.applyInBank())
		{
			return;
		}

		final Widget container = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (container == null)
		{
			return;
		}
		final Widget[] children = container.getDynamicChildren();
		if (children == null || children.length == 0)
		{
			return;
		}

		// Which item id currently sits in each usable bank widget (first widget wins per id).
		final Map<Integer, Widget> byItemId = new LinkedHashMap<>();
		final List<Widget> itemWidgets = new ArrayList<>();
		for (Widget w : children)
		{
			if (w == null || w.getItemId() == -1)
			{
				continue;
			}
			itemWidgets.add(w);
			byItemId.putIfAbsent(w.getItemId(), w);
		}
		if (itemWidgets.isEmpty())
		{
			return;
		}

		// Resolve the active target of every enabled list into a slot, tracking cross-list overlap.
		// A held lane (just withdrawn) still keeps the bank filtered but leaves its slot empty.
		final Map<Integer, Placement> bySlot = new LinkedHashMap<>();
		boolean managing = false;
		for (RegearList list : data.lists)
		{
			if (list == null || !list.enabled)
			{
				continue;
			}
			list.ensureLanes();
			final int lanes = list.laneCount();
			for (int lane = 0; lane < lanes; lane++)
			{
				final RegearItem item = list.activeItem(lane);
				final int slot = list.absoluteSlot(lane);
				if (item == null || slot < 0)
				{
					continue;
				}
				managing = true;
				if (list.isLaneHeld(lane, currentTick))
				{
					continue;
				}
				final Placement p = new Placement(list.name, lane, slot, item);
				p.next = list.nextItem(lane);
				if (bySlot.containsKey(slot))
				{
					overlapDetected = true;
					continue;
				}
				bySlot.put(slot, p);
			}
		}

		if (!managing)
		{
			// No enabled list has anything to manage: leave the bank exactly as the game built it.
			return;
		}

		// Resolve each target to a bank widget up front, so the hide pass can skip targets -- never
		// hide-then-show one, which would flicker it.
		final Set<Widget> used = new HashSet<>();
		int maxRow = 0;
		for (Placement p : bySlot.values())
		{
			final Widget w = byItemId.get(p.item.id);
			if (w == null)
			{
				p.missing = true;
				missingLabels.add(missingLabel(p));
				maxRow = Math.max(maxRow, p.slot / COLUMNS);
				placements.add(p);
				continue;
			}
			if (used.contains(w))
			{
				// The same stack cannot be shown in two slots; the extra lane is a duplicate.
				p.duplicate = true;
				placements.add(p);
				continue;
			}
			used.add(w);
			p.widget = w;
			maxRow = Math.max(maxRow, p.slot / COLUMNS);
			placements.add(p);
		}

		// Hide non-target items and position targets, touching only widgets whose state actually
		// changes, so redundant re-applies are no-ops (this is what stops the flicker).
		boolean changed = false;
		for (Widget w : itemWidgets)
		{
			if (!used.contains(w) && !w.isHidden())
			{
				w.setHidden(true);
				changed = true;
			}
		}
		for (Placement p : placements)
		{
			final Widget w = p.widget;
			if (w == null)
			{
				continue;
			}
			final int x = slotToX(p.slot);
			final int y = slotToY(p.slot);
			if (w.isHidden() || w.getOriginalX() != x || w.getOriginalY() != y)
			{
				w.setOriginalX(x);
				w.setOriginalY(y);
				w.setHidden(false);
				w.revalidate();
				changed = true;
			}
		}

		if (!changed)
		{
			return;
		}

		// Keep the placed rows in view and reflow once, only when something actually moved.
		if (container.getScrollY() != 0)
		{
			container.setScrollY(0);
		}
		if (client.getVarcIntValue(VarClientID.BANK_SCROLLPOS) != 0)
		{
			client.setVarcIntValue(VarClientID.BANK_SCROLLPOS, 0);
		}
		final int neededHeight = (maxRow + 1) * (ITEM_HEIGHT + Y_PADDING);
		if (container.getScrollHeight() < neededHeight)
		{
			container.setScrollHeight(neededHeight);
		}
		container.revalidate();

		log.debug("[bank] applied {} placement(s) (overlap={}, missing={}) from {} widget(s)",
			placements.size(), overlapDetected, missingLabels.size(), itemWidgets.size());
		for (Placement p : placements)
		{
			final String state = p.missing ? "MISSING" : p.duplicate ? "DUP" : "placed";
			log.debug("[bank]   '{}' lane {} -> slot {} id {} [{}]",
				p.listName, p.lane + 1, p.slot, p.item.id, state);
		}
	}

	private static String missingLabel(Placement p)
	{
		final String id = "id " + p.item.id;
		final String note = p.item.note != null && !p.item.note.isEmpty() ? p.item.note + " (" + id + ")" : id;
		return p.listName + ": " + note;
	}
}
