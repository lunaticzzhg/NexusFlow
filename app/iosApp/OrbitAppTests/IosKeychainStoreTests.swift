@testable import OrbitApp
import ComposeApp
import XCTest

final class IosKeychainStoreTests: XCTestCase {
    func testWriteReadOverwriteAndRemove() {
        let store = IosKeychainStore()
        let key = "test.session.\(UUID().uuidString)"
        defer { _ = store.remove(key: key) }

        XCTAssertEqual(store.read(key: key).status, .notFound)
        XCTAssertEqual(store.write(key: key, value: "first").status, .success)
        XCTAssertEqual(store.read(key: key).value, "first")

        XCTAssertEqual(store.write(key: key, value: "second").status, .success)
        XCTAssertEqual(store.read(key: key).value, "second")

        XCTAssertEqual(store.remove(key: key).status, .success)
        XCTAssertEqual(store.read(key: key).status, .notFound)
    }
}
