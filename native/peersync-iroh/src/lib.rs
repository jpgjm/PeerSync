//! PeerSync(Windows / iOS 共通)の最小 iroh FFI シム。
//!
//! Android 版 PeerSync は `computer.iroh:iroh-android:1.2.0`（iroh コア 1.2.0）を使う。
//! Windows 版も **同じ iroh コア（1.x）** を使うことでリレー/ハンドシェイクの
//! ワイヤプロトコルを一致させ、Android 端末と相互接続できるようにする。
//!
//! ここでは PeerSync が必要とする操作だけを C ABI として公開し、C# から P/Invoke する。
//! プロトコル（LIST/GET など）の処理は C# 側（Proto/*.cs）が行う。ここは iroh の
//! エンドポイント・接続・ストリームの薄いラッパに徹する。
//!
//! 本ファイルは iroh 1.x の公開 API に対して書かれている。cargo がコンパイル時に
//! 解決した実バージョンは Cargo.lock に固定される。万一 API 名が食い違う場合に
//! 触るのは下記の呼び出し箇所だけ（docs/build.md の「iroh API のずれ」を参照）:
//!   - Endpoint::builder / presets::N0 / Builder::{secret_key,alpns,relay_mode,bind}
//!   - Endpoint::{accept, connect, id, online}
//!   - Connection::{open_bi, accept_bi, close, close_reason, remote_id, paths}
//!   - Path::{is_selected, is_relay, is_ip, rtt}（経路表示 ps_conn_path_summary）
//!   - SendStream::{write_all, finish, reset} / RecvStream::read_exact
//!   - SecretKey::{from_bytes, to? , public}
//!   - RelayMode::Custom / RelayMap / RelayUrl / EndpointId / EndpointAddr / VarInt

use std::cell::RefCell;
use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::ptr;
use std::sync::Mutex;
use std::time::Duration;

use tokio::runtime::{Builder as RtBuilder, Handle, Runtime};
use tokio::sync::mpsc;

use iroh::endpoint::{presets, Connection, RecvStream, SendStream, VarInt};
use iroh::{Endpoint, EndpointAddr, EndpointId, RelayMap, RelayMode, SecretKey};

/// PeerSync のワイヤプロトコル ALPN。Android 版と一致させる。
const ALPN: &[u8] = b"peersync/0";

// ---------------------------------------------------------------------------
// エラー伝達（スレッドローカル）
// ---------------------------------------------------------------------------

thread_local! {
    static LAST_ERROR: RefCell<Option<CString>> = RefCell::new(None);
}

fn set_last_error(msg: impl Into<String>) {
    let s = msg.into();
    LAST_ERROR.with(|e| {
        *e.borrow_mut() = CString::new(s).ok();
    });
}

/// 直近のエラーメッセージを返す（呼び出し側が ps_string_free で解放）。無ければ null。
#[no_mangle]
pub extern "C" fn ps_last_error() -> *mut c_char {
    LAST_ERROR.with(|e| match e.borrow().as_ref() {
        Some(c) => c.clone().into_raw(),
        None => ptr::null_mut(),
    })
}

/// ps_* が返した文字列を解放する。
#[no_mangle]
pub extern "C" fn ps_string_free(s: *mut c_char) {
    if !s.is_null() {
        unsafe {
            drop(CString::from_raw(s));
        }
    }
}

fn to_cstring(s: impl Into<String>) -> *mut c_char {
    match CString::new(s.into()) {
        Ok(c) => c.into_raw(),
        Err(_) => ptr::null_mut(),
    }
}

/// *const c_char → String（null/不正は None）
unsafe fn cstr_to_string(p: *const c_char) -> Option<String> {
    if p.is_null() {
        return None;
    }
    CStr::from_ptr(p).to_str().ok().map(|s| s.to_owned())
}

/// パニックを握りつぶして sentinel を返す薄いラッパ。
fn guard_ptr<T>(f: impl FnOnce() -> *mut T) -> *mut T {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(p) => p,
        Err(_) => {
            set_last_error("内部パニック（Rust）");
            ptr::null_mut()
        }
    }
}

fn guard_i32(f: impl FnOnce() -> i32) -> i32 {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(v) => v,
        Err(_) => {
            set_last_error("内部パニック（Rust）");
            -99
        }
    }
}

