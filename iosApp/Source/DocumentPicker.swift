import UIKit
import UniformTypeIdentifiers

/// Presents the Files picker (UIDocumentPicker) modally (shared since 0.2.25).
///
/// Up to 0.2.24 there were two identical implementations, FolderPicker for the shared folder/save destination
/// and DocumentPickerDelegate (SendPickers.swift) for "Send" files/folders, with presentation code in 3 places.
/// Counterpart of fileImporter in the old SwiftUI app.
final class DocumentPicker: NSObject, UIDocumentPickerDelegate {
    private static let tag = "DocumentPicker"
    private static var active: DocumentPicker? // Keeps the delegate alive until selection finishes

    private let completion: ([URL]?) -> Void

    private init(completion: @escaping ([URL]?) -> Void) {
        self.completion = completion
    }

    /// Shows the picker. nil on cancel or failure to present. completion runs on the main thread.
    static func present(types: [UTType], multiple: Bool, completion: @escaping ([URL]?) -> Void) {
        let show = {
            guard let presenter = topViewController() else {
                qlog(.warning, tag, "No presenting ViewController found")
                completion(nil)
                return
            }
            let picker = DocumentPicker(completion: completion)
            active = picker
            let vc = UIDocumentPickerViewController(forOpeningContentTypes: types, asCopy: false)
            vc.allowsMultipleSelection = multiple
            vc.delegate = picker
            presenter.present(vc, animated: true)
        }
        if Thread.isMainThread { show() } else { DispatchQueue.main.async(execute: show) }
    }

    /// Picks one folder (save destination, or a folder for "Send").
    static func pickFolder(completion: @escaping (URL?) -> Void) {
        present(types: [.folder], multiple: false) { completion($0?.first) }
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        completion(urls)
        Self.active = nil
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        completion(nil)
        Self.active = nil
    }
}

/// Topmost ViewController (for modal presentation).
func topViewController() -> UIViewController? {
    let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
    let window = scenes.flatMap { $0.windows }.first { $0.isKeyWindow }
    var top = window?.rootViewController
    while let presented = top?.presentedViewController {
        top = presented
    }
    return top
}
