package com.anonyser.regear;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JMenu;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JMenuItem;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.api.ItemComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * The Regear side panel: create and select lists, set each list's enabled state, visible item count,
 * pattern and bank anchor, and edit its ordered items in a grid (drag to reorder, right-click to
 * duplicate / remove / edit). A live preview shows the click pattern, and warnings surface missing
 * items and pattern overlaps. All editing is here; the plugin applies the result to the bank.
 */
class RegearPanel extends PluginPanel
{
	private static final int GRID_COLUMNS = 4;

	private final RegearPlugin plugin;
	private final ItemManager itemManager;
	private final ClientThread clientThread;

	private final JComboBox<String> listSelector = new JComboBox<>();
	private final JCheckBox enabledToggle = new JCheckBox("Enabled");
	private final JComboBox<Integer> visibleCount = new JComboBox<>(new Integer[]{1, 2, 3, 4, 5, 6});
	private final JComboBox<PatternPreset> patternSelector = new JComboBox<>(PatternPreset.values());
	private final JTextField customField = new JTextField();
	private final JComboBox<String> completionSelector = new JComboBox<>();
	private final PatternPreview preview = new PatternPreview();
	private final JTextField colField = new JTextField(3);
	private final JTextField rowField = new JTextField(3);
	private final JPanel itemGrid = new JPanel(new GridLayout(0, GRID_COLUMNS, 3, 3));
	private final JTextField idField = new JTextField();
	private final JPanel warningsPanel = new JPanel();
	private final JLabel completionInfo = new JLabel();
	private final JLabel pickHint = new JLabel();
	private final JButton tutorialButton = new JButton("Tutorial");
	private final JCheckBox hideTutorialBox = new JCheckBox();
	private javax.swing.Timer flashTimer;
	private boolean flashOn;

	// Guards against the control listeners firing while we are populating them from the model.
	private boolean loading;
	private int dragFrom = -1;
	// When >= 0, the panel is in "pick an alternative for item N" mode: clicking a slot links it.
	private int pickAltFor = -1;
	// Whether the alternative being picked/added should become the preferred (shown-first) variant.
	private boolean pickAltPreferred;

	RegearPanel(RegearPlugin plugin, ItemManager itemManager, ClientThread clientThread)
	{
		this.plugin = plugin;
		this.itemManager = itemManager;
		this.clientThread = clientThread;
		setLayout(new BorderLayout(0, 6));
		setBorder(BorderFactory.createEmptyBorder(8, 6, 8, 6));

		final JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.add(title());
		content.add(vspace());
		content.add(listRow());
		content.add(listButtonsRow());
		content.add(vspace());
		content.add(enabledRow());
		content.add(enableAllRow());
		content.add(labeled("Visible", visibleCount));
		content.add(labeled("Pattern", patternSelector));
		content.add(customFieldRow());
		content.add(vspace());
		content.add(section("Pattern preview"));
		content.add(preview);
		content.add(vspace());
		content.add(section("Bank position"));
		content.add(anchorRow());
		content.add(vspace());
		content.add(labeled("On finish", completionSelector));
		content.add(completionInfo);
		content.add(resetRow());
		content.add(vspace());
		content.add(section("Items (withdraw order)"));
		content.add(pickHint);
		content.add(itemGrid);
		content.add(vspace());
		content.add(addRow());
		content.add(vspace());
		content.add(warningsPanel);

		warningsPanel.setLayout(new BoxLayout(warningsPanel, BoxLayout.Y_AXIS));
		completionInfo.setFont(FontManager.getRunescapeSmallFont());
		completionInfo.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		pickHint.setFont(FontManager.getRunescapeSmallFont());
		pickHint.setForeground(ColorScheme.BRAND_ORANGE);
		pickHint.setAlignmentX(LEFT_ALIGNMENT);
		pickHint.setVisible(false);
		// Keep the whole column left-aligned: the item grid, notes and warnings default to CENTER,
		// which in a vertical BoxLayout pushes the left-aligned control rows to the right (that gap).
		itemGrid.setAlignmentX(LEFT_ALIGNMENT);
		completionInfo.setAlignmentX(LEFT_ALIGNMENT);
		warningsPanel.setAlignmentX(LEFT_ALIGNMENT);

		add(content, BorderLayout.NORTH);
		wireListeners();

		// Flash the Tutorial button (green/yellow to draw the eye, red while a tutorial is running).
		flashTimer = new javax.swing.Timer(450, e ->
		{
			flashOn = !flashOn;
			updateTutorialButton();
			if (!tutorialButton.isVisible())
			{
				return;
			}
			final boolean active = plugin.isTutorialActive();
			final Color hot = active ? new Color(210, 30, 30) : new Color(0, 165, 45);
			final Color cool = active ? new Color(90, 0, 0) : new Color(205, 175, 0);
			tutorialButton.setBackground(flashOn ? hot : cool);
			tutorialButton.repaint();
		});
		flashTimer.start();
		updateTutorialButton();
	}

