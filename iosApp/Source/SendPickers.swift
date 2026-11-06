import UIKit
import Photos
import PhotosUI
import UniformTypeIdentifiers
import PeerSyncShared

/// Pickers for "Send" (counterparts of FlyingCarpet's Files App / Camera Roll / Send Folder).
///
///  - files : DocumentPicker (multiple selection). Holds the picked URLs' security scopes until the batch ends.
///  - folder: DocumentPicker (folder). Expands the contents recursively as "<folder name>/<relative path>".
///  - photos / videos: PHPicker (images only / videos only). Like QSProbe, gets photo library permission and
///    exports the original files to a temp folder via PHAssetResource (no re-encoding, original file names).
///    A Live Photo is sent as 2 files, the still image and its paired video (same as FlyingCarpet and QSProbe).
///    Without permission it falls back to loadFileRepresentation (Live Photo paired videos can't be sent).
///
/// The result is an `IosSendBatch` (batchId + file list). When the Kotlin send batch ends,
/// `SendBatchStore.end(batchId)` releases the scopes and deletes the temp files.
enum SendPickers {
    private static let tag = "SendPickers"

    static func pick(kind: String, completion: @escaping (IosSendBatch?) -> Void) {
        DispatchQueue.main.async {
            switch kind {
            case "files": pickFiles(completion: completion)
            case "folder": pickFolder(completion: completion)
            case "photos": pickMedia(videos: false, completion: completion)
            case "videos": pickMedia(videos: true, completion: completion)
            default:
                qlog(.warning, tag, "unknown picker kind: \(kind)")
                completion(nil)
            }
        }
    }

    // MARK: - Files (multiple)

    private static func pickFiles(completion: @escaping (IosSendBatch?) -> Void) {
        DocumentPicker.present(types: [.item], multiple: true) { urls in
            guard let urls, !urls.isEmpty else {
                completion(nil)
                return
            }
            let batch = SendBatchStore.shared.begin()
            var files: [IosSendFile] = []
            var used = Set<String>()
            for url in urls {
                guard SendBatchStore.shared.retainScope(url, batchId: batch) else {
                    qlog(.warning, tag, "Can't get security scope: \(url.lastPathComponent)")
                    continue
                }
                guard let meta = fileMeta(url) else { continue }
                files.append(IosSendFile(relPath: uniqueName(url.lastPathComponent, &used),
                                         path: url.path, size: meta.0, mtime: meta.1))
            }
            qlog(.info, tag, "files: \(urls.count) picked -> \(files.count) items")
            completion(IosSendBatch(batchId: batch, files: files))
        }
    }

    // MARK: - Folder

    private static func pickFolder(completion: @escaping (IosSendBatch?) -> Void) {
        DocumentPicker.pickFolder { root in
            guard let root else {
                completion(nil)
                return
            }
            let batch = SendBatchStore.shared.begin()
            guard SendBatchStore.shared.retainScope(root, batchId: batch) else {
                qlog(.warning, tag, "Can't get security scope: \(root.lastPathComponent)")
                completion(IosSendBatch(batchId: batch, files: []))
                return
            }
            // Enumeration can be slow, so do it in the background
            DispatchQueue.global(qos: .userInitiated).async {
                let files = enumerateFolder(root)
                qlog(.info, tag, "folder: \(root.lastPathComponent) -> \(files.count) items")
                DispatchQueue.main.async {
                    completion(IosSendBatch(batchId: batch, files: files))
                }
            }
        }
    }

