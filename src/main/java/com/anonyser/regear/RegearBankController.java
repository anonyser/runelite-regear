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
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.banktags.BankTagsPlugin;
import net.runelite.client.plugins.banktags.BankTagsService;
import net.runelite.client.plugins.banktags.TagManager;
import net.runelite.client.plugins.banktags.tabs.Layout;
import net.runelite.client.plugins.banktags.tabs.LayoutManager;

/**
 * The bank layout engine. When the bank is open, this resolves the active target of each enabled list
 * into a fixed bank slot and drives RuneLite's core Bank Tags layout system to show only those items,
 * each in its configured position, so the same few bank slots can be clicked in rhythm.
 *
 * <p>Rather than repositioning bank widgets itself, Regear feeds core: it registers a live filter
 * predicate (a {@link net.runelite.client.plugins.banktags.BankTag}) for a private hidden tag, builds a
 * {@link Layout} mapping each active item to its slot, hands it to core's {@link LayoutManager} with
 * {@link LayoutManager#saveLayout(Layout)}, and opens the tag via
 * {@link BankTagsPlugin#openTag(String, Layout, int)} so core filters and lays the bank out live. It is
 * display-only: it never clicks or withdraws; the player does every click.</p>
 */
@Singleton
class RegearBankController
{
	// Bank grid geometry, matching RuneLite core (BankTagsPlugin): 8 columns of 36x32 icons. Still used
	// by the overlays and tutorial to draw at the slots core positions items in.
	static final int COLUMNS = 8;
	static final int ITEM_WIDTH = 36;
	static final int ITEM_HEIGHT = 32;
	static final int X_PADDING = 12;
	static final int Y_PADDING = 4;
	static final int START_X = 51;
	static final int START_Y = 0;

	// A private, hidden bank-tag name Regear drives. Chosen to be unlikely to collide with a real user
	// tag; it never appears in the tag bar (setHidden) and is filtered by a registered predicate, so no
	// per-item tags are ever written to the user's config.
	private static final String REGEAR_TAG = "regearguide";

	/** One positioned (or missing) active item, recorded for the overlay to annotate. */
	static final class Placement
	{
		final String listName;
		RegearList list;     // the list this placement belongs to
		final int lane;      // 0-based lane index within its list
		final int slot;      // absolute bank slot
		final RegearItem item;
		RegearItem next;     // what this lane advances to next, for the preview; may be null
		boolean missing;     // the active item (and any "or" alternative) is not in the bank
		int withdrawn;       // amount of this item withdrawn so far toward its required quantity
		int required;        // quantity to withdraw before the lane advances (>=1)
		int shownId;         // the canonical id actually shown/withdrawn (primary, or an "or" alternative)

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
	private final ItemManager itemManager;
	private final BankTagsPlugin bankTags;
	private final TagManager tagManager;
	private final LayoutManager layoutManager;

	private RegearData data;
	private final List<Placement> placements = new ArrayList<>();
	private final List<String> missingLabels = new ArrayList<>();
	private boolean overlapDetected;
	private boolean tutorialActive;
	private final RegearTutorial tutorial = new RegearTutorial();

	// The set of (canonical) item ids the bank should currently show. The registered filter predicate
	// reads this live, so advancing the window is just a matter of updating this set and re-opening.
	private final Set<Integer> windowIds = new HashSet<>();
	private boolean tagRegistered;

	// When suppressed (the panel's Hide overlay button, or the tab-away auto-hide), the guide releases
	// the bank and draws nothing until shown again. Owned by the plugin; refreshed before each apply.
	private boolean guideSuppressed;
	// True while our tag is the one we last opened. Lets the plugin notice the player navigating to a
	// different bank tab (our tag is no longer active) and auto-hide the guide.
	private boolean lastApplied;

