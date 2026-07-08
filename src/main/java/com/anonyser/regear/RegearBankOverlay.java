package com.anonyser.regear;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Annotates the bank on top of the repositioned items: a highlight box and lane number on each
 * active slot, an optional preview of the next item, and a marker where an active item is missing
 * from the bank. Reads the placements the {@link RegearBankController} recorded on the last rebuild.
 * Purely informational; it draws, it never interacts.
 */
class RegearBankOverlay extends Overlay
{
	private final Client client;
	private final RegearConfig config;
	private final ItemManager itemManager;
	private final RegearBankController controller;

	@Inject
	RegearBankOverlay(Client client, RegearConfig config, ItemManager itemManager,
		RegearBankController controller)
	{
		this.client = client;
		this.config = config;
		this.itemManager = itemManager;
		this.controller = controller;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.applyInBank())
		{
			return null;
		}
		final Widget container = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (container == null || container.isHidden())
		{
			return null;
		}
		final Point containerLoc = container.getCanvasLocation();

		graphics.setFont(FontManager.getRunescapeSmallFont());
		for (RegearBankController.Placement p : controller.getPlacements())
		{
			if (p.missing)
			{
				if (config.highlightMissing() && containerLoc != null)
				{
					drawMissing(graphics, slotRect(containerLoc, p.slot));
				}
				continue;
			}
			if (p.duplicate || p.widget == null)
			{
				continue;
			}
			final Rectangle b = p.widget.getBounds();
			if (b == null)
			{
				continue;
			}
			if (config.highlightActive())
			{
				graphics.setColor(config.activeColor());
				graphics.setStroke(new BasicStroke(2f));
				graphics.drawRect(b.x, b.y, b.width, b.height);
			}
			if (config.showLaneNumbers())
			{
				drawLaneNumber(graphics, b, p.lane + 1);
			}
			if (config.showNextPreview() && p.next != null)
			{
				drawNextPreview(graphics, b, p.next.id);
			}
			if (p.required > 1)
			{
				drawProgress(graphics, b, p.withdrawn, p.required);
			}
		}
		return null;
	}

	private void drawProgress(Graphics2D g, Rectangle b, int withdrawn, int required)
	{
		final String text = withdrawn + "/" + required;
		g.setFont(FontManager.getRunescapeSmallFont());
		final int x = b.x + 1;
		final int y = b.y + b.height - 1;
		g.setColor(Color.BLACK);
		g.drawString(text, x + 1, y + 1);
		g.setColor(new Color(120, 200, 255));
		g.drawString(text, x, y);
	}

	private static Rectangle slotRect(Point containerLoc, int slot)
	{
		return new Rectangle(
			containerLoc.getX() + RegearBankController.slotToX(slot),
			containerLoc.getY() + RegearBankController.slotToY(slot),
			RegearBankController.ITEM_WIDTH,
			RegearBankController.ITEM_HEIGHT);
	}

	private void drawMissing(Graphics2D g, Rectangle r)
	{
		g.setColor(config.missingColor());
		g.setStroke(new BasicStroke(2f));
		g.drawRect(r.x, r.y, r.width, r.height);
		g.drawLine(r.x, r.y, r.x + r.width, r.y + r.height);
		g.drawLine(r.x + r.width, r.y, r.x, r.y + r.height);
	}

	private void drawLaneNumber(Graphics2D g, Rectangle b, int number)
	{
		final String text = Integer.toString(number);
		final Font prev = g.getFont();
		g.setFont(prev.deriveFont(Font.BOLD, 13f));
		final int x = b.x + 1;
		final int y = b.y + 12;
		g.setColor(Color.BLACK);
		g.drawString(text, x + 1, y + 1);
		g.setColor(Color.WHITE);
		g.drawString(text, x, y);
		g.setFont(prev);
	}

	private void drawNextPreview(Graphics2D g, Rectangle b, int nextId)
	{
		final BufferedImage img = itemManager.getImage(nextId);
		if (img == null)
		{
			return;
		}
		final int size = 18;
		final int x = b.x + b.width - size;
		final int y = b.y + b.height - size;
		final Composite prev = g.getComposite();
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.7f));
		g.drawImage(img, x, y, size, size, null);
		g.setComposite(prev);
	}
}