    /// Lists regular files under the folder as "<folder name>/<relative path>" (excluding empty folders and hidden files).
    private static func enumerateFolder(_ root: URL) -> [IosSendFile] {
        let rootName = root.lastPathComponent
        let keys: [URLResourceKey] = [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey, .isHiddenKey]
        guard let en = FileManager.default.enumerator(
            at: root, includingPropertiesForKeys: keys, options: [.skipsHiddenFiles]) else { return [] }
        var out: [IosSendFile] = []
        let rootPath = root.standardizedFileURL.path
        for case let url as URL in en {
            guard let v = try? url.resourceValues(forKeys: Set(keys)), v.isRegularFile == true else { continue }
            let full = url.standardizedFileURL.path
            guard full.hasPrefix(rootPath + "/") else { continue }
            let rel = String(full.dropFirst(rootPath.count + 1))
            let size = Int64(v.fileSize ?? 0)
            let mtime = Int64((v.contentModificationDate ?? Date()).timeIntervalSince1970 * 1000)
            out.append(IosSendFile(relPath: "\(rootName)/\(rel)", path: url.path, size: size, mtime: mtime))
        }
        return out
    }

    // MARK: - Photos / Videos (PHPicker)

    private static func pickMedia(videos: Bool, completion: @escaping (IosSendBatch?) -> Void) {
        guard let presenter = topViewController() else {
            completion(nil)
            return
        }
        // Photo library permission first (needed for the PHAssetResource path; if denied, continue with the simple path)
        PhotoLibraryAccess.request { granted in
            var config = PHPickerConfiguration(photoLibrary: .shared())
            config.selectionLimit = 0 // Unlimited
            config.filter = videos ? .videos : .images
            config.preferredAssetRepresentationMode = .current // No re-encoding
            let picker = PHPickerViewController(configuration: config)
            let delegate = MediaPickerDelegate(videos: videos, authorized: granted, completion: completion)
            picker.delegate = delegate
            MediaPickerDelegate.active = delegate
            presenter.present(picker, animated: true)
        }
    }

    // MARK: - Helpers

    private static func fileMeta(_ url: URL) -> (Int64, Int64)? {
        guard let attrs = try? FileManager.default.attributesOfItem(atPath: url.path) else {
            qlog(.warning, tag, "Can't read attributes: \(url.lastPathComponent)")
            return nil
        }
        if (attrs[.type] as? FileAttributeType) == .typeDirectory {
            qlog(.warning, tag, "Send folders with \"Folder\": \(url.lastPathComponent)")
            return nil
        }
        let size = (attrs[.size] as? NSNumber)?.int64Value ?? 0
        let mtime = Int64(((attrs[.modificationDate] as? Date) ?? Date()).timeIntervalSince1970 * 1000)
        return (size, mtime)
    }

    /// Renames duplicates in the send list to "name (1).ext".
    static func uniqueName(_ name: String, _ used: inout Set<String>) -> String {
        // Numbering follows the shared Kotlin FileNames (same rule as the receiving side and Android; 0.2.25)
        var candidate = name
        var i: Int32 = 1
        while used.contains(candidate) {
            candidate = FileNames.shared.numbered(name: name, n: i)
            i += 1
        }
        used.insert(candidate)
        return candidate
    }
}

/// Photo library permission (same as QSProbe's PhotoLibraryAccess).
enum PhotoLibraryAccess {
    static var isAuthorized: Bool {
        let status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        return status == .authorized || status == .limited
    }

    static func request(_ completion: @escaping (Bool) -> Void) {
        if isAuthorized {
            completion(true)
            return
        }
        PHPhotoLibrary.requestAuthorization(for: .readWrite) { status in
            DispatchQueue.main.async {
                let granted = (status == .authorized || status == .limited)
                qlog(granted ? .info : .warning, "SendPickers",
                     granted ? "Photo library access granted"
                             : "Photo library access denied (Live Photo paired videos can't be sent)")
                completion(granted)
            }
        }
    }
}

/// PHPicker delegate. Exports the picked items to a temp folder, then returns the result.
private final class MediaPickerDelegate: NSObject, PHPickerViewControllerDelegate {
    static var active: MediaPickerDelegate?
    private static let tag = "SendPickers"

    private let videos: Bool
    private let authorized: Bool
    private let completion: (IosSendBatch?) -> Void
    private let lock = NSLock()
    private var files: [IosSendFile] = []
    private var used = Set<String>()

