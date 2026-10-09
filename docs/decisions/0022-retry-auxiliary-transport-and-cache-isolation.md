# 0022 — Retry, auxiliary transport and thumbnail lock isolation

- Status: accepted for FN-60
- Date: 2026-10-09
- Extends: 0019 playback session lifecycle; 0016 VLC folder covers

## Problem

A directory job launched directly in the player session could survive a retry and
publish a late failure over the new scan's subtitle choices and sibling list.
A second retry during asynchronous teardown could snapshot native Idle/0 instead
of the first retry's 42-second resume target. Directory, subtitle and listen SMB
clients were not all reachable before blocking connect/read; coroutine cancellation
alone could leave exit waiting for a transport timeout. Thumbnail memory getters
shared the same object monitor as disk decode, write, trim and clear.

## Decision

- Each directory scan has its own job, generation and auxiliary transport owner.
  Retry/replay/exit retires that generation before a replacement is started.
  Directory-result validity is independent from the subtitle-selection gate:
  manual Off/embedded choice does not suppress valid options or sibling results.
- Capture retry position on main before replacing the open job or resetting native
  media. Retain it across replacement jobs until the new first frame is ready.
  Explicit seeks, including zero, remain user intent; do not take a maximum of old
  history and the new position.
- Register every auxiliary SMB client before connect/list/read. Retirement detaches
  only that owner's clients under a short lock and schedules transport-first abort
  on independent I/O cleanup jobs. Late registration is rejected and aborted.
  Listen audio providers/reconnect callbacks capture their generation's owner;
  old cleanup cannot close or reconnect through a new generation's client.
  This retains the existing separate playback/listen/directory/subtitle transports.
- Thumbnail filesystem mutations, decodes and byte accounting share a disk lock.
  Bitmap/duration getters use only a short memory lock. Lock order is disk then
  memory; memory holders never acquire the disk lock. Clear serializes with disk
  operations and advances a generation before releasing memory entries, so queued
  pre-clear work cannot repopulate the cleared generation. Bitmap eviction/clear
  removes references and never recycles images still potentially displayed by UI.
  Failed deletion stays accounted for; replacing bytes invalidates the old image
  and duration before fallible duration-sidecar writing.

## Evidence and limits

Pure-JVM regressions cover retry latching, explicit zero and gated auxiliary
connect/list/read cancellation, deferred abort and old/new-owner isolation.
Android instrumentation regressions use real PlayerViewModel/VLC state, controlled
native events and fake SMB clients to cover overlapping retry teardown, late scan
failure after replacement success, manual subtitle choice, directory/listen/sidecar
cancellation and bitmap-cache decode/clear races. No credentials or real NAS are
required. Tests compiled is not tests executed on a phone/tablet; final execution
results are recorded in the FN-60 handoff. No real-device performance claim is made.

## Rejected alternatives

- Reuse subtitle-selection generation for directory results: would strand scanning
  and discard siblings after a manual subtitle choice.
- Rely only on Job.cancel()/join(): blocking SMB calls can ignore cancellation.
- Close a globally mutable client later: stale cleanup can affect a replacement.
- Keep the shared cache monitor or recycle evictions: respectively blocks UI memory
  hits on disk and risks displaying a recycled bitmap.
