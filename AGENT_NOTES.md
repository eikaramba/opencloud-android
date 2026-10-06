# OpenCloud Android - Agent Developer Guide & Project Findings

This guide summarizes essential knowledge for AI coding agents and human developers working on the `opencloud-eu/android` repository.

---

## 1. Project Overview & Context

* **Project Identity**: This is **OpenCloud** (`opencloud-eu`), **not** ownCloud or NextCloud. While historically related to ownCloud, OpenCloud is built on a modern microservices / spaces architecture (similar to Infinite Scale / ocis, CS3 APIs, Tika search service).
* **Search Architecture**: Server-side search uses WebDAV `REPORT` requests with `<oc:search-files>` body sent to `/remote.php/dav/spaces` (or `/dav/spaces`), queried against OpenCloud's search service with OpenSearch/Bleve and Apache Tika for full-text extraction across all spaces/folders.
  * **KQL Syntax**: Server parses `<oc:pattern>` using Keyword Query Language (KQL). Plain queries match filename only (`FreeTextKeywordNode`). Full-text search (content + filename) requires wrapping: `(name:"*<term>*" OR content:"<term>")`. Explicit KQL queries like `content:"wandfarbe" AND 26` are supported directly.
  * **Detailed Findings & Roadmap**: See `SEARCH_FINDINGS_AND_ROADMAP.md` for full findings on full-text search, debounce, and the planned Advanced Search Modal.
  * **"Search inside files" button**: `opencloud_toolbar.xml` has a `root_toolbar_content_search` ImageView between the search bar and the avatar. It is `gone` unless the root search bar is expanded (`ToolbarActivity.setupRootToolbar()`/`restoreSearchBarToolbar()`; `SpacesListFragment` hides it because spaces search matches names only). Tapping it calls `MainFileListViewModel.toggleContentSearch()`; the ViewModel combines the raw `searchFilter` with the mode in `buildSearchPattern()` and sends `content:"<term>"` for free text, while queries that already contain KQL syntax (`:`, leading `(`, ` AND `/` OR `/` NOT `) are sent untouched so `content:searchterm AND .pdf` keeps working. The active/inactive icon pair is `ic_content_search(.active).xml`.
  * **Back navigation from a search hit**: back from a document opened out of a search returns to the search results. Root cause was programmatic folder refreshes (folder-sync broadcast receiver, `onResume()`) calling `MainFileListViewModel.updateFolderToDisplay()`, which cleared the search; they now pass `keepSearchOnSameFolder = true` (see `SEARCH_FINDINGS_AND_ROADMAP.md` §4). `FileDisplayActivity` additionally captures a non-blank search in `setSecondFragment()` and restores it in `onBackPressed()`. Pending device confirmation.

---

## 2. Module Structure & Architecture

The repository follows clean architecture across 5 primary Gradle modules:

```
opencloudApp (Presentation, Activities, Fragments, ViewModels, UI, Koin DI)
     │
     ├──> opencloudData (Data sources, Room DB, FileDao, Repositories)
     │         │
     │         ├──> opencloudDomain (Domain models, Repository interfaces, UseCases)
     │         └──> opencloudComLibrary (Network, HTTP/WebDAV, OpenCloudClient, dav4jvm)
     │
     └──> opencloudTestUtil (Mock fixtures: OC_FILE, OC_ROOT_FOLDER, OC_SPACE_PERSONAL, etc.)
```

### Module Responsibilities:
* **`opencloudComLibrary`**: Low-level networking and WebDAV client. Built on OkHttp 4.9.2 and `com.github.opencloud-eu:android-dav` (fork of `dav4jvm`). Handles methods (`PropfindMethod`, `SearchMethod`, `PutMethod`, `MoveMethod`, etc.) and remote operations (`RemoteOperation<T>`).
* **`opencloudDomain`**: Pure Kotlin business logic. Contains domain models (`OCFile`, `OCSpace`, `OCFileWithSyncInfo`), repository interfaces (`FileRepository`, `SpacesRepository`), and use cases extending `BaseUseCaseWithResult` or `BaseUseCase`. **No Android UI or Room dependencies allowed here.**
* **`opencloudData`**: Implements repositories and handles local persistence. Uses Room (`OpencloudDatabase`, `FileDao`, `SpacesDao`, `OCFileEntity`) and remote data sources (`OCRemoteFileDataSource`).
* **`opencloudApp`**: Android app layer using Koin for dependency injection (`UseCaseModule`, `ViewModelModule`), ViewModels, DataBinding/ViewBinding, and Fragments.
  * Product flavors: `original` and `qa`.
* **`opencloudTestUtil`**: Shared test fixtures and helpers.

---

## 3. Build & Test Commands

* **Launcher JVM**: Java 21 / JVM Target 17.
* **Compile Kotlin (App)**:
  ```bash
  ./gradlew opencloudApp:compileOriginalDebugKotlin
  ```
* **Assemble APK**:
  ```bash
  ./gradlew assembleOriginalDebug
  ```
* **Run Unit Tests by Module**:
  ```bash
  ./gradlew opencloudComLibrary:testDebugUnitTest
  ./gradlew opencloudDomain:testDebugUnitTest
  ./gradlew opencloudData:testDebugUnitTest
  ./gradlew opencloudApp:testOriginalDebugUnitTest
  ```
