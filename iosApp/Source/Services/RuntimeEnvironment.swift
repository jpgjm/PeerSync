import Foundation

/// Detects the runtime environment and resolves environment-dependent paths (generic). Same idea as AlarmClock's RuntimeEnvironment.
enum RuntimeEnvironment {

    /// Name of the environment variable holding the host's original HOME, saved by LiveContainer.
    /// LC always sets it before launching the guest, on every launch path
    /// (LCBootstrap.m LiveContainerMain / LiveProcess main.m / LaunchAppExtension).
    private static let lcHomePathKey = "LC_HOME_PATH"

    /// Whether we're running as a LiveContainer guest.
    /// ProcessInfo.environment is a snapshot from first access, so
    /// check with getenv, which always returns the current value.
    static let isLiveContainer: Bool = getenv(lcHomePathKey) != nil

    /// Whether we were launched via LiveProcess.appex (multitasking / headless).
    /// BGTaskScheduler isn't available in an App Extension process.
    static let isLiveProcess: Bool = {
        if getenv("LP_HOME_PATH") != nil { return true }
        return (hostExecutablePath ?? "").contains("LiveProcess.appex")
    }()

    /// The process's **actual** executable path. LiveContainer swaps `Bundle.main` / `_dyld_get_image_name` /
    /// `ProcessInfo.arguments` for the guest, but the executable path held by dyld
    /// (`_NSGetExecutablePath`) is unchanged. Inside LC it's `…/LiveContainer.app/LiveContainer`.
    static let hostExecutablePath: String? = {
        var size: UInt32 = 0
        _ = _NSGetExecutablePath(nil, &size)
        var buf = [CChar](repeating: 0, count: Int(size) + 1)
        guard _NSGetExecutablePath(&buf, &size) == 0 else { return nil }
        let raw = String(cString: buf)
        // Resolve symlinks (/var -> /private/var etc.) to the real path
        return (raw as NSString).resolvingSymlinksInPath
    }()

    /// The host's .app bundle (the actual process). Ourselves for a normal install, LiveContainer.app inside LC.
    ///
    /// Lookup order:
    ///  1. `+[NSUserDefaults lcMainBundle]` exposed by LiveContainer (LCBootstrap.m):
    ///     LiveContainer.app's own NSBundle, saved **before** `NSBundle.mainBundle` is swapped.
    ///  2. Search dyld's image list for anything under `/LiveContainer.app/` (Frameworks etc.).
    ///     LC hides only its main image (Dyld.m), so bundled framework paths remain.
    ///  3. `_NSGetExecutablePath`. LC swaps this too (confirmed in v0.1.8 logs: it returns the guest path),
    ///     so it's unusable inside LC, but for a normal install it gives ourselves.
    static let hostBundle: Bundle? = {
        if let b = lcMainBundle { return b }
        if let b = liveContainerBundleFromDyld { return b }
        guard let exe = hostExecutablePath else { return nil }
        let appURL = URL(fileURLWithPath: exe).deletingLastPathComponent()
        return Bundle(url: appURL)
    }()

    /// `+[NSUserDefaults lcMainBundle]`. Exists only inside LiveContainer.
    static let lcMainBundle: Bundle? = {
        let sel = NSSelectorFromString("lcMainBundle")
        guard UserDefaults.responds(to: sel) else { return nil }
        guard let obj = (UserDefaults.self as AnyObject).perform(sel)?.takeUnretainedValue() else { return nil }
        return obj as? Bundle
    }()

    /// Infers the LiveContainer.app path from dyld images.
    static let liveContainerBundleFromDyld: Bundle? = {
        let count = _dyld_image_count()
        for i in 0..<count {
            guard let c = _dyld_get_image_name(i) else { continue }
            let path = String(cString: c)
            guard let r = path.range(of: "/LiveContainer.app/") else { continue }
            let appPath = String(path[..<r.upperBound].dropLast()) // ".../LiveContainer.app"
            if let b = Bundle(path: appPath) { return b }
        }
        return nil
    }()

    /// Which host lookup method worked (for diagnostic logs).
    static var hostBundleSource: String {
        if lcMainBundle != nil { return "lcMainBundle" }
        if liveContainerBundleFromDyld != nil { return "dyld(/LiveContainer.app/)" }
        return "_NSGetExecutablePath"
    }

    /// The host's bundle ID (includes the team ID via SideStore, e.g. com.kdt.livecontainer.XXXXXXXXXX).
    static var hostBundleIdentifier: String? {
        hostBundle?.infoDictionary?["CFBundleIdentifier"] as? String
    }

    /// BGTaskSchedulerPermittedIdentifiers from the host's Info.plist.
    static var hostPermittedTaskIdentifiers: [String] {
        hostBundle?.infoDictionary?["BGTaskSchedulerPermittedIdentifiers"] as? [String] ?? []
    }

    /// UIBackgroundModes from the host's Info.plist.
    static var hostBackgroundModes: [String] {
        hostBundle?.infoDictionary?["UIBackgroundModes"] as? [String] ?? []
    }

    /// Whether tasks can be registered with BGTaskScheduler.
    /// - Normal install: yes
    /// - Inside LiveContainer: yes if the host (LiveContainer.app) Info.plist has `UIBackgroundModes: processing` and
    ///   `BGTaskSchedulerPermittedIdentifiers` (`<LC bundle ID>.transfer.N`).
    ///   The system reads the actual process's (the host's) plist, so the guest plist doesn't matter
    ///   (the same "missing from the host plist" issue as LiveContainer #1519 / #1528 / #1666).
    /// - Inside LiveProcess.appex: no (App Extension)
    static var canUseBackgroundTasks: Bool {
        if isLiveProcess { return false }
        if !isLiveContainer { return true }
        return hostBackgroundModes.contains("processing") && !hostPermittedTaskIdentifiers.isEmpty
    }

    /// One-line summary for diagnostics and logs.
    static var summary: String {
        isLiveContainer ? "LiveContainer (guest)" : "normal install"
    }
}
