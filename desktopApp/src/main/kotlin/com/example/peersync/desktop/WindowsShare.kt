package com.example.peersync.desktop

import com.example.peersync.AppInfo
import com.example.peersync.log.PsLog
import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary.StdCallCallback
import java.util.UUID

/**
 * The Windows share window (Share contract). Same role as Android's share sheet and iOS's
 * UIActivityViewController. 0.2.38 brings back the 0.2.4 implementation (removed in 0.2.5 when Windows
 * went back to "Copy").
 *
 * Desktop (non-UWP) apps can't use `DataTransferManager.GetForCurrentView()`; they go through the
 * activation factory's `IDataTransferManagerInterop`, per HWND:
 *   1. `RoGetActivationFactory("…DataTransferManager")` -> `IDataTransferManagerInterop`
 *   2. `GetForWindow(hwnd)` -> `DataTransferManager`
 *   3. `add_DataRequested(handler)`: the handler sets `DataPackage.Properties.Title` (required) and `SetText`
 *   4. `ShowShareUIForWindow(hwnd)`
 *
 * No extra DLL: the COM/WinRT vtables are called directly with JNA (already a dependency).
 *
 * Thread: this API must be called on the UI thread that created the HWND, and DataRequested arrives
 * through that thread's (STA) message pump. In AWT the HWND is created on the "AWT-Windows" (toolkit)
 * thread, not the EDT, and Java has no public API to run code there, so the call is carried over with a
 * WH_GETMESSAGE hook + PostThreadMessage (the hook is installed per share and removed right after).
 */
object WindowsShare {
    private const val TAG = "${AppInfo.NAME}/WindowsShare"

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    /** True on Windows when WinRT (combase.dll, Windows 8+) can be loaded. */
    val isAvailable: Boolean by lazy {
        if (!isWindows) return@lazy false
        runCatching { combase; user32; kernel32 }
            .onFailure { PsLog.w(TAG, "WinRT not available (Share button hidden): $it") }
            .isSuccess
    }

    // ---- minimal Win32 / WinRT bindings ----------------------------------------------------

    @Suppress("FunctionName")
    private interface Combase : Library {
        fun RoInitialize(initType: Int): Int
        fun RoGetActivationFactory(activatableClassId: Pointer, iid: Pointer, factory: PointerByReference): Int
        fun WindowsCreateString(sourceString: WString, length: Int, string: PointerByReference): Int
        fun WindowsDeleteString(string: Pointer?): Int
        fun WindowsGetStringRawBuffer(string: Pointer?, length: IntByReference?): Pointer?
    }

    @Suppress("FunctionName")
    private interface User32 : Library {
        fun GetWindowThreadProcessId(hwnd: Pointer, processId: IntByReference?): Int
        fun SetWindowsHookExW(idHook: Int, lpfn: Callback, hMod: Pointer?, dwThreadId: Int): Pointer?
        fun UnhookWindowsHookEx(hhk: Pointer): Boolean
        fun CallNextHookEx(hhk: Pointer?, nCode: Int, wParam: Pointer?, lParam: Pointer?): Pointer?
        fun PostThreadMessageW(idThread: Int, msg: Int, wParam: Pointer?, lParam: Pointer?): Boolean
        fun RegisterWindowMessageW(string: WString): Int
    }

    @Suppress("FunctionName")
    private interface Kernel32 : Library {
        fun GetCurrentThreadId(): Int
        fun GetLastError(): Int
    }

    private val combase: Combase by lazy { Native.load("combase", Combase::class.java) }
    private val user32: User32 by lazy { Native.load("user32", User32::class.java) }
    private val kernel32: Kernel32 by lazy { Native.load("kernel32", Kernel32::class.java) }

