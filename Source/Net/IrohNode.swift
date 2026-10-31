import Foundation

/// FFI の共通ヘルパ。
enum IrohNative {
    /// Rust 側が返した C 文字列を String にして解放する。
    static func takeString(_ p: UnsafeMutablePointer<CChar>?) -> String? {
        guard let p else { return nil }
        let s = String(cString: p)
        ps_string_free(p)
        return s
    }

    /// 直近のエラーメッセージ（呼び出したスレッドのもの）。
    static func lastError() -> String? {
        takeString(ps_last_error())
    }
}

/// 受信ストリームを proto の FrameIn として見せる（ネイティブ RecvStream のラッパ）。
final class IrohIn: FrameIn {
    private var handle: OpaquePointer?
    private let lock = NSLock()

    init(_ handle: OpaquePointer) { self.handle = handle }

    func readExact(_ n: Int) throws -> Data {
        guard n >= 0 else { throw ProtocolError("bad length \(n)") }
        var buf = Data(count: n)
        if n == 0 { return buf }
        guard let h = handle else { throw ProtocolError("stream closed") }
        let rc = buf.withUnsafeMutableBytes { raw in
            ps_recv_read_exact(h, raw.bindMemory(to: UInt8.self).baseAddress, n)
        }
        if rc != 0 {
            throw ProtocolError(IrohNative.lastError() ?? "read_exact failed (\(rc))")
        }
        return buf
    }

    func dispose() {
        lock.lock()
        defer { lock.unlock() }
        if let h = handle {
            ps_recv_free(h)
            handle = nil
        }
    }

    deinit { dispose() }
}

/// 送信ストリームを proto の FrameOut として見せる（ネイティブ SendStream のラッパ）。
final class IrohOut: FrameOut {
    private var handle: OpaquePointer?
    private let lock = NSLock()

    init(_ handle: OpaquePointer) { self.handle = handle }

    func write(_ data: Data) throws {
        guard let h = handle else { throw ProtocolError("stream closed") }
        let rc = data.withUnsafeBytes { raw in
            ps_send_write(h, raw.bindMemory(to: UInt8.self).baseAddress, data.count)
        }
        if rc != 0 {
            throw ProtocolError(IrohNative.lastError() ?? "write failed (\(rc))")
        }
    }

    func finish() throws {
        guard let h = handle else { throw ProtocolError("stream closed") }
        let rc = ps_send_finish(h)
        if rc != 0 {
            throw ProtocolError(IrohNative.lastError() ?? "finish failed (\(rc))")
        }
    }

    func abort() {
        if let h = handle { _ = ps_send_reset(h, 1) }
    }

    func dispose() {
        lock.lock()
        defer { lock.unlock() }
        if let h = handle {
            ps_send_free(h)
            handle = nil
        }
    }

    deinit { dispose() }
}

enum NodeState: Equatable {
    case stopped
    case starting
    case online
    case offlineOnlyDirect
    case error
}

/// iroh の Endpoint を1つ持ち、
///  - 受信側：ペアリング済みの相手からの接続を受け付け、共有フォルダを公開する
///  - 送信側：相手ごとに接続を張り、RemoteClient を提供する
/// Android 版 net/IrohNode.kt / Windows 版 Net/IrohNode.cs に対応。通信コアは Rust FFI（iroh 1.x）。
final class IrohNode: ObservableObject {
    private static let tag = "IrohNode"

    @Published private(set) var state: NodeState = .stopped
    @Published private(set) var stateNote: String? = nil
    /// UI 表示用の直近イベント（新しい順・最大 20 件）。
    @Published private(set) var events: [String] = []

    private let settings: Settings
    private let log = AppLogger.shared

    private let lifecycleLock = NSLock()
    private let connLock = NSLock()
    private var outgoing: [String: OpaquePointer] = [:]
    private var node: OpaquePointer?
    private var starting = false

    private let server: FileServer

