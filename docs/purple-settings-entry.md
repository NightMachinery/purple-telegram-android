# Purple Settings entry

The main Settings row and its destination screen are named Purple Settings.
The row keeps the existing id, click route and account subtitle. Its custom
purple vector uses the stock 24dp Settings icon slot and inset, with no colored
square background. Work Mode remains the name of the preset feature within
Purple Settings; existing deep links and preset-picker routes keep working.

`PurpleSettingsRow.entry()` owns the row, and SettingsActivity adds it through
one existing Purple hook. The vector lives in `res/drawable/purple_settings.xml`.
