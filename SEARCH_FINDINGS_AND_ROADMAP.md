# OpenCloud Search: Findings, Root Cause & Implementation Roadmap

This document outlines the findings regarding full-text search, keystroke debouncing, and the planned Advanced Search Modal in the OpenCloud Android application. Another developer or agent can use this as an exact guide to finish the implementation.

---

## 1. Executive Summary & Root Cause Analysis

### Issue A: Full-Text Search within Files (PDFs, Docs, etc.)
* **Symptom**: In the OpenCloud browser interface, searching for a word found inside a PDF returns that PDF in the results. In the Android app, the same search query yielded no results unless prefixed manually with `content:"<query>"`.
* **Root Cause Identified**:
  * OpenCloud / oCIS search service uses **Keyword Query Language (KQL)** for parsing queries inside `<oc:pattern>` in WebDAV `REPORT` requests.
  * When a raw word (e.g. `wandfarbe`) without qualifiers is sent in `<oc:pattern>wandfarbe</oc:pattern>`, KQL parses it as a `FreeTextKeywordNode`, which matches **only filenames/titles**, not extracted document content.
  * In the **OpenCloud Web UI** (`opencloud-eu/web`: `packages/web-app-search/src/portals/SearchBar.vue` and `packages/web-pkg/src/composables/search/useSearch.ts`), user queries are formatted by default as:
    ```
    (name:"*<term>*" OR content:"<term>")
    ```
  * In Android's `SearchMethod.kt` (`buildSearchXml`), `searchQuery` was sent raw into `<oc:pattern>` without wrapping it in KQL syntax.
  * When the user tested `content:"<query>"`, it worked immediately because the server-side Tika/search service is configured properly and understands KQL field prefixes.

### Issue B: Search Triggering on Every Keystroke
* **Symptom**: Fast typing on the keyboard triggers multiple search requests.
* **Root Cause Identified**:
  * In `MainFileListViewModel.kt`:
    * `SEARCH_DEBOUNCE_MS = 300L` is too short (average mobile typing interval between keystrokes is 250–400ms).
    * `debouncedSearchFilter` does not have `.distinctUntilChanged()`.
    * When `onQueryTextSubmit` is triggered (pressing Search/Enter on the keyboard), it simply calls `updateSearchFilter(it)` which still waits for debounce instead of executing immediately.

### Issue C: Advanced Search Modal (Feature Request)
* **Requirement**: Provide a button (e.g. tune/filter icon in the toolbar when search is open) that displays an Advanced Search dialog/modal.
* **Capabilities**:
  * Allow regular users to build queries visually without memorizing KQL syntax.
  * Configure whether each term targets **Title & Content (Both)**, **Content only**, or **Title only**.
  * Combine multiple conditions using **AND** / **OR** operators (e.g. `content:"wandfarbe" AND 26`).
  * Live query preview showing the generated KQL syntax.
  * Execute search directly and populate the search bar.

---

## 2. Technical Details & Architecture

### WebDAV Search Request Payload
Endpoint: `REPORT /remote.php/dav/spaces`
```xml
<oc:search-files xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
  <d:prop>
    <d:getlastmodified/>
    <d:getcontentlength/>
    <d:getcontenttype/>
    <d:resourcetype/>
    <d:getetag/>
    <oc:permissions/>
    <oc:id/>
    <oc:fileid/>
    <oc:spaceid/>
    <oc:size/>
    <oc:privatelink/>
    <oc:checksums/>
    <oc:share-types/>
  </d:prop>
  <oc:search>
    <oc:pattern>(name:"*wandfarbe*" OR content:"wandfarbe")</oc:pattern>
    <oc:limit>100</oc:limit>
  </oc:search>
</oc:search-files>
```

### KQL Pattern Formatting Rules
A query formatting helper `formatSearchPattern(query: String)` should be used:
1. If the query already contains structured KQL syntax:
   * Contains a colon `:` (e.g. `content:`, `name:`, `tag:`, `mediatype:`, `mtime:`, `size:`)
   * Starts with `(`
   * Contains boolean operators ` AND `, ` OR `, or ` NOT `
   -> **Leave as-is** (do not double-wrap).
2. Otherwise (plain user query like `wandfarbe` or `annual report`):
   * Escape inner quotes: `val escaped = query.trim().replace("\"", "\\\"")`
   * Format as: `(name:"*$escaped*" OR content:"$escaped")`
3. For legacy fallback (`/remote.php/dav/files/<user>` when spaces returns 404):
   * Legacy ownCloud 10 does not support KQL; send raw query without KQL formatting (`useKql = false`).

---

## 3. Step-by-Step Implementation Guide for the Next Agent

