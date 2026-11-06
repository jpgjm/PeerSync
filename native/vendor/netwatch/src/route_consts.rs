//! [PeerSync patch] Constants from <net/route.h>.
//!
//! `libc` doesn't define the `net/route.h` constants (RTM_* / RTAX_* /
//! RTA_* / RTF_* / RTM_VERSION) on iOS / tvOS / watchOS / visionOS (they're not in the SDK's public headers).
//! The values are shared with macOS in XNU, so define them here for use within netwatch.
//! macOS uses the same values too (they match libc; see bsd/mod.rs / apple/mod.rs).
#![allow(dead_code)]

use libc::c_int;

// net/route.h: rtm_flags
pub const RTF_GATEWAY: c_int = 0x2;
pub const RTF_IFSCOPE: c_int = 0x1000000;

pub const RTM_VERSION: c_int = 5;

// Message types
pub const RTM_ADD: c_int = 0x1;
pub const RTM_DELETE: c_int = 0x2;
pub const RTM_CHANGE: c_int = 0x3;
pub const RTM_GET: c_int = 0x4;
pub const RTM_LOSING: c_int = 0x5;
pub const RTM_REDIRECT: c_int = 0x6;
pub const RTM_MISS: c_int = 0x7;
pub const RTM_LOCK: c_int = 0x8;
pub const RTM_RESOLVE: c_int = 0xb;
pub const RTM_NEWADDR: c_int = 0xc;
pub const RTM_DELADDR: c_int = 0xd;
pub const RTM_IFINFO: c_int = 0xe;
pub const RTM_NEWMADDR: c_int = 0xf;
pub const RTM_DELMADDR: c_int = 0x10;
pub const RTM_IFINFO2: c_int = 0x12;
pub const RTM_NEWMADDR2: c_int = 0x13;
pub const RTM_GET2: c_int = 0x14;

// Bitmask values for rtm_addrs.
pub const RTA_IFP: c_int = 0x10;

// Index offsets for sockaddr array for alternate internal encoding.
pub const RTAX_DST: c_int = 0;
pub const RTAX_GATEWAY: c_int = 1;
pub const RTAX_NETMASK: c_int = 2;
pub const RTAX_IFP: c_int = 4;
pub const RTAX_BRD: c_int = 7;
pub const RTAX_MAX: c_int = 8;
