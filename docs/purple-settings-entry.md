# Purple Settings entry

The main Settings row and its destination screen are named Purple Settings.
The row keeps the existing id, click route and account subtitle. Its custom
white vector uses the stock 24dp Settings icon slot and inset. It sits on
SettingCell.Background with IconBackgroundColors.PURPLE's gradient, rounded
shape and dark-theme border, matching the adjacent Settings tiles. Work Mode
remains the name of the preset feature within Purple Settings; existing deep links and preset-picker routes keep working.

`PurpleSettingsRow.entry()` owns the row, and SettingsActivity adds it through
one existing Purple hook. The vector lives in `res/drawable/purple_settings.xml`.

SettingCell's binding also calls PurpleSettingsRow.alignText() for the title and
subtitle. It retains native text direction and uses TextCell's explicit
LocaleController-based RIGHT/LEFT gravity with TEXT_ALIGNMENT_GRAVITY, so a
Latin title or dynamic preset aligns with its RTL stock neighbors without
rewriting or reversing strings. Binding a reused cell to another icon restores
its exact native gravity and text alignment. The filled tile is unchanged.
