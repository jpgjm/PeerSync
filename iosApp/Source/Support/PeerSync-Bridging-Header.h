#ifndef PeerSync_Bridging_Header_h
#define PeerSync_Bridging_Header_h

/* _NSGetExecutablePath (for RuntimeEnvironment.hostExecutablePath).
 * Swift on iOS can't see it through Darwin, so import it explicitly here.
 * Note: the iroh FFI (peersync_iroh.h) isn't called from Swift; Kotlin (cinterop) uses it. */
#include <mach-o/dyld.h>

#endif /* PeerSync_Bridging_Header_h */
