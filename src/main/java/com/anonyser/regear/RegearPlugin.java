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
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.ScriptID;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
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
	description = "Guided banking: show a rotating set of your gear/potion/food items in fixed bank slots",
	tags = {"bank", "regear", "gear", "switch", "setup", "inventory", "pvp", "filter", "layout"}
)
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
	private RegearBankController bankController;

	@Inject
	private RegearIdOverlay idOverlay;

	@Inject
	private RegearBankOverlay bankOverlay;

	private RegearData data;
	private RegearPanel panel;
	private NavigationButton navButton;

	private boolean bankOpen;
	private int tick;
	private final Map<Integer, Integer> invCounts = new HashMap<>();

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
		log.debug("[life] Regear started with {} list(s)", data.lists.size());
	}

	@Override
	protected void shutDown()
	{
		log.debug("[life] Regear stopped");
		client.setInventoryDragDelay(DEFAULT_DRAG_DELAY);
		overlayManager.remove(idOverlay);
		overlayManager.remove(bankOverlay);
		clientToolbar.removeNavigation(navButton);
		navButton = null;
		panel = null;
		data = null;
		bankOpen = false;
		invCounts.clear();
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

	/** Persist the model, refresh the controller, and re-apply the bank layout if the bank is open. */
	void commit()
	{
		save();
		bankController.setData(data);
		if (bankOpen)
		{
			clientThread.invoke(() ->
			{
				bankController.applyLayout(tick);
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
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (event.getScriptId() == ScriptID.BANKMAIN_FINISHBUILDING)
		{
			bankController.applyLayout(tick);
			SwingUtilities.invokeLater(this::refreshWarnings);
		}
	}

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
			bankController.applyLayout(tick);
			SwingUtilities.invokeLater(this::refreshWarnings);
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == BANK_GROUP)
		{
			bankOpen = true;
			snapshotInventory();
			for (RegearList list : data.lists)
			{
				if (list != null)
				{
					list.clearHolds();
					list.clearWithdrawn();
				}
			}
			applyDragDelay();
			log.debug("[bank] opened; inventory baseline captured ({} stacks)", invCounts.size());
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
			log.debug("[bank] closed");
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() != InventoryID.INVENTORY.getId())
		{
			return;
		}

		final Map<Integer, Integer> before = new HashMap<>(invCounts);
		snapshotInventory();

		if (bankOpen)
		{
			advanceForWithdrawals(before, invCounts);
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
		if ("openPanel".equals(event.getKey()) && config.openPanel() && navButton != null)
		{
			clientToolbar.openPanel(navButton);
			configManager.setConfiguration(RegearConfig.GROUP, "openPanel", false);
			return;
		}
		// Any other Regear setting may change what the bank should show: re-apply while open.
		if (bankOpen)
		{
			clientThread.invoke(() ->
			{
				applyDragDelay();
				bankController.applyLayout(tick);
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
		// Append "Add to Regear" once per item, keyed off its Examine entry (bank/inventory/equipment).
		if (panel == null || !"Examine".equals(event.getOption()))
		{
			return;
		}
		final int id = event.getItemId();
		if (id <= 0)
		{
			return;
		}
		client.getMenu().createMenuEntry(-1)
			.setOption(ADD_OPTION)
			.setTarget(event.getTarget())
			.setType(MenuAction.RUNELITE)
			.onClick(e -> SwingUtilities.invokeLater(() -> panel.addItemToSelected(id)));
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		// While Regear is filtering the bank, a shown item may sit on a widget we repurposed to
		// display a duplicate. Route the click to that item's real bank slot so the withdraw works,
		// exactly as Bank Tag Layouts does. A no-op for an item already on its own widget.
		if (!bankController.isManaging())
		{
			return;
		}
		final MenuEntry menu = event.getMenuEntry();
		if (menu.getParam1() != InterfaceID.Bankmain.ITEMS)
		{
			return;
		}
		final Widget w = menu.getWidget();
		if (w == null || w.getItemId() <= -1)
		{
			return;
		}
		final ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		if (bank == null)
		{
			return;
		}
		final int idx = bank.find(w.getItemId());
		if (idx > -1 && menu.getParam0() != idx)
		{
			menu.setParam0(idx);
		}
	}

	// --- Rotation / detection ----------------------------------------------------------------------

	private void snapshotInventory()
	{
		invCounts.clear();
		final ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
		if (inv == null)
		{
			return;
		}
		for (Item item : inv.getItems())
		{
			if (item.getId() >= 0)
			{
				invCounts.merge(item.getId(), item.getQuantity(), Integer::sum);
			}
		}
	}

	/**
	 * For every item id whose inventory count rose, advance the leftmost active lane (across enabled
	 * lists) that is currently pointing at that id. Only a genuine gain in the inventory counts as
	 * evidence of a withdrawal, so hovers, right-click menus and unrelated changes never advance.
	 */
	private void advanceForWithdrawals(Map<Integer, Integer> before, Map<Integer, Integer> after)
	{
		boolean changed = false;
		for (Map.Entry<Integer, Integer> e : after.entrySet())
		{
			final int id = e.getKey();
			final int gained = e.getValue() - before.getOrDefault(id, 0);
			if (gained > 0 && creditWithdrawal(id, gained))
			{
				changed = true;
			}
		}
		if (changed)
		{
			save();
			bankController.applyLayout(tick);
			SwingUtilities.invokeLater(this::refreshWarnings);
		}
	}

	/**
	 * Credit a withdrawal of {@code gained} of item {@code id} to the leftmost active lane pointing at
	 * it. The lane only advances once the amount withdrawn reaches the item's required quantity, so a
	 * multi-withdraw item (e.g. 30 runes pulled 10 at a time) stays put until it is fully withdrawn.
	 * Returns true if a lane matched (advanced or made partial progress) so the display refreshes.
	 */
	private boolean creditWithdrawal(int id, int gained)
	{
		for (RegearList list : data.lists)
		{
			if (list == null || !list.enabled)
			{
				continue;
			}
			final int lanes = list.laneCount();
			for (int lane = 0; lane < lanes; lane++)
			{
				final RegearItem active = list.activeItem(lane);
				if (active == null || active.id != id)
				{
					continue;
				}
				final int required = Math.max(1, active.quantity);
				list.addWithdrawn(lane, gained);
				final int total = list.getWithdrawn(lane);
				if (total >= required)
				{
					list.advanceLane(lane, list.effectiveCompletion(config.defaultCompletion()));
					final int hold = config.holdTicks();
					if (hold > 0)
					{
						list.holdLane(lane, tick + hold);
					}
					log.debug("[rotate] '{}' lane {} advanced (withdrew {}/{} of id {})",
						list.name, lane + 1, total, required, id);
				}
				else
				{
					log.debug("[rotate] '{}' lane {} progress {}/{} of id {}",
						list.name, lane + 1, total, required, id);
				}
				return true;
			}
		}
		return false;
	}

	private boolean resetLists(CompletionBehavior trigger)
	{
		boolean any = false;
		for (RegearList list : data.lists)
		{
			if (list != null && list.effectiveCompletion(config.defaultCompletion()) == trigger)
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
