# ビルド手順（Mac レス / GitHub Actions）

## 流れ
1. このリポジトリ（`PeerSync_Project/` の中身がリポジトリ直下）を GitHub に push する。
2. `Build IPA` ワークフローが走る（push または手動実行）。
   - `xcodegen generate` で `PeerSync.xcodeproj` を生成
   - xcodebuild の **preBuildScripts**（project.yml 定義）が Rust をビルド:
     - `rustup target add aarch64-apple-ios`（初回のみ）
     - `cargo build --release --target aarch64-apple-ios`
       → `native/peersync-iroh/target/aarch64-apple-ios/release/libpeersync_iroh.a`
   - 未署名でビルド → ad-hoc 署名（`PeerSync.entitlements`）→ IPA 化
3. Artifacts から IPA をダウンロードし、SideStore でインストールする。

初回ビルドは cargo が iroh 一式をコンパイルするため **+5〜10 分程度**かかる
（ランナーはキャッシュを持たないため毎回）。遅さが問題になったら、ワークフローに
`~/.cargo` と `native/peersync-iroh/target` のキャッシュを足す案がある（要相談・仕様 8）。

## netdev / netwatch の差し替え（iOS ビルド修正）
`native/peersync-iroh/Cargo.toml` の `[patch.crates-io]` で、次の2つを `native/vendor/` の
コピーに差し替えている。原因はどちらも「`libc` が iOS では定義していない macOS 用の
型・定数を参照している」こと。
- `netdev` 0.46.3: `src/os/darwin/ipv6_addr_flags.rs` が `libc::in6_ifreq` を使う → 同じ
  レイアウトの構造体をローカル定義。
- `netwatch` 0.19.3: `<net/route.h>` の `RTM_*` / `RTAX_*` / `RTA_IFP` / `RTF_*` /
  `RTM_VERSION` を `libc::` から参照 → `src/route_consts.rs` をローカル定義し参照先を変更。
差分は「[PeerSync patch]」で grep できる箇所だけ。将来 netdev / netwatch（または libc）が
iOS 対応したら、`[patch.crates-io]` と `native/vendor/` を消すだけで元に戻せる。

手元で確認する場合（Mac 不要、Linux でも可）: `cargo check --release --target aarch64-apple-ios`
（ring の C コンパイルで `xcrun` が要るため、Linux では xcrun / SDK ヘッダのダミーが必要）。

## Cargo.lock
初回ビルドで生成される `Cargo.lock` を**コミットして iroh の版を固定**すること
（CI のログ、または手元の Rust 環境で `cargo generate-lockfile` でも作れる。
Windows 版と同様、再現性と Android 版（iroh 1.2.0）との系列一致のため）。

## iroh の API がずれてビルドが通らないとき
`native/peersync-iroh/src/lib.rs` は **Windows 版と同一ファイル**。iroh 1.x の公開 API に
対して書かれており、食い違ったときに触る箇所は lib.rs 冒頭コメントに列挙してある。
修正したら Windows 版の `native/peersync-iroh/src/lib.rs` にも同じ修正を反映すること。

## 動作検証（仕様 6）
- 本版の Info.plist に installd がキャッシュする項目（`BGTaskSchedulerPermittedIdentifiers` 等）は
  **含まれていない**ため、上書きインストールで検証してよい。
- 2 台（iOS ⇔ Android / iOS ⇔ Windows）で**双方向**にペアリングし、共有フォルダを
  設定してから閲覧・取得を試す。

## ビルドが失敗したら（仕様 10）
Actions の xcodebuild ログ（失敗時は `xcodebuild-log` Artifact にも保存される）を
添付してください。ログから原因を特定します。preBuildScripts（cargo）の出力も
同じログに含まれます。
