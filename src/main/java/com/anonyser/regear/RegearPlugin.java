package com.anonyser.regear;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.banktags.BankTagsPlugin;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Regear: a guided banking tool. The player builds ordered withdrawal lists in the side panel; when
 * the bank is open, Regear filters it to a small rotating window of each enabled list and moves those
 * items into fixed, predictable positions so the same few bank slots can be clicked in rhythm. Each
 * lane advances only when the plugin sees the item actually leave the bank. Entirely display-only:
 * Regear never clicks, withdraws or moves anything for the player.
 */
@PluginDescriptor(
	name = "Regear",
	description = "Show the gear or inventory you set in fixed bank slots to withdraw in order (display only)",
	tags = {"bank", "regear", "gear", "switch", "setup", "inventory", "pvp", "filter", "withdraw"}
)
@PluginDependency(BankTagsPlugin.class)
public class RegearPlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(RegearPlugin.class);
	private static final String DATA_KEY = "data";
	private static final String ADD_OPTION = "Add to Regear";
	private static final String DEFAULT_LIST_NAME = "Main Regear";
	private static final int BANK_GROUP = InterfaceID.Bankmain.ITEMS >>> 16;
	private static final int DEFAULT_DRAG_DELAY = 5;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private Gson gson;

	@Inject
	private RegearConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private RegearBankController bankController;

	@Inject
	private RegearIdOverlay idOverlay;

	@Inject
	private RegearBankOverlay bankOverlay;

	@Inject
	private RegearEquipmentOverlay equipmentOverlay;

	private RegearData data;
	private RegearPanel panel;
	private NavigationButton navButton;

	private boolean bankOpen;
	private int tick;
	// Per-session auto-hide: set when the player clicks away to another bank tab, cleared when the bank
	// is reopened. Combines with the persisted overlayHidden flag to suppress the guide.
	private boolean autoHidden;

	// Intercepts bank clicks while the tutorial runs, so nothing is actually withdrawn and each click
	// on a green box advances the lesson instead.
	private final net.runelite.client.input.MouseListener tutorialMouse = new net.runelite.client.input.MouseAdapter()
	{
		@Override
		public java.awt.event.MouseEvent mousePressed(java.awt.event.MouseEvent e)
		{
			return handleTutorialClick(e);
		}
	};

	// Inventory snapshot marking the start of the current regear ("0 withdrawn from here"). Captured
	// when the bank opens and when a sequence is reset. Progress is current inventory minus this
	// baseline, so lane positions are derived from what you actually hold and self-correct on any
	// misclick, double-withdraw or wrong order.
	private final Map<Integer, Integer> baseline = new HashMap<>();

	@Provides
	RegearConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(RegearConfig.class);
	}

	@Override
	protected void startUp()
	{
		data = load();
		bankController.setData(data);

		panel = new RegearPanel(this, itemManager, clientThread);
		navButton = NavigationButton.builder()
			.tooltip("Regear")
			.icon(navIcon())
			.priority(9)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		panel.reload();

		overlayManager.add(idOverlay);
		overlayManager.add(bankOverlay);
		overlayManager.add(equipmentOverlay);
		mouseManager.registerMouseListener(tutorialMouse);
	}

	@Override
	protected void shutDown()
	{
		client.setInventoryDragDelay(DEFAULT_DRAG_DELAY);
		bankController.setTutorialActive(false);
		clientThread.invoke(bankController::unregister);
		mouseManager.unregisterMouseListener(tutorialMouse);
		overlayManager.remove(idOverlay);
		overlayManager.remove(bankOverlay);
		overlayManager.remove(equipmentOverlay);
		clientToolbar.removeNavigation(navButton);
		if (panel != null)
		{
			panel.stopTimers();
		}
		navButton = null;
		panel = null;
		data = null;
		bankOpen = false;
		baseline.clear();
	}

	// --- Panel API ---------------------------------------------------------------------------------

	RegearData getData()
	{
		return data;
	}

	RegearConfig getConfig()
	{
		return config;
	}

	/** Read the current inventory's item ids in slot order (client thread), then deliver them on the EDT. */
	void withInventoryIds(java.util.function.Consumer<List<Integer>> onEdt)
	{
		clientThread.invoke(() ->
		{
			final List<Integer> ids = new ArrayList<>();
			final ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
			if (inv != null)
			{
				for (Item it : inv.getItems())
				{
					if (it.getId() > 0)
					{
						ids.add(it.getId());
					}
				}
			}
			SwingUtilities.invokeLater(() -> onEdt.accept(ids));
		});
	}

	boolean isTutorialActive()
	{
		return bankController.isTutorialActive();
	}

	void toggleTutorial()
	{
		final boolean nowActive = !bankController.isTutorialActive();
		bankController.setTutorialActive(nowActive);
		if (nowActive)
		{
			if (bankOpen)
			{
				startTutorialContent();
			}
			// If the bank is closed the overlay shows "open the bank"; content starts on bank open.
		}
		else
		{
			bankController.getTutorial().reset();
		}
		// Turning the tutorial on releases the Regear bank filter (so the full bank shows); turning it
		// off re-applies it. applyLayout reads the tutorial flag and does the right thing.
		if (bankOpen)
		{
			clientThread.invoke(() ->
			{
				applyBankLayout();
				bankController.forceRebuild();
				SwingUtilities.invokeLater(this::refreshWarnings);
			});
		}
	}

	/** Read whatever bank is open and pick example items: gear if 3+ equippable, else any items. */
	private void startTutorialContent()
	{
		clientThread.invoke(() ->
		{
			final ItemContainer bank = client.getItemContainer(InventoryID.BANK);
			final List<Integer> any = new ArrayList<>();
			final List<Integer> equip = new ArrayList<>();
			final Set<Integer> seen = new HashSet<>();
			if (bank != null)
			{
				for (Item it : bank.getItems())
				{
					final int id = it.getId();
					if (id <= 0 || !seen.add(id))
					{
						continue;
					}
					any.add(id);
					final ItemStats st = itemManager.getItemStats(id);
					if (st != null && st.getEquipment() != null)
					{
						equip.add(id);
					}
					if (any.size() >= 28)
					{
						break;
					}
				}
			}
			final boolean gear = equip.size() >= 3;
			// Pass the full set (up to 28): the small steps use only the first few, while the
			// full-inventory step cycles through them to stand in for a whole inventory's worth.
			final List<Integer> examples = new ArrayList<>(gear ? equip : any);
			bankController.getTutorial().start(gear, examples);
		});
	}

	private java.awt.event.MouseEvent handleTutorialClick(java.awt.event.MouseEvent e)
	{
		if (!bankController.isTutorialActive() || !bankOpen)
		{
			return e;
		}
		final RegearTutorial t = bankController.getTutorial();
		final Widget container = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (container == null)
		{
			return e;
		}
		final java.awt.Rectangle cb = container.getBounds();
		final net.runelite.api.Point loc = container.getCanvasLocation();
		if (cb == null || loc == null || !cb.contains(e.getPoint()))
		{
			return e; // outside the bank items: let the click through (bank close button etc.)
		}
		if (!t.hasSteps())
		{
			return e;
		}
		if (t.isNotesStep())
		{
			t.clickBox(0);
			checkTutorialFinish();
		}
		else
		{
			for (int k = 0; k < t.boxCount(); k++)
			{
				final int slot = t.slotForBox(k);
				if (slot < 0)
				{
					continue;
				}
				final java.awt.Rectangle r = new java.awt.Rectangle(
					loc.getX() + RegearBankController.slotToX(slot),
					loc.getY() + RegearBankController.slotToY(slot), 36, 32);
				if (r.contains(e.getPoint()))
				{
					t.clickBox(k);
					checkTutorialFinish();
					break;
				}
			}
		}
		// Swallow every bank click while the tutorial is running, so nothing is actually withdrawn.
		e.consume();
		return e;
	}

	private void checkTutorialFinish()
	{
		if (bankController.getTutorial().isFinished())
		{
			bankController.setTutorialActive(false);
			bankController.getTutorial().reset();
			if (bankOpen)
			{
				clientThread.invoke(() ->
				{
					applyBankLayout();
					bankController.forceRebuild();
					SwingUtilities.invokeLater(this::refreshWarnings);
				});
			}
		}
	}

	void setHideTutorial(boolean hide)
	{
		configManager.setConfiguration(RegearConfig.GROUP, "hideTutorial", hide);
	}

	/** Persist the model, refresh the controller, and re-apply the bank layout if the bank is open. */
	void commit()
	{
		save();
		bankController.setData(data);
		if (bankOpen)
		{
			clientThread.invoke(() ->
			{
				reconcileAll();
				applyBankLayout();
				// A panel edit changes what the bank should show without any container change, so
				// nothing would rebuild the bank view until the next withdrawal or reopen -- force it.
				bankController.forceRebuild();
				SwingUtilities.invokeLater(this::refreshWarnings);
			});
		}
	}

	void refreshWarnings()
	{
		if (panel != null)
		{
			panel.setWarnings(new ArrayList<>(bankController.getMissingLabels()),
				bankController.isOverlapDetected());
		}
	}

	/** Refresh the guide's suppression state, then (re)apply the bank layout. Client thread only. */
	private void applyBankLayout()
	{
		bankController.setGuideSuppressed(config.overlayHidden() || autoHidden);
		bankController.applyLayout(tick);
	}

	/** True if the whole bank guide is currently hidden (persistent Hide overlay, or tab-away auto-hide). */
	boolean isOverlayGuideHidden()
	{
		return config.overlayHidden() || autoHidden;
	}

	/**
	 * Toggle the whole bank guide from the panel button. Hiding is persistent (stays off across bank
	 * opens until shown); showing also clears any per-session tab-away auto-hide.
	 */
	void toggleOverlayGuide()
	{
		final boolean hideNow = !isOverlayGuideHidden();
		if (!hideNow)
		{
			autoHidden = false;
		}
		configManager.setConfiguration(RegearConfig.GROUP, "overlayHidden", hideNow);
		if (bankOpen)
		{
			clientThread.invoke(() ->
			{
				applyBankLayout();
				bankController.forceRebuild();
				SwingUtilities.invokeLater(this::refreshWarnings);
			});
		}
	}

	/** Copy the given setup to shareable text; null-safe. */
	String exportSetup(RegearList list)
	{
		return list == null ? null : RegearShare.export(java.util.Collections.singletonList(list), gson);
	}

	/** Copy every setup to shareable text. */
	String exportAllSetups()
	{
		return RegearShare.export(data.lists, gson);
	}

	/** Add setups from share text, renaming any that clash on name (" (2)"). Returns how many were added. */
	int importSetups(String text)
	{
		final List<RegearList> incoming = RegearShare.parse(text, gson);
		if (incoming.isEmpty())
		{
			return 0;
		}
		final Set<String> lower = new HashSet<>();
		for (RegearList l : data.lists)
		{
			if (l != null && l.name != null)
			{
				lower.add(l.name.toLowerCase());
			}
		}
		final Set<Integer> freshIds = new HashSet<>();
		for (RegearList l : incoming)
		{
			l.name = RegearShare.uniqueName(l.name, lower);
			lower.add(l.name.toLowerCase());
			// A fresh list id locally, so importing the same share twice never yields two lists that
			// share an id (which a group reference could not tell apart). Groups aren't shared.
			l.id = RegearList.newId();
			data.lists.add(l);
			for (RegearItem it : l.items)
			{
				if (it != null)
				{
					freshIds.add(it.id);
					if (it.alts != null)
					{
						freshIds.addAll(it.alts);
					}
				}
			}
		}
		freshenBaseline(freshIds);
		commit();
		return incoming.size();
	}

	void setShowEquipment(boolean show)
	{
		configManager.setConfiguration(RegearConfig.GROUP, "showEquipmentOverlay", show);
	}

	void setShowItemIds(boolean show)
	{
		configManager.setConfiguration(RegearConfig.GROUP, "showItemIds", show);
	}

	/**
	 * Treat the given item ids as not-yet-withdrawn from here: raise their baseline to the current
	 * inventory count. Called when items are ADDED to a list while banking -- a copy already in the
	 * inventory (typically withdrawn moments before being added) would otherwise count as withdrawn
	 * progress and the fresh lane would park past the item instead of showing it in the bank. Also
	 * resets any progress other lists had recorded for the same id, which matches what adding an
	 * item mid-run means: start it fresh.
	 */
	void freshenBaseline(java.util.Collection<Integer> ids)
	{
		if (ids.isEmpty() || !bankOpen)
		{
			return;
		}
		clientThread.invoke(() ->
		{
			final Map<Integer, Integer> cur = currentInventory();
			for (int id : ids)
			{
				baseline.put(id, cur.getOrDefault(id, 0));
			}
		});
	}

	// --- Persistence -------------------------------------------------------------------------------

	private RegearData load()
	{
		final String json = configManager.getConfiguration(RegearConfig.GROUP, DATA_KEY);
		RegearData loaded = null;
		if (json != null && !json.isEmpty())
		{
			try
			{
				loaded = gson.fromJson(json, RegearData.class);
			}
			catch (Exception e)
			{
				log.warn("[regear] could not parse saved lists, starting empty", e);
			}
		}
		if (loaded == null)
		{
			loaded = new RegearData();
		}
		loaded.normalize();
		return loaded;
	}

	private void save()
	{
		configManager.setConfiguration(RegearConfig.GROUP, DATA_KEY, gson.toJson(data));
	}

	// --- Events ------------------------------------------------------------------------------------

	@Subscribe
	public void onGameTick(GameTick event)
	{
		// Advance the tick clock, and when a lane's hold window ends re-apply so its next item
		// appears. Only re-applies on the exact release tick, so idle ticks do no work.
		tick++;
		if (!bankOpen || data == null)
		{
			return;
		}
		// If the guide is showing and the player clicked away to a different bank tab, auto-hide it so it
		// stops re-filtering the bank back to Regear. It re-arms next time the bank is opened.
		if (!config.overlayHidden() && !autoHidden && bankController.userLeftOurTag())
		{
			autoHidden = true;
			applyBankLayout();
			SwingUtilities.invokeLater(this::refreshWarnings);
			return;
		}
		boolean releaseDue = false;
		for (RegearList list : data.lists)
		{
			if (list != null && list.releaseDue(tick))
			{
				releaseDue = true;
			}
		}
		if (releaseDue)
		{
			applyBankLayout();
			SwingUtilities.invokeLater(this::refreshWarnings);
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == BANK_GROUP)
		{
			bankOpen = true;
			// A fresh bank open re-arms the guide: the tab-away auto-hide is only for the session.
			autoHidden = false;
			captureBaseline();
			for (RegearList list : data.lists)
			{
				if (list != null)
				{
					list.clearHolds();
				}
			}
			reconcileAll();
			applyBankLayout();
			applyDragDelay();
			SwingUtilities.invokeLater(this::refreshWarnings);
			if (bankController.isTutorialActive() && !bankController.getTutorial().hasSteps())
			{
				startTutorialContent();
			}
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == BANK_GROUP)
		{
			bankOpen = false;
			applyDragDelay();
			if (resetLists(CompletionBehavior.RESET_ON_BANK_CLOSE))
			{
				commit();
			}
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		final int containerId = event.getContainerId();
		if (containerId == InventoryID.BANK.getId())
		{
			// A deposit landing can change what the guide should show: the inventory event can fire
			// before the bank container holds the deposited item, so the layout pass reads it as
			// missing. This later bank event repopulates its slot live. Only re-apply when a lane
			// moved or a slot is showing as missing -- the deposit may be exactly what it waits
			// for -- so plain withdrawals (already applied via the inventory event) skip the
			// second full relayout.
			if (bankOpen)
			{
				final boolean changed = reconcileAll();
				if (changed)
				{
					save();
				}
				if (changed || !bankController.getMissingLabels().isEmpty())
				{
					applyBankLayout();
					SwingUtilities.invokeLater(this::refreshWarnings);
				}
			}
			return;
		}
		if (containerId != InventoryID.INVENTORY.getId())
		{
			return;
		}

		if (bankOpen)
		{
			if (reconcileAll())
			{
				save();
				applyBankLayout();
				SwingUtilities.invokeLater(this::refreshWarnings);
			}
		}
		else if (resetLists(CompletionBehavior.RESET_ON_INVENTORY_CHANGE))
		{
			commit();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!RegearConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		// Our own list saves land here too (save() writes the data key). Everything a save should
		// trigger is already done explicitly by the caller, so skip it: otherwise every withdrawal
		// would re-apply the bank layout a second time for nothing.
		if (DATA_KEY.equals(event.getKey()))
		{
			return;
		}
		if ("openPanel".equals(event.getKey()) && config.openPanel() && navButton != null)
		{
			clientToolbar.openPanel(navButton);
			configManager.setConfiguration(RegearConfig.GROUP, "openPanel", false);
			return;
		}
		// Keep the side panel's mirrored toggles (item ids, equipment, on-finish info) in step with
		// the plugin options, whichever side the change came from.
		if (panel != null)
		{
			final RegearPanel p = panel;
			SwingUtilities.invokeLater(p::syncFromConfig);
		}
		// Any other Regear setting may change what the bank should show: re-apply while open.
		if (bankOpen)
		{
			clientThread.invoke(() ->
			{
				applyDragDelay();
				applyBankLayout();
				bankController.forceRebuild();
				SwingUtilities.invokeLater(this::refreshWarnings);
			});
		}
	}

	/**
	 * Raise the item drag delay while the bank is open so rapid withdraw clicks are not misread as
	 * item drags (the Anti-Drag mechanism); restore the client default otherwise.
	 */
	private void applyDragDelay()
	{
		final int delay = bankOpen && config.bankDragDelay() > 0 ? config.bankDragDelay() : DEFAULT_DRAG_DELAY;
		client.setInventoryDragDelay(delay);
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (panel == null)
		{
			return;
		}
		// Append "Add to Regear" once per item, keyed off its Examine entry (bank/inventory/equipment).
		// The inventory beside an OPEN bank has no Examine option (only Deposit-x), so there key off
		// Deposit-All instead -- still exactly one entry per item.
		final boolean examine = "Examine".equals(event.getOption());
		final boolean bankSideInventory = !examine
			&& "Deposit-All".equals(event.getOption())
			&& event.getActionParam1() == InterfaceID.Bankside.ITEMS;
		if (!examine && !bankSideInventory)
		{
			return;
		}
		int id = event.getItemId();
		if (id <= 0 && event.getMenuEntry().getWidget() != null)
		{
			id = event.getMenuEntry().getWidget().getItemId();
		}
		if (id <= 0)
		{
			return;
		}
		final int itemId = id;
		client.getMenu().createMenuEntry(-1)
			.setOption(ADD_OPTION)
			.setTarget(event.getTarget())
			.setType(MenuAction.RUNELITE)
			.onClick(e -> SwingUtilities.invokeLater(() -> panel.addItemToSelected(itemId)));
	}

	// --- Rotation / detection ----------------------------------------------------------------------

	private void captureBaseline()
	{
		baseline.clear();
		baseline.putAll(currentInventory());
	}

	private Map<Integer, Integer> currentInventory()
	{
		final Map<Integer, Integer> counts = new HashMap<>();
		final ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
		if (inv != null)
		{
			for (Item item : inv.getItems())
			{
				if (item.getId() >= 0)
				{
					counts.merge(item.getId(), item.getQuantity(), Integer::sum);
				}
			}
		}
		return counts;
	}

	/** Re-derive every enabled list's lane positions from what has actually been withdrawn. Returns
	 *  true if anything moved, so the caller can re-apply the bank display. */
	private boolean reconcileAll()
	{
		if (data == null)
		{
			return false;
		}
		final Map<Integer, Integer> cur = currentInventory();
		// A deposit lowers the baseline, so an item you put back then withdraw again still counts.
		for (Map.Entry<Integer, Integer> b : baseline.entrySet())
		{
			final int c = cur.getOrDefault(b.getKey(), 0);
			if (c < b.getValue())
			{
				b.setValue(c);
			}
		}
		// Withdrawn per item = current inventory minus the (running-minimum) baseline.
		final Map<Integer, Integer> withdrawn = new HashMap<>();
		for (Map.Entry<Integer, Integer> e : cur.entrySet())
		{
			final int w = e.getValue() - baseline.getOrDefault(e.getKey(), 0);
			if (w > 0)
			{
				withdrawn.put(e.getKey(), w);
			}
		}
		final Set<Integer> worn = equippedIds();
		boolean changed = false;
		for (RegearList list : data.lists)
		{
			if (list != null && list.enabled && reconcile(list, new HashMap<>(withdrawn), worn))
			{
				changed = true;
			}
		}
		// Loop to start: once every enabled list has run past its end, restart the whole sequence
		// from the current inventory so the rotation goes around again. Whole-sequence because the
		// baseline (what "withdrawn" is measured against) is shared across lists. Skipped when the
		// restart would leave nothing pending (e.g. every item skip-if-worn and worn) -- otherwise a
		// list that completes with zero withdrawals would restart again on every event, forever.
		if (config.defaultCompletion() == CompletionBehavior.LOOP && anyEnabledComplete()
			&& restartWouldPend(worn))
		{
			captureBaseline();
			for (RegearList list : data.lists)
			{
				if (list == null || !list.enabled)
				{
					continue;
				}
				list.resetLanes();
				// Re-derive immediately so worn skip-if-worn items are skipped, not shown missing.
				reconcile(list, new HashMap<>(), worn);
				// resetLanes cleared the anti-mash holds, including the one just armed on the slot
				// that finished the round -- re-arm every lane so a mash at the rollover cannot
				// instantly pull the new round's items.
				if (config.holdTicks() > 0)
				{
					for (int k = 0; k < list.laneCount(); k++)
					{
						list.holdLane(k, tick + config.holdTicks());
					}
				}
			}
			changed = true;
		}
		return changed;
	}

	/** True if restarting the sequence would leave at least one item actually pending withdrawal. */
	private boolean restartWouldPend(Set<Integer> worn)
	{
		for (RegearList list : data.lists)
		{
			if (list == null || !list.enabled)
			{
				continue;
			}
			for (RegearItem it : list.items)
			{
				if (it != null && !(it.skipIfWorn && wornCovers(it, worn)))
				{
					return true;
				}
			}
		}
		return false;
	}

	/** True if at least one enabled, non-empty list exists and every such list has finished. */
	private boolean anyEnabledComplete()
	{
		boolean any = false;
		for (RegearList list : data.lists)
		{
			if (list == null || !list.enabled || list.items.isEmpty())
			{
				continue;
			}
			if (!list.isComplete())
			{
				return false;
			}
			any = true;
		}
		return any;
	}

	private Set<Integer> equippedIds()
	{
		final Set<Integer> ids = new HashSet<>();
		final ItemContainer eq = client.getItemContainer(InventoryID.EQUIPMENT);
		if (eq != null)
		{
			for (Item item : eq.getItems())
			{
				if (item.getId() >= 0)
				{
					ids.add(item.getId());
				}
			}
		}
		return ids;
	}

	private static boolean wornCovers(RegearItem it, Set<Integer> worn)
	{
		if (worn.contains(it.id))
		{
			return true;
		}
		if (it.alts != null)
		{
			for (int alt : it.alts)
			{
				if (worn.contains(alt))
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Point each lane of a list at the first item in its column that has not yet been withdrawn,
	 * consuming {@code avail} (the withdrawn multiset) greedily in list order. Because it is derived
	 * from what you actually hold, the sequence self-corrects on any misclick or double-withdraw.
	 */
	private boolean reconcile(RegearList list, Map<Integer, Integer> avail, Set<Integer> worn)
	{
		list.ensureLanes();
		final int n = list.items.size();
		final int[] satisfied = new int[n];
		for (int i = 0; i < n; i++)
		{
			final RegearItem it = list.items.get(i);
			final int req = Math.max(1, it.quantity);
			int got = 0;
			if (it.skipIfWorn && wornCovers(it, worn))
			{
				got = req; // you are wearing it -- treat as satisfied, do not show it
			}
			else
			{
				while (got < req && consume(avail, it))
				{
					got++;
				}
			}
			satisfied[i] = got;
		}
		final int lanes = list.laneCount();
		boolean changed = false;
		for (int k = 0; k < lanes; k++)
		{
			int idx = k;
			while (idx < n && satisfied[idx] >= Math.max(1, list.items.get(idx).quantity))
			{
				idx += lanes;
			}
			final int oldCursor = list.laneCursors[k];
			if (idx != oldCursor)
			{
				// Briefly hold a lane that just advanced so a mash does not run straight into the next.
				if (idx > oldCursor && config.holdTicks() > 0)
				{
					list.holdLane(k, tick + config.holdTicks());
				}
				list.laneCursors[k] = idx;
				changed = true;
			}
			final int progress = idx < n ? satisfied[idx] : 0;
			if (list.getWithdrawn(k) != progress)
			{
				list.setWithdrawn(k, progress);
				changed = true;
			}
		}
		return changed;
	}

	private boolean consume(Map<Integer, Integer> avail, RegearItem it)
	{
		if (tryConsume(avail, it.id))
		{
			return true;
		}
		if (it.alts != null)
		{
			for (int alt : it.alts)
			{
				if (tryConsume(avail, alt))
				{
					return true;
				}
			}
		}
		return false;
	}

	private static boolean tryConsume(Map<Integer, Integer> avail, int id)
	{
		final Integer c = avail.get(id);
		if (c != null && c > 0)
		{
			avail.put(id, c - 1);
			return true;
		}
		return false;
	}

	/** Reset one list's sequence, re-baselining to the current inventory so it starts over from here. */
	void resetSequence(RegearList target)
	{
		if (target == null)
		{
			return;
		}
		clientThread.invoke(() ->
		{
			captureBaseline();
			target.resetLanes();
			reconcileAll();
			save();
			applyBankLayout();
			bankController.forceRebuild();
			SwingUtilities.invokeLater(this::refreshWarnings);
		});
	}

	/** Reset every list's sequence and re-baseline to the current inventory. */
	void resetAll()
	{
		clientThread.invoke(() ->
		{
			captureBaseline();
			for (RegearList list : data.lists)
			{
				if (list != null)
				{
					list.resetLanes();
				}
			}
			reconcileAll();
			save();
			applyBankLayout();
			bankController.forceRebuild();
			SwingUtilities.invokeLater(this::refreshWarnings);
		});
	}

	private boolean resetLists(CompletionBehavior trigger)
	{
		if (config.defaultCompletion() != trigger)
		{
			return false;
		}
		boolean any = false;
		for (RegearList list : data.lists)
		{
			if (list != null)
			{
				list.resetLanes();
				any = true;
			}
		}
		return any;
	}

	/** A small stacked-boxes icon for the side-panel toolbar button. */
	private static BufferedImage navIcon()
	{
		final BufferedImage img = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(220, 220, 220));
		g.setStroke(new BasicStroke(2f));
		g.drawRect(4, 3, 9, 9);
		g.drawRect(11, 12, 9, 9);
		g.setColor(new Color(0, 200, 83));
		g.fillRect(6, 5, 5, 5);
		g.dispose();
		return img;
	}
}
