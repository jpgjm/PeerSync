import Foundation

/// 1件の取得（ダウンロード）。
final class TransferItem: ObservableObject, Identifiable {
    enum State: Equatable {
        case running
        case done(URL)
        case failed(String)
        case cancelled
    }

    let id = UUID()
    let fileName: String
    let peerName: String
    @Published var done: Int64 = 0
    @Published var total: Int64 = 0
    @Published var state: State = .running

    /// キャンセル要求フラグ（転送スレッドから参照される）。
    private let cancelledFlag = NSLock()
    private var _cancelled = false

    init(fileName: String, peerName: String) {
        self.fileName = fileName
        self.peerName = peerName
    }

    func requestCancel() {
        cancelledFlag.lock()
        _cancelled = true
        cancelledFlag.unlock()
    }

    var isCancelRequested: Bool {
        cancelledFlag.lock()
        defer { cancelledFlag.unlock() }
        return _cancelled
    }
}

/// 取得の進捗一覧。Android 版 data/Transfers.kt / Windows 版 Data/Transfers.cs に対応
/// （Android は通知、iOS はアプリ内の一覧で表示する）。
final class TransferManager: ObservableObject {
    private static let tag = "Transfers"

    @Published private(set) var items: [TransferItem] = []
    private let log = AppLogger.shared
    private var running = Set<String>()
    private let runningLock = NSLock()

    /// 取得を開始する。remotePath は相手側の "/" 区切り相対パス。
    /// 保存先: <destRoot>/<相手の名前>/<相手側のパス>（destRoot は Settings.downloadRoot()）
    func download(node: IrohNode, peer: Peer, remotePath: String, fileName: String, destRoot: URL) {
        // 同じ相手・同じパスの取得が進行中なら二重に始めない。
        // 同じ .peersync-part を共有して片方の移動が失敗する（v0.1.3 のログ）ため。
        let key = peer.id + "|" + remotePath
        runningLock.lock()
        if running.contains(key) {
            runningLock.unlock()
            log.debug(TransferManager.tag, "download already running, ignored: \(remotePath)")
            return
        }
        running.insert(key)
        runningLock.unlock()

        let item = TransferItem(fileName: fileName, peerName: peer.name)
        DispatchQueue.main.async {
            self.items.insert(item, at: 0)
        }

        // バックグラウンド保護（1 ファイル = 1 タスク）。前面のユーザー操作直後の main で申請する。
        // OS に打ち切られたらその取得をキャンセルする（途中ファイルは残り、次回は続きから）。
        let bg = BackgroundDownloadTaskPool.shared.begin(
            title: "ファイルを受信中",
            subtitle: "\(fileName)（\(peer.name)）",
            onExpire: { item.requestCancel() })

        let safePeer = peer.name.replacingOccurrences(of: "/", with: "_")
        var dest = destRoot.appendingPathComponent(safePeer, isDirectory: true)
        for seg in remotePath.split(separator: "/").map(String.init) {
            dest.appendPathComponent(seg)
        }

        log.info(TransferManager.tag, "download start: \(remotePath) from \(peer.name) -> \(destRoot.lastPathComponent)/")
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            defer {
                runningLock.lock()
                running.remove(key)
                runningLock.unlock()
            }
            let client = node.client(for: peer.id)
            do {
                var lastUpdate = Date.distantPast
                let saved = try client.download(remotePath, dest: dest, onProgress: { done, total in
                    // UI 更新は 0.1 秒間隔に間引く（最後の 100% は必ず反映される）
                    let now = Date()
                    if now.timeIntervalSince(lastUpdate) >= 0.1 || done == total {
                        lastUpdate = now
                        DispatchQueue.main.async {
                            item.done = done
                            item.total = total
                            let percent = total > 0 ? Int(done * 100 / total) : -1
                            bg?.update(subtitle: "\(fileName)　\(ByteFormat.string(done)) / \(ByteFormat.string(total))",
                                       percent: percent)
                        }
                    }
                }, isCancelled: { item.isCancelRequested })
                log.info(TransferManager.tag, "download done: \(saved.lastPathComponent)")
                DispatchQueue.main.async {
                    item.state = .done(saved)
                    bg?.end(success: true)
                }
            } catch is CancellationError {
                // キャンセルは途中ファイル（.peersync-part）を残す。もう一度取得すると続きから。
                log.info(TransferManager.tag, "download cancelled: \(remotePath)")
                DispatchQueue.main.async {
                    item.state = .cancelled
                    bg?.end(success: false)
                }
            } catch {
                log.warn(TransferManager.tag, "download failed: \(remotePath): \(error)")
                DispatchQueue.main.async {
                    item.state = .failed("\(error)")
                    bg?.end(success: false)
                }
            }
        }
    }

    func remove(_ item: TransferItem) {
        items.removeAll { $0.id == item.id }
    }

    func clearFinished() {
        items.removeAll { item in
            if case .running = item.state { return false }
            return true
        }
    }

    var hasRunning: Bool {
        items.contains { if case .running = $0.state { return true } else { return false } }
    }
}
