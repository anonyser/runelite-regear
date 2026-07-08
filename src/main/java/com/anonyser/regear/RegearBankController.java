package com.anonyser.regear;

import java.util.ArrayList;
import java.util.HashMap;
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
import net.runelite.api.widgets.ItemQuantityMode;
import net.runelite.api.widgets.Widget;

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
		RegearList list;     // the list this placement belongs to (for click-accurate advancing)
		final int lane;      // 0-based lane index within its list
		final int slot;      // absolute bank slot
		final RegearItem item;
		RegearItem next;     // what this lane advances to next, for the preview; may be null
		Widget widget;       // the bank item widget moved here, or null if missing
		boolean missing;
		boolean duplicate;   // a second copy that could not get a spare widget to show it
		boolean synthetic;   // shown on a spare widget repurposed to display a repeated item
		int withdrawn;       // amount of this item withdrawn so far toward its required quantity
		int required;        // quantity to withdraw before the lane advances (>=1)
		int shownId;         // the id actually shown/withdrawn (primary, or an "or" alternative)

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
	private boolean managingBank;
	private boolean tutorialActive;
	private final RegearTutorial tutorial = new RegearTutorial();
	// Widgets we repurposed to show a duplicate item, mapped to their original item id so we can
	// restore them (and rebuild the item map cleanly) on the next apply.
	private final Map<Widget, Integer> synthOriginal = new HashMap<>();

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

	/** The placement currently shown on the given bank widget, or null. Used to advance the exact
	 *  lane whose slot was clicked, so several copies of one item stay counted correctly. */
	Placement placementForWidget(Widget w)
	{
		if (w == null)
		{
			return null;
		}
		for (Placement p : placements)
		{
			if (p.widget == w)
			{
				return p;
			}
		}
		return null;
	}

	List<String> getMissingLabels()
	{
		return missingLabels;
	}

	boolean isOverlapDetected()
	{
		return overlapDetected;
	}

	/** True while Regear is actively filtering/positioning the bank (so clicks should be remapped). */
	boolean isManaging()
	{
		return managingBank;
	}

	boolean isTutorialActive()
	{
		return tutorialActive;
	}

	void setTutorialActive(boolean active)
	{
		this.tutorialActive = active;
	}

	RegearTutorial getTutorial()
	{
		return tutorial;
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
		managingBank = false;

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

		// Restore any widgets we repurposed as duplicate copies last time, so the item map below is
		// built from the bank's real contents (and their original items return when no longer needed).
		if (!synthOriginal.isEmpty())
		{
			for (Map.Entry<Widget, Integer> e : synthOriginal.entrySet())
			{
				if (e.getKey() != null)
				{
					e.getKey().setItemId(e.getValue());
				}
			}
			synthOriginal.clear();
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
				p.list = list;
				p.next = list.nextItem(lane);
				p.withdrawn = list.getWithdrawn(lane);
				p.required = Math.max(1, item.quantity);
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
		managingBank = true;

		// Give the first lane wanting an item that item's own widget; a later lane wanting the SAME
		// item is deferred so we can repurpose a spare widget to show a second copy of it.
		final Set<Widget> used = new HashSet<>();
		final List<Placement> needDuplicate = new ArrayList<>();
		int maxRow = 0;
		for (Placement p : bySlot.values())
		{
			maxRow = Math.max(maxRow, p.slot / COLUMNS);
			// Show whichever of the item's ids (primary, then "or" alternatives) is actually in the bank.
			int shownId = p.item.id;
			Widget w = byItemId.get(shownId);
			if (w == null && p.item.alts != null)
			{
				for (int alt : p.item.alts)
				{
					final Widget aw = byItemId.get(alt);
					if (aw != null)
					{
						w = aw;
						shownId = alt;
						break;
					}
				}
			}
			p.shownId = shownId;
			if (w == null)
			{
				p.missing = true;
				missingLabels.add(missingLabel(p));
				placements.add(p);
				continue;
			}
			if (used.contains(w))
			{
				needDuplicate.add(p);
				placements.add(p);
				continue;
			}
			used.add(w);
			p.widget = w;
			placements.add(p);
		}

		boolean changed = false;

		// Repurpose a spare (non-target) bank widget to display each duplicate copy, so the same item
		// can appear in more than one slot. Clicks on it are routed to the item's real bank slot by
		// the plugin's menu handler (bank.find), so the withdraw still works.
		if (!needDuplicate.isEmpty())
		{
			final List<Widget> spares = new ArrayList<>();
			for (Widget w : itemWidgets)
			{
				if (!used.contains(w))
				{
					spares.add(w);
				}
			}
			int si = 0;
			for (Placement p : needDuplicate)
			{
				final Widget spare = si < spares.size() ? spares.get(si++) : null;
				if (spare == null)
				{
					p.duplicate = true; // no spare widget available to show this copy
					continue;
				}
				final Widget real = byItemId.get(p.shownId);
				synthOriginal.put(spare, spare.getItemId());
				spare.setItemId(p.shownId);
				spare.setItemQuantity(real != null ? real.getItemQuantity() : 1);
				spare.setItemQuantityMode(ItemQuantityMode.STACKABLE);
				used.add(spare);
				p.widget = spare;
				p.synthetic = true;
				changed = true;
			}
		}

		// Hide non-target items and position targets, touching only widgets whose state actually
		// changes, so redundant re-applies are no-ops (this is what stops the flicker).
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
			if (p.synthetic || w.isHidden() || w.getOriginalX() != x || w.getOriginalY() != y)
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
	}

	private static String missingLabel(Placement p)
	{
		final String id = "id " + p.item.id;
		final String note = p.item.note != null && !p.item.note.isEmpty() ? p.item.note + " (" + id + ")" : id;
		return p.listName + ": " + note;
	}
}
