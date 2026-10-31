#ifndef PeerSync_Bridging_Header_h
#define PeerSync_Bridging_Header_h

#include "../../native/peersync-iroh/include/peersync_iroh.h"

/* _NSGetExecutablePath（RuntimeEnvironment.hostExecutablePath 用）。
 * iOS の Swift には Darwin 経由で見えないため、ここで明示的に取り込む。 */
#include <mach-o/dyld.h>

#endif /* PeerSync_Bridging_Header_h */
