//
//  BackgroundDownloadTask.swift
//  PeerSync
//
//  Keeps the app running during a download, even after it moves to the background.
//  A rework of QSProbe's BackgroundTransferTask for "one file = one task".
//
//  ## Two stages (same as QSProbe)
//
//  | Stage | API | Duration | On-screen UI |
//  |---|---|---|---|
//  | 1 | `UIApplication.beginBackgroundTask` | about 30 s | none |
//  | 2 | `BGContinuedProcessingTask` (iOS 26+) | until the transfer ends | system progress UI |
//
//  ## Identifiers (follows what was measured with QSProbe)
//
//  - A task only launches if its identifier is **under the runtime bundle ID** and listed
//    in Info.plist as an **exact match**. SideStore also rewrites Info.plist's
//    `BGTaskSchedulerPermittedIdentifiers` to include the team ID.
//  - Reusing an identifier is fine (QSProbe rev3: still launches after cycling through slots 0-7).
//
//  For one file = one task, Info.plist lists 8 slots, `<bundleId>.transfer.0` to `.7`,
//  and each download gets a free slot. With more than 8 simultaneous downloads,
//  the 9th and later get no stage 2 (only stage 1's ~30 s).
//
//  ## LiveContainer (supported since v0.1.7)
//
//  The guest runs in the host (LiveContainer) process, so the system (the BackgroundTasks daemon)
//  validates identifiers against **the host's Info.plist and bundle ID**. The guest plist is ignored
//  (the same "missing from the host plist" issue as LiveContainer #1519 / #1528 / #1666). So:
//    1. LiveContainer's Info.plist needs `UIBackgroundModes: processing` and
//       `BGTaskSchedulerPermittedIdentifiers` (`com.kdt.livecontainer.transfer.0`-`.7` and `.transfer.*`)
//       (the LiveContainer-bgtask fork). SideStore rewrites them to include the team ID.
//    2. The guest reads the host bundle ID from `+[NSUserDefaults lcMainBundle]` (LiveContainer.app's
//       NSBundle, saved by LC before swapping) and
//       registers / submits with `<host bundle ID>.transfer.N` (RuntimeEnvironment.hostBundle).
//    3. `register()` checks locally against the guest plist (swapped in by LC), so the
//       LiveContainer-bgtask fork merges the exact host identifiers into the guest plist at install time.
//       0.2.31: removed `com.kdt.livecontainer.*` from the guest plist (`.*` entries not starting with
//       the guest bundle ID are discarded, so it never worked).
//  Inside LiveProcess.appex (multitasking) it's an App Extension, so stage 2 is unavailable.
//
//  ## Threading
//
//  Main thread only. `register(using: .main)` makes handlers run on main too.
//

import BackgroundTasks
import Foundation
import UIKit
import PeerSyncShared

/// Slot assigned to one download. TransferManager holds it, feeds progress, and closes it when done.
final class BackgroundDownloadHandle {
    fileprivate let slot: Int
    fileprivate let pool: BackgroundDownloadTaskPool
    fileprivate init(slot: Int, pool: BackgroundDownloadTaskPool) {
        self.slot = slot
        self.pool = pool
    }

    /// Reports progress (call from main). Throttled internally, so call it every time. A negative percent means indeterminate.
    func update(subtitle: String, percent: Int) {
        pool.update(slot: slot, subtitle: subtitle, percent: percent)
    }

    /// Transfer exit (call from main). Call it on success, failure, or cancellation.
    func end(success: Bool) {
        pool.end(slot: slot, success: success)
    }
}

final class BackgroundDownloadTaskPool {
    private static let tag = "BGTask"

    static let shared = BackgroundDownloadTaskPool()

    /// Number of slots listed in Info.plist (`<bundleId>.transfer.0` to `.7`).
    static let slotCount = 8

    /// Reads the plain bundle ID from `project.yml` via Info.plist's `PSDeclaredBundleIdentifier`.
    /// The value before SideStore rewrites it; the fallback when registering with the runtime bundle ID fails.
    private static let declaredBundleIdKey = "PSDeclaredBundleIdentifier"
    private static var declaredBundleId: String? {
        guard let v = Bundle.main.object(forInfoDictionaryKey: declaredBundleIdKey) as? String else { return nil }
        let t = v.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty || t.hasPrefix("$(") ? nil : t
    }

    /// Watchdog time until the handler launches. Normally under 1 second.
    private static let launchTimeout: TimeInterval = 2.0
    /// Redrawing system UI isn't cheap. At most every 250 ms.
    private static let publishInterval: TimeInterval = 0.25
    /// Grace period after a transfer ends before releasing execution (for stream cleanup).
    private static let finishGrace: TimeInterval = 1.0

