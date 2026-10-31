# 構成と設計

## 全体像
```
PeerSync_Project/
  .github/workflows/build-ipa.yml   汎用ビルドワークフロー（プロジェクト非依存）
  project.yml                       XcodeGen 定義（Rust の preBuildScripts を含む）
  PeerSync.entitlements             ad-hoc 署名用（空）
  Source/
    PeerSyncApp.swift               エントリポイント・scenePhase 処理
    Proto/                          ワイヤプロトコル（iroh 非依存、Android/Windows 版からの移植）
      Wire.swift                    符号化/復号・WireEncoder/WireDecoder/Codec
      ShareSource.swift             共有フォルダ読み出し口（security-scoped URL のフォルダ）
      FileServer.swift              公開側ハンドラ（LIST/GET、.. 拒否、隠しファイル非表示）
      RemoteClient.swift            取得側（LIST / GET、途中再開）
    Net/IrohNode.swift              受け付けループ・接続再利用・許可リスト判定（FFI 経由）
    Data/                           Settings / Transfers
    Services/                       Logger / PairingCode / BackgroundKeepAlive /
                                    BackgroundDownloadTask（BGContinuedProcessingTask 8 枠）/
                                    RuntimeEnvironment（LiveContainer 判定・ファイルアプリ表記）
    UI/                             HomeView / BrowseView / QRViews / LogView
    Support/PeerSync-Bridging-Header.h   C ヘッダの取り込み
  native/peersync-iroh/             iroh 1.x を直接使う最小 FFI（Rust, staticlib → libpeersync_iroh.a）
    include/peersync_iroh.h         C ヘッダ（Swift から参照）
    src/lib.rs                      Windows 版と同一（ALPN peersync/0、C ABI）
  native/vendor/netdev/             netdev 0.46.3 の iOS 修正版（[patch.crates-io] で差し替え）
  native/vendor/netwatch/           netwatch 0.19.3 の iOS 修正版（同上）
  Resources/Info.plist              XcodeGen が生成（空の雛形のみ）
  docs/
```

## 通信コアに iroh の Rust を使う理由（Android / Windows 互換）
Android 版は `computer.iroh:iroh-android:1.2.0`（iroh コア **1.2.0**）、Windows 版は
自作 Rust crate（iroh 1.x）で動く。iroh は 0.91 と 1.0 でリレーのワイヤプロトコルに
破壊的変更があり、新旧クライアントは相互接続できない。したがって iOS 版も
**iroh コアのメジャー系列（1.x）を一致**させる必要がある。

iroh の公式 Swift バインディング（iroh-ffi）は旧 0.x 系ベースで 1.x と相互接続できる
保証がない。そこで iOS 版は **Windows 版と同じ Rust crate（`native/peersync-iroh`）を
そのまま流用**し、`staticlib`（libpeersync_iroh.a）としてビルドして Swift から
C ABI を直接呼ぶ。`src/lib.rs` は Windows 版と同一ファイル（差分は Cargo.toml の
crate-type のみ）なので、今後の修正は両プラットフォームにコピーで展開できる。

## FFI 境界
Windows 版 `docs/architecture.md` と同一:
- ノード: `ps_node_start / ps_node_id / ps_node_wait_online / ps_node_accept / ps_node_connect / ps_node_shutdown`
- 接続: `ps_conn_remote_id / ps_conn_open_bi / ps_conn_accept_bi / ps_conn_is_closed / ps_conn_close / ps_conn_free / ps_conn_path_summary`
- ストリーム: `ps_send_write / ps_send_finish / ps_send_reset / ps_send_free / ps_recv_read_exact / ps_recv_free`
- 鍵: `ps_secret_generate / ps_id_from_secret`

ALPN `peersync/0` は Rust 側で設定し、未対応 ALPN は iroh が拒否する。
**ペアリング（許可リスト）判定は Swift 側**（受信接続の相手 ID を取り出し、
未登録なら `ps_conn_close(403)`）。Android 版 `IrohNode.kt` と同じ流れ。

FFI はすべてブロッキング。Swift 側は `DispatchQueue.global()` に退避して呼び、
UI 更新は `DispatchQueue.main` でマーシャリングする。`ps_last_error()` は
スレッドローカルなので、エラー取得は FFI を呼んだ同じスレッドで行っている。

## プロトコル（ALPN `peersync/0`）
1 リクエスト = 1 本の双方向ストリーム。数値はビッグエンディアン、str は u16 長 + UTF-8。
- `LIST path` → エントリ一覧（名前・フォルダか・サイズ・更新日時）
- `GET path offset` → 全体サイズ・残りサイズ＋生データ（256 KiB 単位）
- 共有フォルダの外（`..` 等）は拒否。ドットファイルは非表示。`.peersync-part` は一覧から除外。

更新日時は Unix エポックミリ秒（UTC）。Android 版（`File.lastModified()`）・
Windows 版と揃えてある。

## Android / Windows 固有機能の iOS 置き換え
| Android / Windows | iOS 版 |
|---|---|
| SAF のディレクトリ選択 / FolderPicker | `fileImporter`（UIDocumentPicker）＋ security-scoped bookmark |
| フォアグラウンドサービス / トレイ常駐 | なし（フォアグラウンド中心。任意で無音オーディオ延命） |
| システム通知（進捗・キャンセル） | アプリ内の転送一覧（進捗バー・キャンセル） |
| QR 表示＋カメラ読み取り | Core Image で QR 生成＋AVFoundation で読み取り |
| 受信先 Download/PeerSync / Downloads\PeerSync | `Documents/PeerSync/<相手名>/<相手側パス>`（「ファイル」アプリから見える） |

## Rust のビルド（Mac レス）
GitHub Actions の macOS ランナーには rustup / cargo がプリインストールされている。
`project.yml` の `preBuildScripts` が xcodebuild の中で
`rustup target add aarch64-apple-ios` と `cargo build --release --target aarch64-apple-ios`
を実行し、`LIBRARY_SEARCH_PATHS` + `OTHER_LDFLAGS: -lpeersync_iroh` で静的リンクする。
このため **汎用 build-ipa.yml に Rust 用の変更は不要**（仕様 8）。
`ENABLE_USER_SCRIPT_SANDBOXING=NO` は cargo がネットワーク（crates.io）と
`~/.cargo` に触るために必要。

## 経路表示（ps_conn_path_summary）
`Connection::paths()` のスナップショットから `is_selected()` な Path を取り、
`"<kind>|<rtt_ms>"`（kind: direct / relay / other / none）を返す。文言への変換は
Swift（`IrohNode.formatPathSummary`）/ C# 側で行う。Android 版と同じく、表示できるのは
**こちらから張った接続**の経路のみ。
