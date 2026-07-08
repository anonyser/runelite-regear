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
import net.runelite.api.ScriptID;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
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
				bankController.applyLayout();
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
			bankController.applyLayout();
			SwingUtilities.invokeLater(this::refreshWarnings);
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		// Release any lanes held empty by the anti-spam limiter, one tick after their withdrawal,
		// then re-apply so the next item appears. This caps withdrawals at one per lane per tick.
		if (!bankOpen || data == null)
		{
			return;
		}
		boolean released = false;
		for (RegearList list : data.lists)
		{
			if (list != null && list.anyHeld())
			{
				list.clearHolds();
				released = true;
			}
		}
		if (released)
		{
			bankController.applyLayout();
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
				}
			}
			log.debug("[bank] opened; inventory baseline captured ({} stacks)", invCounts.size());
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == BANK_GROUP)
		{
			bankOpen = false;
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
				bankController.applyLayout();
				SwingUtilities.invokeLater(this::refreshWarnings);
			});
		}
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
		boolean advanced = false;
		for (Map.Entry<Integer, Integer> e : after.entrySet())
		{
			final int id = e.getKey();
			final int gained = e.getValue() - before.getOrDefault(id, 0);
			if (gained > 0 && advanceLaneForItem(id))
			{
				advanced = true;
			}
		}
		if (advanced)
		{
			save();
			bankController.applyLayout();
			SwingUtilities.invokeLater(this::refreshWarnings);
		}
	}

	private boolean advanceLaneForItem(int id)
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
				if (active != null && active.id == id)
				{
					list.advanceLane(lane, list.effectiveCompletion(config.defaultCompletion()));
					if (config.oneWithdrawPerTick())
					{
						list.holdLane(lane);
					}
					log.debug("[rotate] '{}' lane {} advanced on withdrawal of id {} (held={})",
						list.name, lane + 1, id, config.oneWithdrawPerTick());
					return true;
				}
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