// ---------------------------------------------------------------------------
// ハンドル型
// ---------------------------------------------------------------------------

pub struct PsNode {
    _rt: Runtime,
    handle: Handle,
    endpoint: Endpoint,
    accept_rx: Mutex<mpsc::UnboundedReceiver<Connection>>,
}

pub struct PsConn {
    conn: Connection,
    handle: Handle,
}

pub struct PsSend {
    send: SendStream,
    handle: Handle,
}

pub struct PsRecv {
    recv: RecvStream,
    handle: Handle,
}

// ---------------------------------------------------------------------------
// 秘密鍵・ID
// ---------------------------------------------------------------------------

/// 32 バイトの秘密鍵をランダム生成して out32 に書き込む。
#[no_mangle]
pub extern "C" fn ps_secret_generate(out32: *mut u8) -> i32 {
    guard_i32(|| {
        if out32.is_null() {
            set_last_error("out32 が null");
            return -1;
        }
        let mut buf = [0u8; 32];
        if getrandom::getrandom(&mut buf).is_err() {
            set_last_error("乱数生成に失敗");
            return -2;
        }
        // ed25519 の秘密鍵は 32 バイト任意。念のため SecretKey を通して正規化する。
        let sk = SecretKey::from_bytes(&buf);
        let bytes = sk.to_bytes();
        unsafe {
            ptr::copy_nonoverlapping(bytes.as_ptr(), out32, 32);
        }
        0
    })
}

/// 秘密鍵（32 バイト）から端末 ID 文字列を得る（ps_string_free で解放）。
#[no_mangle]
pub extern "C" fn ps_id_from_secret(secret32: *const u8, out_id: *mut *mut c_char) -> i32 {
    guard_i32(|| {
        if secret32.is_null() || out_id.is_null() {
            set_last_error("引数が null");
            return -1;
        }
        let mut buf = [0u8; 32];
        unsafe {
            ptr::copy_nonoverlapping(secret32, buf.as_mut_ptr(), 32);
        }
        let sk = SecretKey::from_bytes(&buf);
        let id = sk.public().to_string();
        unsafe {
            *out_id = to_cstring(id);
        }
        0
    })
}

// ---------------------------------------------------------------------------
// ノード（Endpoint）
// ---------------------------------------------------------------------------

fn start_inner(secret: [u8; 32], relay: Option<String>) -> Result<PsNode, String> {
    let rt = RtBuilder::new_multi_thread()
        .enable_all()
        .build()
        .map_err(|e| format!("tokio runtime: {e}"))?;
    let handle = rt.handle().clone();

    let sk = SecretKey::from_bytes(&secret);

    let (endpoint, rx) = handle.block_on(async move {
        let mut builder = Endpoint::builder(presets::N0)
            .secret_key(sk)
            .alpns(vec![ALPN.to_vec()]);

        if let Some(r) = relay {
            let r = r.trim();
            if !r.is_empty() {
                // iroh 1.4: RelayMap は &str を受ける try_from_iter で作る
                let map = RelayMap::try_from_iter([r])
                    .map_err(|e| format!("リレー URL が不正: {e}"))?;
                builder = builder.relay_mode(RelayMode::Custom(map));
            }
        }

        let endpoint = builder.bind().await.map_err(|e| format!("bind: {e}"))?;

        let (tx, rx) = mpsc::unbounded_channel::<Connection>();
        let ep = endpoint.clone();
        tokio::spawn(async move {
            accept_loop(ep, tx).await;
        });

        Ok::<(Endpoint, mpsc::UnboundedReceiver<Connection>), String>((endpoint, rx))
    })?;

    Ok(PsNode {
        _rt: rt,
        handle,
        endpoint,
        accept_rx: Mutex::new(rx),
    })
}

/// 受け付けループ。ALPN は Endpoint 側で既に絞られている（peersync/0 以外は拒否）。
/// ペアリング（許可リスト）判定は C# 側で行うため、ここでは接続を渡すだけ。
async fn accept_loop(endpoint: Endpoint, tx: mpsc::UnboundedSender<Connection>) {
    loop {
        match endpoint.accept().await {
            Some(incoming) => {
                let tx = tx.clone();
                // ハンドシェイク完了を待つ間に受け付けが止まらないよう、接続ごとに spawn
                tokio::spawn(async move {
                    let connecting = match incoming.accept() {
                        Ok(c) => c,
                        Err(_) => return,
                    };
                    if let Ok(conn) = connecting.await {
                        let _ = tx.send(conn);
                    }
                });
            }
            None => break, // エンドポイントが閉じた
        }
    }
}

