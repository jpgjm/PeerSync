import Foundation

/// 実行環境の判定と、環境依存のパス解決。AlarmClock の RuntimeEnvironment と同じ考え方。
enum RuntimeEnvironment {

    /// LiveContainer が退避しているホスト本来の HOME を指す環境変数名。
    private static let lcHomePathKey = "LC_HOME_PATH"

    /// LiveContainer のゲストとして動いているか。
    static let isLiveContainer: Bool = {
        ProcessInfo.processInfo.environment[lcHomePathKey] != nil
            || UserDefaults.responds(to: NSSelectorFromString("lcMainBundle"))
            // 環境変数が無い版の LC 向けの保険: Documents が二重になる
            || FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
                .path.contains("/Documents/Data/Application/")
    }()

    /// LiveContainer 本体（ホスト）の実コンテナのパス。通常インストール時は nil。
    static let hostHomePath: String? = {
        if let p = ProcessInfo.processInfo.environment[lcHomePathKey] { return p }
        // 環境変数が無い場合は Documents のパスから逆算する:
        //   <host>/Documents/Data/Application/<UUID>/Documents
        guard isLiveContainer else { return nil }
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].path
        guard let range = docs.range(of: "/Documents/Data/Application/") else { return nil }
        return String(docs[..<range.lowerBound])
    }()

    /// LiveProcess.appex（マルチタスク / ヘッドレス）経由で起動されたか。
    /// App Extension のプロセスでは BGTaskScheduler は使えない。
    static let isLiveProcess: Bool = {
        if ProcessInfo.processInfo.environment["LP_HOME_PATH"] != nil { return true }
        return (hostExecutablePath ?? "").contains("LiveProcess.appex")
    }()

    /// プロセスの**実際の**実行ファイルパス。LiveContainer は `Bundle.main` / `_dyld_get_image_name` /
    /// `ProcessInfo.arguments` をゲストに差し替えるが、dyld が持つ実行ファイルパス
    /// （`_NSGetExecutablePath`）はそのまま。LC 内では `…/LiveContainer.app/LiveContainer` になる。
    static let hostExecutablePath: String? = {
        var size: UInt32 = 0
        _ = _NSGetExecutablePath(nil, &size)
        var buf = [CChar](repeating: 0, count: Int(size) + 1)
        guard _NSGetExecutablePath(&buf, &size) == 0 else { return nil }
        let raw = String(cString: buf)
        // シンボリックリンク（/var → /private/var 等）を解決して実体にする
        return (raw as NSString).resolvingSymlinksInPath
    }()

    /// ホスト（プロセスの実体）の .app バンドル。通常インストールでは自分自身、LC 内では LiveContainer.app。
    ///
    /// 探し方（優先順）:
    ///  1. LiveContainer が公開している `+[NSUserDefaults lcMainBundle]`（LCBootstrap.m）。
    ///     `NSBundle.mainBundle` を差し替える**前**に保存した LiveContainer.app 自身の NSBundle。
    ///  2. dyld のイメージ一覧から `/LiveContainer.app/` 配下のもの（Frameworks 等）を探す。
    ///     LC は自分のメインイメージだけを隠す（Dyld.m）ので、同梱フレームワークのパスは残る。
    ///  3. `_NSGetExecutablePath`。LC はこれも差し替える（v0.1.8 のログで確認: ゲストのパスが返る）ため
    ///     LC 内では使えないが、通常インストールではこれで自分自身になる。
    static let hostBundle: Bundle? = {
        if let b = lcMainBundle { return b }
        if let b = liveContainerBundleFromDyld { return b }
        guard let exe = hostExecutablePath else { return nil }
        let appURL = URL(fileURLWithPath: exe).deletingLastPathComponent()
        return Bundle(url: appURL)
    }()

    /// `+[NSUserDefaults lcMainBundle]`。LiveContainer 内でだけ存在する。
    static let lcMainBundle: Bundle? = {
        let sel = NSSelectorFromString("lcMainBundle")
        guard UserDefaults.responds(to: sel) else { return nil }
        guard let obj = (UserDefaults.self as AnyObject).perform(sel)?.takeUnretainedValue() else { return nil }
        return obj as? Bundle
    }()

    /// dyld のイメージから LiveContainer.app のパスを推定する。
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

    /// ホストの探し方のうちどれが効いたか（診断ログ用）。
    static var hostBundleSource: String {
        if lcMainBundle != nil { return "lcMainBundle" }
        if liveContainerBundleFromDyld != nil { return "dyld(/LiveContainer.app/)" }
        return "_NSGetExecutablePath"
    }

    /// ホストの bundle ID（SideStore 経由ならチーム ID 込み。例: com.kdt.livecontainer.XXXXXXXXXX）。
    static var hostBundleIdentifier: String? {
        hostBundle?.infoDictionary?["CFBundleIdentifier"] as? String
    }

    /// ホストの Info.plist にある BGTaskSchedulerPermittedIdentifiers。
    static var hostPermittedTaskIdentifiers: [String] {
        hostBundle?.infoDictionary?["BGTaskSchedulerPermittedIdentifiers"] as? [String] ?? []
    }

    /// ホストの Info.plist にある UIBackgroundModes。
    static var hostBackgroundModes: [String] {
        hostBundle?.infoDictionary?["UIBackgroundModes"] as? [String] ?? []
    }

    /// BGTaskScheduler にタスクを登録できるか。
    /// - 通常インストール: 可
    /// - LiveContainer 内: ホスト（LiveContainer.app）の Info.plist に `UIBackgroundModes: processing` と
    ///   `BGTaskSchedulerPermittedIdentifiers`（`<LC の bundle ID>.transfer.N`）があれば可。
    ///   システムはプロセスの実体＝ホストの plist を見るため、ゲストの plist は関係ない
    ///   （LiveContainer #1519 / #1528 / #1666 と同じ「ホスト plist に無い」問題）。
    /// - LiveProcess.appex 内: 不可（App Extension）
    static var canUseBackgroundTasks: Bool {
        if isLiveProcess { return false }
        if !isLiveContainer { return true }
        return hostBackgroundModes.contains("processing") && !hostPermittedTaskIdentifiers.isEmpty
    }

    /// 診断・ログ用の1行要約。
    static var summary: String {
        isLiveContainer ? "LiveContainer (ゲスト)" : "通常インストール"
    }

    // MARK: - ファイルアプリ上の表示パス

    /// 「ファイル」アプリでたどれる表示パスに変換する。たどれない場所なら nil。
    ///
    /// iOS が「ファイル」アプリに公開するのは `UIFileSharingEnabled` が有効なアプリの
    /// **Documents フォルダだけ**。LiveContainer 内では公開されるのは **ホストの Documents** で、
    /// ゲストのコンテナはその配下の `Data/Application/{ゲストUUID}/` に置かれている。
    ///
    /// ```
    /// ファイル > このデバイス内 > PeerSync / PeerSync / <相手名> / …           （通常インストール）
    /// ファイル > このデバイス内 > LiveContainer / Data/Application/{UUID}/Documents/PeerSync / … （LC）
    /// ```
    static func filesAppPath(for url: URL) -> String? {
        let visibleRootName = isLiveContainer ? "LiveContainer" : "PeerSync"

        let exposedDocuments: String
        if let hostHome = hostHomePath {
            exposedDocuments = hostHome + "/Documents"
        } else {
            exposedDocuments = FileManager.default
                .urls(for: .documentDirectory, in: .userDomainMask)[0].path
        }

        let target = normalized(url.path)
        let root = normalized(exposedDocuments)

        guard target == root || target.hasPrefix(root + "/") else { return nil }
        let relative = String(target.dropFirst(root.count))
        return visibleRootName + relative
    }

    /// `/private/var/...` と `/var/...` を同一視するためにパスを正規化する。
    static func normalized(_ path: String) -> String {
        var p = path
        if p.hasPrefix("/private/var/") {
            p.removeFirst("/private".count)
        }
        while p.count > 1 && p.hasSuffix("/") {
            p.removeLast()
        }
        return p
    }
}