    /** roapi.h: RO_INIT_SINGLETHREADED = 0 (0.2.4 passed 1 = MULTITHREADED by mistake). */
    private const val RO_INIT_SINGLETHREADED = 0
    private const val S_OK = 0
    private const val S_FALSE = 1
    private const val E_NOINTERFACE = -0x7FFFBFFE // 0x80004002
    private const val E_FAIL = -0x7FFFBFFB // 0x80004005
    private const val RPC_E_CHANGED_MODE = -0x7FFEFEFA // 0x80010106
    private const val WH_GETMESSAGE = 3
    private const val HC_ACTION = 0
    private const val PM_REMOVE = 1

    private const val RUNTIME_CLASS = "Windows.ApplicationModel.DataTransfer.DataTransferManager"

    // IIDs (checked against the windows-rs metadata in 0.2.4)
    private val IID_IUnknown = guid("00000000-0000-0000-C000-000000000046")
    private val IID_IAgileObject = guid("94EA2B94-E9CC-49E0-C0FF-EE64CA8F5B90")
    private val IID_IDataTransferManagerInterop = guid("3A3DCD6C-3EAB-43DC-BCDE-45671CE800C8")
    private val IID_IDataTransferManager = guid("A5CAEE9B-8708-49D1-8D36-67D25A8DA00C")

    /**
     * IID of TypedEventHandler<DataTransferManager, DataRequestedEventArgs>, derived with WinRT's rule for
     * parameterized interfaces (UUID v5 of the signature string in namespace 11f47ad5-…).
     */
    private val IID_DataRequestedHandler = guid("EC6F9CC8-46D0-5E0E-B4D2-7D7773AE37A0")

    // vtable indexes (IUnknown = 0..2, IInspectable = 0..5)
    private const val VT_INSPECTABLE_GET_RUNTIME_CLASS_NAME = 4
    private const val VT_INTEROP_GET_FOR_WINDOW = 3
    private const val VT_INTEROP_SHOW_SHARE_UI_FOR_WINDOW = 4
    private const val VT_DTM_ADD_DATA_REQUESTED = 6
    private const val VT_ARGS_GET_REQUEST = 6
    private const val VT_REQUEST_GET_DATA = 6
    private const val VT_PACKAGE_GET_PROPERTIES = 7
    private const val VT_PACKAGE_SET_TEXT = 16
    private const val VT_PROPS_PUT_TITLE = 7

    // ---- public API ------------------------------------------------------------------------

    /** What to share. Put into the DataPackage when DataRequested arrives. */
    private class Request(val hwnd: Long, val title: String, val text: String)

    @Volatile private var pending: Request? = null
    private val lock = Any()

    /**
     * Opens the share window. Any thread is fine (the call is carried to the HWND's UI thread).
     * Failures are only logged (nothing shows on screen).
     */
    fun share(hwnd: Long, title: String, text: String) {
        if (!isAvailable) {
            PsLog.w(TAG, "share: not available here")
            return
        }
        if (hwnd == 0L) {
            PsLog.e(TAG, "share: no window handle")
            return
        }
        val req = Request(hwnd, title, text)
        val ownerTid = user32.GetWindowThreadProcessId(Pointer(hwnd), null)
        val myTid = kernel32.GetCurrentThreadId()
        PsLog.d(TAG, "share: hwnd=0x${hwnd.toString(16)} ownerTid=$ownerTid callerTid=$myTid")
        if (ownerTid == 0) {
            PsLog.e(TAG, "share: couldn't get the window's thread (GetLastError=${kernel32.GetLastError()})")
            return
        }
        if (ownerTid == myTid) {
            showOnOwnerThread(req)
            return
        }
        synchronized(lock) {
            pending = req
            if (!installHook(ownerTid)) {
                pending = null
                return
            }
            if (!user32.PostThreadMessageW(ownerTid, shareMessage, null, null)) {
                PsLog.e(TAG, "share: PostThreadMessage failed (GetLastError=${kernel32.GetLastError()})")
                pending = null
                removeHook()
            }
        }
    }

    // ---- hand-off to the UI thread (WH_GETMESSAGE hook) ----------------------------------------

    private val shareMessage: Int by lazy { user32.RegisterWindowMessageW(WString("${AppInfo.NAME}.ShareRequest")) }
    private var hook: Pointer? = null

