# Search visibility refresh host test

The runner uses real AGP output for `PurpleSearchRefresh`, verifies its class
origin, and injects the exact production adapter/screen bridge bodies into small
in-memory UI ports. It checks account isolation, null lifecycle surfaces,
current query trimming, both notifications, stable hiding and unhide order, and
unchanged source lists and ratings. It checks source wiring and forbids write or
network calls in the owned helper. This is not a native RecyclerView/device run.

Run from any directory after AGP javac:

```
python3 TMessagesProj/jni/purple_jni/tests/run_search_refresh.py --receipt RECEIPT --output SCRATCH
```

`RECEIPT` is the real AGP javac source/classpath receipt. `SCRATCH` must not
already exist. No JSON library or JNI binary is needed by this display hook.

The retained-pager regression ports the review probe: 100 same-account events
while a retained pager is GONE produce zero filter/notify calls. It also covers
logical close during the closing animation, an invisible pager, hidden/unhidden
rows after an explicit reopen refresh, and unchanged raw sources/ratings. Source
guards connect that refresh to both animated and immediate showSearch routes.
