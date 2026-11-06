import SwiftUI
import PeerSyncShared

@main
struct PeerSyncApp: App {
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // Approval notifications: set the delegate before launch finishes (to handle launches from a notification)
        IncomingNotification.shared.prepare()
        // Clean up temp files from the previous "Send" (exported photos/videos)
        SendBatchStore.shared.sweep()
        // Start the Kotlin core (settings, iroh node, transfers)
        IosApp.shared.start(bridge: BridgeImpl.shared)
        // BGTasks must be registered before launch finishes
        BackgroundDownloadTaskPool.shared.register()
    }

    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea(.all)
                .onChange(of: scenePhase) { _, phase in
                    switch phase {
                    case .active:
                        IosApp.shared.onActive()
                    case .background:
                        // With silent-audio keep-alive off, let the OS suspend us (the node isn't stopped
                        // so it can be reused on resume).
                        IosApp.shared.onBackground()
                    default:
                        break
                    }
                }
        }
    }
}

/// Hosts the Compose Multiplatform screens (shared UI) in SwiftUI.
private struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        IosApp.shared.makeViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