* **Run a Specific Test Class or Method**:
  ```bash
  ./gradlew opencloudComLibrary:testDebugUnitTest --tests "*SearchMethodTest*"
  ./gradlew opencloudApp:testOriginalDebugUnitTest --tests "*KeyAppViewModelsTest*"
  ```
* **Static Analysis (Detekt)**:
  ```bash
  ./gradlew detekt
  # or per module:
  ./gradlew opencloudApp:detekt
  ./gradlew opencloudComLibrary:detekt
  ./gradlew opencloudData:detekt
  ./gradlew opencloudDomain:detekt
  ```
  **Rule**: Always run `./gradlew detekt` before finishing any task. Detekt enforces strict rules on line length, unused imports, and swallowed exceptions.

---

## 4. Key WebDAV & Networking Patterns

* **Endpoints**:
  * Global spaces search: `/remote.php/dav/spaces`
  * Scoped space search / browsing: `/remote.php/dav/spaces/<space-id>`
  * Legacy fallback: `/remote.php/dav/files/<username>`
* **WebDAV REPORT**:
  * OkHttp supports `REPORT` method via `Request.Builder().method("REPORT", requestBody)`.
  * `DavResource` in `android-dav` has `protected processMultiStatus(response, callback)` to parse `207 Multi-Status` XML.
  * Custom properties must be registered with `PropertyRegistry` before execution:
    ```kotlin
    PropertyRegistry.register(OCShareTypes.Factory())
    PropertyRegistry.register(OCChecksums.Factory())
    PropertyRegistry.register(OCFileId.Factory())
    PropertyRegistry.register(OCSpaceId.Factory())
    ```
* **Space & Path Parsing (`RemoteFile`)**:
  * URLs containing `/dav/spaces/<spaceId>/<path>` can be parsed using `RemoteFile.getRemotePathFromUrl` and `RemoteFile.getSpaceIdFromUrl`.

---

## 5. Persistence & Database (Room) Guidelines

* **Entity IDs**: `OCFileEntity` uses an auto-generated primary key `id: Long`.
* **UI Invariant**: Every `OCFile` rendered in the file list or passed to file preview/download/share flows **must have a valid database `id`** (`file.id != null`).
* When remote files are retrieved (e.g. from search or synchronization):
  * Check if the file already exists locally: `localFileDataSource.getFileByRemotePath(remotePath, owner, spaceId)`.
  * If it exists, preserve local properties via `remoteFile.copyLocalPropertiesFrom(localFile)`.
  * If it's new, persist it via `localFileDataSource.saveFile(remoteFile)` and reload so it acquires an `id`.

---

## 6. Testing Gotchas & Pitfalls

1. **MockK `relaxed = true` on Flow / Domain Types**:
   * If a usecase returning `Flow<T>` or a model class is mocked with `mockk(relaxed = true)`, MockK returns a generic `java.lang.Object` instead of a `Flow` or the concrete type. This causes runtime `ClassCastException` when collected in coroutines.
   * **Fix**: Explicitly define mock behavior:
     ```kotlin
     every { getAppRegistryWhichAllowCreationAsStreamUseCase(any()) } returns flowOf(emptyList())
     every { getSpaceWithSpecialsByIdForAccountUseCase(any()) } returns OC_SPACE_PERSONAL
     ```

2. **Robolectric & `HttpClient` / `OpenCloudAccount`**:
   * `HttpClient(context)` throws `NullPointerException("Context may not be NULL!")` if passed `null`. In Robolectric tests, use `ApplicationProvider.getApplicationContext<Context>()`.
   * `OpenCloudAccount(account, context)` validates that `account` is registered in Android's `AccountManager`. For tests, register it first:
     ```kotlin
     val account = Account("alice@server", "com.example")
     val am = AccountManager.get(context)
     am.addAccountExplicitly(account, null, null)
     am.setUserData(account, AccountUtils.Constants.KEY_OC_BASE_URL, serverUrl)
     am.setUserData(account, AccountUtils.Constants.KEY_ID, "alice")
     val ocAccount = OpenCloudAccount(account, context)
     ```

3. **Detekt Violations**:
   * **`SwallowedException`**: Never write an empty `catch (e: Exception) {}`. Always log with Timber (`Timber.d(e, "...")`) or rethrow.
   * **`MaxLineLength`**: Detekt fails on lines exceeding max length, including comments, string literals, and test assertions. Break long strings or URL constants across multiple lines.
   * **`UnusedImports`**: Remove unused imports before running detekt.

---

## 7. Dependency Injection (Koin)

* Use cases are registered in `opencloudApp/.../dependecyinjection/UseCaseModule.kt`.
* ViewModels are registered in `opencloudApp/.../dependecyinjection/ViewModelModule.kt`.
* When adding a parameter to a ViewModel constructor, update both `ViewModelModule.kt` (adding `get()`) and test instantiations in `KeyAppViewModelsTest.kt`.

  * **Search hit snippets**: the server returns `oc:highlights` (text with `<mark>` around matches) for content hits. Parsed by `OCHighlights` -> `RemoteFile.highlights` -> `OCFile.highlights` (transient, not persisted) -> `OCFileWithSyncInfo.highlights` (set in `OCFileRepository.searchFiles`). `MainFileListViewModel` drops them unless the pattern contains `content:`; `FileListAdapter` shows them in `search_highlights` (item_file_list.xml) via `SearchHighlightFormatter` (bold matches) in list view only.
