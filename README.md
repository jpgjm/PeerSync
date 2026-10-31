# PeerSync for iOS（v0.1.10）

iPhone / iPad から、**同じ Wi-Fi にいなくても** PeerSync（Android / Windows）端末の共有フォルダを
閲覧・コピーするためのアプリ。通信は [iroh](https://www.iroh.computer)（QUIC＋ホールパンチ＋リレー）。
Swift / SwiftUI、iOS/iPadOS 26.0 以上。Mac レス（GitHub Actions + XcodeGen + SideStore）。

## この版でできること
| 機能 | 状態 |
|---|---|
| 端末 ID（iroh の公開鍵）の生成・保存、QR 表示 | ✅ |
| QR 読み取り・手入力でペアリング（許可リスト。未登録の端末は拒否） | ✅ |
| 共有フォルダを「ファイル」のフォルダ選択で指定（security-scoped bookmark） | ✅ |
| 受信ファイルの保存先を「アプリ内 / カスタム（Files で選んだフォルダ）」から選択 | ✅ |
| 相手の共有フォルダの閲覧（フォルダ移動） | ✅ |
| 単発コピー（進捗はアプリ内の転送一覧。キャンセル可。途中で切れても続きから再開） | ✅ |
| 自前リレーへの切り替え（URL 指定） | ✅ |
| 取得中のバックグラウンド継続（BGContinuedProcessingTask、1 ファイル = 1 タスク。LiveContainer 内は LiveContainer-bgtask フォークで可） | ✅ |
| 無音オーディオによるバックグラウンド延命（任意・スイッチ） | ✅ |
| 常に待ち受け（常駐） | ❌ iOS の制約で不可（docs/limitations.md） |
| 接続経路の表示（● 直接 / リレー、RTT） | ✅ |

## 相互接続
Android 版（iroh 1.2.0）・Windows 版（iroh 1.x / Rust FFI）と同一のワイヤプロトコル
（ALPN `peersync/0`）・同一の iroh 1.x 系列。ペアリングは双方向の手動登録。

詳細は `docs/` を参照。

## 変更履歴
### 0.1.10
- リポジトリ公開向け: `project.yml` の `com.kdt.livecontainer.*` のコメントを英語にし、実測結果
  （`register()` は完全一致のみ。ワイルドカード単体では通らず、LiveContainer-bgtask 側のマージで通る）
  に合わせて書き直した。コード・plist の値は変更なし。

### 0.1.9
- LiveContainer 内でホストの bundle ID を取れていなかった（v0.1.8 ログ: `_NSGetExecutablePath` も
  LC がゲストのパスに差し替える）。`+[NSUserDefaults lcMainBundle]`（LC が `NSBundle.mainBundle` を
  差し替える前に保存した LiveContainer.app の NSBundle）から取るように変更。予備として dyld の
  イメージ一覧から `/LiveContainer.app/` を探す。ログに取得元と path を出す。

### 0.1.8
- v0.1.7 のビルド失敗を修正: `_NSGetExecutablePath` が iOS の Swift から見えない
  （`cannot find '_NSGetExecutablePath' in scope`）。ブリッジングヘッダに `<mach-o/dyld.h>` を追加。
  機能は v0.1.7 と同じ。

### 0.1.7
- LiveContainer 内でも BGContinuedProcessingTask を使えるようにした（要 LiveContainer-bgtask フォーク）。
  ゲストはホストのプロセスで動くため、システムは**ホスト（LiveContainer.app）の Info.plist と
  bundle ID** で識別子を検証する。ゲストは `_NSGetExecutablePath` でホストの .app を見つけて
  bundle ID を読み、`<ホスト bundle ID>.transfer.N` で register / submit する。
  ログにホストの plist（UIBackgroundModes / 許可識別子）と枠 0 の `register()` 結果を出す。
- ゲストの Info.plist に `com.kdt.livecontainer.*` を追加（register() のローカル照合がゲスト側の
  plist を見る場合の保険。SideStore 直接インストールには不要なので、v0.1.6 の直接インストールは
  そのままで良い。入れ替えるなら仕様 6 の手順）。

### 0.1.6
- **要アンインストール→端末再起動→再インストール**（仕様 6）: Info.plist に
  `BGTaskSchedulerPermittedIdentifiers` と `UIBackgroundModes: processing` を追加したため。
  アンインストールで `settings.json` が消え、**端末 ID（秘密鍵）が変わる**ので、相手側（Android /
  Windows）で古い iPad を削除して再ペアリングすること。共有フォルダ・保存先の設定も選び直し。
- 取得を `BGContinuedProcessingTask`（iOS 26）で保護。QSProbe の 2 段構え（beginBackgroundTask
  約 30 秒 → 継続実行タスク）を 1 ファイル = 1 タスクにしたもの（`Services/BackgroundDownloadTask.swift`）。
  識別子は `<bundleId>.transfer.0`〜`.7` の 8 枠を回す（同時 9 件目以降は 1 段目のみ）。
  システムの進捗バナーに「ファイルを受信中 / <ファイル名> x MB / y MB」が出る。
  OS に打ち切られた取得はキャンセル扱い（途中ファイルは残り、次回は続きから）。
  LiveContainer 内では許可識別子がホストの plist を参照するため 2 段目は使わない（約 30 秒のみ）。
- 保存先の表示を AlarmClock 方式の 2 行に変更: 「ファイル」アプリでの場所（このデバイス内 /
  PeerSync/PeerSync/…、LC なら LiveContainer/Data/Application/<UUID>/…）と実際のパス
  （`/var/mobile/Containers/…`）。カスタムフォルダは実パスのみ（ファイルアプリ表記は導出不可）。
  判定は新設の `Services/RuntimeEnvironment.swift`（`LC_HOME_PATH`）。

### 0.1.5
- 「保存先」欄を新設（「共有フォルダ」の直下）。「アプリ内（PeerSync）」と「カスタム」をチェックマークで
  選択。カスタムは Files のフォルダ選択（security-scoped bookmark、共有フォルダと同じ仕組み）。
  保存先の構造は `<保存先>/<相手名>/<相手側のパス>`（Android 版と同じ）。
- 「受信フォルダを「ファイル」で開く」を保存先欄へ移動。カスタム時も現在の保存先を開こうとする
  （開けないフォルダもある。失敗はログに記録）。
- カスタム保存先が未選択・アクセス不可のまま取得すると「保存先にアクセスできません」ダイアログ
  （フォルダを選び直す / アプリ内に保存する / キャンセル）。選び直し・切り替え後はその取得を再開する。
- 旧版の保存先（Documents/PeerSync）はそのまま。移動・削除はしない（仕様 2）。

### 0.1.4
- 同じファイルの取得を連打すると `.peersync-part` を共有して片方の移動が失敗していた。同じ相手・同じ
  パスの取得が進行中なら二重に開始しない（ログに `download already running, ignored`）。
- 「受信フォルダを「ファイル」で開く」ボタンを追加（`shareddocuments://`）。LiveContainer 内で
  実行中は保存先が LiveContainer > Data > Application > (UUID) > Documents > PeerSync になるため、
  その旨を注意文に表示する（docs/limitations.md）。

### 0.1.3
- 「相手」の行をタップすると QR リーダーが開く不具合を修正。`.navigationDestination` を `Section` に
  付けていたためセクション全体が 1 行になり、行内の全ボタンが同時に発火していた。List 側へ移動。
  ヘッダー・フッター・各ボタンも通常の行として表示されるようになる。

### 0.1.2
- iOS ビルド失敗の修正（2件目）: `netwatch 0.19.3` が `<net/route.h>` の定数（`RTM_*` / `RTAX_*` /
  `RTA_IFP` / `RTF_*` / `RTM_VERSION`）を `libc` から取っていたが、`libc` は iOS でそれらを定義しない。
  定数をローカル定義（`src/route_consts.rs`）したコピーを `native/vendor/netwatch` に置き、
  `[patch.crates-io]` で差し替え。値は XNU で macOS と共通。
- `Cargo.lock` を同梱（iroh 1.4.1 に固定）。
- 納品前に `cargo check --target aarch64-apple-ios` で依存一式（iroh 1.4.1 含む）の型チェックが
  通ることを確認済み（リンクと ring のアセンブリは CI の実 SDK で初めて検証される）。

### 0.1.1
- iOS ビルド失敗の修正: 依存 `netdev 0.46.3` が macOS 専用の `libc::in6_ifreq` を iOS でも参照して
  コンパイルに失敗していた。darwin 用コードだけ直したコピーを `native/vendor/netdev` に置き、
  `[patch.crates-io]` で差し替え（docs/build.md）。
- 経路表示（● 直接 / リレー、RTT）を追加。FFI に `ps_conn_path_summary` を追加し（`lib.rs` は
  Windows 版と共通）、閲覧画面の右上に 2 秒ごとに表示する。

### 0.1.0
- 初版。
