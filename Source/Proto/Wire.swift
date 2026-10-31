import Foundation

/// PeerSync のワイヤプロトコル v0（ALPN: "peersync/0"）。
/// Android 版 proto/Wire.kt / Windows 版 Proto/Wire.cs と同一仕様。
///
/// 1 リクエスト = 1 本の双方向ストリーム。
///  リクエスト:  u8 op + 各フィールド → 送信側は finish()
///  レスポンス:  u8 status(0=OK,1=ERR) + 本体
///
///  LIST : str path             → u32 件数 + (str name, u8 isDir, i64 size, i64 mtime) * 件数
///  GET  : str path, i64 offset → i64 total, i64 remaining + 生バイト remaining 分
///
/// 数値はすべてビッグエンディアン。str は u16 長 + UTF-8。iroh にも iOS にも依存しない。
enum Wire {
    static let alpn = "peersync/0"

    static let opList = 1
    static let opGet = 2

    static let statusOk = 0
    static let statusErr = 1

    static let chunk = 256 * 1024
    static let maxEntries = 200_000
}

/// ストリームの受信側。iroh の RecvStream やテスト用パイプで実装する。
/// FFI がブロッキングのため同期 API。呼び出しはバックグラウンドスレッドで行うこと。
protocol FrameIn: AnyObject {
    /// 正確に n バイト読む。終端・エラーでは throw。
    func readExact(_ n: Int) throws -> Data
    /// ネイティブハンドルを解放する（多重呼び出し可）。
    func dispose()
}

/// ストリームの送信側。
protocol FrameOut: AnyObject {
    func write(_ data: Data) throws
    func finish() throws
    /// 途中で送れなくなったときに相手側の読み出しをエラーで終わらせる。
    func abort()
    func dispose()
}

struct ProtocolError: Error, CustomStringConvertible {
    let message: String
    init(_ message: String) { self.message = message }
    var description: String { message }
}

/// 相手側がエラー応答を返したとき。
struct RemoteError: Error, CustomStringConvertible {
    let message: String
    init(_ message: String) { self.message = message }
    var description: String { message }
}

/// ビッグエンディアンでフレームを組み立てる。
final class WireEncoder {
    private var buf = Data()

    @discardableResult
    func u8(_ v: Int) -> WireEncoder {
        buf.append(UInt8(v & 0xFF))
        return self
    }

    @discardableResult
    func i32(_ v: Int32) -> WireEncoder {
        var be = v.bigEndian
        withUnsafeBytes(of: &be) { buf.append(contentsOf: $0) }
        return self
    }

    @discardableResult
    func i64(_ v: Int64) -> WireEncoder {
        var be = v.bigEndian
        withUnsafeBytes(of: &be) { buf.append(contentsOf: $0) }
        return self
    }

    @discardableResult
    func str(_ s: String) throws -> WireEncoder {
        let b = Data(s.utf8)
        guard b.count <= 0xFFFF else { throw ProtocolError("string too long") }
        u8((b.count >> 8) & 0xFF)
        u8(b.count & 0xFF)
        buf.append(b)
        return self
    }

    func bytes() -> Data { buf }
}

/// ストリームからビッグエンディアンで読み出す。
final class WireDecoder {
    private let src: FrameIn
    init(_ src: FrameIn) { self.src = src }

    func u8() throws -> Int {
        Int(try src.readExact(1)[0])
    }

    func i32() throws -> Int32 {
        let d = try src.readExact(4)
        return d.withUnsafeBytes { Int32(bigEndian: $0.loadUnaligned(as: Int32.self)) }
    }

    func i64() throws -> Int64 {
        let d = try src.readExact(8)
        return d.withUnsafeBytes { Int64(bigEndian: $0.loadUnaligned(as: Int64.self)) }
    }

    func str() throws -> String {
        let hi = try u8()
        let lo = try u8()
        let len = (hi << 8) | lo
        if len == 0 { return "" }
        let d = try src.readExact(len)
        guard let s = String(data: d, encoding: .utf8) else { throw ProtocolError("invalid utf-8") }
        return s
    }

    /// 生バイトを n バイト読む（GET の本体用）。
    func raw(_ n: Int) throws -> Data { try src.readExact(n) }
}

enum WireRequest {
    case listDir(path: String)
    case get(path: String, offset: Int64)

    var description: String {
        switch self {
        case .listDir(let p): return "LIST \(p)"
        case .get(let p, let o): return "GET \(p) offset=\(o)"
        }
    }
}

/// ディレクトリエントリ。mtime は Unix エポックミリ秒（UTC）。
struct Entry: Equatable {
    let name: String
    let isDir: Bool
    let size: Int64
    let mtime: Int64
}

struct GetHeader {
    let total: Int64
    let remaining: Int64
    var start: Int64 { total - remaining }
}

/// リクエスト/レスポンスの符号化・復号。
enum Codec {
    static func encodeRequest(_ r: WireRequest) throws -> Data {
        switch r {
        case .listDir(let path):
            return try WireEncoder().u8(Wire.opList).str(path).bytes()
        case .get(let path, let offset):
            return try WireEncoder().u8(Wire.opGet).str(path).i64(offset).bytes()
        }
    }

    static func decodeRequest(_ d: WireDecoder) throws -> WireRequest {
        let op = try d.u8()
        switch op {
        case Wire.opList:
            return .listDir(path: try d.str())
        case Wire.opGet:
            return .get(path: try d.str(), offset: try d.i64())
        default:
            throw ProtocolError("unknown op \(op)")
        }
    }

    static func encodeError(_ message: String) -> Data {
        let msg = message.count > 1000 ? String(message.prefix(1000)) : message
        return (try? WireEncoder().u8(Wire.statusErr).str(msg).bytes()) ?? Data([UInt8(Wire.statusErr), 0, 0])
    }

    static func encodeListOk(_ entries: [Entry]) throws -> Data {
        let e = WireEncoder().u8(Wire.statusOk).i32(Int32(entries.count))
        for x in entries {
            try e.str(x.name)
            e.u8(x.isDir ? 1 : 0).i64(x.size).i64(x.mtime)
        }
        return e.bytes()
    }

    static func encodeGetHeader(_ h: GetHeader) -> Data {
        WireEncoder().u8(Wire.statusOk).i64(h.total).i64(h.remaining).bytes()
    }

    private static func expectOk(_ d: WireDecoder) throws {
        let s = try d.u8()
        switch s {
        case Wire.statusOk:
            return
        case Wire.statusErr:
            throw RemoteError(try d.str())
        default:
            throw ProtocolError("unknown status \(s)")
        }
    }

    static func readListResponse(_ d: WireDecoder) throws -> [Entry] {
        try expectOk(d)
        let n = Int(try d.i32())
        guard n >= 0, n <= Wire.maxEntries else { throw ProtocolError("bad entry count \(n)") }
        var list: [Entry] = []
        list.reserveCapacity(n)
        for _ in 0..<n {
            list.append(Entry(
                name: try d.str(),
                isDir: try d.u8() == 1,
                size: try d.i64(),
                mtime: try d.i64()))
        }
        return list
    }

    static func readGetHeader(_ d: WireDecoder) throws -> GetHeader {
        try expectOk(d)
        let h = GetHeader(total: try d.i64(), remaining: try d.i64())
        guard h.total >= 0, h.remaining >= 0, h.remaining <= h.total else {
            throw ProtocolError("bad header total=\(h.total) remaining=\(h.remaining)")
        }
        return h
    }
}
