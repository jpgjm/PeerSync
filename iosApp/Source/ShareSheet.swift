import UIKit

/// Shows the system share sheet (activity view).
/// Entry point for showing the same `UIActivityViewController` that SwiftUI's
/// `ShareLink(item: text)` uses internally, from the Compose "Share" button.
/// The sheet lists "Copy" alongside AirDrop, Messages, LINE, etc., so
/// the Device ID is copied from here too (the dedicated "Copy" button was removed in 0.2.4).
enum ShareSheet {
    /// - Parameters:
    ///   - text: string to share (pairing code `peersync:<id>?name=<name>`)
    ///   - anchor: frame of the "Share" button (window coordinates, pt). On iPad the popover
    ///     is anchored here (iPad crashes without an anchor).
    ///     If the width is 0 or less, the position is unknown and it's shown centered.
    static func present(text: String, anchor: CGRect) {
        DispatchQueue.main.async {
            guard let presenter = topViewController() else {
                qlog(.warning, "ShareSheet", "No presenting ViewController found")
                return
            }
            let vc = UIActivityViewController(activityItems: [text], applicationActivities: nil)
            vc.completionWithItemsHandler = { activity, completed, _, error in
                if let error {
                    qlog(.error, "ShareSheet", "Share failed: \(error.localizedDescription)")
                } else if completed {
                    qlog(.info, "ShareSheet", "Share completed: \(activity?.rawValue ?? "(unknown)")")
                } else {
                    qlog(.debug, "ShareSheet", "Share cancelled")
                }
            }
            // On iPad (and Stage Manager / Mac Catalyst) it's a popover, which
            // requires sourceView/sourceRect. Ignored on iPhone.
            if let pop = vc.popoverPresentationController {
                let view = presenter.view!
                if anchor.width > 0, anchor.height > 0 {
                    // The frame from Compose is in window coordinates; convert it to presenter.view.
                    let rect = view.convert(anchor, from: nil)
                    pop.sourceView = view
                    pop.sourceRect = rect
                    pop.permittedArrowDirections = [.up, .down]
                    qlog(.debug, "ShareSheet", "popover anchor=\(rect)")
                } else {
                    pop.sourceView = view
                    pop.sourceRect = CGRect(x: view.bounds.midX, y: view.bounds.midY, width: 1, height: 1)
                    pop.permittedArrowDirections = []
                    qlog(.debug, "ShareSheet", "popover anchor unknown: showing centered")
                }
            }
            presenter.present(vc, animated: true)
        }
    }
}
