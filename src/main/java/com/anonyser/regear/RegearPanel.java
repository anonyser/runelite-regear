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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
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

	private final ListSelector listSelector = new ListSelector();
	private final JComboBox<String> groupSelector = new JComboBox<>();
	private final JComboBox<Integer> visibleCount = new JComboBox<>(visibleCounts());
	private final JComboBox<PatternPreset> patternSelector = new JComboBox<>(PatternPreset.values());
	private final JTextField customField = new JTextField();
	private final PatternPreview preview = new PatternPreview();
	private final JTextField colField = new JTextField(3);
	private final JTextField rowField = new JTextField(3);
	private final JPanel itemGrid = new JPanel(new GridLayout(0, GRID_COLUMNS, 3, 3));
	private final JTextField idField = new JTextField();
	private final JPanel warningsPanel = new JPanel();
	private final JLabel completionInfo = new JLabel();
	private final JLabel fitWarning = new JLabel();
	private final JLabel pickHint = new JLabel();
	private final JButton tutorialButton = new JButton("Tutorial");
	private final JCheckBox hideTutorialBox = new JCheckBox();
	private final JButton hideOverlayButton = new JButton("Hide overlay");
	private final JCheckBox showIdsBox = new JCheckBox("Show item ids");
	private final JCheckBox showEquipBox = new JCheckBox("Show equipment while banking");
	private javax.swing.Timer flashTimer;
	private boolean flashOn;

	// Guards against the control listeners firing while we are populating them from the model.
	private boolean loading;
	private int dragFrom = -1;
	// When >= 0, the panel is in "pick an alternative for item N" mode: clicking a slot links it.
	private int pickAltFor = -1;
	// Whether the alternative being picked/added should become the preferred (shown-first) variant.
	private boolean pickAltPreferred;
	// When on, clicking cells in the pattern preview builds a custom pattern in click order.
	private boolean patternClickMode;

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
		content.add(shareRow());
		content.add(vspace());
		content.add(enableAllRow());
		content.add(vspace());
		content.add(section("Groups"));
		content.add(labeled("Group", groupSelector));
		content.add(groupEnableRow());
		content.add(groupButtonsRow());
		content.add(vspace());
		content.add(hideOverlayRow());
		content.add(showIdsRow());
		content.add(labeled("Visible", visibleCount));
		content.add(labeled("Pattern", patternSelector));
		content.add(customFieldRow());
		content.add(vspace());
		content.add(patternPreviewHeader());
		content.add(preview);
		content.add(fitWarning);
		content.add(vspace());
		content.add(section("Bank position"));
		content.add(anchorRow());
		content.add(vspace());
		content.add(completionInfo);
		content.add(resetRow());
		content.add(vspace());
		content.add(section("Items (withdraw order)"));
		content.add(pickHint);
		content.add(itemGrid);
		content.add(vspace());
		content.add(addRow());
		content.add(addInventoryRow());
		content.add(vspace());
		content.add(warningsPanel);
		content.add(vspace());
		content.add(equipmentRow());

		warningsPanel.setLayout(new BoxLayout(warningsPanel, BoxLayout.Y_AXIS));
		completionInfo.setFont(FontManager.getRunescapeSmallFont());
		completionInfo.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		fitWarning.setText("<html><body style='width:185px'>Pattern falls off the bank grid from this"
			+ " anchor - move the anchor left or up.</body></html>");
		fitWarning.setFont(FontManager.getRunescapeSmallFont());
		fitWarning.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
		fitWarning.setAlignmentX(LEFT_ALIGNMENT);
		fitWarning.setVisible(false);
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
			// Keep the Hide/Show overlay label in step even when the state changes without a click here
			// (e.g. the tab-away auto-hide flips it from the plugin side).
			updateHideOverlayButton();
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

	/** A full-width button that hides or shows the whole Regear guide in the bank. */
	private JComponent hideOverlayRow()
	{
		hideOverlayButton.setFocusPainted(false);
		hideOverlayButton.setFont(FontManager.getRunescapeFont());
		hideOverlayButton.setAlignmentX(LEFT_ALIGNMENT);
		hideOverlayButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		hideOverlayButton.setToolTipText(
			"Hide the whole Regear guide in the bank so you can browse freely; click again to bring it back");
		hideOverlayButton.addActionListener(e ->
		{
			plugin.toggleOverlayGuide();
			updateHideOverlayButton();
		});
		updateHideOverlayButton();
		return hideOverlayButton;
	}

	private void updateHideOverlayButton()
	{
		hideOverlayButton.setText(plugin.isOverlayGuideHidden() ? "Show overlay" : "Hide overlay");
	}

	/** Export (current / all) and import setups as shareable text. */
	private JComponent shareRow()
	{
		final JPanel row = new JPanel(new GridLayout(1, 3, 3, 0));
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		final JButton export = compact(button("Export", e -> exportCurrent()));
		export.setToolTipText("Copy the selected setup to the clipboard as shareable text");
		final JButton exportAll = compact(button("Export all", e -> exportAll()));
		exportAll.setToolTipText("Copy every setup to the clipboard as one shareable text");
		final JButton importButton = compact(button("Import", e -> importSetups()));
		importButton.setToolTipText("Paste shared setup text to add it to your lists");
		row.add(export);
		row.add(exportAll);
		row.add(importButton);
		return row;
	}

	private JComponent equipmentRow()
	{
		showEquipBox.setToolTipText("Show a movable panel of your worn equipment while the bank is open (drag it anywhere)");
		showEquipBox.setAlignmentX(LEFT_ALIGNMENT);
		showEquipBox.setSelected(plugin.getConfig().showEquipmentOverlay());
		showEquipBox.addActionListener(e -> plugin.setShowEquipment(showEquipBox.isSelected()));
		return showEquipBox;
	}

	/** Quick toggle for the item id overlay, mirrored with the same setting in the plugin options. */
	private JComponent showIdsRow()
	{
		showIdsBox.setToolTipText(
			"Draw each item's id over items in the bank, inventory and equipment (same as the Item id overlay option)");
		showIdsBox.setAlignmentX(LEFT_ALIGNMENT);
		showIdsBox.setSelected(plugin.getConfig().showItemIds());
		showIdsBox.addActionListener(e -> plugin.setShowItemIds(showIdsBox.isSelected()));
		return showIdsBox;
	}

	/** Mirror plugin-options state into the panel's toggles; called on any Regear config change. */
	void syncFromConfig()
	{
		showIdsBox.setSelected(plugin.getConfig().showItemIds());
		showEquipBox.setSelected(plugin.getConfig().showEquipmentOverlay());
		updateHideOverlayButton();
		updateTutorialButton();
		completionInfo.setText(completionText());
	}

	private String completionText()
	{
		// The width style makes the JLabel compute its preferred height WRAPPED; without it,
		// BoxLayout sizes the label for one unwrapped line and clips the second line.
		return "<html><body style='width:185px'>On finish: " + plugin.getConfig().defaultCompletion()
			+ ". Applies to all lists (plugin options).</body></html>";
	}

	private void exportCurrent()
	{
		final RegearList list = selectedList();
		if (list == null)
		{
			JOptionPane.showMessageDialog(this, "Select a setup to export first.");
			return;
		}
		shareExport(plugin.exportSetup(list), "Copied \"" + list.name + "\" to the clipboard.");
	}

	private void exportAll()
	{
		if (lists().isEmpty())
		{
			JOptionPane.showMessageDialog(this, "There are no setups to export.");
			return;
		}
		shareExport(plugin.exportAllSetups(), "Copied all " + lists().size() + " setups to the clipboard.");
	}

	private void shareExport(String token, String message)
	{
		if (token == null || token.isEmpty())
		{
			JOptionPane.showMessageDialog(this, "Nothing to export.");
			return;
		}
		copyToClipboard(token);
		// Show the text too (selectable) in case the clipboard is unavailable.
		final JTextArea area = new JTextArea(token);
		area.setLineWrap(true);
		area.setEditable(false);
		area.setCaretPosition(0);
		final JScrollPane scroll = new JScrollPane(area);
		scroll.setPreferredSize(new Dimension(240, 90));
		JOptionPane.showMessageDialog(this, scroll, message, JOptionPane.INFORMATION_MESSAGE);
	}

	private void importSetups()
	{
		final JTextArea area = new JTextArea();
		area.setLineWrap(true);
		final String seed = clipboardText();
		if (seed != null && seed.contains(RegearShare.PREFIX))
		{
			area.setText(seed.trim());
		}
		final JScrollPane scroll = new JScrollPane(area);
		scroll.setPreferredSize(new Dimension(240, 90));
		final int ok = JOptionPane.showConfirmDialog(this, scroll, "Paste setup text to import",
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (ok != JOptionPane.OK_OPTION)
		{
			return;
		}
		final int n = plugin.importSetups(area.getText());
		if (n <= 0)
		{
			JOptionPane.showMessageDialog(this, "No valid Regear setup found in that text.");
			return;
		}
		reload();
		JOptionPane.showMessageDialog(this, "Imported " + n + (n == 1 ? " setup." : " setups."));
	}

	private void copyToClipboard(String text)
	{
		try
		{
			java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
				.setContents(new java.awt.datatransfer.StringSelection(text), null);
		}
		catch (Exception ignored)
		{
			// Clipboard may be unavailable (headless/locked); the dialog still shows the text to copy.
		}
	}

	private String clipboardText()
	{
		try
		{
			final Object data = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
				.getData(java.awt.datatransfer.DataFlavor.stringFlavor);
			return data instanceof String ? (String) data : null;
		}
		catch (Exception e)
		{
			return null;
		}
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
		colField.setToolTipText("Anchor column 0-7: the numbers across the TOP of the preview (0 = left edge)");
		rowField.setToolTipText("Anchor row: the numbers down the LEFT of the preview (0 = top of the bank)");
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

	private JComponent addInventoryRow()
	{
		final JButton b = button("Add current inventory", e -> addInventory());
		b.setAlignmentX(LEFT_ALIGNMENT);
		b.setFont(FontManager.getRunescapeFont());
		b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		b.setToolTipText("Add every item in your current inventory to this list, in inventory order");
		return b;
	}

	/** Header row for the pattern preview: the section label plus a "Click to set" toggle. */
	private JComponent patternPreviewHeader()
	{
		final JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
		row.add(section("Pattern preview"), BorderLayout.WEST);
		final JCheckBox click = new JCheckBox("Click to set");
		click.setToolTipText("Click cells in the preview to build the pattern in the order you click");
		click.addActionListener(e ->
		{
			patternClickMode = click.isSelected();
			preview.repaint();
		});
		row.add(click, BorderLayout.EAST);
		return row;
	}

	private static Integer[] visibleCounts()
	{
		final Integer[] a = new Integer[28];
		for (int i = 0; i < 28; i++)
		{
			a[i] = i + 1;
		}
		return a;
	}

	/** Add every item in the current inventory (slot order) to the selected list, creating one if needed. */
	private void addInventory()
	{
		plugin.withInventoryIds(ids ->
		{
			if (ids.isEmpty())
			{
				JOptionPane.showMessageDialog(this, "Your inventory is empty.");
				return;
			}
			RegearList list = selectedList();
			final boolean created = list == null;
			if (created)
			{
				list = new RegearList(DEFAULT_LIST_NAME());
				list.visibleCount = plugin.getConfig().defaultVisibleCount();
				list.enabled = true;
				list.anchorSlot = RegearList.defaultAnchorSlot(0);
				list.resetLanes();
				lists().add(list);
			}
			for (int id : ids)
			{
				list.items.add(new RegearItem(id));
			}
			plugin.freshenBaseline(ids);
			plugin.commit();
			if (created)
			{
				reload();
				SwingUtilities.invokeLater(() -> listSelector.setSelectedIndex(lists().size() - 1));
			}
			else
			{
				refreshForSelection();
			}
		});
	}

	/** Toggle a cell in the custom pattern (click-to-set); switching a preset in seeds its current shape. */
	private void toggleOffset(int col, int row)
	{
		final RegearList list = selectedList();
		if (list == null)
		{
			return;
		}
		if (list.pattern != PatternPreset.CUSTOM)
		{
			list.customOffsets = new ArrayList<>(list.effectiveOffsets());
			list.pattern = PatternPreset.CUSTOM;
		}
		PatternOffset existing = null;
		for (PatternOffset o : list.customOffsets)
		{
			if (o.x == col && o.y == row)
			{
				existing = o;
				break;
			}
		}
		if (existing != null)
		{
			list.customOffsets.remove(existing);
		}
		else if (list.customOffsets.size() < 28)
		{
			list.customOffsets.add(new PatternOffset(col, row));
		}
		list.resetLanes();
		plugin.commit();
		refreshForSelection();
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
		// Null-guarded: plugin shutDown() clears the data while a queued repaint can still reach
		// the preview (which reads other lists' footprints) through this accessor.
		final RegearData data = plugin.getData();
		return data == null ? java.util.Collections.emptyList() : data.lists;
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
			listSelector.reloadItems();
			reloadGroups();
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
		plugin.freshenBaseline(java.util.Collections.singletonList(id));
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
		visibleCount.setEnabled(has);
		patternSelector.setEnabled(has);
		customField.setEnabled(has);
		colField.setEnabled(has);
		rowField.setEnabled(has);

		completionInfo.setText(completionText());
		if (has)
		{
			visibleCount.setSelectedItem(list.visibleCount);
			patternSelector.setSelectedItem(list.pattern);
			customField.setVisible(list.pattern == PatternPreset.CUSTOM);
			customField.setText(offsetsToText(list.customOffsets));
			colField.setText(Integer.toString(list.anchorSlot % RegearList.BANK_COLUMNS));
			rowField.setText(Integer.toString(list.anchorSlot / RegearList.BANK_COLUMNS));
			visibleCount.setEnabled(list.pattern != PatternPreset.SINGLE && list.pattern != PatternPreset.CUSTOM);
			preview.setList(list);
		}
		else
		{
			preview.setList(null);
		}
		fitWarning.setVisible(has && !list.fitsGrid());
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
			// Keep groups honest: a deleted list can't stay a member of anything.
			for (RegearGroup g : groups())
			{
				g.memberIds.remove(list.id);
			}
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
		plugin.freshenBaseline(java.util.Collections.singletonList(altId));
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

	// --- groups ------------------------------------------------------------------------------------

	private List<RegearGroup> groups()
	{
		final RegearData data = plugin.getData();
		return data == null ? java.util.Collections.emptyList() : data.groups;
	}

	private RegearGroup selectedGroup()
	{
		final int i = groupSelector.getSelectedIndex();
		return i >= 0 && i < groups().size() ? groups().get(i) : null;
	}

	/** Repopulate the group dropdown from the model, keeping the current pick where possible. */
	private void reloadGroups()
	{
		final int keep = groupSelector.getSelectedIndex();
		groupSelector.removeAllItems();
		for (RegearGroup g : groups())
		{
			groupSelector.addItem(g.name);
		}
		if (groupSelector.getItemCount() > 0)
		{
			groupSelector.setSelectedIndex(Math.max(0, Math.min(keep, groupSelector.getItemCount() - 1)));
		}
	}

	private JComponent groupEnableRow()
	{
		final JPanel row = new JPanel(new GridLayout(1, 2, 3, 0));
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		final JButton on = compact(button("Enable group", e -> enableGroup()));
		on.setToolTipText("Turn on only this group's setups and turn every other setup off");
		final JButton off = compact(button("Disable group", e -> disableGroup()));
		off.setToolTipText("Turn off this group's setups, leaving the rest as they are");
		row.add(on);
		row.add(off);
		return row;
	}

	private JComponent groupButtonsRow()
	{
		final JPanel row = new JPanel(new GridLayout(1, 3, 3, 0));
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		final JButton create = compact(button("Group checked", e -> groupFromChecked()));
		create.setToolTipText("Make a new group from the setups currently enabled (checked in the dropdown)");
		row.add(create);
		row.add(compact(button("Rename", e -> renameGroup())));
		row.add(compact(button("Delete", e -> deleteGroup())));
		return row;
	}

	/** Enable exactly this group's setups and disable every other one — a one-click loadout switch. */
	private void enableGroup()
	{
		final RegearGroup g = selectedGroup();
		if (g == null)
		{
			JOptionPane.showMessageDialog(this, "No group yet. Enable the setups you want, then \"Group checked\".");
			return;
		}
		for (RegearList l : lists())
		{
			l.enabled = g.contains(l.id);
		}
		plugin.commit();
		refreshForSelection();
	}

	/** Turn off this group's setups; anything outside the group keeps its current state. */
	private void disableGroup()
	{
		final RegearGroup g = selectedGroup();
		if (g == null)
		{
			JOptionPane.showMessageDialog(this, "No group selected.");
			return;
		}
		for (RegearList l : lists())
		{
			if (g.contains(l.id))
			{
				l.enabled = false;
			}
		}
		plugin.commit();
		refreshForSelection();
	}

	/** Snapshot the currently-enabled setups into a new named group. */
	private void groupFromChecked()
	{
		final List<String> ids = new ArrayList<>();
		for (RegearList l : lists())
		{
			if (l.enabled)
			{
				ids.add(l.id);
			}
		}
		if (ids.isEmpty())
		{
			JOptionPane.showMessageDialog(this,
				"Tick the setups you want in the dropdown first, then click Group checked.");
			return;
		}
		final String name = JOptionPane.showInputDialog(this, "Group name:", "Group " + (groups().size() + 1));
		if (name == null || name.trim().isEmpty())
		{
			return;
		}
		final Set<String> lower = new HashSet<>();
		for (RegearGroup g : groups())
		{
			if (g.name != null)
			{
				lower.add(g.name.toLowerCase());
			}
		}
		final RegearGroup group = new RegearGroup(RegearShare.uniqueName(name.trim(), lower));
		group.memberIds = ids;
		groups().add(group);
		plugin.commit();
		reloadGroups();
		SwingUtilities.invokeLater(() -> groupSelector.setSelectedIndex(groups().size() - 1));
	}

	private void renameGroup()
	{
		final RegearGroup g = selectedGroup();
		if (g == null)
		{
			return;
		}
		final String name = JOptionPane.showInputDialog(this, "New group name:", g.name);
		if (name != null && !name.trim().isEmpty())
		{
			g.name = name.trim();
			plugin.commit();
			reloadGroups();
		}
	}

	private void deleteGroup()
	{
		final RegearGroup g = selectedGroup();
		if (g == null)
		{
			return;
		}
		final int ok = JOptionPane.showConfirmDialog(this,
			"Delete group \"" + g.name + "\"? Your setups are not affected.",
			"Delete group", JOptionPane.YES_NO_OPTION);
		if (ok == JOptionPane.YES_OPTION)
		{
			groups().remove(g);
			plugin.commit();
			reloadGroups();
		}
	}

	/**
	 * The setup dropdown, now a checklist. Each row is a checkbox — the list's enabled state — plus
	 * its name: ticking a checkbox enables or disables that setup and keeps the menu open so several
	 * can be set in a row; clicking a name picks that setup for editing and closes the menu. Closed,
	 * the control shows the selected setup's name. Enable all / Disable all and the group buttons all
	 * drive the same enabled flags, so the checkmarks always reflect what is on at the bank.
	 */
	private final class ListSelector extends JPanel
	{
		private final JButton button = new JButton();
		private final JLabel caret = new JLabel("▾");
		private int selectedIndex = -1;

		ListSelector()
		{
			super(new BorderLayout(4, 0));
			setAlignmentX(LEFT_ALIGNMENT);
			setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
			setOpaque(false);
			button.setHorizontalAlignment(SwingConstants.LEFT);
			button.setFocusPainted(false);
			button.setFont(FontManager.getRunescapeFont());
			button.setToolTipText("Pick a setup to edit; tick a checkbox to enable it at the bank");
			button.addActionListener(e -> showMenu());
			caret.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			caret.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			caret.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					showMenu();
				}
			});
			add(button, BorderLayout.CENTER);
			add(caret, BorderLayout.EAST);
			updateText();
		}

		int getSelectedIndex()
		{
			return selectedIndex;
		}

		void setSelectedIndex(int i)
		{
			final int size = lists().size();
			selectedIndex = size == 0 ? -1 : Math.max(0, Math.min(i, size - 1));
			updateText();
			if (!loading)
			{
				refreshForSelection();
			}
		}

		/** Clamp the selection to the current setups and refresh the label; the editor is refreshed by the caller. */
		void reloadItems()
		{
			final int size = lists().size();
			selectedIndex = size == 0 ? -1 : Math.max(0, Math.min(selectedIndex, size - 1));
			updateText();
		}

		private void updateText()
		{
			final List<RegearList> ls = lists();
			button.setText(selectedIndex >= 0 && selectedIndex < ls.size()
				? ls.get(selectedIndex).name : "(no setups)");
		}

		private void showMenu()
		{
			final JPopupMenu menu = new JPopupMenu();
			final List<RegearList> ls = lists();
			if (ls.isEmpty())
			{
				final JMenuItem none = new JMenuItem("No setups yet - use New");
				none.setEnabled(false);
				menu.add(none);
			}
			final int width = Math.max(150, button.getWidth());
			for (int i = 0; i < ls.size(); i++)
			{
				menu.add(menuRow(menu, ls.get(i), i, width));
			}
			menu.show(button, 0, button.getHeight());
		}

		private Component menuRow(JPopupMenu menu, RegearList list, int index, int width)
		{
			final JPanel row = new JPanel(new BorderLayout(6, 0));
			row.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 8));
			row.setPreferredSize(new Dimension(width, 24));
			row.setBackground(index == selectedIndex
				? ColorScheme.DARKER_GRAY_HOVER_COLOR : ColorScheme.DARKER_GRAY_COLOR);
			final JCheckBox cb = new JCheckBox();
			cb.setSelected(list.enabled);
			cb.setOpaque(false);
			cb.setToolTipText("Enable \"" + list.name + "\" at the bank");
			cb.addActionListener(e ->
			{
				list.enabled = cb.isSelected();
				plugin.commit();
				// Preview footprints and warnings follow the new enabled set; the menu stays open.
				refreshForSelection();
			});
			final JLabel name = new JLabel(list.name);
			name.setForeground(Color.WHITE);
			name.setToolTipText("Edit \"" + list.name + "\"");
			name.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			name.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					menu.setVisible(false);
					setSelectedIndex(index);
				}
			});
			row.add(cb, BorderLayout.WEST);
			row.add(name, BorderLayout.CENTER);
			return row;
		}
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
			if (out.size() >= 28)
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

	/**
	 * A to-scale preview of the top of the bank: numbered column and row axes matching the real bank
	 * grid, this list's lanes as numbered green cells at their actual bank positions (anchor +
	 * pattern), the anchor cell outlined orange, and the slots other ENABLED lists occupy in gray so
	 * a new pattern can be placed around them. In "click to set" mode each left click adds (or
	 * removes) a lane at that bank cell, numbered in click order.
	 */
	private final class PatternPreview extends JPanel
	{
		private static final int CELL = 20;
		private static final int AXIS_W = 16;
		private static final int AXIS_H = 13;
		private static final int OX = 2 + AXIS_W;
		private static final int OY = 2 + AXIS_H;
		private static final int MIN_ROWS = 7;
		private static final int MAX_ROWS = 16;
		private RegearList list;

		PatternPreview()
		{
			setAlignmentX(LEFT_ALIGNMENT);
			setBackground(ColorScheme.DARKER_GRAY_COLOR);
			setToolTipText("<html>The bank as Regear sees it: columns 0-7 across the top, rows down the left.<br>"
				+ "Orange outline = this list's anchor (the Col/Row fields). Gray = other enabled lists.</html>");
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					if (!patternClickMode || list == null || !SwingUtilities.isLeftMouseButton(e))
					{
						return;
					}
					final int col = Math.floorDiv(e.getX() - OX, CELL);
					final int rel = Math.floorDiv(e.getY() - OY, CELL);
					if (col >= 0 && rel >= 0 && col < RegearList.BANK_COLUMNS && rel < displayRows())
					{
						// Clicks land on absolute bank cells; the pattern stores them anchor-relative.
						toggleOffset(col - list.anchorSlot % RegearList.BANK_COLUMNS,
							rowStart() + rel - list.anchorSlot / RegearList.BANK_COLUMNS);
					}
				}
			});
		}

		void setList(RegearList list)
		{
			this.list = list;
			revalidate();
			repaint();
		}

		/**
		 * First bank row shown. Normally 0; when this list is anchored so deep that its pattern
		 * would fall past the row cap, the view windows down to keep the anchor and lanes visible
		 * (the row axis labels stay the real bank row numbers).
		 */
		private int rowStart()
		{
			if (list == null)
			{
				return 0;
			}
			final int anchorRow = list.anchorSlot / RegearList.BANK_COLUMNS;
			int selMin = anchorRow;
			int selMax = anchorRow;
			for (PatternOffset o : list.effectiveOffsets())
			{
				selMin = Math.min(selMin, anchorRow + o.y);
				selMax = Math.max(selMax, anchorRow + o.y);
			}
			if (selMax + 2 <= MAX_ROWS)
			{
				return 0;
			}
			return Math.max(0, selMin - 1);
		}

		/** Rows shown: enough to cover this list's pattern and every other enabled list's, min 7. */
		private int displayRows()
		{
			int maxRow = MIN_ROWS - 2;
			if (list != null)
			{
				final int anchorRow = list.anchorSlot / RegearList.BANK_COLUMNS;
				maxRow = Math.max(maxRow, anchorRow);
				for (PatternOffset o : list.effectiveOffsets())
				{
					maxRow = Math.max(maxRow, anchorRow + o.y);
				}
			}
			for (RegearList other : lists())
			{
				if (other == null || other == list || !other.enabled)
				{
					continue;
				}
				for (int slot : other.footprintSlots())
				{
					maxRow = Math.max(maxRow, slot / RegearList.BANK_COLUMNS);
				}
			}
			return Math.min(MAX_ROWS, Math.max(MIN_ROWS, maxRow + 2 - rowStart()));
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(PANEL_WIDTH - 20, OY + displayRows() * CELL + 2);
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, OY + displayRows() * CELL + 2);
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
			final int cols = RegearList.BANK_COLUMNS;
			final int rows = displayRows();
			final int start = rowStart();

			// Axis numbers, so the Col/Row anchor fields map straight onto what you see.
			g.setFont(FontManager.getRunescapeSmallFont());
			g.setColor(ColorScheme.LIGHT_GRAY_COLOR);
			for (int x = 0; x < cols; x++)
			{
				final String n = String.valueOf(x);
				g.drawString(n, OX + x * CELL + (CELL - g.getFontMetrics().stringWidth(n)) / 2, OY - 3);
			}
			for (int y = 0; y < rows; y++)
			{
				final String n = String.valueOf(start + y);
				g.drawString(n, OX - 4 - g.getFontMetrics().stringWidth(n),
					OY + y * CELL + (CELL + g.getFontMetrics().getAscent()) / 2 - 2);
			}

			// Empty grid squares so the layout reads like the real bank.
			for (int y = 0; y < rows; y++)
			{
				for (int x = 0; x < cols; x++)
				{
					final int cx = OX + x * CELL;
					final int cy = OY + y * CELL;
					g.setColor(ColorScheme.DARK_GRAY_COLOR);
					g.fillRect(cx + 1, cy + 1, CELL - 3, CELL - 3);
					g.setColor(new Color(60, 60, 60));
					g.drawRect(cx, cy, CELL - 1, CELL - 1);
				}
			}

			// Slots other enabled lists occupy, in gray, so this pattern can be placed around them.
			g.setColor(new Color(130, 130, 130, 150));
			for (RegearList other : lists())
			{
				if (other == null || other == list || !other.enabled)
				{
					continue;
				}
				for (int slot : other.footprintSlots())
				{
					final int y = slot / cols - start;
					if (y >= 0 && y < rows)
					{
						g.fillRect(OX + slot % cols * CELL + 1, OY + y * CELL + 1, CELL - 3, CELL - 3);
					}
				}
			}

			// The anchor cell, outlined so the Col/Row fields have a visible target.
			final int anchorCol = list.anchorSlot % cols;
			final int anchorRow = list.anchorSlot / cols;
			if (anchorRow - start >= 0 && anchorRow - start < rows)
			{
				g.setColor(ColorScheme.BRAND_ORANGE);
				g.drawRect(OX + anchorCol * CELL, OY + (anchorRow - start) * CELL, CELL - 1, CELL - 1);
			}

			// Numbered green cells for this list's lanes, at their real bank positions.
			g.setFont(FontManager.getRunescapeBoldFont());
			final List<PatternOffset> offs = list.effectiveOffsets();
			for (int i = 0; i < offs.size(); i++)
			{
				final PatternOffset o = offs.get(i);
				final int x = anchorCol + o.x;
				final int y = anchorRow + o.y - start;
				if (x < 0 || x >= cols || y < 0 || y >= rows)
				{
					continue;
				}
				final int cx = OX + x * CELL;
				final int cy = OY + y * CELL;
				g.setColor(new Color(0, 200, 83, 160));
				g.fillRect(cx + 1, cy + 1, CELL - 3, CELL - 3);
				g.setColor(Color.WHITE);
				final String n = String.valueOf(i + 1);
				g.drawString(n, cx + (CELL - g.getFontMetrics().stringWidth(n)) / 2 + 1, cy + CELL - 5);
			}
		}
	}
}
