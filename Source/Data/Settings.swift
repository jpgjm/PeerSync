import Foundation

/// ペアリング済みの相手端末。
struct Peer: Identifiable, Codable, Equatable {
    let id: String
    var name: String
    var shortId: String { id.count <= 10 ? id : String(id.prefix(10)) }
}

/// 端末の設定（Application Support/PeerSync/settings.json に保存）。
/// Android 版 data/Settings.kt / Windows 版 Data/Settings.cs に対応。
/// - 秘密鍵（= 端末 ID の元。消すと ID が変わる）
/// - 共有フォルダ（Files で選んだフォルダの security-scoped bookmark）
/// - ペアリング済みの相手（ここに載っている相手からの接続だけ受け付ける）
/// - 自前リレーの URL（空なら iroh の公開リレー）
/// 旧保存場所からのデータ移行は行わない（仕様 2）。
final class Settings: ObservableObject {
    private static let tag = "Settings"

    private struct PersistModel: Codable {
        var secretKeyBase64: String?
        var peers: [Peer] = []
        var sharedFolderBookmark: Data?
        /// 受信ファイルの保存先: "app"（Documents/PeerSync）または "custom"
        var downloadDestination: String = "app"
        var downloadFolderBookmark: Data?
        var relayUrl: String?
        var keepScreenOn: Bool = true
        var keepAliveAudio: Bool = false
    }

    private let log = AppLogger.shared
    private var model: PersistModel
    private let lock = NSLock()

    /// 秘密鍵（32 バイト）。
    let secretKey: [UInt8]
    /// この端末の ID（公開鍵、16 進 64 文字）。
    let endpointId: String

    @Published private(set) var peers: [Peer] = []
    @Published private(set) var sharedFolderName: String?
    @Published private(set) var downloadDestination: DownloadDestination = .app
    @Published private(set) var downloadFolderName: String?
    @Published var relayUrl: String = ""
    @Published var keepScreenOn: Bool = true
    @Published var keepAliveAudio: Bool = false

    /// 共有フォルダ（security scope 取得済みの URL）。未設定なら nil。
    private var sharedFolderURL: URL?
    /// カスタム保存先（security scope 取得済みの URL）。未設定なら nil。
    private var downloadFolderURL: URL?

    enum DownloadDestination: String {
        case app
        case custom
    }

    init() {
        model = Settings.load()

        // 秘密鍵の読み込み or 生成
        var key = [UInt8](repeating: 0, count: 32)
        if let b64 = model.secretKeyBase64, let data = Data(base64Encoded: b64), data.count == 32 {
            key = [UInt8](data)
        } else {
            let rc = key.withUnsafeMutableBufferPointer { ps_secret_generate($0.baseAddress) }
            precondition(rc == 0, "鍵生成に失敗しました: \(IrohNative.lastError() ?? "?")")
            model.secretKeyBase64 = Data(key).base64Encoded()
        }
        secretKey = key

        // 端末 ID の計算
        var idPtr: UnsafeMutablePointer<CChar>? = nil
        let rc = key.withUnsafeBufferPointer { ps_id_from_secret($0.baseAddress, &idPtr) }
        precondition(rc == 0, "ID 計算に失敗しました: \(IrohNative.lastError() ?? "?")")
        endpointId = IrohNative.takeString(idPtr) ?? ""

        peers = model.peers
        relayUrl = model.relayUrl ?? ""
        keepScreenOn = model.keepScreenOn
        keepAliveAudio = model.keepAliveAudio

        downloadDestination = DownloadDestination(rawValue: model.downloadDestination) ?? .app
        resolveSharedFolder()
        resolveDownloadFolder()
        save()
        log.info(Settings.tag, "loaded. id=\(endpointId.prefix(10)) peers=\(peers.count)")
    }

    // ---- 共有フォルダ ----

    private func resolveSharedFolder() {
        sharedFolderURL = nil
        sharedFolderName = nil
        guard let bookmark = model.sharedFolderBookmark else { return }
        var stale = false
        do {
            let url = try URL(resolvingBookmarkData: bookmark, options: [], relativeTo: nil, bookmarkDataIsStale: &stale)
            guard url.startAccessingSecurityScopedResource() else {
                log.warn(Settings.tag, "shared folder: security scope を取得できません: \(url.lastPathComponent)")
                return
            }
            sharedFolderURL = url
            sharedFolderName = url.lastPathComponent
            if stale {
                // ブックマークが古くなっていたら作り直す
                if let fresh = try? url.bookmarkData() {
                    model.sharedFolderBookmark = fresh
                    save()
                }
                log.info(Settings.tag, "shared folder bookmark refreshed")
            }
            log.debug(Settings.tag, "shared folder: \(url.path)")
        } catch {
            log.warn(Settings.tag, "shared folder bookmark の解決に失敗: \(error)")
        }
    }

