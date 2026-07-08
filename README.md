# Regear

A guided banking tool for RuneLite. Build ordered withdrawal lists in the side panel; when the bank
is open, Regear filters it to a small **rotating window** of each enabled list and moves those items
into fixed, predictable bank slots, so you can click the same few positions in rhythm while the
plugin advances each slot to the next correct item.

**Display-only.** Regear never clicks, withdraws, deposits or moves items for you. It only filters,
positions, highlights and tracks. You perform every click.

## How it works

- **Side panel:** create/rename/delete lists; per list set enabled, visible item count (1-4),
  pattern (Single / Vertical / Z / Custom), bank anchor and completion behaviour. Edit the ordered
  items in a grid (drag to reorder, right-click to duplicate / remove / edit id / set note). Add
  items by id, or by right-clicking any bank/inventory/equipment item and choosing **Add to Regear**.
- **Bank engine:** on the bank's finish-building script, Regear hides every bank item and repositions
  just the active target of each enabled list into its configured slot. This is the same widget
  technique RuneLite's own Bank Tag Layouts use, reimplemented here so Regear is fully self-contained
  and needs no other plugin enabled.
- **Rotation:** the list is split into `visibleCount` lanes; lane *k* walks the list as
  `k, k+L, k+2L, ...` (L = lane count). A lane advances only when the plugin sees that item actually
  leave the bank (inventory count rises), so hovers and misclicks never advance it.
- **Overlays:** an optional item-id overlay (bank/inventory/equipment) for building lists, plus bank
  guidance (active-slot highlight, lane numbers, next-item preview, missing markers).

## Dev

```bash
./gradlew runClient          # launch a RuneLite client with Regear loaded (8g heap, assertions on)
./gradlew build              # compile + unit tests
```

The dev client (`runClient`) streams Regear events (`[life]`, `[bank]`, `[rotate]`) to
`~/.runelite/regear-dev.log` automatically; the desktop launcher opens a window that tails it live.
These are `log.debug` lines, so they never appear in a normal (INFO-level) Hub client.

Display-only; no gameplay automation.
