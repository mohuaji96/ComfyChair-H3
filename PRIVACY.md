# Privacy Policy

**App**: ComfyChair H3 (`com.comfychair.h3`)
**Based on**: [ComfyChair](https://github.com/legal-hkr/comfychair) by Legal HKr

## Overview

ComfyChair H3 is an open-source Android client for a ComfyUI server that you run and control. The
app has no backend of its own: there is no account, no telemetry, no analytics, no crash reporting
service, and no advertising.

## What never leaves your device

Nothing is sent to the developer or to any third party. The only network destination the app talks
to is the ComfyUI server address you enter yourself.

## What is sent to your server

Exactly the data needed to render a job:

- Text prompts and generation parameters (duration, aspect ratio, megapixel target, steps)
- Reference images, tail frames and reference audio you attach
- Workflow definitions you import

These requests go directly to your server. If that server is exposed beyond your own network, that
is your configuration and your responsibility.

## What is stored on the device

| Data | Where | Removed by |
|---|---|---|
| Server address, port, TLS preference | app preferences | clearing app data / uninstalling |
| Generation parameters and prompt text of your workflows | app preferences | clearing app data / uninstalling |
| Generated images and videos (disposable copy) | `cacheDir/gallery_cache` | the cache cap, "clear cache", or the system reclaiming storage |
| Gallery metadata (file names, first-seen time, size, dimensions, duration) | app internal storage | clearing app data / uninstalling |
| Favourites and deleted-item tombstones | app preferences | clearing app data / uninstalling |
| Saved media | your device gallery (MediaStore) | you, manually — the app never deletes these |

The cache is a convenience copy. Anything you deliberately save with the save action goes to your
gallery through MediaStore and stays until you delete it there.

Debug logs are kept in memory only, are not written to disk, and disappear when the process ends.

## Permissions

- Network access — talking to your ComfyUI server
- Read media / photo picker — attaching reference images and videos you choose
- Write to gallery — only when you tap save

The app does not read your gallery in the background, does not access contacts, location, camera,
microphone or SMS.

## Children

The app has no user accounts and no content of its own; it displays whatever your ComfyUI server
generates.

## Changes

Changes to this policy will be committed to this repository, where the full history is public.

## Contact

Open an issue on this repository.