    private final class Slot {
        var identifier: String?           // Identifier accepted by register(). nil means no stage 2
        var inUse = false
        var title = ""
        var subtitle = ""
        var percent = Int.min
        var lastPublished = Date.distantPast
        var onExpire: (() -> Void)?
        var assertionId: UIBackgroundTaskIdentifier = .invalid
        var task: BGTask?                 // Holds the BGContinuedProcessingTask as its base type
        var submitted = false
        var watchdog: DispatchWorkItem?
        var pendingTeardown: DispatchWorkItem?
    }

    private var slots: [Slot] = (0..<BackgroundDownloadTaskPool.slotCount).map { _ in Slot() }
    private var registered = false

    private init() {}

    // MARK: - Launch

    /// Call once at launch (from PeerSyncApp.init). Calls register() for every slot.
    func register() {
        guard !registered else { return }
        registered = true

        qlog(.info, Self.tag, "env = \(RuntimeEnvironment.summary) / bundleId = \(Bundle.main.bundleIdentifier ?? "(unknown)") / declared bundleId = \(Self.declaredBundleId ?? "(none)")")

        guard #available(iOS 26.0, *) else {
            qlog(.info, Self.tag, "Continued processing tasks need iOS 26 (background limited to about 30 s)")
            return
        }

        let permitted = Bundle.main.object(forInfoDictionaryKey: "BGTaskSchedulerPermittedIdentifiers") as? [String] ?? []
        qlog(.info, Self.tag, "Guest plist permitted identifiers = \(permitted)")

        if RuntimeEnvironment.isLiveContainer {
            // Log the host (LiveContainer.app) plist for diagnosis. If processing and the identifiers are missing,
            // LiveContainer needs support for it (the LiveContainer-bgtask fork).
            qlog(.info, Self.tag, "Host executable = \(RuntimeEnvironment.hostExecutablePath ?? "(unknown)")")
            qlog(.info, Self.tag, "Host bundle source = \(RuntimeEnvironment.hostBundleSource) / path = \(RuntimeEnvironment.hostBundle?.bundlePath ?? "(unknown)")")
            qlog(.info, Self.tag, "Host bundleId = \(RuntimeEnvironment.hostBundleIdentifier ?? "(unknown)") / UIBackgroundModes = \(RuntimeEnvironment.hostBackgroundModes)")
            qlog(.info, Self.tag, "Host plist permitted identifiers = \(RuntimeEnvironment.hostPermittedTaskIdentifiers)")
        }

        guard RuntimeEnvironment.canUseBackgroundTasks else {
            if RuntimeEnvironment.isLiveProcess {
                qlog(.warning, Self.tag, "Continued processing tasks unavailable inside LiveProcess (App Extension) (background limited to about 30 s)")
            } else {
                qlog(.warning, Self.tag, "Continued processing tasks unavailable: LiveContainer's Info.plist lacks processing / BGTaskSchedulerPermittedIdentifiers (background limited to about 30 s). Use the LiveContainer-bgtask fork")
            }
            return
        }

        guard !permitted.isEmpty else {
            qlog(.error, Self.tag, "BGTaskSchedulerPermittedIdentifiers is empty. Info.plist is missing it")
            return
        }

        var adopted = 0
        for i in 0..<Self.slotCount {
            for candidate in Self.candidateIdentifiers(slot: i) {
                let ok = BGTaskScheduler.shared.register(forTaskWithIdentifier: candidate, using: .main) { [weak self] task in
                    self?.attach(task, slot: i)
                }
                if i == 0 {
                    // Log per-candidate results for slot 0 only (to diagnose which plist register() checks)
                    qlog(.debug, Self.tag, "register(\(candidate)) = \(ok)")
                }
                if ok {
                    slots[i].identifier = candidate
                    adopted += 1
                    break
                }
            }
        }
        if adopted == 0 {
            qlog(.error, Self.tag, "No usable identifiers. Check BGTaskSchedulerPermittedIdentifiers in project.yml")
        } else {
            qlog(.info, Self.tag, "Adopted identifiers for \(adopted)/\(Self.slotCount) slots (e.g. \(slots[0].identifier ?? "?"))")
        }

