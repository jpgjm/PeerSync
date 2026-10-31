import Foundation

/// 共有フォルダの読み出し口。Android 版 proto/ShareSource.kt / Windows 版 Proto/ShareSource.cs に対応。
/// FileServer はこのインターフェースだけを見る（iroh にも UI にも依存しない）。
protocol ShareSource {
    /// segments（検証済みの相対パス要素）のディレクトリを列挙する。
    func list(_ segments: [String]) throws -> [Entry]
    /// segments のファイルを読み出し用に開く。
    func open(_ segments: [String]) throws -> ShareFileHandle
}

/// 読み出し用のファイルハンドル。
protocol ShareFileHandle: AnyObject {
    var length: Int64 { get }
    func seek(to offset: Int64) throws
    /// 最大 maxLength バイト読む。EOF なら空 Data。
    func read(upTo maxLength: Int) throws -> Data
    func close()
}

/// 通常のフォルダ（security-scoped URL を含む）を公開する ShareSource。
final class FolderShareSource: ShareSource {
    private let root: URL

    init(root: URL) {
        self.root = root
    }

    private func resolve(_ segments: [String]) -> URL {
        var url = root
        for s in segments { url.appendPathComponent(s, isDirectory: false) }
        return url
    }

    func list(_ segments: [String]) throws -> [Entry] {
        let dir = resolve(segments)
        let fm = FileManager.default
        var isDir: ObjCBool = false
        guard fm.fileExists(atPath: dir.path, isDirectory: &isDir), isDir.boolValue else {
            throw ProtocolError("not a directory")
        }
        let keys: [URLResourceKey] = [.isDirectoryKey, .fileSizeKey, .contentModificationDateKey]
        let children = try fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: keys, options: [])
        return children.compactMap { url in
            guard let rv = try? url.resourceValues(forKeys: Set(keys)) else { return nil }
            let isDirectory = rv.isDirectory ?? false
            let size = Int64(rv.fileSize ?? 0)
            let mtime = Int64(((rv.contentModificationDate ?? Date(timeIntervalSince1970: 0)).timeIntervalSince1970) * 1000)
            return Entry(name: url.lastPathComponent, isDir: isDirectory, size: isDirectory ? 0 : size, mtime: mtime)
        }
    }

    func open(_ segments: [String]) throws -> ShareFileHandle {
        let url = resolve(segments)
        let fm = FileManager.default
        var isDir: ObjCBool = false
        guard fm.fileExists(atPath: url.path, isDirectory: &isDir), !isDir.boolValue else {
            throw ProtocolError("not a file")
        }
        let attrs = try fm.attributesOfItem(atPath: url.path)
        let length = (attrs[.size] as? NSNumber)?.int64Value ?? 0
        let handle = try FileHandle(forReadingFrom: url)
        return FolderFileHandle(handle: handle, length: length)
    }
}

private final class FolderFileHandle: ShareFileHandle {
    private let handle: FileHandle
    let length: Int64

    init(handle: FileHandle, length: Int64) {
        self.handle = handle
        self.length = length
    }

    func seek(to offset: Int64) throws {
        try handle.seek(toOffset: UInt64(offset))
    }

    func read(upTo maxLength: Int) throws -> Data {
        try handle.read(upToCount: maxLength) ?? Data()
    }

    func close() {
        try? handle.close()
    }
}