    init(videos: Bool, authorized: Bool, completion: @escaping (IosSendBatch?) -> Void) {
        self.videos = videos
        self.authorized = authorized
        self.completion = completion
    }

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.delegate = nil
        picker.dismiss(animated: true)
        Self.active = nil
        guard !results.isEmpty else {
            completion(nil)
            return
        }
        let batch = SendBatchStore.shared.begin()
        let dir = SendBatchStore.shared.tempDirectory(batchId: batch)
        qlog(.info, Self.tag, "\(videos ? "videos" : "photos"): exporting \(results.count) items (\(authorized ? "PHAssetResource" : "simple path"))")

        let group = DispatchGroup()
        for result in results {
            group.enter()
            if authorized, let id = result.assetIdentifier {
                exportViaAssetResources(identifier: id, into: dir) { group.leave() }
            } else {
                exportViaFileRepresentation(result, into: dir) { group.leave() }
            }
        }
        group.notify(queue: .main) { [self] in
            lock.lock()
            let list = files.sorted { $0.relPath < $1.relPath }
            lock.unlock()
            qlog(.info, Self.tag, "Extracted \(list.count) files")
            completion(IosSendBatch(batchId: batch, files: list))
        }
    }

    /// Main path: export each PHAssetResource (Live Photo = still image + paired video).
    private func exportViaAssetResources(identifier: String, into dir: URL, completion: @escaping () -> Void) {
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [identifier], options: nil).firstObject else {
            qlog(.warning, Self.tag, "Can't get PHAsset (\(identifier))")
            completion()
            return
        }
        let resources = PHAssetResource.assetResources(for: asset)
        func preferred(_ edited: PHAssetResourceType, _ original: PHAssetResourceType) -> PHAssetResource? {
            resources.first { $0.type == edited } ?? resources.first { $0.type == original }
        }
        var wanted: [PHAssetResource] = []
        if videos {
            if let v = preferred(.fullSizeVideo, .video) { wanted.append(v) }
        } else {
            if let p = preferred(.fullSizePhoto, .photo) { wanted.append(p) }
            // Live Photo: send the paired video too (same as FlyingCarpet and QSProbe; Apple receivers recombine them)
            if asset.mediaSubtypes.contains(.photoLive), let paired = preferred(.fullSizePairedVideo, .pairedVideo) {
                wanted.append(paired)
                qlog(.info, Self.tag, "Live Photo: sending as 2 files (still image + paired video)")
            }
        }
        guard !wanted.isEmpty else {
            qlog(.warning, Self.tag, "No exportable resources: \(resources.map { "\($0.type.rawValue)" })")
            completion()
            return
        }
        let inner = DispatchGroup()
        for resource in wanted {
            inner.enter()
            let dest = reserve(resource.originalFilename, in: dir)
            let options = PHAssetResourceRequestOptions()
            options.isNetworkAccessAllowed = true // In case it's only in iCloud
            // writeData writes straight to disk (doesn't load it all into memory like requestData)
            PHAssetResourceManager.default().writeData(for: resource, toFile: dest, options: options) { [self] error in
                defer { inner.leave() }
                if let error {
                    qlog(.warning, Self.tag, "Failed to export \(resource.originalFilename): \(error)")
                    try? FileManager.default.removeItem(at: dest)
                    return
                }
                append(dest, mtime: asset.modificationDate ?? asset.creationDate)
            }
        }
        inner.notify(queue: .global()) { completion() }
    }

    /// Fallback: loadFileRepresentation (Live Photo .pvt packages can't be sent, so they're skipped).
    private func exportViaFileRepresentation(_ result: PHPickerResult, into dir: URL, completion: @escaping () -> Void) {
        let provider = result.itemProvider
        let typeId = videos ? UTType.movie.identifier : UTType.image.identifier
        guard provider.hasItemConformingToTypeIdentifier(typeId) || provider.hasItemConformingToTypeIdentifier(UTType.item.identifier) else {
            qlog(.warning, Self.tag, "Skipped an item that couldn't be extracted")
            completion()
            return
        }
        let useType = provider.hasItemConformingToTypeIdentifier(typeId) ? typeId : UTType.item.identifier
        provider.loadFileRepresentation(forTypeIdentifier: useType) { [self] url, error in
            defer { completion() }
            guard let url else {
                qlog(.warning, Self.tag, "Load failed: \(error?.localizedDescription ?? "unknown")")
                return
            }
            var isDir: ObjCBool = false
            if FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir), isDir.boolValue {
                qlog(.warning, Self.tag, "Skipped \(url.lastPathComponent) because it's a package (allow photo library access to send it)")
                return
            }
            // The original file name may be unavailable -> use a date-time name
            let name = url.lastPathComponent.isEmpty || url.lastPathComponent.hasPrefix(".")
                ? Self.dateName(ext: url.pathExtension, videos: videos) : url.lastPathComponent
            let dest = reserve(name, in: dir)
            do {
                try FileManager.default.copyItem(at: url, to: dest)
                append(dest, mtime: nil)
            } catch {
                qlog(.warning, Self.tag, "Copy failed: \(error)")
            }
        }
    }

    private func reserve(_ filename: String, in dir: URL) -> URL {
        lock.lock()
        let name = SendPickers.uniqueName(filename.isEmpty ? "item" : filename, &used)
        lock.unlock()
        let url = dir.appendingPathComponent(name)
        try? FileManager.default.removeItem(at: url) // writeData fails if the file already exists
        return url
    }

    private func append(_ url: URL, mtime: Date?) {
        guard let attrs = try? FileManager.default.attributesOfItem(atPath: url.path) else { return }
        let size = (attrs[.size] as? NSNumber)?.int64Value ?? 0
        let date = mtime ?? (attrs[.modificationDate] as? Date) ?? Date()
        lock.lock()
        files.append(IosSendFile(relPath: url.lastPathComponent, path: url.path, size: size,
                                 mtime: Int64(date.timeIntervalSince1970 * 1000)))
        lock.unlock()
    }

    private static func dateName(ext: String, videos: Bool) -> String {
        let f = DateFormatter()
        f.dateFormat = "yyyyMMdd_HHmmss"
        let base = (videos ? "VID_" : "IMG_") + f.string(from: Date())
        let e = ext.isEmpty ? (videos ? "mov" : "jpg") : ext
        return "\(base).\(e)"
    }
}

