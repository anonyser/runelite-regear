package com.anonyser.regear;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.inject.Inject;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

/**
 * Draws each item's id in small white text over items in the bank, inventory and worn equipment,
 * so ids are easy to read while building a Regear list. Gated by a single config toggle.
 */
class RegearIdOverlay extends WidgetItemOverlay
{
	private final RegearConfig config;

	@Inject
	RegearIdOverlay(RegearConfig config)
	{
		this.config = config;
		showOnInventory();
		showOnBank();
		showOnEquipment();
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
	{
		if (!config.showItemIds())
		{
			return;
		}
		final Rectangle bounds = widgetItem.getCanvasBounds();
		if (bounds == null)
		{
			return;
		}
		final String text = Integer.toString(itemId);
		graphics.setFont(FontManager.getRunescapeSmallFont());
		final int x = bounds.x + 1;
		final int y = bounds.y + bounds.height - 1;
		// A one-pixel shadow keeps the white readable over bright item sprites.
		graphics.setColor(Color.BLACK);
		graphics.drawString(text, x + 1, y + 1);
		graphics.setColor(Color.WHITE);
		graphics.drawString(text, x, y);
	}
}