	// --- construction helpers ----------------------------------------------------------------------

	private static Component vspace()
	{
		return Box.createVerticalStrut(6);
	}

	private JLabel title()
	{
		final JLabel label = new JLabel("Regear");
		label.setFont(FontManager.getRunescapeBoldFont());
		label.setForeground(Color.WHITE);
		label.setAlignmentX(LEFT_ALIGNMENT);
		return label;
	}

	private JLabel section(String text)
	{
		final JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.BRAND_ORANGE);
		label.setAlignmentX(LEFT_ALIGNMENT);
		return label;
	}

	private JComponent listRow()
	{
		listSelector.setAlignmentX(LEFT_ALIGNMENT);
		listSelector.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		return listSelector;
	}

	private JComponent listButtonsRow()
	{
		final JPanel row = new JPanel(new GridLayout(1, 3, 3, 0));
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		row.add(compact(button("New", e -> newList())));
		row.add(compact(button("Rename", e -> renameList())));
		row.add(compact(button("Delete", e -> deleteList())));
		return row;
	}

	private JComponent enabledRow()
	{
		enabledToggle.setAlignmentX(LEFT_ALIGNMENT);
		return enabledToggle;
	}

	private JComponent enableAllRow()
	{
		final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		row.add(compact(button("Enable all", e -> setAllEnabled(true))));
		final JButton off = compact(button("Disable all", e -> setAllEnabled(false)));
		off.setToolTipText("Disable every list so the bank is unfiltered (easier to search while adding items)");
		row.add(off);

		tutorialButton.setFocusPainted(false);
		tutorialButton.setBorderPainted(false);
		tutorialButton.setOpaque(true);
		tutorialButton.setMargin(new Insets(2, 5, 2, 5));
		tutorialButton.setFont(FontManager.getRunescapeFont());
		tutorialButton.setForeground(Color.WHITE);
		tutorialButton.addActionListener(e ->
		{
			plugin.toggleTutorial();
			updateTutorialButton();
		});
		hideTutorialBox.setToolTipText("Hide the tutorial button");
		hideTutorialBox.addActionListener(e ->
		{
			plugin.setHideTutorial(hideTutorialBox.isSelected());
			updateTutorialButton();
		});
		row.add(tutorialButton);
		row.add(hideTutorialBox);
		return row;
	}

	private void updateTutorialButton()
	{
		final boolean hidden = plugin.getConfig().hideTutorial();
		hideTutorialBox.setSelected(hidden);
		tutorialButton.setVisible(!hidden);
		final boolean active = plugin.isTutorialActive();
		tutorialButton.setText(active ? "End tutorial" : "Tutorial");
		tutorialButton.setToolTipText(active
			? "Stop the tutorial" : "Start an interactive tutorial on the bank");
	}

	/** Stop the flashing timer; called when the plugin shuts down so it does not leak. */
	void stopTimers()
	{
		if (flashTimer != null)
		{
			flashTimer.stop();
		}
	}

	private JComponent customFieldRow()
	{
		final JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		final JLabel l = new JLabel("Offsets");
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		l.setToolTipText("Custom pattern: space-separated x,y offsets, e.g. 0,0 0,1 1,0 1,1");
		row.add(l, BorderLayout.WEST);
		customField.setToolTipText("Space-separated x,y offsets, e.g. 0,0 0,1 1,0 1,1");
		row.add(customField, BorderLayout.CENTER);
		return row;
	}

	private JComponent anchorRow()
	{
		final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		row.add(muted("Col"));
		row.add(colField);
		row.add(muted("Row"));
		row.add(rowField);
		colField.setToolTipText("Anchor column 0-7 (0 = left, 7 = right edge)");
		rowField.setToolTipText("Anchor row from the top of the bank (0-based)");
		return row;
	}

	private JComponent resetRow()
	{
		final JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		final JButton all = button("All", e -> resetAllSequences());
		all.setToolTipText("Reset every list's sequence back to the start");
		all.setPreferredSize(new Dimension(44, 26));
		all.setMargin(new Insets(0, 0, 0, 0));
		row.add(all, BorderLayout.WEST);
		row.add(button("Reset sequence", e -> resetSequence()), BorderLayout.CENTER);
		return row;
	}

	private JComponent addRow()
	{
		final JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		idField.setToolTipText("Item id to add to the selected list");
		row.add(idField, BorderLayout.CENTER);
		row.add(button("Add", e -> addFromField()), BorderLayout.EAST);
		return row;
	}

	private JComponent labeled(String text, JComponent field)
	{
		final JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		final JLabel l = new JLabel(text);
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		l.setPreferredSize(new Dimension(64, 20));
		row.add(l, BorderLayout.WEST);
		row.add(field, BorderLayout.CENTER);
		return row;
	}

	private static JLabel muted(String text)
	{
		final JLabel l = new JLabel(text);
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		return l;
	}

	private JButton button(String text, java.awt.event.ActionListener action)
	{
		final JButton b = new JButton(text);
		b.setFocusPainted(false);
		b.addActionListener(action);
		return b;
	}

	/** Tighten a button's padding and font so short labels fit in a narrow, shared row. */
	private static JButton compact(JButton b)
	{
		b.setMargin(new Insets(2, 2, 2, 2));
		b.setFont(FontManager.getRunescapeFont());
		return b;
	}

	// --- listeners ---------------------------------------------------------------------------------

	private void wireListeners()
	{
		listSelector.addActionListener(e ->
		{
			if (!loading)
			{
				refreshForSelection();
			}
		});
		enabledToggle.addActionListener(e -> mutate(list -> list.enabled = enabledToggle.isSelected()));
		visibleCount.addActionListener(e ->
		{
			if (!loading)
			{
				mutate(list ->
				{
					list.visibleCount = (Integer) visibleCount.getSelectedItem();
					list.resetLanes();
				});
			}
		});
		patternSelector.addActionListener(e ->
		{
			if (!loading)
			{
				mutate(list ->
				{
					list.pattern = (PatternPreset) patternSelector.getSelectedItem();
					list.resetLanes();
				});
			}
		});
		completionSelector.addActionListener(e ->
		{
			if (!loading)
			{
				mutate(list -> list.completion = completionFromSelector());
			}
		});
		customField.addActionListener(e -> commitCustomOffsets());
		customField.addFocusListener(new java.awt.event.FocusAdapter()
		{
			@Override
			public void focusLost(java.awt.event.FocusEvent e)
			{
				commitCustomOffsets();
			}
		});
		colField.addActionListener(e -> commitAnchor());
		rowField.addActionListener(e -> commitAnchor());
		colField.addFocusListener(anchorFocus());
		rowField.addFocusListener(anchorFocus());
		idField.addActionListener(e -> addFromField());
	}

	private java.awt.event.FocusAdapter anchorFocus()
	{
		return new java.awt.event.FocusAdapter()
		{
			@Override
			public void focusLost(java.awt.event.FocusEvent e)
			{
				commitAnchor();
			}
		};
	}

	// --- model access ------------------------------------------------------------------------------

	private List<RegearList> lists()
	{
		return plugin.getData().lists;
	}

	private RegearList selectedList()
	{
		final int i = listSelector.getSelectedIndex();
		return i >= 0 && i < lists().size() ? lists().get(i) : null;
	}

	private interface ListEdit
	{
		void apply(RegearList list);
	}

	private void mutate(ListEdit edit)
	{
		final RegearList list = selectedList();
		if (list == null)
		{
			return;
		}
		edit.apply(list);
		plugin.commit();
		refreshForSelection();
	}

	// --- public entry points -----------------------------------------------------------------------

	/** Rebuild the whole panel from the model; safe to call on the EDT. */
	void reload()
	{
		SwingUtilities.invokeLater(() ->
		{
			loading = true;
			final int keep = listSelector.getSelectedIndex();
			listSelector.removeAllItems();
			for (RegearList list : lists())
			{
				listSelector.addItem(list.name);
			}
			if (listSelector.getItemCount() > 0)
			{
				listSelector.setSelectedIndex(Math.max(0, Math.min(keep, listSelector.getItemCount() - 1)));
			}
			loading = false;
			refreshForSelection();
		});
	}

	/** Add an item to the selected list (creating a default list if none exists). EDT only. */
	void addItemToSelected(int id)
	{
		if (id <= 0)
		{
			return;
		}
		RegearList list = selectedList();
		if (list == null)
		{
			list = new RegearList(DEFAULT_LIST_NAME());
			list.visibleCount = plugin.getConfig().defaultVisibleCount();
			list.enabled = true;
			list.anchorSlot = RegearList.defaultAnchorSlot(0);
			list.resetLanes();
			lists().add(list);
			plugin.commit();
			reload();
			// Reselect the new list after reload repopulates the selector.
			SwingUtilities.invokeLater(() ->
			{
				listSelector.setSelectedIndex(lists().size() - 1);
				addItemToSelected(id);
			});
			return;
		}
		list.items.add(new RegearItem(id));
		plugin.commit();
		refreshForSelection();
	}

	void setWarnings(List<String> missing, boolean overlap)
	{
		SwingUtilities.invokeLater(() ->
		{
			warningsPanel.removeAll();
			if (overlap)
			{
				warningsPanel.add(warning("Pattern overlap detected", ColorScheme.PROGRESS_ERROR_COLOR));
			}
			for (String m : missing)
			{
				warningsPanel.add(warning("Missing: " + m, ColorScheme.PROGRESS_ERROR_COLOR));
			}
			warningsPanel.revalidate();
			warningsPanel.repaint();
		});
	}

	private static String DEFAULT_LIST_NAME()
	{
		return "Main Regear";
	}

	// --- refresh -----------------------------------------------------------------------------------

	private void refreshForSelection()
	{
		final RegearList list = selectedList();
		loading = true;
		final boolean has = list != null;
		enabledToggle.setEnabled(has);
		visibleCount.setEnabled(has);
		patternSelector.setEnabled(has);
		customField.setEnabled(has);
		colField.setEnabled(has);
		rowField.setEnabled(has);
		completionSelector.setEnabled(has);

		completionSelector.removeAllItems();
		completionSelector.addItem("Default (global)");
		for (CompletionBehavior b : CompletionBehavior.values())
		{
			completionSelector.addItem(b.toString());
		}

		if (has)
		{
			enabledToggle.setSelected(list.enabled);
			visibleCount.setSelectedItem(list.visibleCount);
			patternSelector.setSelectedItem(list.pattern);
			customField.setVisible(list.pattern == PatternPreset.CUSTOM);
			customField.setText(offsetsToText(list.customOffsets));
			colField.setText(Integer.toString(list.anchorSlot % RegearList.BANK_COLUMNS));
			rowField.setText(Integer.toString(list.anchorSlot / RegearList.BANK_COLUMNS));
			completionSelector.setSelectedIndex(list.completion == null ? 0 : list.completion.ordinal() + 1);
			completionInfo.setText("<html>Default: " + plugin.getConfig().defaultCompletion() + "</html>");
			visibleCount.setEnabled(list.pattern != PatternPreset.SINGLE && list.pattern != PatternPreset.CUSTOM);
			preview.setList(list);
		}
		else
		{
			preview.setList(null);
			completionInfo.setText("");
		}
		loading = false;

		pickHint.setVisible(pickAltFor >= 0);
		if (pickAltFor >= 0)
		{
			pickHint.setText("<html>Click an item to link it as an alternative (any other click cancels)</html>");
		}

		rebuildGrid();
		revalidate();
		repaint();
	}

	private void rebuildGrid()
	{
		itemGrid.removeAll();
		final RegearList list = selectedList();
		if (list != null)
		{
			for (int i = 0; i < list.items.size(); i++)
			{
				itemGrid.add(itemSlot(list, i));
			}
		}
		itemGrid.revalidate();
		itemGrid.repaint();
	}

	private JComponent itemSlot(RegearList list, int index)
	{
		final RegearItem item = list.items.get(index);
		final JPanel slot = new JPanel(new BorderLayout());
		slot.setPreferredSize(new Dimension(44, 44));
		slot.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		final boolean picking = pickAltFor >= 0 && pickAltFor != index;
		slot.setBorder(BorderFactory.createLineBorder(
			picking ? ColorScheme.BRAND_ORANGE : ColorScheme.DARK_GRAY_COLOR));

		final JLabel icon = new JLabel();
		icon.setHorizontalAlignment(SwingConstants.CENTER);
		icon.setVerticalAlignment(SwingConstants.CENTER);
		slot.add(icon, BorderLayout.CENTER);

		final boolean hasAlts = !item.alts.isEmpty();
		final JLabel tag = new JLabel((index + 1) + (hasAlts ? "+" : ""), SwingConstants.CENTER);
		tag.setFont(FontManager.getRunescapeSmallFont());
		tag.setForeground(hasAlts ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
		slot.add(tag, BorderLayout.SOUTH);

		// Resolve the icon and name off the client thread, then apply on the EDT.
		final int id = item.id;
		clientThread.invoke(() ->
		{
			final AsyncBufferedImage img = itemManager.getImage(id, Math.max(1, item.quantity), item.quantity > 1);
			final String name = itemName(id);
			final List<String> altLines = new ArrayList<>();
			for (int a : item.alts)
			{
				altLines.add(itemName(a) + " (id " + a + ")");
			}
			SwingUtilities.invokeLater(() ->
			{
				img.addTo(icon);
				final StringBuilder tip = new StringBuilder("<html>")
					.append(name).append(" (id ").append(id).append(')');
				if (item.note != null && !item.note.isEmpty())
				{
					tip.append(" - ").append(item.note);
				}
				for (int i = 0; i < altLines.size(); i++)
				{
					tip.append("<br>&nbsp;&nbsp;fallback ").append(i + 1).append(": ").append(altLines.get(i));
				}
				if (item.skipIfWorn)
				{
					tip.append("<br>(skip if worn)");
				}
				slot.setToolTipText(tip.append("</html>").toString());
			});
		});

		slot.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				// In "pick an alternative" mode a left-click links the clicked item; anything else cancels.
				if (pickAltFor >= 0)
				{
					if (SwingUtilities.isLeftMouseButton(e) && index != pickAltFor)
					{
						addAlternative(pickAltFor, list.items.get(index).id, pickAltPreferred);
					}
					pickAltFor = -1;
					refreshForSelection();
					return;
				}
				dragFrom = index;
				maybePopup(e, list, index);
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (maybePopup(e, list, index))
				{
					return;
				}
				if (dragFrom >= 0)
				{
					final int target = slotIndexAt(e);
					if (target >= 0 && target != dragFrom)
					{
						reorder(list, dragFrom, target);
					}
				}
				dragFrom = -1;
			}
		});
		return slot;
	}

	private boolean maybePopup(MouseEvent e, RegearList list, int index)
	{
		if (!e.isPopupTrigger())
		{
			return false;
		}
		final JPopupMenu menu = new JPopupMenu();
		menu.add(menuItem("Duplicate", () ->
		{
			list.items.add(index + 1, list.items.get(index).copy());
			plugin.commit();
			refreshForSelection();
		}));
		menu.add(menuItem("Remove", () ->
		{
			list.items.remove(index);
			plugin.commit();
			refreshForSelection();
		}));
		menu.add(menuItem("Edit item id", () ->
		{
			final String in = JOptionPane.showInputDialog(this, "Item id:", list.items.get(index).id);
			final Integer v = parseId(in);
			if (v != null)
			{
				list.items.get(index).id = v;
				plugin.commit();
				refreshForSelection();
			}
		}));
		menu.add(menuItem("Set amount", () ->
		{
			final String in = JOptionPane.showInputDialog(this,
				"Withdraw amount (the item stays and the sequence waits until you pull this many):",
				list.items.get(index).quantity);
			final Integer v = parseId(in);
			if (v != null)
			{
				list.items.get(index).quantity = Math.max(0, v);
				plugin.commit();
				refreshForSelection();
			}
		}));
		menu.add(menuItem("Set note", () ->
		{
			final String in = JOptionPane.showInputDialog(this, "Note / label:", list.items.get(index).note);
			if (in != null)
			{
				list.items.get(index).note = in.trim().isEmpty() ? null : in.trim();
				plugin.commit();
				refreshForSelection();
			}
		}));
		final JMenu orMenu = new JMenu("Alternative (or)");
		orMenu.add(menuItem("Add id, preferred...", () -> promptAlt(index, true)));
		orMenu.add(menuItem("Add id, fallback...", () -> promptAlt(index, false)));
		orMenu.add(menuItem("Pick from panel (preferred)", () -> startPick(index, true)));
		orMenu.add(menuItem("Pick from panel (fallback)", () -> startPick(index, false)));
		if (!list.items.get(index).alts.isEmpty())
		{
			orMenu.add(menuItem("Clear alternatives", () ->
			{
				list.items.get(index).alts.clear();
				plugin.commit();
				refreshForSelection();
			}));
		}
		menu.add(orMenu);
		final boolean worn = list.items.get(index).skipIfWorn;
		menu.add(menuItem((worn ? "✓ " : "") + "Skip if worn", () ->
		{
			list.items.get(index).skipIfWorn = !worn;
			plugin.commit();
			refreshForSelection();
		}));
		menu.add(menuItem("Move left", () -> reorder(list, index, index - 1)));
		menu.add(menuItem("Move right", () -> reorder(list, index, index + 1)));
		menu.show(e.getComponent(), e.getX(), e.getY());
		return true;
	}

	private JMenuItem menuItem(String text, Runnable action)
	{
		final JMenuItem item = new JMenuItem(text);
		item.addActionListener(e -> action.run());
		return item;
	}

	private void reorder(RegearList list, int from, int to)
	{
		if (from < 0 || from >= list.items.size() || to < 0 || to >= list.items.size())
		{
			return;
		}
		final RegearItem moved = list.items.remove(from);
		list.items.add(to, moved);
		plugin.commit();
		refreshForSelection();
	}

	/** Which grid slot (item index) sits under a mouse event, translated into grid coordinates. */
	private int slotIndexAt(MouseEvent e)
	{
		final Component src = e.getComponent();
		final java.awt.Point p = SwingUtilities.convertPoint(src, e.getPoint(), itemGrid);
		final Component hit = itemGrid.getComponentAt(p);
		if (hit == null)
		{
			return -1;
		}
		for (int i = 0; i < itemGrid.getComponentCount(); i++)
		{
			if (itemGrid.getComponent(i) == hit)
			{
				return i;
			}
		}
		return -1;
	}

	// --- list-level actions ------------------------------------------------------------------------

	private void newList()
	{
		final String name = JOptionPane.showInputDialog(this, "List name:", "Regear " + (lists().size() + 1));
		if (name == null || name.trim().isEmpty())
		{
			return;
		}
		final RegearList list = new RegearList(name.trim());
		list.visibleCount = plugin.getConfig().defaultVisibleCount();
		list.anchorSlot = RegearList.defaultAnchorSlot(enabledCount());
		list.resetLanes();
		lists().add(list);
		plugin.commit();
		reload();
		SwingUtilities.invokeLater(() -> listSelector.setSelectedIndex(lists().size() - 1));
	}

	private void renameList()
	{
		final RegearList list = selectedList();
		if (list == null)
		{
			return;
		}
		final String name = JOptionPane.showInputDialog(this, "New name:", list.name);
		if (name != null && !name.trim().isEmpty())
		{
			list.name = name.trim();
			plugin.commit();
			reload();
		}
	}

	private void deleteList()
	{
		final RegearList list = selectedList();
		if (list == null)
		{
			return;
		}
		final int ok = JOptionPane.showConfirmDialog(this, "Delete list \"" + list.name + "\"?",
			"Delete list", JOptionPane.YES_NO_OPTION);
		if (ok == JOptionPane.YES_OPTION)
		{
			lists().remove(list);
			plugin.commit();
			reload();
		}
	}

	private void resetSequence()
	{
		final RegearList list = selectedList();
		if (list != null)
		{
			plugin.resetSequence(list);
			refreshForSelection();
		}
	}

	private void resetAllSequences()
	{
		plugin.resetAll();
		refreshForSelection();
	}

	private void addAlternative(int itemIndex, int altId, boolean preferred)
	{
		final RegearList list = selectedList();
		if (list == null || itemIndex < 0 || itemIndex >= list.items.size() || altId <= 0)
		{
			return;
		}
		final RegearItem it = list.items.get(itemIndex);
		it.alts.remove((Integer) altId); // never duplicate an id in the alt list
		if (preferred)
		{
			if (it.id > 0 && it.id != altId)
			{
				it.alts.add(0, it.id); // the old primary becomes the top fallback
			}
			it.id = altId; // the preferred variant is shown and tried first
		}
		else if (altId != it.id && !it.alts.contains(altId))
		{
			it.alts.add(altId);
		}
		plugin.commit();
	}

	private void promptAlt(int index, boolean preferred)
	{
		final String in = JOptionPane.showInputDialog(this,
			(preferred ? "Preferred" : "Fallback") + " alternative item id (either version counts):");
		final Integer v = parseId(in);
		if (v != null)
		{
			addAlternative(index, v, preferred);
			refreshForSelection();
		}
	}

	private void startPick(int index, boolean preferred)
	{
		pickAltFor = index;
		pickAltPreferred = preferred;
		refreshForSelection();
	}

	private void addFromField()
	{
		final Integer id = parseId(idField.getText());
		if (id != null)
		{
			idField.setText("");
			addItemToSelected(id);
		}
	}

	private int enabledCount()
	{
		int n = 0;
		for (RegearList l : lists())
		{
			if (l.enabled)
			{
				n++;
			}
		}
		return n;
	}

	private void setAllEnabled(boolean on)
	{
		for (RegearList l : lists())
		{
			l.enabled = on;
		}
		plugin.commit();
		refreshForSelection();
	}

	private void commitAnchor()
	{
		if (loading)
		{
			return;
		}
		final RegearList list = selectedList();
		if (list == null)
		{
			return;
		}
		final Integer col = clamp(parseId(colField.getText()), 0, 7);
		final Integer row = clamp(parseId(rowField.getText()), 0, 40);
		if (col != null && row != null)
		{
			list.anchorSlot = row * RegearList.BANK_COLUMNS + col;
			plugin.commit();
			refreshForSelection();
		}
	}

	private void commitCustomOffsets()
	{
		if (loading)
		{
			return;
		}
		final RegearList list = selectedList();
		if (list == null)
		{
			return;
		}
		list.customOffsets = parseOffsets(customField.getText());
		list.resetLanes();
		plugin.commit();
		refreshForSelection();
	}

	private CompletionBehavior completionFromSelector()
	{
		final int i = completionSelector.getSelectedIndex();
		return i <= 0 ? null : CompletionBehavior.values()[i - 1];
	}

	// --- parsing helpers ---------------------------------------------------------------------------

	private static Integer parseId(String text)
	{
		if (text == null)
		{
			return null;
		}
		try
		{
			return Integer.parseInt(text.trim());
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	private static Integer clamp(Integer v, int min, int max)
	{
		if (v == null)
		{
			return null;
		}
		return Math.max(min, Math.min(max, v));
	}

	private static List<PatternOffset> parseOffsets(String text)
	{
		final List<PatternOffset> out = new ArrayList<>();
		if (text == null)
		{
			return out;
		}
		for (String token : text.trim().split("\\s+"))
		{
			final String[] parts = token.split(",");
			if (parts.length == 2)
			{
				final Integer x = parseId(parts[0]);
				final Integer y = parseId(parts[1]);
				if (x != null && y != null)
				{
					out.add(new PatternOffset(x, y));
				}
			}
			if (out.size() >= 6)
			{
				break;
			}
		}
		return out;
	}

	private static String offsetsToText(List<PatternOffset> offsets)
	{
		final StringBuilder sb = new StringBuilder();
		for (PatternOffset o : offsets)
		{
			if (sb.length() > 0)
			{
				sb.append(' ');
			}
			sb.append(o.x).append(',').append(o.y);
		}
		return sb.toString();
	}

	private JComponent warning(String text, Color color)
	{
		final JLabel l = new JLabel("<html>" + text + "</html>");
		l.setForeground(color);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setAlignmentX(LEFT_ALIGNMENT);
		l.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));
		return l;
	}

	/** Item name for a tooltip. Must be called on the client thread (getItemComposition asserts it). */
	private String itemName(int id)
	{
		final ItemComposition c = itemManager.getItemComposition(id);
		return c != null && c.getName() != null ? c.getName() : "Item";
	}

	// --- pattern preview ---------------------------------------------------------------------------

	/** A small grid that numbers the lanes at their pattern offsets. */
	private static final class PatternPreview extends JPanel
	{
		private RegearList list;

		PatternPreview()
		{
			setPreferredSize(new Dimension(PANEL_WIDTH - 20, 92));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, 92));
			setAlignmentX(LEFT_ALIGNMENT);
			setBackground(ColorScheme.DARKER_GRAY_COLOR);
		}

		void setList(RegearList list)
		{
			this.list = list;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g0)
		{
			super.paintComponent(g0);
			if (list == null)
			{
				return;
			}
			final Graphics2D g = (Graphics2D) g0;
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			final List<PatternOffset> offs = list.effectiveOffsets();
			int maxX = 0;
			int maxY = 0;
			for (PatternOffset o : offs)
			{
				maxX = Math.max(maxX, o.x);
				maxY = Math.max(maxY, o.y);
			}
			final int cell = 22;
			final int ox = 6;
			final int oy = 6;
			g.setColor(ColorScheme.DARK_GRAY_COLOR);
			for (int y = 0; y <= maxY; y++)
			{
				for (int x = 0; x <= maxX; x++)
				{
					g.drawRect(ox + x * cell, oy + y * cell, cell - 2, cell - 2);
				}
			}
			g.setFont(FontManager.getRunescapeBoldFont());
			for (int i = 0; i < offs.size(); i++)
			{
				final PatternOffset o = offs.get(i);
				final int cx = ox + o.x * cell;
				final int cy = oy + o.y * cell;
				g.setColor(new Color(0, 200, 83, 90));
				g.fillRect(cx, cy, cell - 2, cell - 2);
				g.setColor(Color.WHITE);
				g.drawString(String.valueOf(i + 1), cx + 6, cy + 15);
			}
		}
	}
}