/// ノードを起動する。secret32 は 32 バイト。relay_url は null または空文字で公開リレー。
#[no_mangle]
pub extern "C" fn ps_node_start(secret32: *const u8, relay_url: *const c_char) -> *mut PsNode {
    guard_ptr(|| {
        if secret32.is_null() {
            set_last_error("secret32 が null");
            return ptr::null_mut();
        }
        let mut secret = [0u8; 32];
        unsafe {
            ptr::copy_nonoverlapping(secret32, secret.as_mut_ptr(), 32);
        }
        let relay = unsafe { cstr_to_string(relay_url) };
        match start_inner(secret, relay) {
            Ok(node) => Box::into_raw(Box::new(node)),
            Err(e) => {
                set_last_error(e);
                ptr::null_mut()
            }
        }
    })
}

/// この端末の ID 文字列（ps_string_free で解放）。
#[no_mangle]
pub extern "C" fn ps_node_id(node: *mut PsNode) -> *mut c_char {
    guard_ptr(|| {
        let node = match unsafe { node.as_ref() } {
            Some(n) => n,
            None => return ptr::null_mut(),
        };
        to_cstring(node.endpoint.id().to_string())
    })
}

/// リレーに接続できるまで待つ。1=オンライン, 0=タイムアウト, <0=エラー。
#[no_mangle]
pub extern "C" fn ps_node_wait_online(node: *mut PsNode, timeout_ms: u64) -> i32 {
    guard_i32(|| {
        let node = match unsafe { node.as_ref() } {
            Some(n) => n,
            None => return -1,
        };
        let ok = node.handle.block_on(async {
            tokio::time::timeout(Duration::from_millis(timeout_ms), node.endpoint.online())
                .await
                .is_ok()
        });
        if ok {
            1
        } else {
            0
        }
    })
}

/// 次の受信接続を待つ（ブロッキング）。shutdown でチャネルが閉じると null。
#[no_mangle]
pub extern "C" fn ps_node_accept(node: *mut PsNode) -> *mut PsConn {
    guard_ptr(|| {
        let node = match unsafe { node.as_ref() } {
            Some(n) => n,
            None => return ptr::null_mut(),
        };
        let mut rx = match node.accept_rx.lock() {
            Ok(g) => g,
            Err(_) => return ptr::null_mut(),
        };
        let conn = node.handle.block_on(async { rx.recv().await });
        match conn {
            Some(conn) => Box::into_raw(Box::new(PsConn {
                conn,
                handle: node.handle.clone(),
            })),
            None => ptr::null_mut(),
        }
    })
}

/// peer_id へ接続する。
#[no_mangle]
pub extern "C" fn ps_node_connect(node: *mut PsNode, peer_id: *const c_char) -> *mut PsConn {
    guard_ptr(|| {
        let node = match unsafe { node.as_ref() } {
            Some(n) => n,
            None => return ptr::null_mut(),
        };
        let id_str = match unsafe { cstr_to_string(peer_id) } {
            Some(s) => s,
            None => {
                set_last_error("peer_id が null");
                return ptr::null_mut();
            }
        };
        let id: EndpointId = match id_str.parse() {
            Ok(i) => i,
            Err(e) => {
                set_last_error(format!("端末 ID が不正: {e}"));
                return ptr::null_mut();
            }
        };
        let addr = EndpointAddr::from(id);
        let res = node
            .handle
            .block_on(async { node.endpoint.connect(addr, ALPN).await });
        match res {
            Ok(conn) => Box::into_raw(Box::new(PsConn {
                conn,
                handle: node.handle.clone(),
            })),
            Err(e) => {
                set_last_error(format!("connect: {e}"));
                ptr::null_mut()
            }
        }
    })
}

