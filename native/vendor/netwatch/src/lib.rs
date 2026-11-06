//! Networking related utilities

pub mod interfaces;
pub mod ip;
mod ip_family;
pub mod netmon;
// [PeerSync patch] net/route.h constants missing from libc on iOS
#[cfg(any(target_os = "macos", target_os = "ios"))]
pub(crate) mod route_consts;
#[cfg(not(wasm_browser))]
mod udp;

pub use self::ip_family::IpFamily;
#[cfg(not(wasm_browser))]
pub use self::udp::{UdpSender, UdpSocket};
