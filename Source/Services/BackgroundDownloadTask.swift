//
//  BackgroundDownloadTask.swift
//  PeerSync
//
//  取得（ダウンロード）のあいだ、アプリがバックグラウンドへ回っても実行を続けるための箱。
//  QSProbe の BackgroundTransferTask を「1 ファイル = 1 タスク」向けに作り直したもの。
//
//  ## 2 段構え（QSProbe と同じ）
//
//  | 段 | API | 効く時間 | 画面表示 |
//  |---|---|---|---|
//  | 1 | `UIApplication.beginBackgroundTask` | 約 30 秒 | 無し |
//  | 2 | `BGContinuedProcessingTask` (iOS 26+) | 転送が終わるまで | システムが進捗 UI を出す |
//
//  ## 識別子（QSProbe で実測した結論を踏襲）
//
//  - 識別子は **実行時 bundle ID の配下**にあり、かつ Info.plist に**完全一致**で
//    書かれていなければ起動しない。SideStore は Info.plist の
//    `BGTaskSchedulerPermittedIdentifiers` もチーム ID 込みに書き換える。
//  - 同じ識別子の使い回しは問題にならない（QSProbe rev3: 枠 0〜7 を一周しても起動）。
//
//  1 ファイル = 1 タスクにするため、`<bundleId>.transfer.0` 〜 `.7` の 8 枠を Info.plist に
//  並べ、取得ごとに空いている枠を 1 つ割り当てる。8 件を超えて同時に取得すると、
//  9 件目以降は 2 段目なし（1 段目の約 30 秒のみ）になる。
//
//  ## LiveContainer（v0.1.7 で対応）
//
//  ゲストはホスト（LiveContainer）のプロセスで動くので、システム（BackgroundTasks のデーモン）は
//  **ホストの Info.plist と bundle ID** で識別子を検証する。ゲストの plist は見られない
//  （LiveContainer #1519 / #1528 / #1666 と同じ「ホストの plist に無い」問題）。したがって
//    1. LiveContainer 側の Info.plist に `UIBackgroundModes: processing` と
//       `BGTaskSchedulerPermittedIdentifiers`（`com.kdt.livecontainer.transfer.0`〜`.7` と `.transfer.*`）
//       が必要（LiveContainer-bgtask フォーク）。SideStore がチーム ID 込みに書き換える。
//    2. ゲスト側は、`+[NSUserDefaults lcMainBundle]`（LC が差し替え前に保存した LiveContainer.app の
//       NSBundle）からホストの bundle ID を読み、
//       `<ホスト bundle ID>.transfer.N` で register / submit する（RuntimeEnvironment.hostBundle）。
//    3. `register()` のローカル照合が（LC が差し替えた）ゲストの plist を見る可能性に備え、
//       ゲストの plist にも `com.kdt.livecontainer.*`（ワイルドカード）を置いてある。
//  LiveProcess.appex（マルチタスク）内は App Extension なので 2 段目は使えない。
//
//  ## スレッド
//
//  main スレッド専用。`register(using: .main)` にしてあるのでハンドラも main に来る。
//

import BackgroundTasks
import Foundation
import UIKit

/// 1 件の取得に割り当てた枠。TransferManager が持ち、進捗を流し込み、終わったら閉じる。
final class BackgroundDownloadHandle {
    fileprivate let slot: Int
    fileprivate let pool: BackgroundDownloadTaskPool
    fileprivate init(slot: Int, pool: BackgroundDownloadTaskPool) {
        self.slot = slot
        self.pool = pool
    }

    /// 進捗の反映（main から呼ぶ）。間引きは中でやるので毎回投げてよい。percent 負値は不定。
    func update(subtitle: String, percent: Int) {
        pool.update(slot: slot, subtitle: subtitle, percent: percent)
    }

    /// 転送の出口（main から呼ぶ）。成功 / 失敗 / 取り消しのいずれでも呼ぶ。
    func end(success: Bool) {
        pool.end(slot: slot, success: success)
    }
}

