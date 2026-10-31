import Foundation
import UIKit

/// ペアリング用コード：peersync:<端末ID>?name=<端末名>。Android 版 ui/Qr.kt / Windows 版 PairingCode.cs と互換。
enum PairingCode {
    static func encode(id: String, name: String? = nil) -> String {
        let n = name ?? UIDevice.current.name
        let escaped = n.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed.subtracting(CharacterSet(charactersIn: "&=?"))) ?? n
        return "peersync:\(id)?name=\(escaped)"
    }

    /// QR 文字列または ID 直貼りから (id, name) を取り出す。不正なら nil。
    static func decode(_ text: String) -> (id: String, name: String?)? {
        var body = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if body.lowercased().hasPrefix("peersync:") {
            body = String(body.dropFirst("peersync:".count))
        }

        var id = body
        var name: String? = nil
        if let q = body.firstIndex(of: "?") {
            id = String(body[..<q])
            let query = body[body.index(after: q)...]
            for kv in query.split(separator: "&") {
                if let eq = kv.firstIndex(of: "="), kv[..<eq] == "name" {
                    name = String(kv[kv.index(after: eq)...]).removingPercentEncoding
                }
            }
        }

        id = id.trimmingCharacters(in: .whitespaces)
        // iroh の端末 ID は 32 バイト公開鍵の 16 進 64 文字
        let ok = id.count == 64 && id.allSatisfy { $0.isHexDigit }
        return ok ? (id, name) : nil
    }
}
