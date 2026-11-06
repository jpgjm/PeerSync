import UIKit
import PeerSyncShared

/// Implementation of the IosBridge used by the shared Kotlin code.
/// Delegates to the proven Swift service layer (BackgroundDownloadTaskPool, BackgroundKeepAlive,
/// SaveDestinationStore, RuntimeEnvironment). SharedFolderStore was removed with the shared folder in 0.2.36.
final class BridgeImpl: NSObject, IosBridge {
    static let shared = BridgeImpl()

    /// Safely accesses SaveDestinationStore (@MainActor) from any thread.
    ///
    /// Up to 0.2.5 this called `MainActor.assumeIsolated` directly, but receiving a "Send"
    /// (FileServer -> Incoming.sinkFor -> saveRootPath) runs on an iroh coroutine (global queue),
    /// so assumeIsolated's queue check (dispatch_assert_queue) failed and it
    /// crashed with SIGTRAP (.ips: `BridgeImpl.saveRootPath` <- `IosDownloadTargets.create`).
    /// Off the main thread, dispatch synchronously to the main queue (no deadlock, since main
    /// never waits on the receiving thread).
    private func onMain<T>(_ body: @MainActor () -> T) -> T {
        if Thread.isMainThread {
            return MainActor.assumeIsolated(body)
        }
        return DispatchQueue.main.sync {
            MainActor.assumeIsolated(body)
        }
    }

    /// Default device name (the user can change it in Settings since 0.2.36). Returns uname()'s machine (a model identifier
    /// such as "iPad12,1"). Since iOS 16 UIDevice.current.name only returns a generic
    /// name like "iPad", so use this to match Android's Build.MODEL ("F-51F").
    func deviceName() -> String { Self.machineIdentifier() }

