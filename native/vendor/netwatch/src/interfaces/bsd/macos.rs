// [PeerSync patch] RTM_* / RTF_IFSCOPE は iOS の libc に無いため crate::route_consts を使う
use super::{MessageType, RoutingStack, WireFormat};

// Hardcoded based on the generated values here: https://cs.opensource.google/go/x/net/+/master:route/zsys_darwin.go
const SIZEOF_IF_MSGHDR_DARWIN15: usize = 0x70;
const SIZEOF_IFA_MSGHDR_DARWIN15: usize = 0x14;
const SIZEOF_IFMA_MSGHDR_DARWIN15: usize = 0x10;
const SIZEOF_IF_MSGHDR2_DARWIN15: usize = 0xa0;
const SIZEOF_IFMA_MSGHDR2_DARWIN15: usize = 0x14;
const SIZEOF_IF_DATA_DARWIN15: usize = 0x60;
const SIZEOF_IF_DATA64_DARWIN15: usize = 0x80;

const SIZEOF_RT_MSGHDR_DARWIN15: usize = 0x5c;
const SIZEOF_RT_MSGHDR2_DARWIN15: usize = 0x5c;
const SIZEOF_RT_METRICS_DARWIN15: usize = 0x38;

const SIZEOF_SOCKADDR_STORAGE: usize = 0x80;
pub(super) const SIZEOF_SOCKADDR_INET: usize = 0x10;
pub(super) const SIZEOF_SOCKADDR_INET6: usize = 0x1c;

pub(super) fn probe_routing_stack() -> RoutingStack {
    let rtm_version = crate::route_consts::RTM_VERSION;

    let rtm = WireFormat {
        ext_off: 36,
        body_off: SIZEOF_RT_MSGHDR_DARWIN15,
        typ: MessageType::Route,
    };
    let rtm2 = WireFormat {
        ext_off: 36,
        body_off: SIZEOF_RT_MSGHDR2_DARWIN15,
        typ: MessageType::Route,
    };
    let ifm = WireFormat {
        ext_off: 16,
        body_off: SIZEOF_IF_MSGHDR_DARWIN15,
        typ: MessageType::Interface,
    };
    let ifm2 = WireFormat {
        ext_off: 32,
        body_off: SIZEOF_IF_MSGHDR2_DARWIN15,
        typ: MessageType::Interface,
    };
    let ifam = WireFormat {
        ext_off: SIZEOF_IFA_MSGHDR_DARWIN15,
        body_off: SIZEOF_IFA_MSGHDR_DARWIN15,
        typ: MessageType::InterfaceAddr,
    };
    let ifmam = WireFormat {
        ext_off: SIZEOF_IFMA_MSGHDR_DARWIN15,
        body_off: SIZEOF_IFMA_MSGHDR_DARWIN15,
        typ: MessageType::InterfaceMulticastAddr,
    };
    let ifmam2 = WireFormat {
        ext_off: SIZEOF_IFMA_MSGHDR2_DARWIN15,
        body_off: SIZEOF_IFMA_MSGHDR2_DARWIN15,
        typ: MessageType::InterfaceMulticastAddr,
    };

    let wire_formats = [
        (crate::route_consts::RTM_ADD, rtm),
        (crate::route_consts::RTM_DELETE, rtm),
        (crate::route_consts::RTM_CHANGE, rtm),
        (crate::route_consts::RTM_GET, rtm),
        (crate::route_consts::RTM_LOSING, rtm),
        (crate::route_consts::RTM_REDIRECT, rtm),
        (crate::route_consts::RTM_MISS, rtm),
        (crate::route_consts::RTM_LOCK, rtm),
        (crate::route_consts::RTM_RESOLVE, rtm),
        (crate::route_consts::RTM_NEWADDR, ifam),
        (crate::route_consts::RTM_DELADDR, ifam),
        (crate::route_consts::RTM_IFINFO, ifm),
        (crate::route_consts::RTM_NEWMADDR, ifmam),
        (crate::route_consts::RTM_DELMADDR, ifmam),
        (crate::route_consts::RTM_IFINFO2, ifm2),
        (crate::route_consts::RTM_NEWMADDR2, ifmam2),
        (crate::route_consts::RTM_GET2, rtm2),
    ]
    .into_iter()
    .collect();

    RoutingStack {
        rtm_version,
        wire_formats,
        kernel_align: 4,
    }
}
