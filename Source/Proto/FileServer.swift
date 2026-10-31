import Foundation

/// 共有フォルダ（ShareSource）の中だけを公開するサーバー側ハンドラ。
/// Android 版 proto/FileServer.kt / Windows 版 Proto/FileServer.cs と同一仕様。
/// パスは "/" 区切りの相対パス。".." などは拒否する。
/// sourceProvider が nil を返したら「共有フォルダ未設定」として扱う。
final class FileServer {
    static let partSuffix = ".peersync-part"

    private let sourceProvider: () -> ShareSource?
    private let showHidden: Bool
    private let log: ((String) -> Void)?

    init(sourceProvider: @escaping () -> ShareSource?,
         showHidden: Bool = false,
         log: ((String) -> Void)? = nil) {
        self.sourceProvider = sourceProvider
        self.showHidden = showHidden
        self.log = log
    }

    /// 1 ストリーム＝1 リクエストを処理する（ブロッキング。バックグラウンドで呼ぶこと）。
    func handle(input: FrameIn, output: FrameOut) {
        let req: WireRequest
        do {
            req = try Codec.decodeRequest(WireDecoder(input))
        } catch {
            log?("bad request: \(error)")
            try? output.write(Codec.encodeError("bad request: \(error)"))
            try? output.finish()
            return
        }

        log?("request \(req.description)")
        do {
            switch req {
            case .listDir(let path):
                try output.write(Codec.encodeListOk(try listEntries(path)))
            case .get(let path, let offset):
                try sendFile(path: path, offset: offset, output: output)
                try output.finish()
                return
            }
        } catch let e as ProtocolError {
            log?("request rejected: \(e.message)")
            try? output.write(Codec.encodeError(e.message))
        } catch {
            log?("request failed: \(error)")
            try? output.write(Codec.encodeError("\(error)"))
        }

        try? output.finish()
    }

    /// 相対パスを検証して要素に分ける。
    static func segments(_ relPath: String) throws -> [String] {
        let parts = relPath.split(separator: "/").map(String.init).filter { !$0.isEmpty }
        for p in parts {
            if p == ".." || p == "." || p.contains("\\") || p.contains("\u{0000}") {
                throw ProtocolError("invalid path")
            }
        }
        return parts
    }

    private func source() throws -> ShareSource {
        guard let s = sourceProvider() else { throw ProtocolError("共有フォルダが設定されていません") }
        return s
    }

    private func listEntries(_ relPath: String) throws -> [Entry] {
        let segs = try FileServer.segments(relPath)
        let entries = try source().list(segs)
            .filter { showHidden || !$0.name.hasPrefix(".") }
            .filter { !$0.name.hasSuffix(FileServer.partSuffix) }
            .sorted { a, b in
                if a.isDir != b.isDir { return a.isDir }
                return a.name.localizedCaseInsensitiveCompare(b.name) == .orderedAscending
            }
        return Array(entries.prefix(Wire.maxEntries))
    }

    private func sendFile(path: String, offset: Int64, output: FrameOut) throws {
        let handle = try source().open(try FileServer.segments(path))
        defer { handle.close() }

        let total = handle.length
        // 受信側の途中ファイルが元より大きい＝元が差し替わった。最初から送り直す
        let start = (offset >= 0 && offset <= total) ? offset : 0
        log?("send \(path) total=\(total) start=\(start)")
        try output.write(Codec.encodeGetHeader(GetHeader(total: total, remaining: total - start)))
        try handle.seek(to: start)

        var left = total - start
        while left > 0 {
            let want = Int(min(Int64(Wire.chunk), left))
            let data = try handle.read(upTo: want)
            if data.isEmpty {
                // 送信中にファイルが縮んだ：相手の読み出しをエラーにして終える
                output.abort()
                throw ProtocolError("file shrank during transfer")
            }
            try output.write(data)
            left -= Int64(data.count)
        }
        log?("send done \(path)")
    }
}
