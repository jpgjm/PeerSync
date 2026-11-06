import Foundation

/// Creates and resolves security-scoped bookmarks (the save destination, SaveDestinationStore).
///
/// 0.2.25 shared this between the shared folder and the save destination so both recreate stale bookmarks;
/// the shared folder (SharedFolderStore) was removed in 0.2.36.
enum ScopedBookmark {
    struct Resolved {
        let url: URL
        /// Whether startAccessingSecurityScopedResource succeeded. If false, no access (only the name is known).
        let accessed: Bool
        /// Bookmark recreated because the old one was stale. Save it again.
        let refreshed: Data?
    }

    /// Creates a bookmark from a picked folder URL (the scope is open only while creating it).
    static func make(_ url: URL, tag: String) -> Data? {
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }
        do {
            return try url.bookmarkData()
        } catch {
            qlog(.error, tag, "Failed to create bookmark: \(url.lastPathComponent): \(error)")
            return nil
        }
    }

    /// Resolves a bookmark and opens its scope. The caller closes the scope (when accessed is true).
    static func resolve(_ data: Data, tag: String) -> Resolved? {
        var stale = false
        let url: URL
        do {
            url = try URL(resolvingBookmarkData: data, options: [], relativeTo: nil, bookmarkDataIsStale: &stale)
        } catch {
            qlog(.warning, tag, "Failed to resolve bookmark: \(error)")
            return nil
        }
        guard url.startAccessingSecurityScopedResource() else {
            qlog(.warning, tag, "Can't get security scope: \(url.lastPathComponent)")
            return Resolved(url: url, accessed: false, refreshed: nil)
        }
        var refreshed: Data?
        if stale {
            refreshed = try? url.bookmarkData()
            qlog(.info, tag, refreshed != nil ? "Recreated stale bookmark" : "Couldn't recreate stale bookmark")
        }
        return Resolved(url: url, accessed: true, refreshed: refreshed)
    }
}
