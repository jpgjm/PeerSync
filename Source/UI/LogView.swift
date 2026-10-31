import SwiftUI

/// アプリ内ログの閲覧・共有（書き出し）。
struct LogView: View {
    @ObservedObject private var logger = AppLogger.shared
    @State private var minLevel: AppLogger.Level = .debug

    private var filtered: [AppLogger.Line] {
        let order: [AppLogger.Level] = [.debug, .info, .warn, .error]
        guard let minIndex = order.firstIndex(of: minLevel) else { return logger.lines }
        return logger.lines.filter { order.firstIndex(of: $0.level) ?? 0 >= minIndex }
    }

    var body: some View {
        List {
            Section {
                Picker("レベル", selection: $minLevel) {
                    Text("DEBUG以上").tag(AppLogger.Level.debug)
                    Text("INFO以上").tag(AppLogger.Level.info)
                    Text("WARN以上").tag(AppLogger.Level.warn)
                    Text("ERRORのみ").tag(AppLogger.Level.error)
                }
                .pickerStyle(.segmented)
            }
            Section {
                ForEach(filtered.reversed()) { line in
                    VStack(alignment: .leading, spacing: 2) {
                        HStack {
                            Text(line.level.rawValue)
                                .font(.caption2.bold())
                                .foregroundStyle(color(line.level))
                            Text(line.tag)
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                            Spacer()
                            Text(Self.timeString(line.time))
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                        Text(line.message)
                            .font(.system(.caption, design: .monospaced))
                            .textSelection(.enabled)
                    }
                }
            }
        }
        .navigationTitle("ログ")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                ShareLink(item: logger.logFileURL) {
                    Image(systemName: "square.and.arrow.up")
                }
            }
            ToolbarItem(placement: .secondaryAction) {
                Button("消去", role: .destructive) { logger.clear() }
            }
        }
    }

    private func color(_ level: AppLogger.Level) -> Color {
        switch level {
        case .debug: return .secondary
        case .info: return .blue
        case .warn: return .orange
        case .error: return .red
        }
    }

    private static func timeString(_ date: Date) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "HH:mm:ss"
        return f.string(from: date)
    }
}
