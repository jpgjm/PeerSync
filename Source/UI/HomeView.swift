import SwiftUI
import UniformTypeIdentifiers

struct HomeView: View {
    @EnvironmentObject private var settings: Settings
    @EnvironmentObject private var node: IrohNode
    @EnvironmentObject private var transfers: TransferManager

    @State private var showQrSheet = false
    @State private var showScanner = false
    @State private var showManualAdd = false
    @State private var manualText = ""
    @State private var showFolderPicker = false
    @State private var showDownloadFolderPicker = false
    @State private var relayDraft = ""
    @State private var addError: String? = nil

    var body: some View {
        NavigationStack {
            List {
                statusSection
                deviceSection
                sharedFolderSection
                destinationSection
                peersSection
                if !transfers.items.isEmpty {
                    transfersSection
                }
                settingsSection
                logSection
            }
            .navigationTitle("PeerSync")
            // 注意: Section にモディファイアを付けると List が行に分解できず、セクション全体が
            // 1 行になって行内の全ボタンが同時に発火する（v0.1.2 の「相手をタップすると QR が開く」）。
            // 遷移先の定義は List 側に付ける。
            .navigationDestination(for: String.self) { peerId in
                if let peer = settings.peers.first(where: { $0.id == peerId }) {
                    BrowseView(peer: peer)
                }
            }
            .sheet(isPresented: $showQrSheet) {
                QRDisplayView(code: PairingCode.encode(id: settings.endpointId))
            }
            .sheet(isPresented: $showScanner) {
                QRScanView { text in
                    showScanner = false
                    addPeer(from: text)
                }
            }
            .alert("IDを入力", isPresented: $showManualAdd) {
                TextField("peersync:… または 64桁のID", text: $manualText)
                Button("キャンセル", role: .cancel) { manualText = "" }
                Button("追加") {
                    addPeer(from: manualText)
                    manualText = ""
                }
            } message: {
                Text("相手の「QR表示」の下にあるコード、または端末IDを貼り付けてください。")
            }
            .alert("追加できません", isPresented: Binding(
                get: { addError != nil },
                set: { if !$0 { addError = nil } })) {
                Button("OK", role: .cancel) { addError = nil }
            } message: {
                Text(addError ?? "")
            }
            .fileImporter(isPresented: $showFolderPicker,
                          allowedContentTypes: [.folder]) { result in
                if case .success(let url) = result {
                    settings.setSharedFolder(url)
                }
            }
            .fileImporter(isPresented: $showDownloadFolderPicker,
                          allowedContentTypes: [.folder]) { result in
                if case .success(let url) = result {
                    settings.setDownloadFolder(url)
                }
            }
            .onAppear { relayDraft = settings.relayUrl }
        }
    }

    // ---- 状態 ----

    private var statusSection: some View {
        Section("状態") {
            HStack {
                Circle()
                    .fill(statusColor)
                    .frame(width: 10, height: 10)
                Text(statusText)
                Spacer()
                Button("再起動") { node.restart() }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
            }
            if let note = node.stateNote {
                Text(note).font(.caption).foregroundStyle(.secondary)
            }
            ForEach(node.events.prefix(3), id: \.self) { e in
                Text(e).font(.caption2).foregroundStyle(.secondary)
            }
        }
    }

    private var statusColor: Color {
        switch node.state {
        case .online: return .green
        case .offlineOnlyDirect: return .yellow
        case .starting: return .orange
        case .error: return .red
        case .stopped: return .gray
        }
    }

    private var statusText: String {
        switch node.state {
        case .online: return "オンライン（リレー接続済み）"
        case .offlineOnlyDirect: return "直接接続のみ"
        case .starting: return "起動中…"
        case .error: return "エラー"
        case .stopped: return "停止中"
        }
    }

    // ---- この端末 ----

    private var deviceSection: some View {
        Section("この端末") {
            HStack {
                Text("ID")
                Spacer()
                Text(String(settings.endpointId.prefix(10)) + "…")
                    .font(.system(.body, design: .monospaced))
                    .foregroundStyle(.secondary)
            }
            Button {
                UIPasteboard.general.string = PairingCode.encode(id: settings.endpointId)
            } label: {
                Label("ペアリングコードをコピー", systemImage: "doc.on.doc")
            }
            Button {
                showQrSheet = true
            } label: {
                Label("QR表示", systemImage: "qrcode")
            }
        }
    }

