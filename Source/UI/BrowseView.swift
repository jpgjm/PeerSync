import SwiftUI

/// 相手の共有フォルダを閲覧し、ファイルをタップで取得する。
/// Android 版 ui/BrowseScreen.kt / Windows 版 Views/BrowsePage に対応。
struct BrowseView: View {
    let peer: Peer

    @EnvironmentObject private var node: IrohNode
    @EnvironmentObject private var transfers: TransferManager
    @EnvironmentObject private var settings: Settings

    /// 保存先にアクセスできないときのダイアログ。pendingDownload は選び直し後に再開する取得。
    @State private var showDestAlert = false
    @State private var showDestPicker = false
    @State private var pendingDownload: (remotePath: String, fileName: String)? = nil

    /// 現在のパス要素（ルートは空）。
    @State private var pathSegments: [String] = []
    @State private var entries: [Entry] = []
    @State private var loading = false
    @State private var errorMessage: String? = nil
    @State private var connectionNote = "接続中…"

    private var currentPath: String { pathSegments.joined(separator: "/") }

    var body: some View {
        List {
            Section {
                // パス行：「上矢印 → パス名」の並び（ファイルマネージャーと同じ）
                HStack(spacing: 12) {
                    Button {
                        if !pathSegments.isEmpty {
                            pathSegments.removeLast()
                            load()
                        }
                    } label: {
                        Image(systemName: "arrow.up")
                    }
                    .buttonStyle(.borderless)
                    .disabled(pathSegments.isEmpty)
                    Text("/" + currentPath)
                        .font(.system(.caption, design: .monospaced))
                        .lineLimit(1)
                        .truncationMode(.head)
                    Spacer()
                    Text(connectionNote)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }

            if let msg = errorMessage {
                Section {
                    Text(msg).foregroundStyle(.red)
                    Button("再試行") { load() }
                }
            }

            Section {
                if loading {
                    HStack {
                        ProgressView()
                        Text("読み込み中…").foregroundStyle(.secondary)
                    }
                }
                ForEach(entries, id: \.name) { entry in
                    row(entry)
                }
                if !loading && entries.isEmpty && errorMessage == nil {
                    Text("（空のフォルダ）").foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle(peer.name)
        .navigationBarTitleDisplayMode(.inline)
        .alert("保存先にアクセスできません", isPresented: $showDestAlert) {
            Button("フォルダを選び直す") { showDestPicker = true }
            Button("アプリ内に保存する") {
                settings.setDownloadDestination(.app)
                resumePending()
            }
            Button("キャンセル", role: .cancel) { pendingDownload = nil }
        } message: {
            Text(settings.hasDownloadFolder
                 ? "カスタム保存先「\(settings.downloadFolderName ?? "")」が見つからないか、アクセス権が失われています。"
                 : "カスタム保存先のフォルダが選ばれていません。")
        }
        .fileImporter(isPresented: $showDestPicker, allowedContentTypes: [.folder]) { result in
            if case .success(let url) = result {
                settings.setDownloadFolder(url)
                resumePending()
            } else {
                pendingDownload = nil
            }
        }
        .onAppear { load() }
        // 経路表示（● 直接 / リレー、RTT）を 2 秒ごとに更新する。
        // pathSummary は接続ロックを取るので、接続中にメインスレッドが止まらないよう
        // バックグラウンドで取得してから UI に反映する。
        .onReceive(Timer.publish(every: 2, on: .main, in: .common).autoconnect()) { _ in
            let peerId = peer.id
            DispatchQueue.global(qos: .utility).async {
                let summary = node.pathSummary(peerId)
                DispatchQueue.main.async {
                    if let summary { connectionNote = summary }
                }
            }
        }
    }

    @ViewBuilder
    private func row(_ entry: Entry) -> some View {
        if entry.isDir {
            Button {
                pathSegments.append(entry.name)
                load()
            } label: {
                HStack {
                    Image(systemName: "folder.fill").foregroundStyle(.blue)
                    Text(entry.name).lineLimit(1)
                    Spacer()
                    Image(systemName: "chevron.right")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .foregroundStyle(.primary)
        } else {
            Button {
                let remote = pathSegments.isEmpty ? entry.name : currentPath + "/" + entry.name
                startDownload(remotePath: remote, fileName: entry.name)
            } label: {
                HStack {
                    Image(systemName: "doc")
                    VStack(alignment: .leading) {
                        Text(entry.name).lineLimit(1)
                        Text("\(ByteFormat.string(entry.size))　\(Self.dateString(entry.mtime))")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Image(systemName: "arrow.down.circle")
                        .padding(.leading, 12)
                        .foregroundStyle(.tint)
                }
            }
            .foregroundStyle(.primary)
        }
    }

    private func startDownload(remotePath: String, fileName: String) {
        guard let root = settings.downloadRoot() else {
            AppLogger.shared.warn("Browse", "download destination unavailable (\(settings.downloadDestination.rawValue))")
            pendingDownload = (remotePath, fileName)
            showDestAlert = true
            return
        }
        transfers.download(node: node, peer: peer, remotePath: remotePath, fileName: fileName, destRoot: root)
    }

    private func resumePending() {
        guard let p = pendingDownload else { return }
        pendingDownload = nil
        startDownload(remotePath: p.remotePath, fileName: p.fileName)
    }

    private func load() {
        loading = true
        errorMessage = nil
        let path = currentPath
        let peerId = peer.id
        DispatchQueue.global(qos: .userInitiated).async {
            let client = node.client(for: peerId)
            do {
                let list = try client.list(path)
                DispatchQueue.main.async {
                    // 戻る操作等でパスが変わっていたら捨てる
                    guard path == currentPath else { return }
                    entries = list
                    loading = false
                    connectionNote = node.pathSummary(peerId) ?? "接続中…"
                }
            } catch {
                AppLogger.shared.warn("Browse", "list failed: \(error)")
                DispatchQueue.main.async {
                    guard path == currentPath else { return }
                    entries = []
                    loading = false
                    errorMessage = "取得できませんでした: \(error)"
                    connectionNote = node.pathSummary(peerId) ?? "未接続"
                }
            }
        }
    }

    private static func dateString(_ epochMs: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(epochMs) / 1000)
        let f = DateFormatter()
        f.dateStyle = .short
        f.timeStyle = .short
        return f.string(from: date)
    }
}
