package com.anonyser.regear;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * A small movable panel that mirrors the player's worn equipment in the game's own layout (helm on
 * top; cape, amulet, ammo; weapon, body, shield; legs; gloves, boots, ring). It renders only while
 * the bank is open and reads the equipment container each frame, so it updates live as items are
 * equipped. Drag it anywhere like any overlay; it is display-only and never interacts.
 */
class RegearEquipmentOverlay extends Overlay
{
	private static final int SLOT = 36;      // matches the bank/inventory icon width
	private static final int GAP = 4;
	private static final int PAD = 6;
	private static final int COLS = 3;
	private static final int ROWS = 5;
	private static final int WIDTH = PAD * 2 + COLS * SLOT + (COLS - 1) * GAP;
	private static final int HEIGHT = PAD * 2 + ROWS * SLOT + (ROWS - 1) * GAP;

	private final Client client;
	private final RegearConfig config;
	private final ItemManager itemManager;

	@Inject
	RegearEquipmentOverlay(Client client, RegearConfig config, ItemManager itemManager)
	{
		this.client = client;
		this.config = config;
		this.itemManager = itemManager;
		setPosition(OverlayPosition.TOP_LEFT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setMovable(true);
		setSnappable(true);
		setResizable(false);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showEquipmentOverlay() || !bankOpen())
		{
			return null;
		}

		// Panel background so the cells read clearly wherever it is dragged.
		graphics.setColor(new Color(24, 24, 24, 220));
		graphics.fillRect(0, 0, WIDTH, HEIGHT);
		graphics.setColor(new Color(60, 60, 60));
		graphics.drawRect(0, 0, WIDTH - 1, HEIGHT - 1);

		final ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
		for (Cell cell : CELLS)
		{
			final int x = PAD + cell.col * (SLOT + GAP);
			final int y = PAD + cell.row * (SLOT + GAP);

			// The equipment cell background, matching the game's empty-slot look.
			graphics.setColor(new Color(40, 40, 40, 235));
			graphics.fillRect(x, y, SLOT, SLOT);
			graphics.setColor(new Color(70, 70, 70));
			graphics.drawRect(x, y, SLOT, SLOT);

			final Item item = equipment == null ? null : equipment.getItem(cell.slot.getSlotIdx());
			if (item == null || item.getId() <= 0)
			{
				continue;
			}
			final int qty = Math.max(1, item.getQuantity());
			final BufferedImage img = itemManager.getImage(item.getId(), qty, qty > 1);
			if (img != null)
			{
				// Centre the item sprite in the cell.
				graphics.drawImage(img, x + (SLOT - img.getWidth()) / 2, y + (SLOT - img.getHeight()) / 2, null);
			}
		}
		return new Dimension(WIDTH, HEIGHT);
	}

	private boolean bankOpen()
	{
		final Widget bank = client.getWidget(InterfaceID.Bankmain.ITEMS);
		return bank != null && !bank.isHidden();
	}

	/** One equipment cell: which worn slot it shows and where it sits in the 3x5 layout. */
	private static final class Cell
	{
		final EquipmentInventorySlot slot;
		final int col;
		final int row;

		Cell(EquipmentInventorySlot slot, int col, int row)
		{
			this.slot = slot;
			this.col = col;
			this.row = row;
		}
	}

	// The classic worn-equipment arrangement.
	private static final Cell[] CELLS = {
		new Cell(EquipmentInventorySlot.HEAD, 1, 0),
		new Cell(EquipmentInventorySlot.CAPE, 0, 1),
		new Cell(EquipmentInventorySlot.AMULET, 1, 1),
		new Cell(EquipmentInventorySlot.AMMO, 2, 1),
		new Cell(EquipmentInventorySlot.WEAPON, 0, 2),
		new Cell(EquipmentInventorySlot.BODY, 1, 2),
		new Cell(EquipmentInventorySlot.SHIELD, 2, 2),
		new Cell(EquipmentInventorySlot.LEGS, 1, 3),
		new Cell(EquipmentInventorySlot.GLOVES, 0, 4),
		new Cell(EquipmentInventorySlot.BOOTS, 1, 4),
		new Cell(EquipmentInventorySlot.RING, 2, 4),
	};
}
