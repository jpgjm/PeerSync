import SwiftUI

@main
struct PeerSyncApp: App {
    @StateObject private var settings: Settings
    @StateObject private var node: IrohNode
    @StateObject private var transfers = TransferManager()
    @Environment(\.scenePhase) private var scenePhase

    init() {
        let s = Settings()
        _settings = StateObject(wrappedValue: s)
        _node = StateObject(wrappedValue: IrohNode(settings: s))
        AppLogger.shared.info("App", "PeerSync launch (\(RuntimeEnvironment.summary))")
        // BGTask の登録は起動完了までに済ませる必要がある
        BackgroundDownloadTaskPool.shared.register()
    }

    var body: some Scene {
        WindowGroup {
            HomeView()
                .environmentObject(settings)
                .environmentObject(node)
                .environmentObject(transfers)
                .onAppear {
                    node.start()
                    applyKeepSettings()
                }
                .onChange(of: scenePhase) { _, phase in
                    switch phase {
                    case .active:
                        AppLogger.shared.debug("App", "scene active")
                        node.start()
                        applyKeepSettings()
                    case .background:
                        AppLogger.shared.debug("App", "scene background")
                        // 無音延命が OFF なら OS に任せてサスペンドされる（ノードは止めない。
                        // 復帰時に再利用できるため）。
                    default:
                        break
                    }
                }
                .onChange(of: settings.keepScreenOn) { _, _ in applyKeepSettings() }
                .onChange(of: settings.keepAliveAudio) { _, _ in applyKeepSettings() }
        }
    }

    private func applyKeepSettings() {
        UIApplication.shared.isIdleTimerDisabled = settings.keepScreenOn
        if settings.keepAliveAudio {
            BackgroundKeepAlive.shared.start()
        } else {
            BackgroundKeepAlive.shared.stop()
        }
    }
}
