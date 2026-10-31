import Foundation

/// 取得側（クライアント）。openStream は「新しい双方向ストリームを1本開く」関数。
/// Android 版 proto/RemoteClient.kt / Windows 版 Proto/RemoteClient.cs と同一仕様。
/// すべてブロッキング。バックグラウンドスレッドから呼ぶこと。
final class RemoteClient {
    private let openStream: () throws -> (out: FrameOut, input: FrameIn)

    init(openStream: @escaping () throws -> (out: FrameOut, input: FrameIn)) {
        self.openStream = openStream
    }

    func list(_ path: String) throws -> [Entry] {
        let (output, input) = try openStream()
        defer {
            output.dispose()
            input.dispose()
        }
        try output.write(try Codec.encodeRequest(.listDir(path: path)))
        try output.finish()
        return try Codec.readListResponse(WireDecoder(input))
    }

    /// path を dest に保存する。途中で切れても dest + partSuffix が残り、次回はその続きから受け取る。
    /// - Returns: 保存したファイルの URL
    func download(_ path: String,
                  dest: URL,
                  onProgress: ((Int64, Int64) -> Void)? = nil,
                  isCancelled: (() -> Bool)? = nil) throws -> URL {
        let fm = FileManager.default
        let part = URL(fileURLWithPath: dest.path + FileServer.partSuffix)
        try fm.createDirectory(at: dest.deletingLastPathComponent(), withIntermediateDirectories: true)
        var offset: Int64 = 0
        if fm.fileExists(atPath: part.path) {
            let attrs = try? fm.attributesOfItem(atPath: part.path)
            offset = (attrs?[.size] as? NSNumber)?.int64Value ?? 0
        }

        let (output, input) = try openStream()
        defer {
            output.dispose()
            input.dispose()
        }

        try output.write(try Codec.encodeRequest(.get(path: path, offset: offset)))
        try output.finish()

        let d = WireDecoder(input)
        let header = try Codec.readGetHeader(d)

        if !fm.fileExists(atPath: part.path) {
            fm.createFile(atPath: part.path, contents: nil)
        }
        let raf = try FileHandle(forWritingTo: part)
        do {
            // サーバー側が offset を補正した場合（元ファイルが小さくなった等）はそこに合わせる
            try raf.truncate(atOffset: UInt64(header.start))
            try raf.seek(toOffset: UInt64(header.start))

            var done = header.start
            onProgress?(done, header.total)
            var left = header.remaining
            while left > 0 {
                if isCancelled?() == true {
                    try? raf.close()
                    throw CancellationError()
                }
                let n = Int(min(Int64(Wire.chunk), left))
                let chunk = try d.raw(n)
                try raf.write(contentsOf: chunk)
                left -= Int64(n)
                done += Int64(n)
                onProgress?(done, header.total)
            }
            try raf.close()
        } catch {
            try? raf.close()
            throw error
        }

        let finalFile = RemoteClient.uniqueTarget(dest)
        try fm.moveItem(at: part, to: finalFile)
        return finalFile
    }

    static func uniqueTarget(_ dest: URL) -> URL {
        let fm = FileManager.default
        if !fm.fileExists(atPath: dest.path) { return dest }
        let dir = dest.deletingLastPathComponent()
        let ext = dest.pathExtension // "txt" または ""
        let base = dest.deletingPathExtension().lastPathComponent
        var i = 1
        while true {
            let name = ext.isEmpty ? "\(base) (\(i))" : "\(base) (\(i)).\(ext)"
            let candidate = dir.appendingPathComponent(name)
            if !fm.fileExists(atPath: candidate.path) { return candidate }
            i += 1
        }
    }
}