final class BackgroundDownloadTaskPool {
    private static let tag = "BGTask"

    static let shared = BackgroundDownloadTaskPool()

    /// Info.plist に並べた枠の数（`<bundleId>.transfer.0` 〜 `.7`）。
    static let slotCount = 8

    /// `project.yml` に書いた素の bundle ID を Info.plist の `PSDeclaredBundleIdentifier` から読む。
    /// SideStore が書き換える前の値。実行時の bundle ID で登録できなかったときの第 2 候補。
    private static let declaredBundleIdKey = "PSDeclaredBundleIdentifier"
    private static var declaredBundleId: String? {
        guard let v = Bundle.main.object(forInfoDictionaryKey: declaredBundleIdKey) as? String else { return nil }
        let t = v.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty || t.hasPrefix("$(") ? nil : t
    }

    /// ハンドラ起動までの見張り時間。本来 1 秒未満で来る。
    private static let launchTimeout: TimeInterval = 2.0
    /// システム UI の再描画は安くない。最短 250 ms 間隔。
    private static let publishInterval: TimeInterval = 0.25
    /// 転送が終わってから実行保持を手放すまでの猶予（ストリームの後始末のため）。
    private static let finishGrace: TimeInterval = 1.0

    private final class Slot {
        var identifier: String?           // register() が通った識別子。nil なら 2 段目は使えない
        var inUse = false
        var title = ""
        var subtitle = ""
        var percent = Int.min
        var lastPublished = Date.distantPast
        var onExpire: (() -> Void)?
        var assertionId: UIBackgroundTaskIdentifier = .invalid
        var task: BGTask?                 // BGContinuedProcessingTask を基底型で保持
        var submitted = false
        var watchdog: DispatchWorkItem?
        var pendingTeardown: DispatchWorkItem?
    }

    private var slots: [Slot] = (0..<BackgroundDownloadTaskPool.slotCount).map { _ in Slot() }
    private var registered = false
    private let log = AppLogger.shared

    private init() {}

    // MARK: - 起動時

    /// 起動時に 1 度だけ呼ぶ（PeerSyncApp.init から）。全枠を register() する。
    func register() {
        guard !registered else { return }
        registered = true

        log.info(Self.tag, "環境 = \(RuntimeEnvironment.summary) / bundleId = \(Bundle.main.bundleIdentifier ?? "(不明)") / 宣言 bundleId = \(Self.declaredBundleId ?? "(無し)")")

        guard #available(iOS 26.0, *) else {
            log.info(Self.tag, "iOS 26 未満のため継続実行タスクは使えません（バックグラウンドは約 30 秒まで）")
            return
        }

        let permitted = Bundle.main.object(forInfoDictionaryKey: "BGTaskSchedulerPermittedIdentifiers") as? [String] ?? []
        log.info(Self.tag, "ゲスト plist の許可識別子 = \(permitted)")

        if RuntimeEnvironment.isLiveContainer {
            // ホスト（LiveContainer.app）の plist を診断用に出す。ここに processing と識別子が無ければ
            // LiveContainer 側の対応（LiveContainer-bgtask フォーク）が必要。
            log.info(Self.tag, "ホスト実行ファイル = \(RuntimeEnvironment.hostExecutablePath ?? "(不明)")")
            log.info(Self.tag, "ホスト bundle の取得元 = \(RuntimeEnvironment.hostBundleSource) / path = \(RuntimeEnvironment.hostBundle?.bundlePath ?? "(不明)")")
            log.info(Self.tag, "ホスト bundleId = \(RuntimeEnvironment.hostBundleIdentifier ?? "(不明)") / UIBackgroundModes = \(RuntimeEnvironment.hostBackgroundModes)")
            log.info(Self.tag, "ホスト plist の許可識別子 = \(RuntimeEnvironment.hostPermittedTaskIdentifiers)")
        }