    /** Held strongly (if it were GC'd, the next native call into it would crash). */
    private val hookProc = object : StdCallCallback {
        @Suppress("unused")
        fun callback(nCode: Int, wParam: Pointer?, lParam: Pointer?): Pointer? {
            val next = user32.CallNextHookEx(null, nCode, wParam, lParam)
            if (nCode == HC_ACTION && Pointer.nativeValue(wParam) == PM_REMOVE.toLong() && lParam != null) {
                // MSG { HWND hwnd; UINT message; WPARAM wParam; LPARAM lParam; DWORD time; POINT pt; }
                val message = lParam.getInt(Native.POINTER_SIZE.toLong())
                if (message == shareMessage) {
                    val req: Request?
                    synchronized(lock) {
                        req = pending
                        pending = null
                        removeHook()
                    }
                    if (req != null) {
                        runCatching { showOnOwnerThread(req) }
                            .onFailure { PsLog.e(TAG, "share: exception on the UI thread: $it") }
                    }
                }
            }
            return next
        }
    }

    private fun installHook(tid: Int): Boolean {
        if (hook != null) return true
        val h = user32.SetWindowsHookExW(WH_GETMESSAGE, hookProc, null, tid)
        if (h == null) {
            PsLog.e(TAG, "share: SetWindowsHookEx failed (GetLastError=${kernel32.GetLastError()})")
            return false
        }
        hook = h
        return true
    }

    private fun removeHook() {
        hook?.let {
            if (!user32.UnhookWindowsHookEx(it)) {
                PsLog.w(TAG, "UnhookWindowsHookEx failed (GetLastError=${kernel32.GetLastError()})")
            }
        }
        hook = null
    }

    // ---- WinRT calls (run on the HWND's UI thread) ---------------------------------------------

    /** DataTransferManager and registered handler per HWND (the HWND changes when the window is recreated). */
    private class WindowState(val interop: Pointer, val dtm: Pointer, val handler: DataRequestedHandler) {
        var current: Request? = null
    }

    private val states = HashMap<Long, WindowState>()

    private fun showOnOwnerThread(req: Request) {
        val hr = combase.RoInitialize(RO_INIT_SINGLETHREADED)
        when (hr) {
            S_OK -> PsLog.d(TAG, "RoInitialize: initialized")
            S_FALSE -> PsLog.d(TAG, "RoInitialize: already initialized")
            // AWT's toolkit thread already called OleInitialize (STA); WinRT works on it as is
            RPC_E_CHANGED_MODE -> PsLog.d(TAG, "RoInitialize: already initialized in another mode (continuing)")
            else -> {
                PsLog.e(TAG, "RoInitialize failed: ${hrText(hr)}")
                return
            }
        }

        val state = states[req.hwnd] ?: createState(req.hwnd)?.also { states[req.hwnd] = it } ?: return
        state.current = req
        val rc = state.interop.vtbl(VT_INTEROP_SHOW_SHARE_UI_FOR_WINDOW)
            .invokeInt(arrayOf(state.interop, Pointer(req.hwnd)))
        if (rc != S_OK) {
            PsLog.e(TAG, "ShowShareUIForWindow failed: ${hrText(rc)}")
            state.current = null
        } else {
            PsLog.i(TAG, "share window shown: ${req.text.length} chars")
        }
    }

