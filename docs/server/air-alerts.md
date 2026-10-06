# Air-raid alerts

[Server guide](readme.md) · [API](api.md)

Air-raid alerts are optional and off for every account until the user enables them in Android
Settings or the browser account page. The server accepts UkraineAlarm webhook callbacks and
checks the official API before publishing a change. Only oblast-level `AIR` starts and all-clears
are sent. No phone location is collected for this nationwide subscription.

The Android app keeps its authenticated WebSocket open only while its screen is visible. It shows
the current snapshot without notifying about alerts that were already active on connection, then
posts a system notification for later changes. Android notification permission and channel
settings can suppress delivery. Provider outages, network loss, and webhook delays can also make
the feed late or stale. Use official warning
channels for decisions about personal safety.

## Configure the provider

1. Obtain a dedicated UkraineAlarm API key from [the official API](https://api.ukrainealarm.com/).
   A key used by another integration may already own a webhook subscription.
2. Deploy the server with a public HTTPS `CELLS_PUBLIC_URL` and set
   `CELLS_AIR_ALERTS_API_KEY` and `CELLS_AIR_ALERTS_WEBHOOK_SECRET` in the private Compose `.env`.
   The secret must be a random alphanumeric string of at least 32 characters; never commit or log
   it. The server registers its callback at startup.
3. Review the operator privacy notice for the new account preference and notification purpose,
   then update its version when the notice changes. Keep the callback URL and path out of reverse
   proxy access logs. The bundled Caddyfile does not enable access logging.

If either setting is absent, the feature is unavailable and the normal cell server continues to
run. Registration failures do not stop the server: the worker retries and reconciles its snapshot
every five minutes. A callback only requests a refresh; the authenticated provider GET determines
whether an alert has started or cleared. Never treat a failed GET as an all-clear.

The [UkraineAlarm Python client](https://github.com/UkraineAlarm/UkraineAlarm-python) documents
the v3 alert, region and webhook endpoints used here.