    init(settings: Settings) {
        self.settings = settings
        let logger = AppLogger.shared
        self.server = FileServer(
            sourceProvider: { [weak settings] in settings?.shareSource() },
            log: { m in logger.debug(IrohNode.tag, "server: \(m)") })
    }

    private func setState(_ s: NodeState, note: String? = nil) {
        DispatchQueue.main.async {
            self.state = s
            self.stateNote = note
        }
    }

    func start() {
        lifecycleLock.lock()
        if node != nil || starting {
            lifecycleLock.unlock()
            return
        }
        starting = true
        lifecycleLock.unlock()
        setState(.starting)

        let secret = settings.secretKey
        let relay = settings.relayUrl.trimmingCharacters(in: .whitespaces)

        DispatchQueue.global(qos: .userInitiated).async { [self] in
            let n: OpaquePointer? = secret.withUnsafeBufferPointer { sp in
                if relay.isEmpty {
                    return ps_node_start(sp.baseAddress, nil)
                }
                return relay.withCString { rp in ps_node_start(sp.baseAddress, rp) }
            }
            guard let n else {
                let msg = IrohNative.lastError() ?? "bind に失敗しました"
                log.error(IrohNode.tag, "start failed: \(msg)")
                setState(.error, note: msg)
                lifecycleLock.lock()
                starting = false
                lifecycleLock.unlock()
                return
            }

            lifecycleLock.lock()
            node = n
            starting = false
            lifecycleLock.unlock()
            log.info(IrohNode.tag, "node started. id=\(settings.endpointId.prefix(10))")

            // 受け付けループ
            DispatchQueue.global(qos: .utility).async { [self] in
                acceptLoop(n)
            }

            // ホームリレーにつながるまで待つ（つながらなくても直接接続は試せる）
            let online = ps_node_wait_online(n, 15_000)
            if online == 1 {
                log.info(IrohNode.tag, "relay online")
                setState(.online)
            } else {
                log.warn(IrohNode.tag, "relay offline (direct only)")
                setState(.offlineOnlyDirect, note: "リレーに未接続（直接接続のみ試行）")
            }
        }
    }