/// Per-batch items to clean up (security scopes and temp folders).
final class SendBatchStore {
    static let shared = SendBatchStore()
    private static let tag = "SendPickers"

    private let lock = NSLock()
    private var scoped: [String: [URL]] = [:]
    private var tempDirs: [String: URL] = [:]

    private var outbox: URL {
        FileManager.default.temporaryDirectory.appendingPathComponent(AppInfo.shared.OUTBOX_FOLDER, isDirectory: true)
    }

    /// At launch: removes leftovers from the previous run.
    func sweep() {
        try? FileManager.default.removeItem(at: outbox)
    }

    func begin() -> String {
        let id = UUID().uuidString
        lock.lock()
        scoped[id] = []
        lock.unlock()
        return id
    }

    /// Acquires a security scope and holds it until the batch ends.
    func retainScope(_ url: URL, batchId: String) -> Bool {
        let ok = url.startAccessingSecurityScopedResource()
        if ok {
            lock.lock()
            scoped[batchId, default: []].append(url)
            lock.unlock()
        }
        return ok
    }

    func tempDirectory(batchId: String) -> URL {
        let dir = outbox.appendingPathComponent(batchId, isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        lock.lock()
        tempDirs[batchId] = dir
        lock.unlock()
        return dir
    }

    /// Called when the Kotlin send batch ends (success, failure, or cancel).
    func end(_ batchId: String) {
        lock.lock()
        let urls = scoped.removeValue(forKey: batchId) ?? []
        let dir = tempDirs.removeValue(forKey: batchId)
        lock.unlock()
        for u in urls { u.stopAccessingSecurityScopedResource() }
        if let dir { try? FileManager.default.removeItem(at: dir) }
        qlog(.debug, Self.tag, "batch ended: \(batchId.prefix(8)) scopes=\(urls.count) temp=\(dir != nil)")
    }
}