### Step 1: Update `SearchMethod.kt` & `SearchRemoteFilesOperation.kt`
* **File**: `opencloudComLibrary/src/main/java/eu/opencloud/android/lib/common/http/methods/webdav/SearchMethod.kt`
  * Add `formatSearchPattern(query: String): String`:
    ```kotlin
    fun formatSearchPattern(query: String): String {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.contains(":") || trimmed.startsWith("(") ||
            trimmed.contains(" AND ") || trimmed.contains(" OR ") || trimmed.contains(" NOT ")
        ) {
            return trimmed
        }
        val escaped = trimmed.replace("\"", "\\\"")
        return """(name:"*$escaped*" OR content:"$escaped")"""
    }
    ```
  * In `buildSearchXml(pattern, limit, properties, useKql = true)`:
    * Apply `formatSearchPattern(pattern)` when `useKql == true`.
  * In `SearchMethod` constructor, add `useKql: Boolean = true`.
* **File**: `opencloudComLibrary/src/main/java/eu/opencloud/android/lib/resources/files/SearchRemoteFilesOperation.kt`
  * When executing initial search on `/remote.php/dav/spaces`, pass `useKql = true`.
  * In the 404 fallback block to `client.userFilesWebDavUri`, pass `useKql = false`.

### Step 2: Fix Debounce & Immediate Submit
* **File**: `opencloudApp/src/main/java/eu/opencloud/android/presentation/files/filelist/MainFileListViewModel.kt`
  * Increase `SEARCH_DEBOUNCE_MS` from `300L` to `500L` or `600L`.
  * Add an `immediateSearchTrigger = MutableSharedFlow<String>(extraBufferCapacity = 1)`.
  * Merge debounced flow with immediate submit flow and apply `.distinctUntilChanged()`:
    ```kotlin
    @OptIn(FlowPreview::class)
    private val debouncedSearchFilter: Flow<String> = merge(
        searchFilter.debounce { query ->
            if (query.isBlank()) 0L else SEARCH_DEBOUNCE_MS
        },
        immediateSearchTrigger
    ).distinctUntilChanged()
    ```
  * Add `fun submitSearchFilter(query: String)`:
    ```kotlin
    fun submitSearchFilter(query: String) {
        searchFilter.update { query }
        immediateSearchTrigger.tryEmit(query)
    }
    ```
* **File**: `opencloudApp/src/main/java/eu/opencloud/android/presentation/files/filelist/MainFileListFragment.kt`
  * In `onQueryTextSubmit(query: String?)`:
    * Call `mainFileListViewModel.submitSearchFilter(query)` instead of `updateSearchFilter(query)` so pressing Enter/Search executes immediately without delay.

### Step 3: Implement Advanced Search Modal & Button
1. **Toolbar Icon**:
   * **File**: `opencloudApp/src/main/res/layout/opencloud_toolbar.xml`
   * Add an `AppCompatImageView` with `android:id="@+id/root_toolbar_advanced_search"` between the `SearchView` and `root_toolbar_avatar`.
   * Icon: create or use `ic_tune.xml` (standard Material tune/filter sliders icon).
   * Visibility: `gone` by default; toggled to `visible` in `ToolbarActivity.kt` when `root_toolbar_search_view` is expanded.
2. **Advanced Search Dialog / BottomSheet**:
   * Create `AdvancedSearchDialogFragment` (or `BottomSheetDialogFragment` using `Theme.Design.BottomSheetDialog`).
   * Layout: `dialog_advanced_search.xml`
     * Header with title ("Advanced Search") and Reset button.
     * Container / RecyclerView for dynamic condition rows:
       * Row layout:
         * Operator selector: `AND` / `OR` (hidden for the first row).
         * Target scope selector: RadioGroup or ChipGroup with options:
           * "Title & Content" (Both) -> generates `(name:"*<term>*" OR content:"<term>")` or plain term
           * "Content only" -> generates `content:"<term>"`
           * "Title only" -> generates `name:"*<term>*"`
         * Text input for search term.
         * Delete button to remove condition (if more than 1).
     * Button: "+ Add Condition".
     * Live KQL preview box (showing the assembled query in real time, e.g. `content:"wandfarbe" AND 26`).
     * Action buttons: "Search" (applies query to searchView and executes) and "Cancel".
3. **Integration**:
   * In `MainFileListFragment.kt`:
     * Bind click listener on `root_toolbar_advanced_search` to show `AdvancedSearchDialogFragment`.
     * On query submission from dialog:
       * Set query on `searchView.setQuery(query, false)`
       * Call `mainFileListViewModel.submitSearchFilter(query)`

### Step 4: Unit & Integration Tests
* `SearchMethodTest.kt`: Test `formatSearchPattern` for plain terms, already-formatted KQL, quotes escaping, and `useKql = false`.
* `KeyAppViewModelsTest.kt`: Test `MainFileListViewModel` debounce and `submitSearchFilter`.
* Run verification commands:
  ```bash
  ./gradlew opencloudComLibrary:testDebugUnitTest
  ./gradlew opencloudApp:testOriginalDebugUnitTest
  ./gradlew detekt
  ```

---

## 4. What is implemented today (content search toggle + back navigation)

The Advanced Search Modal of Step 3 is **not** implemented. A smaller, dedicated feature was built
instead; keep these names in mind so the modal can reuse them.