        // Cancel requests left over from the previous session (this app uses no other BGTasks).
        BGTaskScheduler.shared.getPendingTaskRequests { requests in
            guard !requests.isEmpty else { return }
            qlog(.warning, Self.tag, "Cancelling leftover requests from last run — \(requests.map { $0.identifier }.joined(separator: ", "))")
            BGTaskScheduler.shared.cancelAllTaskRequests()
        }
    }

    /// Candidate identifiers for slot i, in order:
    ///  In LC: under the host (LiveContainer) bundle ID -> under the runtime bundle ID -> under the declared plain bundle ID
    ///  Normal: under the runtime bundle ID -> under the declared plain bundle ID
    private static func candidateIdentifiers(slot: Int) -> [String] {
        var c: [String] = []
        if RuntimeEnvironment.isLiveContainer, let host = RuntimeEnvironment.hostBundleIdentifier {
            c.append("\(host).transfer.\(slot)")
        }
        if let runtime = Bundle.main.bundleIdentifier {
            let s = "\(runtime).transfer.\(slot)"
            if !c.contains(s) { c.append(s) }
        }
        if let declared = declaredBundleId {
            let s = "\(declared).transfer.\(slot)"
            if !c.contains(s) { c.append(s) }
        }
        return c
    }

    // MARK: - Begin / update / end

    /// Download entry point. Call right after a user action (i.e. foreground, on main).
    /// nil if no slot is free (that download proceeds without stage 1 either).
    func begin(title: String, subtitle: String, onExpire: @escaping () -> Void) -> BackgroundDownloadHandle? {
        guard let i = slots.firstIndex(where: { !$0.inUse }) else {
            qlog(.warning, Self.tag, "No free slot (up to \(Self.slotCount) at once). This download continues without background protection")
            return nil
        }
        let s = slots[i]
        s.inUse = true
        s.title = title
        s.subtitle = subtitle
        s.percent = Int.min
        s.lastPublished = .distantPast
        s.onExpire = onExpire

        holdAssertion(i)
        submit(i)
        return BackgroundDownloadHandle(slot: i, pool: self)
    }

    fileprivate func update(slot i: Int, subtitle: String, percent: Int) {
        let s = slots[i]
        guard s.inUse else { return }
        guard subtitle != s.subtitle || percent != s.percent else { return }
        let now = Date()
        let overdue = now.timeIntervalSince(s.lastPublished) >= Self.publishInterval
        // Never throttle 100%. If the last update is dropped, the banner looks stuck at 99%.
        guard overdue || percent >= 100 else { return }
        s.subtitle = subtitle
        s.percent = percent
        s.lastPublished = now
        applyToTask(i)
    }

    fileprivate func end(slot i: Int, success: Bool) {
        let s = slots[i]
        guard s.inUse else { return }
        cancelWatchdog(i)
        s.onExpire = nil

        if #available(iOS 26.0, *), let task = s.task as? BGContinuedProcessingTask {
            task.updateTitle(s.title, subtitle: success ? "Completed" : s.subtitle)
            if success {
                task.progress.totalUnitCount = 100
                task.progress.completedUnitCount = 100
            }
            // Extend briefly for stream cleanup, then close
            let item = DispatchWorkItem { [weak self] in self?.finishNow(i, success: success) }
            s.pendingTeardown = item
            DispatchQueue.main.asyncAfter(deadline: .now() + Self.finishGrace, execute: item)
        } else {
            finishNow(i, success: success)
        }
    }

    private func finishNow(_ i: Int, success: Bool) {
        let s = slots[i]
        s.pendingTeardown?.cancel()
        s.pendingTeardown = nil

        if #available(iOS 26.0, *) {
            if let task = s.task as? BGContinuedProcessingTask {
                task.setTaskCompleted(success: success)
                qlog(.info, Self.tag, "Ended continued processing task (slot \(i), success=\(success))")
            } else if s.submitted, let id = s.identifier {
                // Handler not called yet = not started. Cancel the whole request.
                BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: id)
                qlog(.info, Self.tag, "Cancelled request before launch — \(id)")
            }
        }
        clearSlot(i)
        releaseAssertion(i)
    }

    // MARK: - Stage 2 (BGContinuedProcessingTask)

    private func submit(_ i: Int) {
        guard #available(iOS 26.0, *) else { return }
        let s = slots[i]
        guard let id = s.identifier else {
            qlog(.debug, Self.tag, "Slot \(i): no usable identifier, skipping stage 2 (background limited to about 30 s)")
            return
        }
        let request = BGContinuedProcessingTaskRequest(identifier: id, title: s.title, subtitle: s.subtitle)
        request.strategy = .fail // Started by a foreground user action; if it can't run, we want the reason
        do {
            try BGTaskScheduler.shared.submit(request)
            s.submitted = true
            qlog(.info, Self.tag, "Submitted — \(id)")
            startWatchdog(i)
        } catch {
            s.submitted = false
            qlog(.error, Self.tag, "Submit failed — \(id): \(error.localizedDescription) (background limited to about 30 s)")
        }
    }

    /// Called when the OS launches the task. Stage 1 is released here.
    private func attach(_ task: BGTask, slot i: Int) {
        cancelWatchdog(i)
        let s = slots[i]

        guard #available(iOS 26.0, *), let continued = task as? BGContinuedProcessingTask else {
            qlog(.error, Self.tag, "Received task is not a BGContinuedProcessingTask — \(type(of: task))")
            task.setTaskCompleted(success: false)
            return
        }
        guard s.inUse else {
            qlog(.info, Self.tag, "Transfer already finished; closing task — \(continued.identifier)")
            continued.setTaskCompleted(success: false)
            return
        }

        s.task = continued
        qlog(.info, Self.tag, "★ Continued processing task launched (slot \(i)). Download will continue in the background — \(continued.identifier)")
        applyToTask(i)
        releaseAssertion(i)

        continued.expirationHandler = { [weak self] in
            continued.setTaskCompleted(success: false)
            guard let self else { return }
            let handler = s.onExpire
            self.clearSlot(i)
            self.releaseAssertion(i)
            qlog(.warning, Self.tag, "Continued processing task expired (slot \(i)). Stopping download")
            handler?()
        }
    }

    private func applyToTask(_ i: Int) {
        guard #available(iOS 26.0, *) else { return }
        let s = slots[i]
        guard s.inUse, let task = s.task as? BGContinuedProcessingTask else { return }
        task.updateTitle(s.title, subtitle: s.subtitle)
        if s.percent < 0 {
            task.progress.totalUnitCount = 0 // Indeterminate progress
            task.progress.completedUnitCount = 0
        } else {
            task.progress.totalUnitCount = 100
            task.progress.completedUnitCount = Int64(min(100, max(0, s.percent)))
        }
    }

    // MARK: - Launch watchdog

    private func startWatchdog(_ i: Int) {
        cancelWatchdog(i)
        let item = DispatchWorkItem { [weak self] in
            guard let self else { return }
            let s = self.slots[i]
            guard s.task == nil, s.inUse else { return }
            qlog(.error, Self.tag, "Task didn't launch within \(Int(Self.launchTimeout)) s of submitting — \(s.identifier ?? "?") (background limited to about 30 s)")
            if #available(iOS 26.0, *) {
                BGTaskScheduler.shared.getPendingTaskRequests { requests in
                    qlog(.warning, Self.tag, requests.isEmpty
                             ? "No pending requests (the system may have dropped it after accepting)"
                             : "Pending requests — \(requests.map { $0.identifier }.joined(separator: ", "))")
                }
            }
        }
        slots[i].watchdog = item
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.launchTimeout, execute: item)
    }

    private func cancelWatchdog(_ i: Int) {
        slots[i].watchdog?.cancel()
        slots[i].watchdog = nil
    }

    // MARK: - Stage 1 (beginBackgroundTask)

    private func holdAssertion(_ i: Int) {
        let s = slots[i]
        guard s.assertionId == .invalid else { return }
        s.assertionId = UIApplication.shared.beginBackgroundTask(withName: AppInfo.shared.identifier(suffix: "transfer.\(i)")) { [weak self] in
            guard let self else { return }
            self.releaseAssertion(i)
            // If stage 2 is already running, the transfer continues even when stage 1 expires.
            guard s.task == nil, s.inUse else { return }
            let handler = s.onExpire
            self.clearSlot(i)
            qlog(.warning, Self.tag, "Background time ran out (slot \(i)). Stopping download")
            handler?()
        }
    }

    private func releaseAssertion(_ i: Int) {
        let s = slots[i]
        guard s.assertionId != .invalid else { return }
        let id = s.assertionId
        s.assertionId = .invalid
        UIApplication.shared.endBackgroundTask(id)
    }

    private func clearSlot(_ i: Int) {
        let s = slots[i]
        s.pendingTeardown?.cancel()
        s.pendingTeardown = nil
        s.watchdog?.cancel()
        s.watchdog = nil
        s.task = nil
        s.submitted = false
        s.inUse = false
        s.onExpire = nil
        s.title = ""
        s.subtitle = ""
        s.percent = Int.min
        s.lastPublished = .distantPast
    }
}
