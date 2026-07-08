# Regear

A banking helper for RuneLite. You build the gear or inventory you want in a side panel, pick a
layout style, and Regear shows those items in fixed bank slots so you can withdraw them in the order
you set. It works like other banking plugins: it filters the bank and puts the next items where you
expect them. It does not click, withdraw, or move anything for you.

## What it does

- Build ordered lists in the side panel: a full 28-slot regear, a repot, a spec switch, or anything
  you withdraw the same way each time.
- When the bank is open, each enabled list shows a small rotating window of its items in set
  positions, so you click the same few slots in a rhythm.
- Show 1 to 6 slots at once, in a single spot, a vertical line, a Z block, or your own custom layout.
- Set a withdraw amount per item. A rune set to 30 stays put until you have pulled all 30.
- Add "or" alternatives per item, like a fresh and a used piece of gear, or several potion doses. It
  shows whichever one you own, and you can order the fallbacks.
- "Skip if worn" hides an item while you are already wearing it.
- An item id overlay and a right-click "Add to Regear" for building lists.
- A short tutorial that walks you through the patterns on your own bank.

The next items are worked out from what is actually in your inventory, so a misclick or a double
withdraw sorts itself out.

## Screenshots

![Side panel](screenshots/panel.png)

![Bank layout](screenshots/bank.png)

![Tutorial](screenshots/tutorial.png)

## Adding items

Right-click a bank, inventory, or worn item and choose "Add to Regear", or type an item id in the
panel. If you want to look up ids, the Item ID and Lookup plugin does that.

## Note

Display only. Regear never automates gameplay. You perform every click.
