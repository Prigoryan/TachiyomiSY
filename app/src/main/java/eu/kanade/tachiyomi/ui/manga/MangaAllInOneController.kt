package eu.kanade.tachiyomi.ui.manga

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.SelectableAdapter
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.databinding.MangaAllInOneControllerBinding
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.base.controller.NucleusController
import eu.kanade.tachiyomi.ui.base.controller.withFadeTransaction
import eu.kanade.tachiyomi.ui.browse.source.SourceController
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceController
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchController
import eu.kanade.tachiyomi.ui.library.ChangeMangaCategoriesDialog
import eu.kanade.tachiyomi.ui.library.LibraryController
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.main.offsetAppbarHeight
import eu.kanade.tachiyomi.ui.manga.chapter.ChaptersPresenter
import eu.kanade.tachiyomi.ui.manga.chapter.DeleteChaptersDialog
import eu.kanade.tachiyomi.ui.manga.chapter.DownloadCustomChaptersDialog
import eu.kanade.tachiyomi.ui.manga.chapter.MangaAllInOneChapterHolder
import eu.kanade.tachiyomi.ui.manga.chapter.MangaAllInOneChapterItem
import eu.kanade.tachiyomi.ui.manga.track.TrackController
import eu.kanade.tachiyomi.ui.migration.manga.design.PreMigrationController
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.recent.history.HistoryController
import eu.kanade.tachiyomi.ui.recent.updates.UpdatesController
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.getCoordinates
import eu.kanade.tachiyomi.util.view.gone
import eu.kanade.tachiyomi.util.view.shrinkOnScroll
import eu.kanade.tachiyomi.util.view.snack
import eu.kanade.tachiyomi.util.view.visible
import exh.EH_SOURCE_ID
import exh.EXH_SOURCE_ID
import java.util.Date
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import reactivecircus.flowbinding.android.view.clicks
import reactivecircus.flowbinding.swiperefreshlayout.refreshes
import timber.log.Timber
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