/// ノードを停止して解放する。
#[no_mangle]
pub extern "C" fn ps_node_shutdown(node: *mut PsNode) {
    if node.is_null() {
        return;
    }
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let node = unsafe { Box::from_raw(node) };
        let ep = node.endpoint.clone();
        node.handle.block_on(async move {
            ep.close().await;
        });
        drop(node);
    }));
}

// ---------------------------------------------------------------------------
// 接続
// ---------------------------------------------------------------------------

/// 相手の端末 ID 文字列（ps_string_free で解放）。
#[no_mangle]
pub extern "C" fn ps_conn_remote_id(conn: *mut PsConn) -> *mut c_char {
    guard_ptr(|| {
        let conn = match unsafe { conn.as_ref() } {
            Some(c) => c,
            None => return ptr::null_mut(),
        };
        to_cstring(conn.conn.remote_id().to_string())
    })
}

/// 新しい双方向ストリームを開く（送信側は send、受信側は recv）。
#[no_mangle]
pub extern "C" fn ps_conn_open_bi(
    conn: *mut PsConn,
    out_send: *mut *mut PsSend,
    out_recv: *mut *mut PsRecv,
) -> i32 {
    guard_i32(|| {
        let conn = match unsafe { conn.as_ref() } {
            Some(c) => c,
            None => return -1,
        };
        if out_send.is_null() || out_recv.is_null() {
            return -1;
        }
        let res = conn.handle.block_on(async { conn.conn.open_bi().await });
        match res {
            Ok((send, recv)) => {
                unsafe {
                    *out_send = Box::into_raw(Box::new(PsSend {
                        send,
                        handle: conn.handle.clone(),
                    }));
                    *out_recv = Box::into_raw(Box::new(PsRecv {
                        recv,
                        handle: conn.handle.clone(),
                    }));
                }
                0
            }
            Err(e) => {
                set_last_error(format!("open_bi: {e}"));
                -2
            }
        }
    })
}

/// 次の受信双方向ストリームを受け付ける。接続が閉じていれば負値。
#[no_mangle]
pub extern "C" fn ps_conn_accept_bi(
    conn: *mut PsConn,
    out_send: *mut *mut PsSend,
    out_recv: *mut *mut PsRecv,
) -> i32 {
    guard_i32(|| {
        let conn = match unsafe { conn.as_ref() } {
            Some(c) => c,
            None => return -1,
        };
        if out_send.is_null() || out_recv.is_null() {
            return -1;
        }
        let res = conn.handle.block_on(async { conn.conn.accept_bi().await });
        match res {
            Ok((send, recv)) => {
                unsafe {
                    *out_send = Box::into_raw(Box::new(PsSend {
                        send,
                        handle: conn.handle.clone(),
                    }));
                    *out_recv = Box::into_raw(Box::new(PsRecv {
                        recv,
                        handle: conn.handle.clone(),
                    }));
                }
                0
            }
            Err(_) => -2, // 接続が閉じた（通常の終了）
        }
    })
}

/// 接続が閉じているか。1=閉, 0=開。
#[no_mangle]
pub extern "C" fn ps_conn_is_closed(conn: *mut PsConn) -> i32 {
    guard_i32(|| {
        let conn = match unsafe { conn.as_ref() } {
            Some(c) => c,
            None => return 1,
        };
        if conn.conn.close_reason().is_some() {
            1
        } else {
            0
        }
    })
}

/// 接続を閉じる。
#[no_mangle]
pub extern "C" fn ps_conn_close(conn: *mut PsConn, code: u32, reason: *const c_char) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let conn = match unsafe { conn.as_ref() } {
            Some(c) => c,
            None => return,
        };
        let reason = unsafe { cstr_to_string(reason) }.unwrap_or_default();
        conn.conn
            .close(VarInt::from_u32(code), reason.as_bytes());
    }));
}