    // ---- 共有フォルダ ----

    private var sharedFolderSection: some View {
        Section {
            HStack {
                Image(systemName: "folder")
                Text(settings.sharedFolderName ?? "未設定")
                    .foregroundStyle(settings.sharedFolderName == nil ? .secondary : .primary)
                Spacer()
                Button("変更") { showFolderPicker = true }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
            }
        } header: {
            Text("共有フォルダ")
        } footer: {
            Text("相手に公開するフォルダ。選ぶまでは相手から見えません。")
        }
    }

    // ---- 保存先（受信フォルダ）----

    private var destinationSection: some View {
        Section {
            // ○ アプリ内
            Button {
                settings.setDownloadDestination(.app)
            } label: {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("アプリ内（PeerSync）").foregroundStyle(.primary)
                        PathDetail(url: AppPaths.downloadRoot)
                    }
                    Spacer()
                    if settings.downloadDestination == .app {
                        Image(systemName: "checkmark").foregroundStyle(.tint)
                    }
                }
            }
            // ○ カスタム
            // 注意: 行全体を Button にすると、行内の「変更」Button も同時に発火する（List の仕様）。
            // 行は onTapGesture、「変更」だけ Button にする。
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("カスタム")
                    if let path = settings.downloadFolderPath {
                        PathDetail(url: URL(fileURLWithPath: path))
                    } else {
                        Text(settings.downloadFolderName.map { "\($0)（アクセスできません）" } ?? "フォルダ未選択")
                            .font(.caption2).foregroundStyle(.secondary)
                    }
                }
                Spacer()
                if settings.downloadDestination == .custom {
                    Image(systemName: "checkmark").foregroundStyle(.tint)
                }
                Button("変更") { showDownloadFolderPicker = true }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
            }
            .contentShape(Rectangle())
            .onTapGesture {
                if settings.hasDownloadFolder {
                    settings.setDownloadDestination(.custom)
                } else {
                    showDownloadFolderPicker = true // 未選択なら選択から始める
                }
            }
            Button {
                let root = settings.downloadRoot() ?? AppPaths.downloadRoot
                if let url = AppPaths.filesAppURL(for: root) {
                    AppLogger.shared.debug("Home", "open files app: \(root.path)")
                    UIApplication.shared.open(url) { ok in
                        if !ok { AppLogger.shared.warn("Home", "files app could not open: \(root.path)") }
                    }
                }
            } label: {
                Label("受信フォルダを「ファイル」で開く", systemImage: "arrow.down.doc")
            }
        } header: {
            Text("保存先")
        } footer: {
            Text("受信ファイルは <保存先>/<相手名>/<相手側のパス> に保存されます。")
        }
    }

    // ---- 相手 ----

    private var peersSection: some View {
        Section {
            ForEach(settings.peers) { peer in
                NavigationLink(value: peer.id) {
                    VStack(alignment: .leading) {
                        Text(peer.name)
                        Text(peer.shortId + "…")
                            .font(.caption2)
                            .font(.system(.caption2, design: .monospaced))
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .onDelete { indexSet in
                for i in indexSet {
                    let peer = settings.peers[i]
                    node.disconnect(peer.id)
                    settings.removePeer(id: peer.id)
                }
            }
            Button {
                showScanner = true
            } label: {
                Label("QRで追加", systemImage: "qrcode.viewfinder")
            }
            Button {
                showManualAdd = true
            } label: {
                Label("手入力で追加", systemImage: "keyboard")
            }
        } header: {
            Text("相手（\(settings.peers.count)）")
        } footer: {
            Text("両方の端末で相手を追加して初めてつながります（許可リスト方式）。")
        }
    }

    // ---- 転送 ----

    private var transfersSection: some View {
        Section {
            ForEach(transfers.items) { item in
                TransferRow(item: item)
            }
        } header: {
            HStack {
                Text("転送")
                Spacer()
                Button("完了分を消去") { transfers.clearFinished() }
                    .font(.caption)
            }
        }
    }

    // ---- 設定 ----

    private var settingsSection: some View {
        Section {
            HStack {
                TextField("リレーURL（空なら公開リレー）", text: $relayDraft)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                Button("適用") {
                    settings.setRelayUrl(relayDraft)
                    node.restart()
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
                .disabled(relayDraft.trimmingCharacters(in: .whitespaces) == settings.relayUrl)
            }
            Toggle("画面を消さない", isOn: Binding(
                get: { settings.keepScreenOn },
                set: { settings.setKeepScreenOn($0) }))
            Toggle("無音オーディオで延命", isOn: Binding(
                get: { settings.keepAliveAudio },
                set: { settings.setKeepAliveAudio($0) }))
        } header: {
            Text("設定")
        } footer: {
            Text("iOSには常駐の仕組みがないため、待ち受けは基本的にアプリを開いている間だけです。「無音オーディオで延命」をONにすると画面を消してもしばらく待ち受けできますが、バッテリーを消費します。")
        }
    }

    private var logSection: some View {
        Section {
            NavigationLink {
                LogView()
            } label: {
                Label("ログ", systemImage: "doc.text.magnifyingglass")
            }
        }
    }

    // ---- 相手追加 ----

    private func addPeer(from text: String) {
        guard let (id, name) = PairingCode.decode(text) else {
            addError = "ペアリングコードを読み取れませんでした。"
            return
        }
        if id == settings.endpointId {
            addError = "自分自身は追加できません。"
            return
        }
        settings.addPeer(Peer(id: id, name: name ?? String(id.prefix(10))))
    }
}

struct TransferRow: View {
    @ObservedObject var item: TransferItem

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(item.fileName)
                    .lineLimit(1)
                Spacer()
                switch item.state {
                case .running:
                    Button {
                        item.requestCancel()
                    } label: {
                        Image(systemName: "xmark.circle")
                    }
                    .buttonStyle(.borderless)
                case .done:
                    Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                case .failed:
                    Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.red)
                case .cancelled:
                    Image(systemName: "slash.circle").foregroundStyle(.secondary)
                }
            }
            switch item.state {
            case .running:
                ProgressView(value: item.total > 0 ? Double(item.done) / Double(item.total) : 0)
                Text("\(ByteFormat.string(item.done)) / \(ByteFormat.string(item.total))　\(item.peerName)")
                    .font(.caption2).foregroundStyle(.secondary)
            case .done:
                Text("完了　\(item.peerName)")
                    .font(.caption2).foregroundStyle(.secondary)
            case .failed(let msg):
                Text("失敗: \(msg)")
                    .font(.caption2).foregroundStyle(.red)
                    .lineLimit(2)
            case .cancelled:
                Text("キャンセル（もう一度取得すると続きから）")
                    .font(.caption2).foregroundStyle(.secondary)
            }
        }
    }
}