	@Inject
	RegearBankController(Client client, RegearConfig config, ItemManager itemManager,
		BankTagsPlugin bankTags, TagManager tagManager, LayoutManager layoutManager)
	{
		this.client = client;
		this.config = config;
		this.itemManager = itemManager;
		this.bankTags = bankTags;
		this.tagManager = tagManager;
		this.layoutManager = layoutManager;
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

	void setGuideSuppressed(boolean suppressed)
	{
		this.guideSuppressed = suppressed;
	}

	boolean isGuideSuppressed()
	{
		return guideSuppressed;
	}

	/**
	 * True if the guide was showing but our tag is no longer the active one -- i.e. the player clicked
	 * a different bank tab. Used to auto-hide the guide so it stops fighting the tab they chose.
	 */
	boolean userLeftOurTag()
	{
		return lastApplied && !REGEAR_TAG.equals(bankTags.getActiveTag());
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
	 * Recompute the active targets and drive core to filter + lay the bank out. Runs on the client
	 * thread. Registers the hidden Regear tag on first use. When nothing should be managed (disabled,
	 * tutorial running, or no enabled list has an active target) it releases the tag so the bank returns
	 * to normal.
	 *
	 * @param currentTick current game tick, used to evaluate each lane's anti-spam hold
	 */
	void applyLayout(int currentTick)
	{
		placements.clear();
		missingLabels.clear();
		overlapDetected = false;

		if (data == null || !config.applyInBank() || tutorialActive || guideSuppressed)
		{
			release();
			return;
		}

		final ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		if (bank == null)
		{
			return;
		}

		// Canonical ids currently in the bank, for the "or"-alternative pick and the missing check.
		final Set<Integer> bankIds = new HashSet<>();
		for (Item it : bank.getItems())
		{
			if (it.getId() >= 0)
			{
				bankIds.add(itemManager.canonicalize(it.getId()));
			}
		}

		// Resolve the active target of every enabled list into a slot, tracking cross-list overlap. A
		// held lane (just withdrawn) still keeps the bank filtered but leaves its slot empty.
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
			// No enabled list has anything to manage: hand the bank back to the game.
			release();
			return;
		}

		// Build the filter set + the layout from the resolved placements.
		final Layout layout = new Layout(REGEAR_TAG);
		final Set<Integer> newWindow = new HashSet<>();
		for (Placement p : bySlot.values())
		{
			// Show whichever of the item's ids (primary, then "or" alternatives) is actually in the bank.
			int shownId = -1;
			final int primary = itemManager.canonicalize(p.item.id);
			if (bankIds.contains(primary))
			{
				shownId = primary;
			}
			else if (p.item.alts != null)
			{
				for (int alt : p.item.alts)
				{
					final int c = itemManager.canonicalize(alt);
					if (bankIds.contains(c))
					{
						shownId = c;
						break;
					}
				}
			}
			if (shownId < 0)
			{
				p.missing = true;
				p.shownId = primary;
				missingLabels.add(missingLabel(p));
				placements.add(p);
				continue;
			}
			p.shownId = shownId;
			layout.setItemAtPos(shownId, p.slot);
			newWindow.add(shownId);
			placements.add(p);
		}

		// Update the live filter set, then drive core to filter + lay out the bank. openTag relayouts
		// immediately, so the window advances live as items are withdrawn.
		windowIds.clear();
		windowIds.addAll(newWindow);
		ensureTagRegistered();
		// Store the layout on the tag through core's LayoutManager, then open it. openTag applies the same
		// layout immediately (relayout) so the window advances live as items are withdrawn.
		layoutManager.saveLayout(layout);
		bankTags.openTag(REGEAR_TAG, layout, BankTagsService.OPTION_HIDE_TAG_NAME);
		lastApplied = true;
	}

	/** Register the hidden filter predicate once. The predicate reads {@link #windowIds} live. */
	private void ensureTagRegistered()
	{
		if (!tagRegistered)
		{
			tagManager.registerTag(REGEAR_TAG, id -> windowIds.contains(itemManager.canonicalize(id)));
			tagManager.setHidden(REGEAR_TAG, true);
			tagRegistered = true;
		}
	}

	/** Close our tag if it is the one currently open, returning the bank to its normal view. */
	private void release()
	{
		lastApplied = false;
		if (REGEAR_TAG.equals(bankTags.getActiveTag()))
		{
			bankTags.closeBankTag();
		}
	}

	/** Release the bank and drop the registered tag. Called on shutdown, on the client thread. */
	void unregister()
	{
		release();
		if (tagRegistered)
		{
			tagManager.unregisterTag(REGEAR_TAG);
			layoutManager.removeLayout(REGEAR_TAG);
			tagRegistered = false;
		}
		windowIds.clear();
		placements.clear();
		missingLabels.clear();
	}

	private static String missingLabel(Placement p)
	{
		final String id = "id " + p.item.id;
		final String note = p.item.note != null && !p.item.note.isEmpty() ? p.item.note + " (" + id + ")" : id;
		return p.listName + ": " + note;
	}
}
