# Player strip layout

The audio context strip uses a 48dp row so each seek button has a 48dp square
touch target. PurpleRecordingStrip measures its title after checkPlayer has
updated the title padding and speed visibility; style-time padding was replaced
by those upstream updates and allowed long music titles under the seek labels.
The helper assigns actual title bounds and END ellipsis, without accumulating
padding. PurplePlayerStripLayout mirrors physical bounds for RTL and reserves
only visible play, close and speed controls. A side-menu inset is bounded when
space is short; the title can shrink to zero but cannot overlap controls.

Seek labels use inappPlayerTitle, with the same selector mechanism as stock
controls. Theme changes are read on draw. This follows the player surface's
text color in light, dark and custom colored themes. A docked recording hides
seek controls and uses its own weighted LinearLayout title, whose width already
excludes its timer and actions; it shares no title padding with playback.
When the recorder closes, measurement makes the seek controls visible again.

Upstream hooks are confined to FragmentContextView's helper construction,
style update, measurement, style height and recorder show/hide. The host media
suite checks title/control separation and mirroring across narrow and wide
strips, optional controls and side-menu insets. Device acceptance must still
judge real text, theme contrast, hit delivery, recording transitions and audio
style transitions to live location and group calls.