/// 保存先の 2 行表示（AlarmClock の診断画面と同じ形）:
///  「ファイル」アプリ: このデバイス内 / PeerSync/PeerSync/…（たどれない場所はその旨）
///  実際のパス: /var/mobile/Containers/…
struct PathDetail: View {
    let url: URL

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text("「ファイル」アプリ")
                .font(.caption2).foregroundStyle(.tertiary)
            if let visible = RuntimeEnvironment.filesAppPath(for: url) {
                Text("このデバイス内 / \(visible)")
                    .font(.caption2.monospaced()).foregroundStyle(.secondary)
                    .textSelection(.enabled)
            } else {
                Text("たどれません（このアプリの Documents の外にあるため。選んだ場所を直接開いてください）")
                    .font(.caption2).foregroundStyle(.secondary)
            }
            Text("実際のパス")
                .font(.caption2).foregroundStyle(.tertiary)
                .padding(.top, 2)
            Text(url.path)
                .font(.caption2.monospaced()).foregroundStyle(.secondary)
                .textSelection(.enabled)
        }
    }
}

enum ByteFormat {
    static func string(_ bytes: Int64) -> String {
        let fmt = ByteCountFormatter()
        fmt.countStyle = .file
        return fmt.string(fromByteCount: bytes)
    }
}