    /// The machine field of uname(3) (e.g. "iPad12,1", "iPhone16,2").
    /// 0.2.31: dropped the fallback to UIDevice.name when empty (machine is hw.machine, and
    /// uname only fails on invalid arguments).
    static func machineIdentifier() -> String {
        var info = utsname()
        uname(&info)
        return withUnsafeBytes(of: &info.machine) { raw in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
    }

    func runtimeSummary() -> String { RuntimeEnvironment.summary }

    // MARK: - Share sheet

    func shareText(text: String, anchorX: Double, anchorY: Double,
                   anchorWidth: Double, anchorHeight: Double) {
        ShareSheet.present(
            text: text,
            anchor: CGRect(x: anchorX, y: anchorY, width: anchorWidth, height: anchorHeight))
    }

    // MARK: - Save destination (delegates to SaveDestinationStore)

    func saveDestState() -> IosSaveDestState {
        onMain {
            let store = SaveDestinationStore.shared
            let appRoot = SaveDestinationStore.appRoot
            return IosSaveDestState(
                kind: store.destination.rawValue,
                appPath: Self.realPath(appRoot.path),
                inLiveContainer: RuntimeEnvironment.isLiveContainer,
                customName: store.folderName,
                customPath: store.folderPath)
        }
    }

    /// Real path for display (resolves links like /var -> /private/var; matches how the custom destination is shown; 0.2.27).
    /// Falls back to the original path if it can't be resolved.
    private static func realPath(_ path: String) -> String {
        guard let p = realpath(path, nil) else { return path }
        defer { free(p) }
        return String(cString: p)
    }

    func setSaveDestKind(kind: String) {
        onMain {
            let d = SaveDestinationStore.Destination(rawValue: kind) ?? .app
            SaveDestinationStore.shared.setDestination(d)
        }
    }

    func pickSaveDestFolder(callback: @escaping (KotlinBoolean) -> Void) {
        DocumentPicker.pickFolder { [self] url in
            guard let url else {
                callback(KotlinBoolean(value: false))
                return
            }
            // Calling self's methods in an escaping closure needs explicit self (cause of the 0.2.6 build failure)
            self.onMain {
                SaveDestinationStore.shared.setFolder(url)
            }
            callback(KotlinBoolean(value: true))
        }
    }

    func openSaveRootInFilesApp() {
        onMain {
            guard let root = SaveDestinationStore.shared.root(),
                  let url = SaveDestinationStore.filesAppURL(for: root) else {
                qlog(.warning, "SaveDestination", "Can't open in Files (no save destination)")
                return
            }
            UIApplication.shared.open(url) { ok in
                if !ok { qlog(.warning, "SaveDestination", "Couldn't open in Files: \(url)") }
            }
        }
    }

    func saveRootPath() -> String? {
        onMain {
            SaveDestinationStore.shared.root()?.path
        }
    }

    func saveRootUnavailableReason() -> String {
        onMain {
            let store = SaveDestinationStore.shared
            if store.hasFolder {
                return "The Custom save folder \"\(store.folderName ?? "")\" can't be found, or PeerSync no longer has access to it."
            }
            return "No folder is selected for the Custom save location."
        }
    }

    // MARK: - "Send" pickers

    func pickSendItems(kind: String, callback: @escaping (IosSendBatch?) -> Void) {
        SendPickers.pick(kind: kind, completion: callback)
    }

    func endSendBatch(batchId: String) {
        SendBatchStore.shared.end(batchId)
    }

    /// Writes the clipboard image to a temp PNG file (like LocalSend's "Paste"; Kotlin checks for text first).
    func clipboardImage() -> IosSendBatch? {
        let pb = UIPasteboard.general
        guard pb.hasImages, let image = pb.image, let data = image.pngData() else { return nil }
        let batch = SendBatchStore.shared.begin()
        let dir = SendBatchStore.shared.tempDirectory(batchId: batch)
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd_HH-mm"
        let name = "clipboard_\(f.string(from: Date())).png"
        let url = dir.appendingPathComponent(name)
        do {
            try data.write(to: url)
        } catch {
            qlog(.warning, "Bridge", "Failed to write clipboard image: \(error)")
            SendBatchStore.shared.end(batch)
            return nil
        }
        qlog(.info, "Bridge", "clipboard image -> \(data.count) bytes")
        return IosSendBatch(batchId: batch, files: [
            IosSendFile(relPath: name, path: url.path, size: Int64(data.count),
                        mtime: Int64(Date().timeIntervalSince1970 * 1000)),
        ])
    }

    // MARK: - Local notifications (0.2.25: 8 functions -> 2; title and body are built in Kotlin)

    func postNotification(notificationId: String, title: String, body: String, category: String?, token: String?,
                          logLabel: String) {
        IncomingNotification.shared.post(id: notificationId, title: title, body: body, category: category, token: token,
                                         logLabel: logLabel)
    }

    func clearNotification(notificationId: String) {
        IncomingNotification.shared.clear(id: notificationId)
    }

    // MARK: - QR

    func scanQr(callback: @escaping (String?) -> Void) {
        QRScanner.present(completion: callback)
    }

    // MARK: - Keep-alive

    func applyKeepScreenOn(on: Bool) {
        DispatchQueue.main.async {
            UIApplication.shared.isIdleTimerDisabled = on
        }
    }

    func applyKeepAliveAudio(on: Bool) {
        DispatchQueue.main.async {
            if on {
                BackgroundKeepAlive.shared.start()
            } else {
                BackgroundKeepAlive.shared.stop()
            }
        }
    }

    // MARK: - Background protection for downloads

    func beginTransferGuard(title: String, subtitle: String,
                            onExpire: @escaping () -> Void) -> IosTransferGuardHandle? {
        // Transfers.download is called on the main thread right after a UI action
        onMain {
            guard let handle = BackgroundDownloadTaskPool.shared.begin(
                title: title, subtitle: subtitle, onExpire: onExpire) else {
                return nil
            }
            return GuardHandle(handle: handle)
        }
    }

    private final class GuardHandle: NSObject, IosTransferGuardHandle {
        private let handle: BackgroundDownloadHandle

        init(handle: BackgroundDownloadHandle) {
            self.handle = handle
        }

        func update(subtitle: String, percent: Int32) {
            DispatchQueue.main.async { [handle] in
                handle.update(subtitle: subtitle, percent: Int(percent))
            }
        }

        func end(success: Bool) {
            DispatchQueue.main.async { [handle] in
                handle.end(success: success)
            }
        }
    }
}
