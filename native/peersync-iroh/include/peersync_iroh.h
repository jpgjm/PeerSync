/* PeerSync iroh FFI の C ヘッダ。src/lib.rs の #[no_mangle] 関数と1対1対応。
 * Swift からはブリッジングヘッダ経由で参照する。 */
#ifndef PEERSYNC_IROH_H
#define PEERSYNC_IROH_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct PsNode PsNode;
typedef struct PsConn PsConn;
typedef struct PsSend PsSend;
typedef struct PsRecv PsRecv;

/* 直近のエラーメッセージ（ps_string_free で解放）。無ければ NULL。 */
char *ps_last_error(void);
void ps_string_free(char *s);

/* 鍵・ID */
int32_t ps_secret_generate(uint8_t *out32);
int32_t ps_id_from_secret(const uint8_t *secret32, char **out_id);

/* ノード */
PsNode *ps_node_start(const uint8_t *secret32, const char *relay_url);
char *ps_node_id(PsNode *node);
int32_t ps_node_wait_online(PsNode *node, uint64_t timeout_ms);
PsConn *ps_node_accept(PsNode *node);
PsConn *ps_node_connect(PsNode *node, const char *peer_id);
void ps_node_shutdown(PsNode *node);

/* 接続 */
char *ps_conn_remote_id(PsConn *conn);
int32_t ps_conn_open_bi(PsConn *conn, PsSend **out_send, PsRecv **out_recv);
int32_t ps_conn_accept_bi(PsConn *conn, PsSend **out_send, PsRecv **out_recv);
int32_t ps_conn_is_closed(PsConn *conn);
void ps_conn_close(PsConn *conn, uint32_t code, const char *reason);
/* 選択中の経路 "<kind>|<rtt_ms>"（kind: direct/relay/other/none）。ps_string_free で解放。 */
char *ps_conn_path_summary(PsConn *conn);
void ps_conn_free(PsConn *conn);

/* ストリーム */
int32_t ps_send_write(PsSend *send, const uint8_t *buf, size_t len);
int32_t ps_send_finish(PsSend *send);
int32_t ps_send_reset(PsSend *send, uint64_t code);
void ps_send_free(PsSend *send);
int32_t ps_recv_read_exact(PsRecv *recv, uint8_t *buf, size_t len);
void ps_recv_free(PsRecv *recv);

#ifdef __cplusplus
}
#endif

#endif /* PEERSYNC_IROH_H */
