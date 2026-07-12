package com.anonyser.regear;

import java.awt.Color;
import java.awt.FontMetrics;
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
		final FontMetrics fm = graphics.getFontMetrics();
		final int tw = fm.stringWidth(text);
		final int x = bounds.x + (bounds.width - tw) / 2;
		final int y = bounds.y + (bounds.height + fm.getAscent()) / 2 - 1;
		// Dead centre of the icon, on a filled backing strip: the game's quantity owns the top-left
		// corner and charge counters / Regear's own withdraw progress own the bottom-left, so the
		// middle is the one spot where the id stays readable without piling onto other text.
		graphics.setColor(new Color(0, 0, 0, 180));
		graphics.fillRect(x - 2, y - fm.getAscent(), tw + 4, fm.getAscent() + 3);
		graphics.setColor(Color.WHITE);
		graphics.drawString(text, x, y);
	}
}