    private fun createState(hwnd: Long): WindowState? {
        val classId = hstring(RUNTIME_CLASS) ?: return null
        try {
            val factoryRef = PointerByReference()
            var hr = combase.RoGetActivationFactory(classId, IID_IDataTransferManagerInterop, factoryRef)
            if (hr != S_OK) {
                PsLog.e(TAG, "RoGetActivationFactory(IDataTransferManagerInterop) failed: ${hrText(hr)}")
                return null
            }
            val interop = factoryRef.value

            val dtmRef = PointerByReference()
            hr = interop.vtbl(VT_INTEROP_GET_FOR_WINDOW)
                .invokeInt(arrayOf(interop, Pointer(hwnd), IID_IDataTransferManager, dtmRef))
            if (hr != S_OK) {
                PsLog.e(TAG, "GetForWindow failed: ${hrText(hr)}")
                return null
            }
            val dtm = dtmRef.value
            PsLog.d(TAG, "GetForWindow OK: ${runtimeClassName(dtm)}")

            val handler = DataRequestedHandler()
            val tokenRef = LongByReference()
            hr = dtm.vtbl(VT_DTM_ADD_DATA_REQUESTED).invokeInt(arrayOf(dtm, handler.pointer, tokenRef))
            if (hr != S_OK) {
                PsLog.e(TAG, "add_DataRequested failed: ${hrText(hr)}")
                return null
            }
            PsLog.d(TAG, "add_DataRequested OK: token=${tokenRef.value}")
            return WindowState(interop, dtm, handler)
        } finally {
            combase.WindowsDeleteString(classId)
        }
    }

    /**
     * The DataRequested handler: a COM object (vtable QueryInterface / AddRef / Release / Invoke) built by
     * hand from JNA Memory and callbacks. It lives until the app exits, so the ref count is a formality.
     */
    private class DataRequestedHandler {
        // JNA callbacks must be held in fields so they aren't GC'd
        private val queryInterface = object : StdCallCallback {
            @Suppress("unused")
            fun callback(self: Pointer, riid: Pointer, out: Pointer): Int {
                val requested = riid.getByteArray(0, 16)
                val ok = listOf(IID_IUnknown, IID_IAgileObject, IID_DataRequestedHandler)
                    .any { it.getByteArray(0, 16).contentEquals(requested) }
                return if (ok) {
                    out.setPointer(0, self)
                    addRef.callback(self)
                    S_OK
                } else {
                    PsLog.d(TAG, "handler QueryInterface: unsupported IID ${guidText(riid)}")
                    out.setPointer(0, null)
                    E_NOINTERFACE
                }
            }
        }
        private var refs = 1
        private val addRef = object : StdCallCallback {
            @Suppress("unused")
            fun callback(self: Pointer): Int = ++refs
        }
        private val release = object : StdCallCallback {
            @Suppress("unused")
            fun callback(self: Pointer): Int = (--refs).coerceAtLeast(0)
        }
        private val invoke = object : StdCallCallback {
            @Suppress("unused")
            fun callback(self: Pointer, sender: Pointer?, args: Pointer?): Int =
                runCatching { onDataRequested(sender, args) }.getOrElse {
                    PsLog.e(TAG, "DataRequested threw: $it")
                    E_FAIL
                }
        }

        private val vtable: Memory = Memory(4L * Native.POINTER_SIZE).also {
            it.setPointer(0L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(queryInterface))
            it.setPointer(1L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(addRef))
            it.setPointer(2L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(release))
            it.setPointer(3L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(invoke))
        }
        private val instance: Memory = Memory(Native.POINTER_SIZE.toLong()).also { it.setPointer(0, vtable) }

        /** The pointer passed as the COM object. */
        val pointer: Pointer get() = instance
    }