/**
 * Fragment that shows manga information.
 * Uses R.layout.manga_info_controller.
 * UI related actions should be called from here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MangaAllInOneController :
    NucleusController<MangaAllInOneControllerBinding, MangaAllInOnePresenter>,
    ChangeMangaCategoriesDialog.Listener,
    CoroutineScope,
    ActionMode.Callback,
    FlexibleAdapter.OnItemClickListener,
    FlexibleAdapter.OnItemLongClickListener,
    DownloadCustomChaptersDialog.Listener,
    DeleteChaptersDialog.Listener,
    MangaAllInOneAdapter.MangaAllInOneInterface {

    constructor(manga: Manga?, fromSource: Boolean = false, smartSearchConfig: SourceController.SmartSearchConfig? = null, update: Boolean = false) : super(
        Bundle().apply {
            putLong(MANGA_EXTRA, manga?.id ?: 0)
            putBoolean(FROM_SOURCE_EXTRA, fromSource)
            putParcelable(SMART_SEARCH_CONFIG_EXTRA, smartSearchConfig)
            putBoolean(UPDATE_EXTRA, update)
        }
    ) {
        this.manga = manga
        if (manga != null) {
            source = Injekt.get<SourceManager>().getOrStub(manga.source)
        }
    }

    // EXH -->
    constructor(redirect: ChaptersPresenter.EXHRedirect) : super(
        Bundle().apply {
            putLong(MANGA_EXTRA, redirect.manga.id!!)
            putBoolean(UPDATE_EXTRA, redirect.update)
        }
    ) {
        this.manga = redirect.manga
        if (manga != null) {
            source = Injekt.get<SourceManager>().getOrStub(redirect.manga.source)
        }
    }
    // EXH <--

    constructor(mangaId: Long) : this(
        Injekt.get<DatabaseHelper>().getManga(mangaId).executeAsBlocking()
    )

    @Suppress("unused")
    constructor(bundle: Bundle) : this(bundle.getLong(MANGA_EXTRA))

    var manga: Manga? = null
        private set

    var source: Source? = null
        private set

    private val preferences: PreferencesHelper by injectLazy()

    /**
     * Adapter containing a list of chapters.
     */
    private var adapter: MangaAllInOneAdapter? = null

    /**
     * Action mode for multiple selection.
     */
    private var actionMode: ActionMode? = null

    /**
     * Selected items. Used to restore selections after a rotation.
     */
    private val selectedItems = mutableSetOf<MangaAllInOneChapterItem>()

    private var lastClickPosition = -1

    private var initialLoad: Boolean = true

    // EXH -->
    val smartSearchConfig: SourceController.SmartSearchConfig? = args.getParcelable(SMART_SEARCH_CONFIG_EXTRA)

    override val coroutineContext: CoroutineContext = Job() + Dispatchers.Main
    // EXH <--

    val fromSource = args.getBoolean(FROM_SOURCE_EXTRA, false)

    var update = args.getBoolean(UPDATE_EXTRA, false)

    override val controllerScope = scope

    init {
        setHasOptionsMenu(true)
    }

    override fun getTitle(): String? {
        return manga?.title
    }

    override fun createPresenter(): MangaAllInOnePresenter {
        return MangaAllInOnePresenter(
            this, manga!!, source!!, smartSearchConfig
        )
    }

    override fun inflateView(inflater: LayoutInflater, container: ViewGroup): View {
        binding = MangaAllInOneControllerBinding.inflate(inflater)
        return binding.root
    }

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)

        if (manga == null || source == null) return

        // Set SwipeRefresh to refresh manga data.
        binding.swipeRefresh.refreshes()
            .onEach { fetchMangaFromSource(manualFetch = true) }
            .launchIn(scope)

        // Init RecyclerView and adapter
        adapter = MangaAllInOneAdapter(this, view.context)

        binding.recycler.adapter = adapter
        binding.recycler.layoutManager = LinearLayoutManager(view.context)
        binding.recycler.addItemDecoration(DividerItemDecoration(view.context, DividerItemDecoration.VERTICAL))
        binding.recycler.setHasFixedSize(true)
        adapter?.fastScroller = binding.fastScroller

        binding.fab.clicks()
            .onEach {
                val item = presenter.getNextUnreadChapter()
                if (item != null) {
                    // Create animation listener
                    val revealAnimationListener: Animator.AnimatorListener = object : AnimatorListenerAdapter() {
                        override fun onAnimationStart(animation: Animator?) {
                            openChapter(item.chapter, true)
                        }
                    }

                    // Get coordinates and start animation
                    val coordinates = binding.fab.getCoordinates()
                    if (!binding.revealView.showRevealEffect(coordinates.x, coordinates.y, revealAnimationListener)) {
                        openChapter(item.chapter)
                    }
                } else {
                    view.context.toast(R.string.no_next_chapter)
                }
            }
            .launchIn(scope)

        binding.fab.shrinkOnScroll(binding.recycler)

        binding.actionToolbar.offsetAppbarHeight(activity!!)
        binding.fab.offsetAppbarHeight(activity!!)
    }

    private fun getHeader(): MangaAllInOneHolder? {
        return binding.recycler.findViewHolderForAdapterPosition(0) as? MangaAllInOneHolder
    }

    private fun addMangaHeader() {
        if (adapter?.scrollableHeaders?.isEmpty() == true) {
            adapter?.removeAllScrollableHeaders()
            adapter?.addScrollableHeader(presenter.headerItem)
        }
    }

    // EXH -->
    override fun openSmartSearch() {
        val smartSearchConfig = SourceController.SmartSearchConfig(presenter.manga.title, presenter.manga.id!!)

        router?.pushController(
            SourceController(
                Bundle().apply {
                    putParcelable(SourceController.SMART_SEARCH_CONFIG, smartSearchConfig)
                }
            ).withFadeTransaction()
        )
    }

    override suspend fun mergeWithAnother() {
        try {
            val mergedManga = withContext(Dispatchers.IO + NonCancellable) {
                presenter.smartSearchMerge(presenter.manga, smartSearchConfig?.origMangaId!!)
            }

            router?.pushController(
                MangaAllInOneController(
                    mergedManga,
                    true,
                    update = true
                ).withFadeTransaction()
            )
            applicationContext?.toast("Manga merged!")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            else {
                applicationContext?.toast("Failed to merge manga: ${e.message}")
            }
        }
    }

    override fun copyToClipboard(label: String, text: String) {
        activity!!.copyToClipboard(label, text)
    }

    override fun migrateManga() {
        PreMigrationController.navigateToMigration(
            preferences.skipPreMigration().get(),
            router,
            listOf(presenter.manga.id!!)
        )
    }

    override fun mangaPresenter(): MangaAllInOnePresenter {
        return presenter
    }
    // EXH <--

    // AZ -->
    override fun openRecommends() {
        val recommendsConfig = BrowseSourceController.RecommendsConfig(presenter.manga)

        router?.pushController(
            BrowseSourceController(
                Bundle().apply {
                    putParcelable(BrowseSourceController.RECOMMENDS_CONFIG, recommendsConfig)
                }
            ).withFadeTransaction()
        )
    }
    // AZ <--

    override fun openTracking() {
        router?.pushController(
            TrackController(fromAllInOne = true, manga = manga).withFadeTransaction()
        )
    }

    /**
     * Check if manga is initialized.
     * If true update view with manga information,
     * if false fetch manga information
     *
     * @param manga manga object containing information about manga.
     * @param source the source of the manga.
     */
    override fun onNextManga(manga: Manga, source: Source, chapters: List<MangaAllInOneChapterItem>, lastUpdateDate: Date, chapterCount: Float) {
        if (manga.initialized) {
            // Update view.
            setMangaInfo(manga, source, chapters, lastUpdateDate, chapterCount)
            if (fromSource && !presenter.hasRequested && chapters.isNullOrEmpty()) {
                fetchMangaFromSource(fetchManga = false)
            }
        } else {
            // Initialize manga.
            fetchMangaFromSource()
        }
    }

    /**
     * Update the view with manga information.
     *
     * @param manga manga object containing information about manga.
     * @param source the source of the manga.
     */
    override fun setMangaInfo(manga: Manga, source: Source?, chapters: List<MangaAllInOneChapterItem>, lastUpdateDate: Date, chapterCount: Float) {
        val view = view ?: return

        if (update ||
            // Auto-update old format galleries
            (
                (presenter.manga.source == EH_SOURCE_ID || presenter.manga.source == EXH_SOURCE_ID) &&
                    chapters.size == 1 && chapters.first().date_upload == 0L
                )
        ) {
            update = false
            fetchMangaFromSource()
        }

        val adapter = adapter ?: return
        adapter.updateDataSet(chapters)
        addMangaHeader()
        adapter.recyclerView?.post {
            setChapterCount(chapterCount)
            setLastUpdateDate(lastUpdateDate)
        }

        if (selectedItems.isNotEmpty()) {
            adapter.clearSelection() // we need to start from a clean state, index may have changed
            createActionModeIfNeeded()
            selectedItems.forEach { item ->
                val position = adapter.indexOf(item)
                if (position != -1 && !adapter.isSelected(position)) {
                    adapter.toggleSelection(position)
                }
            }
            actionMode?.invalidate()
        }
    }

    override fun openInWebView() {
        val source = presenter.source as? HttpSource ?: return

        val url = try {
            source.mangaDetailsRequest(presenter.manga).url.toString()
        } catch (e: Exception) {
            return
        }

        val activity = activity ?: return
        val intent = WebViewActivity.newIntent(activity, url, source.id, presenter.manga.title)
        startActivity(intent)
    }

    /**
     * Called to run Intent with [Intent.ACTION_SEND], which show share dialog.
     */
    override fun shareManga() {
        val context = view?.context ?: return

        val source = presenter.source as? HttpSource ?: return
        try {
            val url = source.mangaDetailsRequest(presenter.manga).url.toString()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            }
            startActivity(Intent.createChooser(intent, context.getString(R.string.action_share)))
        } catch (e: Exception) {
            context.toast(e.message)
        }
    }

    /**
     * Start fetching manga information from source.
     */
    override fun fetchMangaFromSource(manualFetch: Boolean, fetchManga: Boolean, fetchChapters: Boolean) {
        setRefreshing(true)
        // Call presenter and start fetching manga information
        presenter.fetchMangaFromSource(manualFetch, fetchManga, fetchChapters)
    }

    override fun onFetchMangaDone() {
        setRefreshing(false)
    }

    /**
     * Update swipe refresh to start showing refresh in progress spinner.
     */
    override fun onFetchMangaError(error: Throwable) {
        setRefreshing(false)
        activity?.toast(error.message)
    }

    /**
     * Set swipe refresh status.
     *
     * @param value whether it should be refreshing or not.
     */
    override fun setRefreshing(value: Boolean) {
        binding.swipeRefresh.isRefreshing = value
    }

    override fun onFavoriteClick() {
        val manga = presenter.manga

        if (manga.favorite) {
            getHeader()?.toggleFavorite()
            activity?.toast(activity?.getString(R.string.manga_removed_library))
        } else {
            val categories = presenter.getCategories()
            val defaultCategoryId = preferences.defaultCategory()
            val defaultCategory = categories.find { it.id == defaultCategoryId }

            when {
                // Default category set
                defaultCategory != null -> {
                    getHeader()?.toggleFavorite()
                    presenter.moveMangaToCategory(manga, defaultCategory)
                    activity?.toast(activity?.getString(R.string.manga_added_library))
                }

                // Automatic 'Default' or no categories
                defaultCategoryId == 0 || categories.isEmpty() -> {
                    getHeader()?.toggleFavorite()
                    presenter.moveMangaToCategory(manga, null)
                    activity?.toast(activity?.getString(R.string.manga_added_library))
                }

                // Choose a category
                else -> {
                    val ids = presenter.getMangaCategoryIds(manga)
                    val preselected = ids.mapNotNull { id ->
                        categories.indexOfFirst { it.id == id }.takeIf { it != -1 }
                    }.toTypedArray()

                    ChangeMangaCategoriesDialog(this, listOf(manga), categories, preselected)
                        .showDialog(router)
                }
            }
        }
    }

    override fun onCategoriesClick() {
        val manga = presenter.manga
        val categories = presenter.getCategories()

        val ids = presenter.getMangaCategoryIds(manga)
        val preselected = ids.mapNotNull { id ->
            categories.indexOfFirst { it.id == id }.takeIf { it != -1 }
        }.toTypedArray()

        ChangeMangaCategoriesDialog(this, listOf(manga), categories, preselected)
            .showDialog(router)
    }

    override fun updateCategoriesForMangas(mangas: List<Manga>, categories: List<Category>) {
        val manga = mangas.firstOrNull() ?: return

        if (!manga.favorite) {
            getHeader()?.toggleFavorite()
            activity?.toast(activity?.getString(R.string.manga_added_library))
        }

        presenter.moveMangaToCategories(manga, categories)
    }

    /**
     * Perform a global search using the provided query.
     *
     * @param query the search query to pass to the search controller
     */
    override fun performGlobalSearch(query: String) {
        val router = router ?: return
        router.pushController(GlobalSearchController(query).withFadeTransaction())
    }

    private fun setChapterCount(count: Float) {
        getHeader()?.setChapterCount(count)
    }

    private fun setLastUpdateDate(date: Date) {
        getHeader()?.setLastUpdateDate(date)
    }

    fun setFavoriteButtonState(isFavorite: Boolean) {
        getHeader()?.setFavoriteButtonState(isFavorite)
    }

    override fun isInitialLoadAndFromSource() = fromSource && initialLoad

    override fun removeInitialLoad() {
        initialLoad = false
    }

    // --> EH
    override fun wrapTag(namespace: String, tag: String) =
        if (tag.contains(' ')) {
            "$namespace:\"$tag$\""
        } else {
            "$namespace:$tag$"
        }

    private fun parseTag(tag: String) = tag.substringBefore(':').trim() to tag.substringAfter(':').trim()

    override fun isEHentaiBasedSource(): Boolean {
        val sourceId = presenter.source.id
        return sourceId == EH_SOURCE_ID ||
            sourceId == EXH_SOURCE_ID
    }
    // <-- EH

    /**
     * Perform a search using the provided query.
     *
     * @param query the search query to the previous controller
     */
    override fun performSearch(query: String) {
        val router = router ?: return

        if (router.backstackSize < 2) {
            return
        }

        when (val previousController = router.backstack[router.backstackSize - 2].controller()) {
            is LibraryController -> {
                router.handleBack()
                previousController.search(query)
            }
            is UpdatesController,
            is HistoryController -> {
                // Manually navigate to LibraryController
                router.handleBack()
                (router.activity as MainActivity).setSelectedNavItem(R.id.nav_library)
                val controller = router.getControllerWithTag(R.id.nav_library.toString()) as LibraryController
                controller.search(query)
            }
            is BrowseSourceController -> {
                router.handleBack()
                previousController.searchWithQuery(query)
            }
        }
    }

    // CHAPTER FUNCTIONS START HERE
    override fun onDestroyView(view: View) {
        super.onDestroyView(view)
        destroyActionModeIfNeeded()
        binding.actionToolbar.destroy()
        adapter = null
    }

    override fun onActivityResumed(activity: Activity) {
        if (view == null) return

        // Check if animation view is visible
        if (binding.revealView.visibility == View.VISIBLE) {
            // Show the unreveal effect
            val coordinates = binding.fab.getCoordinates()
            binding.revealView.hideRevealEffect(coordinates.x, coordinates.y, 1920)
        }

        super.onActivityResumed(activity)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.chapters, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        // Initialize menu items.
        val menuFilterRead = menu.findItem(R.id.action_filter_read) ?: return
        val menuFilterUnread = menu.findItem(R.id.action_filter_unread)
        val menuFilterDownloaded = menu.findItem(R.id.action_filter_downloaded)
        val menuFilterBookmarked = menu.findItem(R.id.action_filter_bookmarked)
        val menuFilterEmpty = menu.findItem(R.id.action_filter_empty)

        // Set correct checkbox values.
        menuFilterRead.isChecked = presenter.onlyRead()
        menuFilterUnread.isChecked = presenter.onlyUnread()
        menuFilterDownloaded.isChecked = presenter.onlyDownloaded()
        menuFilterDownloaded.isEnabled = !presenter.forceDownloaded()
        menuFilterBookmarked.isChecked = presenter.onlyBookmarked()

        val filterSet = presenter.onlyRead() || presenter.onlyUnread() || presenter.onlyDownloaded() || presenter.onlyBookmarked()

        if (filterSet) {
            val filterColor = activity!!.getResourceColor(R.attr.colorFilterActive)
            DrawableCompat.setTint(menu.findItem(R.id.action_filter).icon, filterColor)
        }

        // Only show remove filter option if there's a filter set.
        menuFilterEmpty.isVisible = filterSet

        // Disable unread filter option if read filter is enabled.
        if (presenter.onlyRead()) {
            menuFilterUnread.isEnabled = false
        }
        // Disable read filter option if unread filter is enabled.
        if (presenter.onlyUnread()) {
            menuFilterRead.isEnabled = false
        }

        // Display mode submenu
        if (presenter.manga.displayMode == Manga.DISPLAY_NAME) {
            menu.findItem(R.id.display_title).isChecked = true
        } else {
            menu.findItem(R.id.display_chapter_number).isChecked = true
        }

        // Sorting mode submenu
        if (presenter.manga.sorting == Manga.SORTING_SOURCE) {
            menu.findItem(R.id.sort_by_source).isChecked = true
        } else {
            menu.findItem(R.id.sort_by_number).isChecked = true
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.display_title -> {
                item.isChecked = true
                setDisplayMode(Manga.DISPLAY_NAME)
            }
            R.id.display_chapter_number -> {
                item.isChecked = true
                setDisplayMode(Manga.DISPLAY_NUMBER)
            }

            R.id.sort_by_source -> {
                item.isChecked = true
                presenter.setSorting(Manga.SORTING_SOURCE)
            }
            R.id.sort_by_number -> {
                item.isChecked = true
                presenter.setSorting(Manga.SORTING_NUMBER)
            }

            R.id.download_next, R.id.download_next_5, R.id.download_next_10,
            R.id.download_custom, R.id.download_unread, R.id.download_all
            -> downloadChapters(item.itemId)

            R.id.action_filter_unread -> {
                item.isChecked = !item.isChecked
                presenter.setUnreadFilter(item.isChecked)
                activity?.invalidateOptionsMenu()
            }
            R.id.action_filter_read -> {
                item.isChecked = !item.isChecked
                presenter.setReadFilter(item.isChecked)
                activity?.invalidateOptionsMenu()
            }
            R.id.action_filter_downloaded -> {
                item.isChecked = !item.isChecked
                presenter.setDownloadedFilter(item.isChecked)
                activity?.invalidateOptionsMenu()
            }
            R.id.action_filter_bookmarked -> {
                item.isChecked = !item.isChecked
                presenter.setBookmarkedFilter(item.isChecked)
                activity?.invalidateOptionsMenu()
            }
            R.id.action_filter_empty -> {
                presenter.removeFilters()
                activity?.invalidateOptionsMenu()
            }
            R.id.action_sort -> presenter.revertSortOrder()
        }
        return super.onOptionsItemSelected(item)
    }

    fun onChapterStatusChange(download: Download) {
        getHolder(download.chapter)?.notifyStatus(download.status)
    }

    private fun getHolder(chapter: Chapter): MangaAllInOneChapterHolder? {
        return binding.recycler.findViewHolderForItemId(chapter.id!!) as? MangaAllInOneChapterHolder
    }

    fun openChapter(chapter: Chapter, hasAnimation: Boolean = false) {
        val activity = activity ?: return
        val intent = ReaderActivity.newIntent(activity, presenter.manga, chapter)
        if (hasAnimation) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        startActivity(intent)
    }

    override fun onItemClick(view: View?, position: Int): Boolean {
        val adapter = adapter ?: return false
        val item = adapter.getItem(position) as MangaAllInOneChapterItem? ?: return false
        return if (actionMode != null && adapter.mode == SelectableAdapter.Mode.MULTI) {
            lastClickPosition = position
            toggleSelection(position)
            true
        } else {
            openChapter(item.chapter)
            false
        }
    }

    override fun onItemLongClick(position: Int) {
        createActionModeIfNeeded()
        when {
            lastClickPosition == -1 -> setSelection(position)
            lastClickPosition > position ->
                for (i in position until lastClickPosition)
                    setSelection(i)
            lastClickPosition < position ->
                for (i in lastClickPosition + 1..position)
                    setSelection(i)
            else -> setSelection(position)
        }
        lastClickPosition = position
        adapter?.notifyDataSetChanged()
    }

    // SELECTIONS & ACTION MODE

    private fun toggleSelection(position: Int) {
        val adapter = adapter ?: return
        val item = adapter.getItem(position) ?: return
        adapter.toggleSelection(position)
        adapter.notifyDataSetChanged()
        if (adapter.isSelected(position)) {
            selectedItems.add(item as MangaAllInOneChapterItem)
        } else {
            selectedItems.remove(item)
        }
        actionMode?.invalidate()
    }

    private fun setSelection(position: Int) {
        val adapter = adapter ?: return
        val item = adapter.getItem(position) ?: return
        if (!adapter.isSelected(position)) {
            adapter.toggleSelection(position)
            selectedItems.add(item as MangaAllInOneChapterItem)
            actionMode?.invalidate()
        }
    }

    private fun getSelectedChapters(): List<MangaAllInOneChapterItem> {
        val adapter = adapter ?: return emptyList()
        return adapter.selectedPositions.mapNotNull { adapter.getItem(it) as MangaAllInOneChapterItem }
    }

    private fun createActionModeIfNeeded() {
        if (actionMode == null) {
            actionMode = (activity as? AppCompatActivity)?.startSupportActionMode(this)
            binding.actionToolbar.show(
                actionMode!!,
                R.menu.chapter_selection
            ) { onActionItemClicked(it!!) }
        }
    }

    private fun destroyActionModeIfNeeded() {
        lastClickPosition = -1
        actionMode?.finish()
    }

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        mode.menuInflater.inflate(R.menu.generic_selection, menu)
        adapter?.mode = SelectableAdapter.Mode.MULTI
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        val count = adapter?.selectedItemCount ?: 0
        if (count == 0) {
            // Destroy action mode if there are no items selected.
            destroyActionModeIfNeeded()
        } else {
            mode.title = count.toString()

            val chapters = getSelectedChapters()
            binding.actionToolbar.findItem(R.id.action_download)?.isVisible = chapters.any { !it.isDownloaded }
            binding.actionToolbar.findItem(R.id.action_delete)?.isVisible = chapters.any { it.isDownloaded }
            binding.actionToolbar.findItem(R.id.action_bookmark)?.isVisible = chapters.any { !it.chapter.bookmark }
            binding.actionToolbar.findItem(R.id.action_remove_bookmark)?.isVisible = chapters.all { it.chapter.bookmark }
            binding.actionToolbar.findItem(R.id.action_mark_as_read)?.isVisible = chapters.any { !it.chapter.read }
            binding.actionToolbar.findItem(R.id.action_mark_as_unread)?.isVisible = chapters.all { it.chapter.read }

            // Hide FAB to avoid interfering with the bottom action toolbar
            // binding.fab.hide()
            binding.fab.gone()
        }
        return false
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        return onActionItemClicked(item)
    }

    private fun onActionItemClicked(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_select_all -> selectAll()
            R.id.action_select_inverse -> selectInverse()
            R.id.action_download -> downloadChapters(getSelectedChapters())
            R.id.action_delete -> showDeleteChaptersConfirmationDialog()
            R.id.action_bookmark -> bookmarkChapters(getSelectedChapters(), true)
            R.id.action_remove_bookmark -> bookmarkChapters(getSelectedChapters(), false)
            R.id.action_mark_as_read -> markAsRead(getSelectedChapters())
            R.id.action_mark_as_unread -> markAsUnread(getSelectedChapters())
            R.id.action_mark_previous_as_read -> markPreviousAsRead(getSelectedChapters())
            else -> return false
        }
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        binding.actionToolbar.hide()
        adapter?.mode = SelectableAdapter.Mode.SINGLE
        adapter?.clearSelection()
        selectedItems.clear()
        actionMode = null

        // TODO: there seems to be a bug in MaterialComponents where the [ExtendedFloatingActionButton]
        // fails to show up properly
        // binding.fab.show()
        binding.fab.visible()
    }

    override fun onDetach(view: View) {
        destroyActionModeIfNeeded()
        super.onDetach(view)
    }

    // SELECTION MODE ACTIONS

    private fun selectAll() {
        val adapter = adapter ?: return
        adapter.selectAll()
        selectedItems.addAll(adapter.items)
        actionMode?.invalidate()
    }

    private fun selectInverse() {
        val adapter = adapter ?: return

        selectedItems.clear()
        for (i in 0..adapter.itemCount) {
            adapter.toggleSelection(i)
        }
        selectedItems.addAll(adapter.selectedPositions.mapNotNull { adapter.getItem(it) as MangaAllInOneChapterItem })

        actionMode?.invalidate()
        adapter.notifyDataSetChanged()
    }

    private fun markAsRead(chapters: List<MangaAllInOneChapterItem>) {
        presenter.markChaptersRead(chapters, true)
        if (presenter.preferences.removeAfterMarkedAsRead()) {
            deleteChapters(chapters)
        }
    }

    private fun markAsUnread(chapters: List<MangaAllInOneChapterItem>) {
        presenter.markChaptersRead(chapters, false)
    }

    private fun downloadChapters(chapters: List<MangaAllInOneChapterItem>) {
        val view = view
        presenter.downloadChapters(chapters)
        if (view != null && !presenter.manga.favorite) {
            binding.recycler.snack(view.context.getString(R.string.snack_add_to_library), Snackbar.LENGTH_INDEFINITE) {
                setAction(R.string.action_add) {
                    presenter.addToLibrary()
                }
            }
        }
    }

    private fun showDeleteChaptersConfirmationDialog() {
        DeleteChaptersDialog(this).showDialog(router)
    }

    override fun deleteChapters() {
        deleteChapters(getSelectedChapters())
    }

    private fun markPreviousAsRead(chapters: List<MangaAllInOneChapterItem>) {
        val adapter = adapter ?: return
        val prevChapters = if (presenter.sortDescending()) adapter.items.reversed() else adapter.items
        val chapterPos = prevChapters.indexOf(chapters.last())
        if (chapterPos != -1) {
            markAsRead(prevChapters.take(chapterPos))
        }
    }

    private fun bookmarkChapters(chapters: List<MangaAllInOneChapterItem>, bookmarked: Boolean) {
        presenter.bookmarkChapters(chapters, bookmarked)
    }

    fun deleteChapters(chapters: List<MangaAllInOneChapterItem>) {
        if (chapters.isEmpty()) return

        presenter.deleteChapters(chapters)
    }

    fun onChaptersDeleted(chapters: List<MangaAllInOneChapterItem>) {
        // this is needed so the downloaded text gets removed from the item
        chapters.forEach {
            adapter?.updateItem(it)
        }
        adapter?.notifyDataSetChanged()
    }

    fun onChaptersDeletedError(error: Throwable) {
        Timber.e(error)
    }

    // OVERFLOW MENU DIALOGS

    private fun setDisplayMode(id: Int) {
        presenter.setDisplayMode(id)
        adapter?.notifyDataSetChanged()
    }

    private fun getUnreadChaptersSorted() = presenter.chapters
        .filter { !it.read && it.status == Download.NOT_DOWNLOADED }
        .distinctBy { it.name }
        .sortedByDescending { it.source_order }

    private fun downloadChapters(choice: Int) {
        val chaptersToDownload = when (choice) {
            R.id.download_next -> getUnreadChaptersSorted().take(1)
            R.id.download_next_5 -> getUnreadChaptersSorted().take(5)
            R.id.download_next_10 -> getUnreadChaptersSorted().take(10)
            R.id.download_custom -> {
                showCustomDownloadDialog()
                return
            }
            R.id.download_unread -> presenter.chapters.filter { !it.read }
            R.id.download_all -> presenter.chapters
            else -> emptyList()
        }
        if (chaptersToDownload.isNotEmpty()) {
            downloadChapters(chaptersToDownload)
        }
    }

    private fun showCustomDownloadDialog() {
        DownloadCustomChaptersDialog(this, presenter.chapters.size).showDialog(router)
    }

    override fun downloadCustomChapters(amount: Int) {
        val chaptersToDownload = getUnreadChaptersSorted().take(amount)
        if (chaptersToDownload.isNotEmpty()) {
            downloadChapters(chaptersToDownload)
        }
    }

    companion object {
        // EXH -->
        const val UPDATE_EXTRA = "update"
        const val SMART_SEARCH_CONFIG_EXTRA = "smartSearchConfig"
        // EXH <--
        const val FROM_SOURCE_EXTRA = "from_source"
        const val MANGA_EXTRA = "manga"
    }
}