/// 選択中の経路を "<kind>|<rtt_ms>" 形式の文字列で返す（ps_string_free で解放）。
/// kind: direct（IP 直接）/ relay（リレー経由）/ other / none（選択中の経路なし・切断）。
/// 例: "direct|12", "relay|85"。表示文言の組み立ては呼び出し側（Swift / C#）で行う。
/// iroh 1.4: Connection::paths() のスナップショットから is_selected な Path を探す。
#[no_mangle]
pub extern "C" fn ps_conn_path_summary(conn: *mut PsConn) -> *mut c_char {
    guard_ptr(|| {
        let conn = match unsafe { conn.as_ref() } {
            Some(c) => c,
            None => return ptr::null_mut(),
        };
        if conn.conn.close_reason().is_some() {
            return to_cstring("none|0");
        }
        let paths = conn.conn.paths();
        let mut selected: Option<(&'static str, u128)> = None;
        for p in paths.iter() {
            if p.is_selected() {
                let kind = if p.is_relay() {
                    "relay"
                } else if p.is_ip() {
                    "direct"
                } else {
                    "other"
                };
                selected = Some((kind, p.rtt().as_millis()));
                break;
            }
        }
        match selected {
            Some((kind, rtt)) => to_cstring(format!("{kind}|{rtt}")),
            None => to_cstring("none|0"),
        }
    })
}

/// 接続ハンドルを解放する（接続自体は閉じない）。
#[no_mangle]
pub extern "C" fn ps_conn_free(conn: *mut PsConn) {
    if !conn.is_null() {
        unsafe {
            drop(Box::from_raw(conn));
        }
    }
}

// ---------------------------------------------------------------------------
// ストリーム
// ---------------------------------------------------------------------------

/// 送信ストリームに全バイト書き込む。
#[no_mangle]
pub extern "C" fn ps_send_write(send: *mut PsSend, buf: *const u8, len: usize) -> i32 {
    guard_i32(|| {
        let send = match unsafe { send.as_mut() } {
            Some(s) => s,
            None => return -1,
        };
        if buf.is_null() && len != 0 {
            return -1;
        }
        let slice = unsafe { std::slice::from_raw_parts(buf, len) };
        let handle = send.handle.clone();
        let res = handle.block_on(async { send.send.write_all(slice).await });
        match res {
            Ok(()) => 0,
            Err(e) => {
                set_last_error(format!("write_all: {e}"));
                -2
            }
        }
    })
}

/// 送信ストリームを正常終了する。
#[no_mangle]
pub extern "C" fn ps_send_finish(send: *mut PsSend) -> i32 {
    guard_i32(|| {
        let send = match unsafe { send.as_mut() } {
            Some(s) => s,
            None => return -1,
        };
        match send.send.finish() {
            Ok(()) => 0,
            Err(e) => {
                set_last_error(format!("finish: {e}"));
                -2
            }
        }
    })
}

/// 送信ストリームをリセット（相手の読み出しをエラーで終わらせる）。
#[no_mangle]
pub extern "C" fn ps_send_reset(send: *mut PsSend, code: u64) -> i32 {
    guard_i32(|| {
        let send = match unsafe { send.as_mut() } {
            Some(s) => s,
            None => return -1,
        };
        match send.send.reset(VarInt::from_u64(code).unwrap_or(VarInt::from_u32(1))) {
            Ok(()) => 0,
            Err(e) => {
                set_last_error(format!("reset: {e}"));
                -2
            }
        }
    })
}

/// 送信ストリームを解放する。
#[no_mangle]
pub extern "C" fn ps_send_free(send: *mut PsSend) {
    if !send.is_null() {
        unsafe {
            drop(Box::from_raw(send));
        }
    }
}

/// 受信ストリームから正確に len バイト読む。0=成功, <0=EOF/エラー。
#[no_mangle]
pub extern "C" fn ps_recv_read_exact(recv: *mut PsRecv, buf: *mut u8, len: usize) -> i32 {
    guard_i32(|| {
        let recv = match unsafe { recv.as_mut() } {
            Some(r) => r,
            None => return -1,
        };
        if len == 0 {
            return 0;
        }
        if buf.is_null() {
            return -1;
        }
        let slice = unsafe { std::slice::from_raw_parts_mut(buf, len) };
        let handle = recv.handle.clone();
        let res = handle.block_on(async { recv.recv.read_exact(slice).await });
        match res {
            Ok(()) => 0,
            Err(e) => {
                set_last_error(format!("read_exact: {e}"));
                -2
            }
        }
    })
}

/// 受信ストリームを解放する。
#[no_mangle]
pub extern "C" fn ps_recv_free(recv: *mut PsRecv) {
    if !recv.is_null() {
        unsafe {
            drop(Box::from_raw(recv));
        }
    }
}
