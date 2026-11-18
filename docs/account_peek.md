# Account-specific Peek

Each signed-in account owns its saved Peek, published clock and focus grace.
Changing the selected account does not transfer a Peek or reset another account's
grace. Stock accounts do not acquire a Peek from the shared Work preset.

The foreground registry binds an entry to both its canonical account key and its
sign-in lifetime. An expired grace, absolute deadline or explicit End first hides
that account locally. Failed state cleanup keeps that account hidden and retries
once a second and immediately on resume. Another account remains usable. A stale callback cannot
end the account that later occupies the same slot.

Reload prepares a candidate without publishing it. The state save and native
publication both check captured source authority, account identities, local
preferences and foreground deadlines. A change after the save reports saved but
unpublished state and schedules repair; it never rolls back newer disk bytes.
Startup cleanup is acknowledged only after native, account views and Java state
are published. Refusal before that point schedules another bounded repair and holds the protection toggle until cleanup succeeds.

The stock wait cover, picker and settings controls retain their existing styles.
Their captured account owns Start, Extend, End and wait escalation. Screen-time
Peek observation follows the selected account. Shared preset/source changes may
end all account Peeks deliberately.

Qualification combines complete production Java API compilation with actual core
JNI, account state writers, prepared reload commits, foreground lifecycle ports
and retained native account handles. Host ports do not establish real Android UI
or application behavior; final application qualification remains required.
