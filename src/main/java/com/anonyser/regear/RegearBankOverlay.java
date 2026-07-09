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
		if (controller.isTutorialActive())
		{
			final Widget bank = client.getWidget(InterfaceID.Bankmain.ITEMS);
			if (bank == null || bank.isHidden())
			{
				drawOpenBankMessage(graphics);
			}
			else
			{
				drawTutorial(graphics, bank);
			}
			return null;
		}
		if (!config.applyInBank() || controller.isGuideSuppressed())
		{
			return null;
		}
		final Widget container = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (container == null || container.isHidden())
		{
			return null;
		}
		final Rectangle view = container.getBounds();
		if (view == null)
		{
			return null;
		}
		// The bank scrolls: offset each slot by the current scroll so the highlights track the items,
		// and only draw a box whose cell is fully within the visible area, so it disappears when the
		// item is scrolled out of view and pops back onto it when scrolled back.
		final int scrollY = container.getScrollY();
		graphics.setFont(FontManager.getRunescapeSmallFont());
		for (RegearBankController.Placement p : controller.getPlacements())
		{
			final Rectangle b = slotRect(view, scrollY, p.slot);
			if (b == null)
			{
				continue; // this slot is scrolled out of the visible bank area
			}
			if (p.missing)
			{
				if (config.highlightMissing())
				{
					drawMissing(graphics, b);
				}
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

	private void drawOpenBankMessage(Graphics2D g)
	{
		final String msg = "Open the bank to start the Regear tutorial";
		g.setFont(FontManager.getRunescapeBoldFont());
		final int w = g.getFontMetrics().stringWidth(msg) + 24;
		final int h = 34;
		final int x = Math.max(4, (client.getCanvasWidth() - w) / 2);
		final int y = 70;
		final boolean on = System.currentTimeMillis() / 450 % 2 == 0;
		g.setColor(new Color(0, 0, 0, 205));
		g.fillRoundRect(x, y, w, h, 8, 8);
		g.setStroke(new BasicStroke(3f));
		g.setColor(on ? new Color(0, 220, 60) : new Color(230, 210, 0));
		g.drawRoundRect(x, y, w, h, 8, 8);
		g.setColor(Color.WHITE);
		g.drawString(msg, x + 12, y + 22);
	}

	private void drawTutorial(Graphics2D g, Widget container)
	{
		final Rectangle cb = container.getBounds();
		final Point loc = container.getCanvasLocation();
		if (cb == null || loc == null)
		{
			return;
		}
		final RegearTutorial t = controller.getTutorial();

		// Dim the whole bank red so the green targets stand out.
		g.setColor(new Color(150, 20, 20, 90));
		g.fillRect(cb.x, cb.y, cb.width, cb.height);

		if (!t.hasSteps())
		{
			drawStepBanner(g, cb, "Add a few items to your bank",
				"The tutorial needs at least two items to demonstrate.", null);
			return;
		}
		if (t.isNotesStep())
		{
			drawNotes(g, cb);
			return;
		}
		final RegearTutorial.Step step = t.current();
		if (step == null)
		{
			return;
		}
		final String progress = t.isRotatingStep() ? t.stepProgress() + " / " + t.stepTotal() : null;
		drawStepBanner(g, cb, "(" + t.stepNumber() + "/" + t.stepCount() + ")  " + step.title, step.instruction, progress);

		final boolean pulse = System.currentTimeMillis() / 350 % 2 == 0;
		final int expected = t.expectedBox();
		for (int k = 0; k < t.boxCount(); k++)
		{
			final int slot = t.slotForBox(k);
			if (slot < 0)
			{
				continue;
			}
			final int x = loc.getX() + RegearBankController.slotToX(slot);
			final int y = loc.getY() + RegearBankController.slotToY(slot);
			final boolean done = k < t.clicked();
			final boolean next = k == expected;
			g.setColor(new Color(0, 0, 0, 215));
			g.fillRect(x - 1, y - 1, 38, 34);
			final BufferedImage img = itemManager.getImage(t.itemForBox(k));
			if (img != null)
			{
				g.drawImage(img, x, y, null);
			}
			g.setStroke(new BasicStroke(next && pulse ? 3f : 2f));
			g.setColor(done ? new Color(90, 90, 90) : next ? new Color(0, 255, 90) : new Color(0, 170, 60));
			g.drawRect(x - 1, y - 1, 38, 34);
			g.setFont(FontManager.getRunescapeBoldFont());
			g.setColor(Color.BLACK);
			g.drawString(String.valueOf(k + 1), x + 2, y + 12);
			g.setColor(done ? new Color(170, 170, 170) : Color.WHITE);
			g.drawString(String.valueOf(k + 1), x + 1, y + 11);
		}
	}

	private void drawStepBanner(Graphics2D g, Rectangle cb, String title, String instruction, String progress)
	{
		final int x = cb.x;
		final int y = cb.y - 44;
		final int w = Math.max(200, cb.width);
		final int h = 40;
		g.setColor(new Color(0, 0, 0, 215));
		g.fillRoundRect(x, y, w, h, 8, 8);
		g.setStroke(new BasicStroke(2f));
		g.setColor(new Color(0, 200, 60));
		g.drawRoundRect(x, y, w, h, 8, 8);
		g.setFont(FontManager.getRunescapeBoldFont());
		g.setColor(Color.WHITE);
		g.drawString(title, x + 8, y + 16);
		if (progress != null)
		{
			// Right-aligned on the title row so it never pushes the instruction out of the box.
			final int pw = g.getFontMetrics().stringWidth(progress);
			g.setColor(new Color(140, 255, 140));
			g.drawString(progress, x + w - pw - 10, y + 16);
		}
		if (instruction != null)
		{
			g.setFont(FontManager.getRunescapeSmallFont());
			g.setColor(new Color(200, 255, 200));
			g.drawString(instruction, x + 8, y + 32);
		}
	}

	private void drawNotes(Graphics2D g, Rectangle cb)
	{
		// Solid dark panel so the text is readable over the busy bank behind it.
		g.setColor(new Color(0, 0, 0, 238));
		g.fillRoundRect(cb.x + 2, cb.y + 2, cb.width - 4, cb.height - 4, 10, 10);
		g.setStroke(new BasicStroke(2f));
		g.setColor(new Color(0, 200, 60));
		g.drawRoundRect(cb.x + 2, cb.y + 2, cb.width - 4, cb.height - 4, 10, 10);

		final int x = cb.x + 12;
		int y = cb.y + 24;
		g.setFont(FontManager.getRunescapeBoldFont());
		shadow(g, "You're ready. Here's how to build one:", x, y, new Color(0, 255, 90));

		g.setFont(FontManager.getRunescapeSmallFont());
		final String[] steps = {
			"1.  Open the Regear panel (icon on the right) and click New.",
			"2.  Name it, then add items: right-click an item -> Add to Regear.",
			"3.  Set how many slots show, and a pattern: Z, Vertical, Single or Custom.",
			"4.  Enable it (or Enable all), open the bank, click the green slots in order.",
		};
		for (String s : steps)
		{
			y += 20;
			shadow(g, s, x, y, Color.WHITE);
		}

		y += 26;
		g.setFont(FontManager.getRunescapeBoldFont());
		shadow(g, "Good to know:", x, y, new Color(0, 255, 90));
		g.setFont(FontManager.getRunescapeSmallFont());
		final String[] tips = {
			"- When a list finishes: Stop, Loop, or reset on bank close / inventory change.",
			"- Reset all lists, or reset a single list, whenever you want.",
			"- Hide this tutorial from the panel any time.",
		};
		for (String tip : tips)
		{
			y += 20;
			shadow(g, tip, x, y, new Color(210, 210, 210));
		}

		y += 30;
		final boolean on = System.currentTimeMillis() / 400 % 2 == 0;
		g.setFont(FontManager.getRunescapeBoldFont());
		shadow(g, "Click anywhere to finish the tutorial", x, y,
			on ? new Color(255, 95, 95) : new Color(255, 190, 190));
	}

	/** Draw text with a black drop-shadow for contrast over any background. */
	private static void shadow(Graphics2D g, String text, int x, int y, Color color)
	{
		g.setColor(Color.BLACK);
		g.drawString(text, x + 1, y + 1);
		g.setColor(color);
		g.drawString(text, x, y);
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

	/**
	 * The canvas rectangle for a slot, shifted by the current bank scroll, or {@code null} if the cell
	 * is not fully within the scroll viewport (so the highlight is hidden until it is scrolled back).
	 */
	private static Rectangle slotRect(Rectangle view, int scrollY, int slot)
	{
		final int x = view.x + RegearBankController.slotToX(slot);
		final int y = view.y + RegearBankController.slotToY(slot) - scrollY;
		if (y < view.y || y + RegearBankController.ITEM_HEIGHT > view.y + view.height)
		{
			return null;
		}
		return new Rectangle(x, y, RegearBankController.ITEM_WIDTH, RegearBankController.ITEM_HEIGHT);
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
