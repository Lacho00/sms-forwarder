# Manual test checklist

Run through it on a real phone with the Release APK. Note any cases that fail.

- [ ] Normal SMS from a configured sender: reaches the endpoint and shows as sent.
- [ ] Multipart SMS: forwarded only once, with the full text.
- [ ] Airplane mode: the SMS stays queued and is sent when the network returns.
- [ ] Force stop and open the app: the sweep recovers the missed SMS and notifies.
- [ ] Wrong token: 401 response, notification; after fixing the token it is requeued and sent.
- [ ] Phone reboot: the app stays active and the sweep stays scheduled.
- [ ] The previous forwarding app is disabled before setup.
- [ ] Light and dark mode: the UI looks right in both.
- [ ] Remove Autostart (MIUI): the warning appears.
- [ ] Android 15, restricted settings: if the permission dialog does not show, the first launch
      explains "Allow restricted settings".
- [ ] Key lost after transferring to another device: the app shows `NOT_CONFIGURED` and does not crash.
- [ ] The first sweep after setup sends nothing from before the setup.
- [ ] An SMS that arrives during the first launch is not forwarded.
- [ ] The first launch does not let you continue without at least one sender.
- [ ] Emptying the sender list in Settings shows the `NO_SENDERS` card and notification.

## v1.1.0

- [ ] Launcher icon: looks right in light mode, dark mode and with the themed (monochrome) icon.
- [ ] Changing the language (Spanish/English) in Settings does not lose the screen you are on.
- [ ] Pause forwarding: new SMS are not sent; on resume they are sent.
- [ ] Delete from the app an SMS that is still in the inbox and wait for a sweep: it is not forwarded.
- [ ] Sweep interval: after changing it, `adb shell dumpsys jobscheduler | grep dev.smsforwarder`
      shows the new period.
- [ ] Turn off dynamic color: the UI uses the fixed palette and looks right in light and dark.
- [ ] Hide text: Home and Detail do not show the SMS content.
- [ ] Home: search and filters (all, queued, error) narrow the list.
- [ ] About: shows the correct version, the repository link opens and the MIT license appears.
- [ ] Notifications by type (recovered, stuck, permissions): when each is turned off, that notice does not appear.
- [ ] A 400 rejection still notifies even when the optional notices are turned off.
- [ ] Retention: with a short period, sent history is purged; queued or failed SMS are not deleted.

## Updating from 1.1 to 1.2

Install the branch's Release APK over the real 1.1.0 without uninstalling (`adb install -r`).

- [ ] Home shows the same history as before the update.
- [ ] Settings shows the same senders.
- [ ] The connection test works.
- [ ] A new SMS from a configured sender reaches the endpoint with 200.
- [ ] The app does not ask for any new permission.

The Release APK is not debuggable, so the database cannot be copied before updating.
The safety net is the migration tests (T3) and the import tests (T5). Whoever installs confirms
beforehand that they accept this risk.

## v1.3.0

- [ ] Create one destination of each template (standard JSON, Telegram, ntfy, custom) and press Test:
      standard JSON with body `{}` responds 400 (expected); Telegram and ntfy receive the test SMS.
- [ ] The test SMS does not appear in Home and leaves no attempts.
- [ ] A failing destination next to a working one: the SMS reaches the working one and the failing one does not delay it.
- [ ] Pause a destination with queued SMS: they are not sent; on resume they are sent right away and the other
      destination, already sent, does not repeat.
- [ ] Pause a destination that has failed deliveries: they stay in error, they do not go back to "queued".
- [ ] Delete a destination with queued SMS: the warning gives the number of SMS, the deliveries are
      canceled and there is no stuck notice.
- [ ] Regex rule with `(?i)` and another with a "contains" text filter.
- [ ] Rule with a "does not contain" filter: an SMS that contains the text is discarded and another from the same
      sender that does not contain it is forwarded.
- [ ] Rule with SIM 2 on a single-SIM phone: forwards nothing.
- [ ] Edit the token of a destination with 401: the SMS is forwarded again.
- [ ] Resume a paused destination after fixing its token (it had 401/403): its SMS go back to the queue
      and are sent.
- [ ] Detail: "Resend to a destination" sends the SMS to the chosen destination, even one that already received it.
- [ ] Detail: the HTTP code only appears if it comes from a failing delivery; with one destination at 200 and
      another at 403, "Error 200" does not show.
- [ ] Home: the destination chips filter the list and each row shows the per-destination status.
- [ ] Changing the language with an editor open does not lose the screen.
- [ ] Updating from 1.2.0 to 1.3.0 (`adb install -r`) does not lose destinations, rules or history.

## v2.0.0

- [ ] Export without secrets (checkbox unchecked) and open the file: no destination has a URL or secret
      header values (the names of those headers do appear).
- [ ] Export with secrets: checking the box shows the warning; the file contains URLs, tokens and
      secret headers.
- [ ] Import the file with secrets on the same phone: everything is added as a duplicate with the suffix
      ` (2)` and nothing is replaced. The summary says "Imported N destination(s) / rule(s)" (singular and plural
      correct) and a progress indicator shows while importing.
- [ ] Import the file without secrets: the summary includes "No URL or token (add them in Destinations):
      <names>"; in Destinations those destinations appear in the "No credentials" row; an active rule that
      uses them shows the `NOT_CONFIGURED` warning; after adding the URL, queued SMS are sent right away.
- [ ] Import an invalid file (http URL, broken regex, `"version": 2`, any text file):
      error message and nothing changes (no destinations, rules or secrets).
- [ ] If saving the secrets fails during import, the message says nothing was imported and the
      destination and rule lists stay the same.
- [ ] Export diagnostics from About and search the file for an SMS body, a sender
      and a destination URL: none of them appears.
- [ ] About: back arrow, round icon and link to the source code.
- [ ] Menu ⋮ with Export, Import and About on all three tabs.
- [ ] With the legacy migration in safe mode, Export and Import are disabled.
- [ ] Updating from 1.3.0 to 2.0.0 (`adb install -r`) does not lose destinations, rules or history.
- [ ] The APK asks for no new permissions (`aapt dump permissions` same as 1.3.0).