        guard RuntimeEnvironment.canUseBackgroundTasks else {
            if RuntimeEnvironment.isLiveProcess {
                log.warn(Self.tag, "LiveProcess（App Extension）内のため継続実行タスクは使えません（バックグラウンドは約 30 秒まで）")
            } else {
                log.warn(Self.tag, "LiveContainer 側の Info.plist に processing / BGTaskSchedulerPermittedIdentifiers が無いため継続実行タスクは使えません（バックグラウンドは約 30 秒まで）。LiveContainer-bgtask フォークを使ってください")
            }
            return
        }

        guard !permitted.isEmpty else {
            log.error(Self.tag, "BGTaskSchedulerPermittedIdentifiers が空です。Info.plist が届いていません")
            return
        }

        var adopted = 0
        for i in 0..<Self.slotCount {
            for candidate in Self.candidateIdentifiers(slot: i) {
                let ok = BGTaskScheduler.shared.register(forTaskWithIdentifier: candidate, using: .main) { [weak self] task in
                    self?.attach(task, slot: i)
                }
                if i == 0 {
                    // 枠 0 だけ候補ごとの結果を残す（register() がどちらの plist を見ているかの診断用）
                    log.debug(Self.tag, "register(\(candidate)) = \(ok)")
                }
                if ok {
                    slots[i].identifier = candidate
                    adopted += 1
                    break
                }
            }
        }
        if adopted == 0 {
            log.error(Self.tag, "使える識別子がありませんでした。project.yml の BGTaskSchedulerPermittedIdentifiers を確認してください")
        } else {
            log.info(Self.tag, "識別子を \(adopted)/\(Self.slotCount) 枠採用しました（例: \(slots[0].identifier ?? "?")）")
        }