    /// Files で選んだフォルダ（security-scoped URL）を共有フォルダとして保存する。
    func setSharedFolder(_ url: URL) {
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }
        do {
            let bookmark = try url.bookmarkData()
            if let old = sharedFolderURL { old.stopAccessingSecurityScopedResource() }
            model.sharedFolderBookmark = bookmark
            save()
            resolveSharedFolder()
            log.info(Settings.tag, "shared folder set: \(url.lastPathComponent)")
        } catch {
            log.error(Settings.tag, "shared folder の保存に失敗: \(error)")
        }
    }

    /// サーバーが使う共有フォルダ。未選択、またはアクセスできなければ nil。
    func shareSource() -> ShareSource? {
        guard let url = sharedFolderURL else { return nil }
        var isDir: ObjCBool = false
        guard FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir), isDir.boolValue else {
            log.warn(Settings.tag, "shared folder missing: \(url.lastPathComponent)")
            return nil
        }
        return FolderShareSource(root: url)
    }

    // ---- 保存先（受信フォルダ）----

    private func resolveDownloadFolder() {
        downloadFolderURL = nil
        downloadFolderName = nil
        guard let bookmark = model.downloadFolderBookmark else { return }
        var stale = false
        do {
            let url = try URL(resolvingBookmarkData: bookmark, options: [], relativeTo: nil, bookmarkDataIsStale: &stale)
            guard url.startAccessingSecurityScopedResource() else {
                log.warn(Settings.tag, "download folder: security scope を取得できません: \(url.lastPathComponent)")
                downloadFolderName = url.lastPathComponent
                return
            }
            downloadFolderURL = url
            downloadFolderName = url.lastPathComponent
            if stale, let fresh = try? url.bookmarkData() {
                model.downloadFolderBookmark = fresh
                save()
                log.info(Settings.tag, "download folder bookmark refreshed")
            }
            log.debug(Settings.tag, "download folder: \(url.path)")
        } catch {
            log.warn(Settings.tag, "download folder bookmark の解決に失敗: \(error)")
        }
    }

    /// Files で選んだフォルダをカスタム保存先として保存し、保存先を「カスタム」に切り替える。
    func setDownloadFolder(_ url: URL) {
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }
        do {
            let bookmark = try url.bookmarkData()
            if let old = downloadFolderURL { old.stopAccessingSecurityScopedResource() }
            model.downloadFolderBookmark = bookmark
            model.downloadDestination = DownloadDestination.custom.rawValue
            downloadDestination = .custom
            save()
            resolveDownloadFolder()
            log.info(Settings.tag, "download folder set: \(url.lastPathComponent)")
        } catch {
            log.error(Settings.tag, "download folder の保存に失敗: \(error)")
        }
    }

    func setDownloadDestination(_ d: DownloadDestination) {
        model.downloadDestination = d.rawValue
        downloadDestination = d
        save()
        log.info(Settings.tag, "download destination: \(d.rawValue)")
    }

    /// カスタム保存先のフォルダが選択済みか。
    var hasDownloadFolder: Bool { model.downloadFolderBookmark != nil }

    /// カスタム保存先の実パス（表示用）。未選択・解決不可なら nil。
    var downloadFolderPath: String? { downloadFolderURL?.path }

    /// 受信ファイルの保存先ルート。
    /// - app: Documents/PeerSync（常に使える）
    /// - custom: 選んだフォルダ。未選択・アクセス不可なら nil（呼び出し側でダイアログを出す）
    func downloadRoot() -> URL? {
        switch downloadDestination {
        case .app:
            return AppPaths.downloadRoot
        case .custom:
            guard let url = downloadFolderURL else { return nil }
            var isDir: ObjCBool = false
            guard FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir), isDir.boolValue else {
                log.warn(Settings.tag, "download folder missing: \(url.lastPathComponent)")
                return nil
            }
            return url
        }
    }

    // ---- 相手 ----

    func addPeer(_ peer: Peer) {
        lock.lock()
        model.peers.removeAll { $0.id == peer.id }
        model.peers.append(peer)
        lock.unlock()
        save()
        DispatchQueue.main.async { self.peers = self.model.peers }
        log.info(Settings.tag, "peer added: \(peer.name) (\(peer.shortId))")
    }

    func removePeer(id: String) {
        lock.lock()
        model.peers.removeAll { $0.id == id }
        lock.unlock()
        save()
        DispatchQueue.main.async { self.peers = self.model.peers }
        log.info(Settings.tag, "peer removed: \(String(id.prefix(10)))")
    }

    /// 受信接続の許可リスト判定（バックグラウンドスレッドからも呼ばれる）。
    func isPaired(_ id: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return model.peers.contains { $0.id == id }
    }

    func peerName(_ id: String) -> String {
        lock.lock()
        defer { lock.unlock() }
        return model.peers.first { $0.id == id }?.name ?? String(id.prefix(10))
    }

    // ---- その他設定 ----

    func setRelayUrl(_ url: String) {
        model.relayUrl = url.trimmingCharacters(in: .whitespaces)
        relayUrl = model.relayUrl ?? ""
        save()
        log.debug(Settings.tag, "relay url set: \(relayUrl.isEmpty ? "(公開リレー)" : relayUrl)")
    }

    func setKeepScreenOn(_ on: Bool) {
        model.keepScreenOn = on
        keepScreenOn = on
        save()
    }

    func setKeepAliveAudio(_ on: Bool) {
        model.keepAliveAudio = on
        keepAliveAudio = on
        save()
    }

    // ---- 永続化 ----

    private static func load() -> PersistModel {
        do {
            let url = AppPaths.settingsFile
            if FileManager.default.fileExists(atPath: url.path) {
                let data = try Data(contentsOf: url)
                return try JSONDecoder().decode(PersistModel.self, from: data)
            }
        } catch {
            AppLogger.shared.warn(tag, "settings load failed: \(error)")
        }
        return PersistModel()
    }

    private func save() {
        lock.lock()
        defer { lock.unlock() }
        do {
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
            let data = try encoder.encode(model)
            try data.write(to: AppPaths.settingsFile, options: .atomic)
        } catch {
            log.warn(Settings.tag, "settings save failed: \(error)")
        }
    }
}

private extension Data {
    func base64Encoded() -> String { base64EncodedString() }
}
