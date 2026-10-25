# Peek focus host tests

run_wait_gate.py also runs run_peek_focus.py on immutable real AGP classes. Production controller, foreground owner, focus model, local policy, ForegroundDetector, ScreenReceiver and core origins are asserted. Android/UI, clock, preferences, IO and account/settings routing are explicitly synthetic ports. The original wait suite retains its real routed writer/account/JNI coverage. Fixtures are synthetic. Device lifecycle, layout and accessibility are not qualified here.