        // 前のセッションが積み残した申請を消す（このアプリは他に BGTask を使わない）。
        BGTaskScheduler.shared.getPendingTaskRequests { [log] requests in
            guard !requests.isEmpty else { return }
            log.warn(Self.tag, "前回の積み残しを取り下げます — \(requests.map { $0.identifier }.joined(separator: ", "))")
            BGTaskScheduler.shared.cancelAllTaskRequests()
        }
    }

    /// 枠 i の候補識別子。優先順:
    ///  LC 内: ホスト（LiveContainer）の bundle ID 配下 → 実行時 bundle ID 配下 → 宣言した素の bundle ID 配下
    ///  通常:  実行時 bundle ID 配下 → 宣言した素の bundle ID 配下
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

    // MARK: - 開始 / 更新 / 終了

    /// 取得の入口。ユーザー操作の直後（= 前面、main）に呼ぶこと。
    /// 空き枠が無ければ nil（その取得は 1 段目も無しで進む）。
    func begin(title: String, subtitle: String, onExpire: @escaping () -> Void) -> BackgroundDownloadHandle? {
        guard let i = slots.firstIndex(where: { !$0.inUse }) else {
            log.warn(Self.tag, "空き枠がありません（同時 \(Self.slotCount) 件まで）。この取得はバックグラウンド保護なしで進めます")
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
        // 100% は間引かない。最後の 1 回が落ちるとバナーが 99% で止まって見える。
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
            task.updateTitle(s.title, subtitle: success ? "完了しました" : s.subtitle)
            if success {
                task.progress.totalUnitCount = 100
                task.progress.completedUnitCount = 100
            }
            // ストリームの後始末のため少しだけ延ばしてから閉じる
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
                log.info(Self.tag, "継続実行タスクを終了しました (枠\(i), success=\(success))")
            } else if s.submitted, let id = s.identifier {
                // ハンドラがまだ呼ばれていない = 実行前。リクエストごと取り下げる。
                BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: id)
                log.info(Self.tag, "起動前の申請を取り下げました — \(id)")
            }
        }
        clearSlot(i)
        releaseAssertion(i)
    }

    // MARK: - 2 段目 (BGContinuedProcessingTask)

    private func submit(_ i: Int) {
        guard #available(iOS 26.0, *) else { return }
        let s = slots[i]
        guard let id = s.identifier else {
            log.debug(Self.tag, "枠\(i): 使える識別子が無いため 2 段目は使いません（バックグラウンドは約 30 秒まで）")
            return
        }
        let request = BGContinuedProcessingTaskRequest(identifier: id, title: s.title, subtitle: s.subtitle)
        request.strategy = .fail // 前面のユーザー操作起点。動かせないなら理由が欲しい
        do {
            try BGTaskScheduler.shared.submit(request)
            s.submitted = true
            log.info(Self.tag, "申請しました — \(id)")
            startWatchdog(i)
        } catch {
            s.submitted = false
            log.error(Self.tag, "申請に失敗しました — \(id): \(error.localizedDescription)（バックグラウンドは約 30 秒まで）")
        }
    }

    /// OS がタスクを起動したときに呼ばれる。ここで 1 段目を手放す。
    private func attach(_ task: BGTask, slot i: Int) {
        cancelWatchdog(i)
        let s = slots[i]

        guard #available(iOS 26.0, *), let continued = task as? BGContinuedProcessingTask else {
            log.error(Self.tag, "渡されたタスクが BGContinuedProcessingTask ではありませんでした — \(type(of: task))")
            task.setTaskCompleted(success: false)
            return
        }
        guard s.inUse else {
            log.info(Self.tag, "転送が既に終わっていたためタスクを閉じます — \(continued.identifier)")
            continued.setTaskCompleted(success: false)
            return
        }

        s.task = continued
        log.info(Self.tag, "★ 継続実行タスクが起動しました（枠\(i)）。バックグラウンドでも取得を続けます — \(continued.identifier)")
        applyToTask(i)
        releaseAssertion(i)

        continued.expirationHandler = { [weak self] in
            continued.setTaskCompleted(success: false)
            guard let self else { return }
            let handler = s.onExpire
            self.clearSlot(i)
            self.releaseAssertion(i)
            self.log.warn(Self.tag, "継続実行タスクが打ち切られました（枠\(i)）。取得を中止します")
            handler?()
        }
    }

    private func applyToTask(_ i: Int) {
        guard #available(iOS 26.0, *) else { return }
        let s = slots[i]
        guard s.inUse, let task = s.task as? BGContinuedProcessingTask else { return }
        task.updateTitle(s.title, subtitle: s.subtitle)
        if s.percent < 0 {
            task.progress.totalUnitCount = 0 // 不定プログレス
            task.progress.completedUnitCount = 0
        } else {
            task.progress.totalUnitCount = 100
            task.progress.completedUnitCount = Int64(min(100, max(0, s.percent)))
        }
    }

    // MARK: - 起動見張り

    private func startWatchdog(_ i: Int) {
        cancelWatchdog(i)
        let item = DispatchWorkItem { [weak self] in
            guard let self else { return }
            let s = self.slots[i]
            guard s.task == nil, s.inUse else { return }
            self.log.error(Self.tag, "申請から \(Int(Self.launchTimeout)) 秒経ってもタスクが起動しませんでした — \(s.identifier ?? "?")（バックグラウンドは約 30 秒まで）")
            if #available(iOS 26.0, *) {
                BGTaskScheduler.shared.getPendingTaskRequests { [log = self.log] requests in
                    log.warn(Self.tag, requests.isEmpty
                             ? "待機中の申請はありません（システムが受理後に取り下げた可能性）"
                             : "待機中の申請 — \(requests.map { $0.identifier }.joined(separator: ", "))")
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

    // MARK: - 1 段目 (beginBackgroundTask)

    private func holdAssertion(_ i: Int) {
        let s = slots[i]
        guard s.assertionId == .invalid else { return }
        s.assertionId = UIApplication.shared.beginBackgroundTask(withName: "PeerSync.transfer.\(i)") { [weak self] in
            guard let self else { return }
            self.releaseAssertion(i)
            // 2 段目が既に走っているなら、1 段目が切れても転送は続く。
            guard s.task == nil, s.inUse else { return }
            let handler = s.onExpire
            self.clearSlot(i)
            self.log.warn(Self.tag, "バックグラウンドの猶予が尽きました（枠\(i)）。取得を中止します")
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
