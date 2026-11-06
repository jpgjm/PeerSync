import Foundation
import PeerSyncShared

/// Log severity (keeps the same call interface as the old AppLog.swift).
enum LogLevel: Int {
    case debug = 0
    case info = 1
    case warning = 2
    case error = 3
}

/// Log function callable from anywhere. Forwards to the shared Kotlin PsLog
/// (in-app Logs screen + Documents/logs/peersync.log + NSLog).
/// Same signature as qlog() in the old iOS app, so the reused Swift service layer works unchanged.
func qlog(_ level: LogLevel = .info, _ category: String = "app", _ message: String) {
    IosAppKt.psLog(level: Int32(level.rawValue), tag: category, message: message)
}
