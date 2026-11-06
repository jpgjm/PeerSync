import Foundation
import Observation

/// Generic store for the save destination (Default / Custom).
///
/// - Settings are stored self-contained in UserDefaults (keys "SaveDestination.*").
///   Doesn't depend on the app's settings file.
/// - The custom destination is kept as a security-scoped bookmark of a folder picked in Files
///   and resolved at launch (recreated if stale).
/// - The default destination is Documents/<app display name>/; the name is derived from the Bundle at runtime.
/// - Depends on qlog() in Source/Logging/.
@MainActor
@Observable
final class SaveDestinationStore {
    static let shared = SaveDestinationStore()
    private static let tag = "SaveDestination"

    enum Destination: String {
        case app
        case custom
    }

    private static let destinationKey = "SaveDestination.destination"
    private static let bookmarkKey = "SaveDestination.bookmark"

    private(set) var destination: Destination
    /// Custom destination (URL with security scope acquired). nil if unset or unresolvable.
    private(set) var folderURL: URL?
    /// Custom destination folder name (kept from the bookmark even without access).
    private(set) var folderName: String?

    /// App display name, used e.g. for the default destination folder name (resolved from the Bundle at runtime).
    static var appDisplayName: String {
        (Bundle.main.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String)
            ?? (Bundle.main.object(forInfoDictionaryKey: "CFBundleName") as? String)
            ?? "App"
    }

    /// Default destination: Documents/<app display name>/ (always available; also visible in Files).
    static var appRoot: URL {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(appDisplayName, isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// URL that opens the folder in the Files app (shareddocuments:// scheme).
    static func filesAppURL(for folder: URL) -> URL? {
        URL(string: "shareddocuments://" + folder.path)
    }

    private init() {
        let d = UserDefaults.standard
        destination = Destination(rawValue: d.string(forKey: Self.destinationKey) ?? "") ?? .app
        resolveFolder()
    }

    // ---- Custom destination ----

    private func resolveFolder() {
        folderURL = nil
        folderName = nil
        guard let bookmark = UserDefaults.standard.data(forKey: Self.bookmarkKey),
              let r = ScopedBookmark.resolve(bookmark, tag: Self.tag) else { return }
        folderName = r.url.lastPathComponent
        guard r.accessed else { return }
        folderURL = r.url
        if let fresh = r.refreshed {
            UserDefaults.standard.set(fresh, forKey: Self.bookmarkKey)
        }
        qlog(.debug, Self.tag, "folder: \(r.url.path)")
    }

    /// Saves the folder picked in Files as the custom destination and switches the destination to "Custom".
    func setFolder(_ url: URL) {
        guard let bookmark = ScopedBookmark.make(url, tag: Self.tag) else { return }
        if let old = folderURL { old.stopAccessingSecurityScopedResource() }
        UserDefaults.standard.set(bookmark, forKey: Self.bookmarkKey)
        UserDefaults.standard.set(Destination.custom.rawValue, forKey: Self.destinationKey)
        destination = .custom
        resolveFolder()
        qlog(.info, Self.tag, "folder set: \(url.lastPathComponent)")
    }

    func setDestination(_ d: Destination) {
        UserDefaults.standard.set(d.rawValue, forKey: Self.destinationKey)
        destination = d
        qlog(.info, Self.tag, "destination: \(d.rawValue)")
    }

    /// Whether a custom destination folder has been picked.
    var hasFolder: Bool { UserDefaults.standard.data(forKey: Self.bookmarkKey) != nil }

    /// Real path of the custom destination (for display). nil if not picked or unresolvable.
    var folderPath: String? { folderURL?.path }

    /// Save destination root.
    /// - app: Documents/<app display name> (always available)
    /// - custom: the picked folder. nil if not picked or inaccessible (the caller shows a dialog)
    func root() -> URL? {
        switch destination {
        case .app:
            return Self.appRoot
        case .custom:
            guard let url = folderURL else { return nil }
            var isDir: ObjCBool = false
            guard FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir), isDir.boolValue else {
                qlog(.warning, Self.tag, "folder missing: \(url.lastPathComponent)")
                return nil
            }
            return url
        }
    }
}