    func stop(completion: (() -> Void)? = nil) {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            lifecycleLock.lock()
            let n = node
            node = nil
            lifecycleLock.unlock()

            connLock.lock()
            for c in outgoing.values {
                "bye".withCString { ps_conn_close(c, 0, $0) }
                ps_conn_free(c)
            }
            outgoing.removeAll()
            connLock.unlock()

            if let n {
                ps_node_shutdown(n) // accept ループは null 返却で終了する
            }
            log.info(IrohNode.tag, "node stopped")
            setState(.stopped)
            if let completion { DispatchQueue.main.async(execute: completion) }
        }
    }

    func restart() {
        stop { [weak self] in self?.start() }
    }

    // ---------------- 受信側 ----------------

    private func acceptLoop(_ n: OpaquePointer) {
        while true {
            guard let conn = ps_node_accept(n) else { return } // shutdown

            guard let remote = IrohNative.takeString(ps_conn_remote_id(conn)) else {
                ps_conn_free(conn)
                continue
            }
            if !settings.isPaired(remote) {
                addEvent("未登録の端末を拒否: \(remote.prefix(10))")
                "not paired".withCString { ps_conn_close(conn, 403, $0) }
                ps_conn_free(conn)
                continue
            }
            addEvent("接続を受け付け: \(settings.peerName(remote))")
            DispatchQueue.global(qos: .utility).async { [self] in
                serveConnection(conn)
            }
        }
    }

    private func serveConnection(_ conn: OpaquePointer) {
        defer { ps_conn_free(conn) }
        while true {
            var send: OpaquePointer? = nil
            var recv: OpaquePointer? = nil
            let rc = ps_conn_accept_bi(conn, &send, &recv)
            if rc < 0 { return } // 接続が閉じた
            guard let s = send, let r = recv else { return }
            DispatchQueue.global(qos: .utility).async { [self] in
                let input = IrohIn(r)
                let output = IrohOut(s)
                server.handle(input: input, output: output)
                input.dispose()
                output.dispose()
            }
        }
    }

    // ---------------- 送信側 ----------------

    private func getOrConnect(_ peerId: String) throws -> OpaquePointer {
        connLock.lock()
        defer { connLock.unlock() }

        if let existing = outgoing[peerId] {
            if ps_conn_is_closed(existing) == 0 { return existing }
            ps_conn_free(existing)
            outgoing.removeValue(forKey: peerId)
        }

        lifecycleLock.lock()
        let n = node
        lifecycleLock.unlock()
        guard let n else { throw ProtocolError("ノードが起動していません") }

        let conn = peerId.withCString { ps_node_connect(n, $0) }
        guard let conn else {
            throw ProtocolError(IrohNative.lastError() ?? "接続に失敗しました")
        }
        outgoing[peerId] = conn
        return conn
    }

    /// 相手ごとの RemoteClient。接続は再利用し、死んでいたら1回だけ張り直す。
    func client(for peerId: String) -> RemoteClient {
        RemoteClient { [self] in
            var conn = try getOrConnect(peerId)
            var send: OpaquePointer? = nil
            var recv: OpaquePointer? = nil
            var rc = ps_conn_open_bi(conn, &send, &recv)
            if rc < 0 {
                // 古い接続が死んでいた可能性があるので1回だけ張り直す
                connLock.lock()
                if let dead = outgoing[peerId] {
                    ps_conn_free(dead)
                    outgoing.removeValue(forKey: peerId)
                }
                connLock.unlock()
                conn = try getOrConnect(peerId)
                rc = ps_conn_open_bi(conn, &send, &recv)
                if rc < 0 {
                    throw ProtocolError(IrohNative.lastError() ?? "ストリームを開けませんでした")
                }
            }
            guard let s = send, let r = recv else {
                throw ProtocolError("ストリームを開けませんでした")
            }
            return (IrohOut(s), IrohIn(r))
        }
    }

    /// 相手との経路を1行で返す（Android 版と同じ「● 直接 / リレー、RTT」）。未接続なら nil。
    /// FFI の ps_conn_path_summary は "<kind>|<rtt_ms>" を返す（kind: direct/relay/other/none）。
    func pathSummary(_ peerId: String) -> String? {
        connLock.lock()
        defer { connLock.unlock() }
        guard let conn = outgoing[peerId] else { return nil }
        if ps_conn_is_closed(conn) != 0 { return "切断" }
        guard let raw = IrohNative.takeString(ps_conn_path_summary(conn)) else { return "接続済み" }
        return IrohNode.formatPathSummary(raw)
    }

    /// "<kind>|<rtt_ms>" → 表示文言。
    static func formatPathSummary(_ raw: String) -> String {
        let parts = raw.split(separator: "|", maxSplits: 1).map(String.init)
        let kind = parts.first ?? "none"
        let rtt = parts.count > 1 ? parts[1] : "0"
        switch kind {
        case "direct": return "● 直接 \(rtt)ms"
        case "relay": return "リレー \(rtt)ms"
        case "other": return "接続済み \(rtt)ms"
        default: return "経路なし"
        }
    }

    func disconnect(_ peerId: String) {
        connLock.lock()
        defer { connLock.unlock() }
        if let conn = outgoing[peerId] {
            "bye".withCString { ps_conn_close(conn, 0, $0) }
            ps_conn_free(conn)
            outgoing.removeValue(forKey: peerId)
        }
    }

    // ---------------- ログ（UI 表示用の直近イベント） ----------------

    private func addEvent(_ msg: String) {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "HH:mm:ss"
        let line = "\(f.string(from: Date())) \(msg)"
        log.debug(IrohNode.tag, msg)
        DispatchQueue.main.async {
            self.events.insert(line, at: 0)
            if self.events.count > 20 {
                self.events.removeLast(self.events.count - 20)
            }
        }
    }
}
