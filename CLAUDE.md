# Working on VantaView

## Feature checklist

`docs/FEATURES.md` is the list of agreed features. Keep it up to date in the same commit as the work:

- When a commit finishes a feature, change its `- [ ]` to `- [x]` and remove its *(italic note)*.
- When a feature is only partly built, leave it unticked and update its *(italic note)* to say what's missing.
- A new feature that isn't on the list goes under "Built that wasn't on the original list", ticked.
- Never tick something that isn't in the code yet.

## What's new

`docs/WHATS-NEW.md` is the "What's new" shown in the app's update notice for test builds. In the same commit as any change people will notice, add a short plain-English bullet at the top (newest first) and keep the list to about 8 bullets. Write it for viewers, not developers: what they can now do, not how it was built. For a release, copy the relevant bullets into `docs/releases/v<version>.md`.

The app shows these notes in its own language. In the same commit, add the same bullet, translated, at the top of every `docs/whats-new/<language>.md` (es, fr, de, it, pt, nl, pl, tr, ar), and keep each file to the same bullets in the same order as `docs/WHATS-NEW.md` (a unit test checks the counts). For a release, also put translated notes in `docs/releases/v<version>/<language>.md`.

## Releases

Every release must keep the permanent download link working (it's used for Downloader codes):

- The APK attached to a release is always named `VantaView.apk`. Never add the version to the file name.
- Every release is published as the latest release, never as a pre-release. (`test-latest` is the only pre-release.)
- Permanent link: https://github.com/wardy-uk1/VantaView/releases/latest/download/VantaView.apk
- Write the notes in `docs/releases/v<version>.md` before running the Release workflow.