    /** DataRequested: put the Title and Text into the DataPackage. */
    private fun onDataRequested(sender: Pointer?, args: Pointer?): Int {
        val req = sender?.let { s -> states.values.firstOrNull { it.dtm == s } }?.current
            ?: states.values.firstNotNullOfOrNull { it.current }
        if (req == null || args == null) {
            PsLog.w(TAG, "DataRequested: nothing to share")
            return S_OK
        }
        PsLog.d(TAG, "DataRequested: filling the package")

        val requestRef = PointerByReference()
        var hr = args.vtbl(VT_ARGS_GET_REQUEST).invokeInt(arrayOf(args, requestRef))
        if (hr != S_OK) {
            PsLog.e(TAG, "get_Request failed: ${hrText(hr)}")
            return hr
        }
        val request = requestRef.value
        try {
            val pkgRef = PointerByReference()
            hr = request.vtbl(VT_REQUEST_GET_DATA).invokeInt(arrayOf(request, pkgRef))
            if (hr != S_OK) {
                PsLog.e(TAG, "get_Data failed: ${hrText(hr)}")
                return hr
            }
            val pkg = pkgRef.value
            try {
                val propsRef = PointerByReference()
                hr = pkg.vtbl(VT_PACKAGE_GET_PROPERTIES).invokeInt(arrayOf(pkg, propsRef))
                if (hr != S_OK) {
                    PsLog.e(TAG, "get_Properties failed: ${hrText(hr)}")
                    return hr
                }
                val props = propsRef.value
                try {
                    // The Share contract requires a Title (without it the window shows an error)
                    val hTitle = hstring(req.title) ?: return E_FAIL
                    try {
                        hr = props.vtbl(VT_PROPS_PUT_TITLE).invokeInt(arrayOf(props, hTitle))
                        if (hr != S_OK) PsLog.e(TAG, "put_Title failed: ${hrText(hr)}")
                    } finally {
                        combase.WindowsDeleteString(hTitle)
                    }
                    val hText = hstring(req.text) ?: return E_FAIL
                    try {
                        hr = pkg.vtbl(VT_PACKAGE_SET_TEXT).invokeInt(arrayOf(pkg, hText))
                        if (hr != S_OK) PsLog.e(TAG, "SetText failed: ${hrText(hr)}")
                        else PsLog.d(TAG, "SetText OK (${req.text.length} chars)")
                    } finally {
                        combase.WindowsDeleteString(hText)
                    }
                } finally {
                    props.release()
                }
            } finally {
                pkg.release()
            }
        } finally {
            request.release()
        }
        return S_OK
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** The i-th function in a COM object's vtable. */
    private fun Pointer.vtbl(i: Int): Function =
        Function.getFunction(getPointer(0).getPointer(i.toLong() * Native.POINTER_SIZE))

    private fun Pointer.release() {
        vtbl(2).invokeInt(arrayOf(this))
    }

    private fun runtimeClassName(inspectable: Pointer): String {
        val ref = PointerByReference()
        val hr = inspectable.vtbl(VT_INSPECTABLE_GET_RUNTIME_CLASS_NAME).invokeInt(arrayOf(inspectable, ref))
        if (hr != S_OK) return "(GetRuntimeClassName ${hrText(hr)})"
        return try {
            combase.WindowsGetStringRawBuffer(ref.value, IntByReference())?.getWideString(0) ?: "(null)"
        } finally {
            combase.WindowsDeleteString(ref.value)
        }
    }

    /** Creates an HSTRING. The caller must WindowsDeleteString it. */
    private fun hstring(s: String): Pointer? {
        val ref = PointerByReference()
        val hr = combase.WindowsCreateString(WString(s), s.length, ref)
        if (hr != S_OK) {
            PsLog.e(TAG, "WindowsCreateString failed: ${hrText(hr)}")
            return null
        }
        return ref.value
    }

    /** GUID text -> the native 16-byte layout (Data1..3 little-endian, Data4 as is). */
    private fun guid(text: String): Pointer {
        val u = UUID.fromString(text)
        val hi = u.mostSignificantBits
        val lo = u.leastSignificantBits
        val m = Memory(16)
        m.setInt(0, (hi ushr 32).toInt())
        m.setShort(4, (hi ushr 16).toShort())
        m.setShort(6, hi.toShort())
        for (i in 0 until 8) m.setByte(8L + i, (lo ushr (56 - 8 * i)).toByte())
        return m
    }

    private fun guidText(p: Pointer): String {
        val d1 = p.getInt(0).toUInt().toString(16).padStart(8, '0')
        val d2 = p.getShort(4).toUShort().toString(16).padStart(4, '0')
        val d3 = p.getShort(6).toUShort().toString(16).padStart(4, '0')
        val d4 = p.getByteArray(8, 8).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        return "{$d1-$d2-$d3-${d4.substring(0, 4)}-${d4.substring(4)}}"
    }

    private fun hrText(hr: Int): String = "HRESULT 0x" + hr.toUInt().toString(16).padStart(8, '0')
}