* **Toolbar button** `root_toolbar_content_search` (`opencloud_toolbar.xml`): a 48dp
  `AppCompatImageView` between the `SearchView` and `root_toolbar_avatar`, `visibility="gone"` until
  the root search bar is expanded. `ToolbarActivity.setupRootToolbar()` shows/hides it in lockstep
  with the search bar (title click listener, `onCloseListener`, disabled branch) and
  `ToolbarActivity.restoreSearchBarToolbar()` re-expands the bar after back navigation; it no-ops on
  screens that use the standard toolbar. `SpacesListFragment.setTextHintRootToolbar()` hides the
  button because space searches only match space names.
* **Icons**: `opencloudApp/src/main/res/drawable/ic_content_search.xml` (inactive) and
  `ic_content_search_active.xml` (active), hand-written 24dp vectors tinted with
  `?attr/colorControlNormal` like the other vector icons of that folder.
* **Strings**: `content_description_search_content` ("Search file contents"),
  `search_content_enabled` ("Searching inside files"), `search_content_disabled` ("Searching file names").
* **ViewModel** `MainFileListViewModel.kt`: `contentSearchEnabled: MutableStateFlow<Boolean>` (public),
  `setContentSearchEnabled()`, `toggleContentSearch()`, `isContentSearchEnabled()`, `isSearchActive()`;
  `debouncedSearchFilter` became `effectiveSearchFilter = combine(searchFilter, contentSearchEnabled)
  { buildSearchPattern(...) }.debounce {}.distinctUntilChanged()`, which is what `fileListUiState`
  consumes, so a mode change re-runs the search for the same term. `updateFolderToDisplay()` clears
  both the filter and the mode.
  `buildSearchPattern()` escapes inner quotes and wraps free text as `content:"<term>"`;
  `isFreeTextQuery()` returns false when the query already contains `:`, starts with `(` or contains
  ` AND `/` OR `/` NOT ` (case sensitive, like the rules in section 2 above), which is how manual KQL
  such as `content:searchterm AND .pdf` survives untouched.
* **Fragment** `MainFileListFragment.kt`: `setContentSearchButtonListener()` (called from `initViews()`)
  attaches the click listener -> `toggleContentSearch()` + snackbar; `setContentSearchIcon()` and
  `observeContentSearchMode()` swap the icon from the `contentSearchEnabled` flow; plus
  `currentSearchQuery()`, `isContentSearchActive()` and `restoreSearch(query, contentSearchOnly)`,
  which delegate to the ViewModel and let `FileDisplayActivity` re-apply a search.
* **Back navigation (root cause fixed, needs device confirmation)**
  Root cause: programmatic "refresh the current folder" calls cleared the search while a document
  was open - `FileDisplayActivity.SyncBroadcastReceiver` (`navigateToFolder(currentDir)` on every
  folder-sync event) and `onResume()` (`updateFileListOption`) both ended in
  `MainFileListViewModel.updateFolderToDisplay()`, which blanks `searchFilter`. Back then found no
  search and walked to the parent folder. Fix: `updateFolderToDisplay(folder, keepSearchOnSameFolder)`
  keeps the search when the same folder (and same file-list option) is re-submitted;
  `MainFileListFragment.navigateToFolder()` and `updateFileListOption()` use it. User-driven
  navigation (folder click, FAB browse-up, drawer option change) still clears the search.
  Safety net: `FileDisplayActivity.setSecondFragment()` remembers a non-blank search in
  `searchQueryOnSecondFragment`/`contentSearchOnSecondFragment` (never overwritten by a blank one),
  `cleanSecondFragment()` resets it, and `onBackPressed()` does `cleanSecondFragment()` +
  `restoreSearch(...)` + `updateToolbar(...)` + `restoreSearchBarToolbar()`.
* **Test**: `KeyAppViewModelsTest.kt` -> `MainFileListViewModel content search mode restricts the query
  to file contents` verifies the wrapped `content:"wandfarbe"` pattern, that an already-KQL query is
  passed to `SearchFilesUseCase` unchanged, and that browsing into a folder clears the mode.
* **Still open**: `SearchMethod.formatSearchPattern`/`useKql` (Step 1), the `immediateSearchTrigger`
  submit path (Step 2) and the Advanced Search Modal (Step 3).

---

## 5. Key References in Codebase
* `SearchMethod.kt`: `opencloudComLibrary/src/main/java/eu/opencloud/android/lib/common/http/methods/webdav/SearchMethod.kt`
* `SearchRemoteFilesOperation.kt`: `opencloudComLibrary/src/main/java/eu/opencloud/android/lib/resources/files/SearchRemoteFilesOperation.kt`
* `MainFileListViewModel.kt`: `opencloudApp/src/main/java/eu/opencloud/android/presentation/files/filelist/MainFileListViewModel.kt`
* `MainFileListFragment.kt`: `opencloudApp/src/main/java/eu/opencloud/android/presentation/files/filelist/MainFileListFragment.kt`
* `ToolbarActivity.kt`: `opencloudApp/src/main/java/eu/opencloud/android/ui/activity/ToolbarActivity.kt`
* `opencloud_toolbar.xml`: `opencloudApp/src/main/res/layout/opencloud_toolbar.xml`
