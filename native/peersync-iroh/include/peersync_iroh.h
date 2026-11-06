/* C header for the PeerSync iroh FFI. Maps 1:1 to the #[no_mangle] functions in src/lib.rs.
 * Swift refers to it via the bridging header. */
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

/* Most recent error message (free with ps_string_free). NULL if none. */
char *ps_last_error(void);
void ps_string_free(char *s);

/* Keys and IDs */
int32_t ps_secret_generate(uint8_t *out32);
int32_t ps_id_from_secret(const uint8_t *secret32, char **out_id);

/* Node */
PsNode *ps_node_start(const uint8_t *secret32, const char *relay_url);
char *ps_node_id(PsNode *node);
/* Home relay URL. NULL if not connected. Free with ps_string_free. */
char *ps_node_home_relay(PsNode *node);
int32_t ps_node_wait_online(PsNode *node, uint64_t timeout_ms);
PsConn *ps_node_accept(PsNode *node);
PsConn *ps_node_connect(PsNode *node, const char *peer_id);
/* Stop (blocked accept returns NULL; handle stays valid), then free once no call is running (0.2.37). */
void ps_node_close(PsNode *node);
void ps_node_free(PsNode *node);

/* Connections */
char *ps_conn_remote_id(PsConn *conn);
int32_t ps_conn_open_bi(PsConn *conn, PsSend **out_send, PsRecv **out_recv);
int32_t ps_conn_accept_bi(PsConn *conn, PsSend **out_send, PsRecv **out_recv);
int32_t ps_conn_is_closed(PsConn *conn);
void ps_conn_close(PsConn *conn, uint32_t code, const char *reason);
/* Selected path "<kind>|<rtt_ms>" (kind: direct/relay/other/none). Free with ps_string_free. */
char *ps_conn_path_summary(PsConn *conn);
void ps_conn_free(PsConn *conn);

/* Streams */
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
