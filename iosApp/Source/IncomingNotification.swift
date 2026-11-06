import UIKit
import UserNotifications
import PeerSyncShared

/// Local notifications (approval for the other device's "Send", pairing requests, unpair notices, receive started, etc.).
/// Counterpart of QSProbe's TransferNotification.
///
/// QSProbe had no notification buttons because it showed a confirmation code, but PeerSync only deals with
/// paired devices, so it adds "Accept" and "Decline" buttons. In the foreground the in-app approval dialog
/// is shown, so no notification is posted (keeps Notification Center clean).
final class IncomingNotification: NSObject, UNUserNotificationCenterDelegate {
    static let shared = IncomingNotification()
    private static let tag = "IncomingNotify"

    // Category IDs are shared with the Kotlin side that builds the notifications (IosNotifications) (0.2.25)
    private static let category = IosNotifications.shared.categoryIncoming
    /// For text-only offers (buttons: "Copy" / "Close").
    private static let textCategory = IosNotifications.shared.categoryIncomingText
    // Button IDs also come from Kotlin (IosNotifications <- AppInfo) (0.2.30; values same as 0.2.29)
    private static let actionAccept = IosNotifications.shared.actionAccept
    private static let actionReject = IosNotifications.shared.actionReject
    /// For pairing requests (0.2.14; buttons: "Allow" / "Cancel").
    private static let pairCategory = IosNotifications.shared.categoryPair
    private static let actionPairAccept = IosNotifications.shared.actionPairAccept
    private static let actionPairReject = IosNotifications.shared.actionPairReject
    private static let userInfoToken = "token"

    private var prepared = false

    /// Once at launch: register categories, set the delegate, request permission.
    func prepare() {
        guard !prepared else { return }
        prepared = true
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        // Button titles come from Kotlin's PsText (same wording as the dialogs and Android notifications; 0.2.30)
        let accept = UNNotificationAction(identifier: Self.actionAccept, title: PsText.shared.accept, options: [])
        let reject = UNNotificationAction(identifier: Self.actionReject, title: PsText.shared.reject, options: [.destructive])
        let copy = UNNotificationAction(identifier: Self.actionAccept, title: PsText.shared.copyLabel, options: [])
        let close = UNNotificationAction(identifier: Self.actionReject, title: PsText.shared.close, options: [])
        let pairAccept = UNNotificationAction(identifier: Self.actionPairAccept, title: PsText.shared.allow, options: [])
        let pairReject = UNNotificationAction(identifier: Self.actionPairReject, title: PsText.shared.cancel, options: [.destructive])
        center.setNotificationCategories([
            UNNotificationCategory(identifier: Self.category, actions: [accept, reject],
                                   intentIdentifiers: [], options: []),
            UNNotificationCategory(identifier: Self.textCategory, actions: [copy, close],
                                   intentIdentifiers: [], options: []),
            UNNotificationCategory(identifier: Self.pairCategory, actions: [pairAccept, pairReject],
                                   intentIdentifiers: [], options: []),
        ])
        center.requestAuthorization(options: [.alert, .sound]) { granted, error in
            if let error {
                qlog(.warning, Self.tag, "Notification permission request failed: \(error.localizedDescription)")
            } else {
                qlog(.info, Self.tag, "Notification permission = \(granted ? "granted" : "denied")")
            }
        }
    }

    /// Posts a local notification (0.2.25: one function for all 5 kinds; title and body are built from Kotlin's PsText).
    /// Skipped in the foreground, where the in-app dialog/indicator is shown (keeps Notification Center clean).
    /// - Parameters:
    ///   - category: category for notifications with buttons (an IosNotifications category); nil for no buttons
    ///   - token: token used to return the decision from a notification button (stored in userInfo)
    ///   - logLabel: short name for logs
    func post(id: String, title: String, body: String, category: String?, token: String?, logLabel: String) {
        DispatchQueue.main.async {
            if UIApplication.shared.applicationState == .active {
                qlog(.debug, Self.tag, "In foreground; not posting \(logLabel) notification")
                return
            }
            let content = UNMutableNotificationContent()
            content.title = title
            content.body = body
            content.sound = .default
            if let category { content.categoryIdentifier = category }
            if let token { content.userInfo = [Self.userInfoToken: token] }
            let request = UNNotificationRequest(identifier: id, content: content, trigger: nil)
            UNUserNotificationCenter.current().add(request) { error in
                if let error {
                    qlog(.warning, Self.tag, "Can't post \(logLabel) notification: \(error.localizedDescription)")
                } else {
                    qlog(.info, Self.tag, "Posted \(logLabel) notification")
                }
            }
        }
    }

    /// Removes the notification (after a decision, a timeout, or closing it in the app).
    func clear(id: String) {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [id])
        center.removeDeliveredNotifications(withIdentifiers: [id])
    }

    // MARK: - UNUserNotificationCenterDelegate

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        defer { completionHandler() }
        guard let token = response.notification.request.content.userInfo[Self.userInfoToken] as? String else { return }
        switch response.actionIdentifier {
        case Self.actionAccept:
            qlog(.info, Self.tag, "\"Accept\" from notification")
            IosApp.shared.decideOffer(token: token, accept: true)
        case Self.actionReject:
            qlog(.info, Self.tag, "\"Decline\" from notification")
            IosApp.shared.decideOffer(token: token, accept: false)
        case Self.actionPairAccept:
            qlog(.info, Self.tag, "\"Allow\" from notification (pairing)")
            IosApp.shared.decidePairing(token: token, accept: true)
        case Self.actionPairReject:
            qlog(.info, Self.tag, "\"Cancel\" from notification (pairing)")
            IosApp.shared.decidePairing(token: token, accept: false)
        default:
            // Body tap: just bring the app to the foreground; decide in the in-app dialog
            qlog(.debug, Self.tag, "Notification tapped; coming to foreground")
        }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        // No banner in the foreground; the in-app dialog is shown
        completionHandler([])
    }
}
