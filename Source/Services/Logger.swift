import Foundation

/// アプリ内で閲覧・共有できるデバッグログ（重要度レベル付き）。
/// Mac が無く Xcode のコンソールを見られないため、ファイルにも書き出す。
final class AppLogger: ObservableObject {
    enum Level: String, CaseIterable {
        case debug = "DEBUG"
        case info = "INFO"
        case warn = "WARN"
        case error = "ERROR"
    }

    struct Line: Identifiable {
        let id = UUID()
        let time: Date
        let level: Level
        let tag: String
        let message: String
    }

    static let shared = AppLogger()

    /// 画面表示用の直近ログ（最大 maxLines 行）。
    @Published private(set) var lines: [Line] = []
    private let maxLines = 1000

    private let queue = DispatchQueue(label: "peersync.logger")
    private let fileURL: URL
    private let formatter: DateFormatter

    private init() {
        let dir = AppPaths.supportDir.appendingPathComponent("logs", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        fileURL = dir.appendingPathComponent("peersync.log")
        formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
        // 肥大化防止：2 MiB を超えていたら古いログを捨てる
        if let size = (try? FileManager.default.attributesOfItem(atPath: fileURL.path)[.size]) as? NSNumber,
           size.int64Value > 2 * 1024 * 1024 {
            try? FileManager.default.removeItem(at: fileURL)
        }
    }

    var logFileURL: URL { fileURL }

    func debug(_ tag: String, _ message: String) { log(.debug, tag, message) }
    func info(_ tag: String, _ message: String) { log(.info, tag, message) }
    func warn(_ tag: String, _ message: String) { log(.warn, tag, message) }
    func error(_ tag: String, _ message: String) { log(.error, tag, message) }

    func log(_ level: Level, _ tag: String, _ message: String) {
        let line = Line(time: Date(), level: level, tag: tag, message: message)
        let text = "\(formatter.string(from: line.time)) [\(level.rawValue)] \(tag): \(message)\n"
        queue.async { [self] in
            if let data = text.data(using: .utf8) {
                if FileManager.default.fileExists(atPath: fileURL.path),
                   let h = try? FileHandle(forWritingTo: fileURL) {
                    _ = try? h.seekToEnd()
                    try? h.write(contentsOf: data)
                    try? h.close()
                } else {
                    try? data.write(to: fileURL)
                }
            }
        }
        DispatchQueue.main.async { [self] in
            lines.append(line)
            if lines.count > maxLines {
                lines.removeFirst(lines.count - maxLines)
            }
        }
    }

    func clear() {
        queue.async { [self] in
            try? FileManager.default.removeItem(at: fileURL)
        }
        DispatchQueue.main.async { [self] in
            lines.removeAll()
        }
    }
}

/// 保存場所。設定・ログは Application Support、受信ファイルは Documents（Files アプリから見える）。
enum AppPaths {
    static var supportDir: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("PeerSync", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base
    }

    static var documentsDir: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    /// 受信ファイルの保存先ルート: Documents/PeerSync/
    static var downloadRoot: URL {
        let dir = documentsDir.appendingPathComponent("PeerSync", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static var settingsFile: URL {
        supportDir.appendingPathComponent("settings.json")
    }

    /// 「ファイル」アプリでフォルダを開く URL（shareddocuments:// スキーム）。
    /// 直接インストールなら このiPhone内 > PeerSync、LiveContainer 内で実行している場合は
    /// LiveContainer > Data > Application > <UUID> > Documents > PeerSync が開く。
    static func filesAppURL(for folder: URL) -> URL? {
        URL(string: "shareddocuments://" + folder.path)
    }

    /// LiveContainer 内で実行しているか。判定は RuntimeEnvironment に集約。
    static var isRunningInLiveContainer: Bool { RuntimeEnvironment.isLiveContainer }
}
