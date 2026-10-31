# 既知の制限・注意

## iOS 固有の制約
- **取得のバックグラウンド継続は BGContinuedProcessingTask**（iOS 26）。前面のタップを起点に
  申請し、システムが進捗バナーを出す。8 枠（`.transfer.0`〜`.7`）を超える同時取得は
  beginBackgroundTask の約 30 秒のみ。LiveContainer 内では、ホスト側の
  Info.plist に processing と識別子がある LiveContainer-bgtask フォークでのみ 2 段目が使える
  （素の LiveContainer では約 30 秒のみ）。LiveProcess（マルチタスク）内は常に約 30 秒のみ。
  `BGTaskSchedulerPermittedIdentifiers` は installd がキャッシュするため、変更時は
  アンインストール→再起動→再インストールが必要で、そのとき端末 ID が変わる（仕様 6）。
- **「常に待ち受け」は不可**。iOS には Android のフォアグラウンドサービス相当がなく、
  バックグラウンドでは数十秒でサスペンドされる。待ち受けは基本的に
  **アプリを開いている間だけ**。補助として:
  - 「画面を消さない」（`isIdleTimerDisabled`）: アプリを開いたまま画面が自動ロックされない。
  - 「無音オーディオで延命」: 無音を再生し続けることで画面を消しても動き続ける
    （QSProbe と同じ手法。バッテリーを消費する。SideStore 配布前提の裏技で、
    App Store 審査では認められない）。
- 受信ファイルの保存先は「アプリ内」（`Documents/PeerSync/<相手名>/<相手側パス>`。「ファイル」>
  このiPhone内 > PeerSync）または「カスタム」（Files で選んだフォルダ `<選んだフォルダ>/<相手名>/…`）。
  写真ライブラリへの直接保存はしない。カスタム保存先は security-scoped bookmark で保持するため、
  フォルダが削除・移動されるとアクセスできなくなる（取得時にダイアログで選び直せる）。
- 「受信フォルダを「ファイル」で開く」は `shareddocuments://` スキーム。アプリ内の Documents は
  確実に開けるが、他アプリ配下や iCloud Drive のフォルダは開けないことがある（ログに記録）。
- 共有フォルダは「ファイル」のフォルダ選択（security-scoped bookmark）。iCloud Drive や
  他アプリ提供のフォルダも選べるが、プロバイダ次第で列挙・読み出しが遅い／失敗することがある。
  確実なのは「このiPhone内」のフォルダ。

## LiveContainer 内で実行する場合
- LiveContainer はゲストの Documents を自分のコンテナ内（`…/LiveContainer/Documents/Data/
  Application/<UUID>/Documents/`）に置くため、「ファイル」アプリでは **このiPhone内 >
  LiveContainer > Data > Application > <UUID> > Documents > PeerSync** に見える（「PeerSync」
  フォルダは直下に出ない）。ホーム画面の「受信フォルダを「ファイル」で開く」で直接開ける。
- 端末 ID（秘密鍵）は直接インストールと LiveContainer で**別々**になる（設定の保存先が違うため）。
  両方で使う場合は相手側にそれぞれペアリングが必要。

## Android / Windows 版からの意図的な差異
- 経路表示は **送信側（こちらから張った接続）のみ**。相手から張られた接続の経路は表示しない
  （Android 版と同じ）。
- **ペアリングは双方向の手動登録**（全プラットフォーム共通）。片方だけだと
  「取得できませんでした」になる。
- 転送の進捗はシステム通知ではなくアプリ内の「転送」欄（iOS の通知は進捗更新に不向き）。

## 相互接続（iOS ⇔ Android ⇔ Windows）
- 相互接続には **iroh コアのメジャー系列（1.x）一致**が前提（`architecture.md`）。
  Android 版は iroh 1.2.0。iOS 版は `Cargo.lock` に固定された 1.x を使う。
- 公開リレー（n0）は無料だが保証はない。安定運用するなら自前リレー（iroh-relay）を
  立てて URL を設定する。
- 起動直後はリレー接続まで数秒かかる。

## データ・保存
- 設定・ログは `Application Support/PeerSync/`、受信ファイルは `Documents/PeerSync/`。
- **データ移行はしない**（仕様 2）。保存場所を変えても旧データの移動・削除は行わない。
- 秘密鍵（= 端末 ID）は settings.json に保存。**アプリを削除すると ID が変わる**ので、
  再インストール後は相手側で再ペアリングが必要（SideStore の 7 日再署名は
  上書きインストールなので ID は保たれる）。
- 途中再開は「受信側の途中ファイル（`.peersync-part`）のサイズ」から続きを送るだけ。
  元ファイルが同サイズ以上で差し替わると壊れる可能性がある（同期段階でハッシュ照合を入れる予定）。

## 無料 Personal Team / SideStore
- 特別な entitlement は使わない（カメラは Info.plist の利用目的文のみ）。
- App Extension なし（App ID は 1 枠）。
- Bundle ID は SideStore がインストール時にチーム ID を付加するため、コード内で
  Bundle ID を直書きしていない。

## この版で未実装（次の段階）
- 常時同期（インデックス交換・差分取得）、送信のみ/受信のみ、競合処理。
