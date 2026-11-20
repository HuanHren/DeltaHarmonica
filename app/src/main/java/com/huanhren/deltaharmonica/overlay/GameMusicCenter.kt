package com.huanhren.deltaharmonica.overlay

import android.content.res.ColorStateList
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.isEmpty
import androidx.core.view.isNotEmpty
import androidx.core.widget.doAfterTextChanged
import com.huanhren.deltaharmonica.R
import com.huanhren.deltaharmonica.data.SongLibraryItem
import com.huanhren.deltaharmonica.midi.SongCompatibility
import com.huanhren.deltaharmonica.midi.SongCompatibilityReport
import com.huanhren.deltaharmonica.model.InstrumentLayout
import com.huanhren.deltaharmonica.online.OnlineCatalog
import com.huanhren.deltaharmonica.online.OnlineEntry
import com.huanhren.deltaharmonica.online.OnlineScore
import com.huanhren.deltaharmonica.playback.PlaybackPreparation
import com.huanhren.deltaharmonica.ui.AppViewModel
import com.huanhren.deltaharmonica.ui.OnlineSongStage
import com.huanhren.deltaharmonica.ui.currentPlaybackControls
import com.huanhren.deltaharmonica.ui.songCompatibilityLabel
import com.huanhren.deltaharmonica.ui.songDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat

/** Retain the search field and list when transfer progress or playback changes. */
internal class GameMusicCenter(
    private val style: OverlayStyle,
    private val model: AppViewModel,
    parentScope: CoroutineScope,
    private val prefs: MusicCenterPreferences,
    private val layout: () -> InstrumentLayout?,
    private val onKeyboard: (Boolean) -> Unit,
    private val onPlay: () -> Unit,
) {
    private enum class Tab(val title: Int) {
        ONLINE(R.string.game_music_online), LOCAL(R.string.game_music_saved),
        RECENT(R.string.game_music_recent), FAVORITES(R.string.game_music_favorites),
    }

    private data class Row(val title: String, val subtitle: String = "", val entry: OnlineEntry? = null,
        val item: SongLibraryItem? = null, val query: String? = null)

    private val context = style.context
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val tabs = row()
    private val tools = row()
    private val status = style.text("").apply { setPadding(style.dp(4), style.dp(3), style.dp(4), style.dp(3)) }
    private val search = object : EditText(context) {
        override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
            if (keyCode == KeyEvent.KEYCODE_BACK && editing) {
                if (event.action == KeyEvent.ACTION_UP) releaseKeyboard()
                return true
            }
            return super.onKeyPreIme(keyCode, event)
        }
    }.apply {
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT
        imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        hint = label(R.string.game_music_search_hint)
        contentDescription = label(R.string.online_search)
        setTextColor(style.foreground)
        setHintTextColor(style.secondaryForeground)
        backgroundTintList = ColorStateList.valueOf(style.accent)
        textSize = 14f
        filters = arrayOf(InputFilter.LengthFilter(128))
    }
    private val adapter = object : BaseAdapter() {
        private var rows = emptyList<Row>()
        fun submit(next: List<Row>) {
            if (rows == next) return
            rows = next
            notifyDataSetChanged()
        }

        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            ((convertView as? TextView) ?: style.text("", 13f, strong = true).apply {
                maxLines = 2
                minHeight = style.touchSize
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                setPadding(style.dp(5), style.dp(2), style.dp(5), style.dp(2))
            }).apply {
                val item = getItem(position)
                text = if (item.subtitle.isBlank()) item.title else item.title + "\n" + item.subtitle
            }
    }
    private val list = ListView(context).apply {
        adapter = this@GameMusicCenter.adapter
        dividerHeight = style.dp(1)
        setOnItemClickListener { _, _, index, _ ->
            val item = this@GameMusicCenter.adapter.getItem(index)
            item.query?.let(::commitQuery)
            item.entry?.let { entry ->
                prefs.remember(query)
                openDetail(entry)
            }
            item.item?.let(::openLocal)
        }
    }
    private val detailBody = column()
    private val detailScroll = ScrollView(context).apply { addView(detailBody); visibility = View.GONE }
    val view = column().apply {
        isFocusableInTouchMode = true
        addView(tabs)
        addView(search, -1, -2)
        addView(tools)
        addView(status, -1, -2)
        addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(detailScroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private var tab = Tab.ONLINE
    private var catalog: OnlineCatalog? = null
    private var results = emptyList<OnlineEntry>()
    private var query = ""
    private var pageSize = 24
    private var history = false
    private var editing = false
    private var activeSession = false
    private var shown = false
    private var currentEntry: OnlineEntry? = null
    private var currentLocal: SongLibraryItem? = null
    private var score: OnlineScore? = null
    private var compatibility: SongCompatibilityReport? = null
    private var catalogJob: Job? = null
    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var transferJob: Job? = null
    private var catalogFailure: String? = null
    private var detailFailure: String? = null
    private var operation: String? = null
    private var transferMessage: String? = null
    private var transferSequence = 0
    private var cached = emptyMap<String, SongLibraryItem>()

    init {
        search.doAfterTextChanged { text ->
            val value = text.toString()
            if (value != query) {
                query = value
                history = false
                filter()
            }
        }
        search.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            commitQuery(query)
            true
        }
        installSearchFocus()
        scope.launch {
            combine(model.songs.items, model.songs.problems, model.libraryBusy) { items, problems, _ ->
                buildMap<String, SongLibraryItem> {
                    for (item in items) {
                        if (item.id in problems) continue
                        for (id in item.online?.remoteIds.orEmpty()) {
                            val revision = item.online?.revision(id) ?: continue
                            if (revision >= (get(id)?.online?.revision(id) ?: Long.MIN_VALUE)) put(id, item)
                        }
                    }
                }
            }.collect { downloaded: Map<String, SongLibraryItem> ->
                cached = downloaded
                if (transferJob?.isActive != true) render()
            }
        }
        model.preferences?.let { scope.launch { it.recent.collect { if (tab == Tab.RECENT) render() } } }
        scope.launch {
            PlaybackPreparation.progress
                .collect { if (shown && !activeSession && (currentEntry != null || currentLocal != null)) render() }
        }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun installSearchFocus() {
        search.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN && !editing && !activeSession) startEditing()
            false
        }
        search.setOnFocusChangeListener { _, focused -> if (!focused && editing) releaseKeyboard() }
    }

    private fun startEditing() {
        editing = true
        onKeyboard(true)
        search.post {
            if (editing && shown) {
                search.requestFocus()
                context.getSystemService(InputMethodManager::class.java)?.showSoftInput(search, 0)
            }
        }
    }

    fun releaseKeyboard() {
        if (!editing) return
        editing = false
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
        view.requestFocus()
        onKeyboard(false)
    }

    fun show(duringPlayback: Boolean) {
        if (duringPlayback) releaseKeyboard()
        activeSession = duringPlayback
        shown = true
        if (catalog == null && catalogFailure == null && catalogJob?.isActive != true && tab == Tab.ONLINE) reload(false)
        render()
    }

    fun hide() {
        shown = false
        releaseKeyboard()
    }

    fun dispose() {
        hide()
        scope.cancel()
    }

    private fun commitQuery(value: String) {
        val saved = value.trim()
        prefs.remember(saved)
        query = saved
        search.setText(saved)
        search.setSelection(saved.length)
        history = false
        releaseKeyboard()
        filter()
    }

    private fun filter() {
        searchJob?.cancel()
        pageSize = 24
        val current = catalog ?: return render()
        val needle = query
        searchJob = scope.launch {
            if (editing) delay(100)
            results = withContext(Dispatchers.Default) { current.search(needle) }
            list.setSelection(0)
            render()
        }
    }

    private fun reload(force: Boolean) {
        if (catalogJob?.isActive == true) return
        catalogFailure = null
        catalogJob = scope.launch {
            try {
                catalog = model.onlineLibrary.catalog(force)
                filter()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                catalogFailure = error.localizedMessage ?: label(R.string.online_catalog_error)
            } finally {
                catalogJob = null
                render()
            }
        }
        render()
    }

    private fun openDetail(entry: OnlineEntry, refresh: Boolean = false) {
        releaseKeyboard()
        detailJob?.cancel()
        currentEntry = entry
        currentLocal = null
        score = null
        compatibility = null
        detailFailure = null
        transferMessage = null
        if (entry.id !in cached || refresh) {
            detailJob = scope.launch {
                try {
                    val loaded = model.onlineLibrary.detail(entry.id, refresh)
                    score = loaded
                    compatibility = model.inspectOnlineSong(loaded, layout())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    detailFailure = error.localizedMessage ?: label(R.string.online_detail_error)
                } finally {
                    if (currentEntry?.id == entry.id) render()
                }
            }
        }
        detailScroll.scrollTo(0, 0)
        render()
    }

    private fun openLocal(item: SongLibraryItem) {
        releaseKeyboard()
        currentLocal = item
        currentEntry = null
        transferMessage = null
        detailScroll.scrollTo(0, 0)
        render()
    }

    private fun backToList() {
        detailJob?.cancel()
        currentEntry = null
        currentLocal = null
        render()
    }

    private fun mayActivate() =
        !activeSession && currentPlaybackControls().canStart && !model.libraryBusy.value && !model.imports.state.value.active

    private fun transfer(entry: OnlineEntry, activate: Boolean) {
        if (transferJob?.isActive == true || (activate && !mayActivate())) return
        val sequence = ++transferSequence
        transferMessage = null
        val selected = score
        transferJob = scope.launch {
            var stage = R.string.online_downloading
            try {
                model.prepareOnlineSong(entry, selected, layout(), activate, onStage = {
                    stage = when (it) {
                        OnlineSongStage.DOWNLOADING -> R.string.online_downloading
                        OnlineSongStage.CONVERTING -> R.string.online_converting
                        OnlineSongStage.SAVING -> R.string.online_saving
                        OnlineSongStage.ACTIVATING -> R.string.online_activating
                    }
                    operation = label(stage)
                    renderStatus()
                }, onProgress = { bytes, total ->
                    if (total != null && total > 0) view.post {
                        if (sequence == transferSequence && stage == R.string.online_downloading) {
                            operation = label(R.string.online_transfer_percent, label(stage), (bytes * 100 / total).toInt())
                            renderStatus()
                        }
                    }
                })
                transferMessage = label(R.string.game_music_saved_done)
                if (activate && shown && !activeSession && currentEntry?.id == entry.id) onPlay()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                transferMessage = error.localizedMessage ?: label(R.string.online_transfer_error)
            } finally {
                transferSequence++
                operation = null
                transferJob = null
                render()
            }
        }
        render()
    }

    private fun activateLocal(item: SongLibraryItem) {
        if (!mayActivate()) return
        model.activateSong(item.id) { error ->
            if (!shown || currentLocal?.id != item.id) return@activateSong
            if (error == null) onPlay() else {
                transferMessage = error
                render()
            }
        }
    }

    private fun renderStatus() {
        if (shown && (currentEntry != null || currentLocal != null)) {
            status.visibility = View.VISIBLE
            status.text = operation.orEmpty()
        }
    }

    private fun render() {
        if (!shown) return
        val entry = currentEntry
        val local = currentLocal
        val detailOpen = entry != null || local != null
        tabs.visibility = if (detailOpen) View.GONE else View.VISIBLE
        tools.visibility = if (detailOpen) View.GONE else View.VISIBLE
        search.visibility = if (!detailOpen && tab == Tab.ONLINE && !activeSession) View.VISIBLE else View.GONE
        list.visibility = if (detailOpen) View.GONE else View.VISIBLE
        detailScroll.visibility = if (detailOpen) View.VISIBLE else View.GONE
        if (detailOpen) renderDetail(entry, local) else renderList()
    }

    private fun renderList() {
        tabs.removeAllViews()
        Tab.entries.forEach { next ->
            tabs.action(label(next.title), selected = tab == next) {
                releaseKeyboard()
                tab = next
                history = false
                if (tab == Tab.ONLINE && catalog == null) reload(false)
                list.setSelection(0)
                render()
            }
        }
        tools.removeAllViews()
        val rows = when (tab) {
            Tab.ONLINE -> {
                tools.action(label(if (catalogFailure != null) R.string.retry else R.string.online_refresh),
                    enabled = catalogJob?.isActive != true) { reload(true) }
                if (!activeSession || prefs.searches.isNotEmpty()) {
                    tools.action(label(R.string.game_music_history), selected = history) {
                        releaseKeyboard()
                        history = !history
                        render()
                    }
                    tools.action(label(if (history) R.string.game_music_clear_history else R.string.game_music_clear_query)) {
                        if (history) { prefs.clearSearches(); render() } else commitQuery("")
                    }
                }
                if (!history && results.size > pageSize) tools.action(label(R.string.online_more)) { pageSize += 24; render() }
                if (history) prefs.searches.map { Row(it, query = it) } else results.take(pageSize).map(::onlineRow)
            }
            Tab.LOCAL -> model.songs.items.value.map { Row(it.displayName, localSource(it), item = it) }
            Tab.RECENT -> {
                val items = model.songs.items.value.associateBy { it.id }
                model.preferences?.recent?.value.orEmpty().entries.sortedByDescending { it.value }.mapNotNull { (id, time) ->
                    items[id]?.let { item ->
                        val date = DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                        Row(item.displayName, label(R.string.game_music_recent_track, localSource(item), date.toString()), item = item)
                    }
                }
            }
            Tab.FAVORITES -> prefs.favorites.values.toList().asReversed().map(::onlineRow)
        }
        tools.visibility = if (tools.isEmpty()) View.GONE else View.VISIBLE
        adapter.submit(rows)
        status.text = when {
            tab == Tab.ONLINE && catalogFailure != null -> catalogFailure
            tab == Tab.ONLINE && catalogJob?.isActive == true -> label(R.string.studio_library_wait)
            rows.isEmpty() -> label(R.string.game_music_no_matches)
            tab == Tab.ONLINE && catalog?.stale == true -> label(R.string.online_cached)
            tab == Tab.ONLINE && activeSession -> label(R.string.game_music_playback_search_blocked)
            tab == Tab.ONLINE && query.isBlank() && !history -> label(R.string.game_music_browse_latest)
            else -> ""
        }
        status.visibility = if (status.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun onlineRow(entry: OnlineEntry): Row {
        val byline = entry.composer.ifBlank { entry.uploader }
        val title = if (entry.id in cached) label(R.string.game_music_cached_track, entry.title) else entry.title
        return Row(title, byline, entry = entry)
    }

    private fun localSource(item: SongLibraryItem) =
        label(if (item.online == null) R.string.game_music_saved else R.string.online_available_offline)

    private fun renderDetail(entry: OnlineEntry?, item: SongLibraryItem?) {
        val cachedItem = entry?.id?.let(cached::get)
        status.text = operation ?: transferMessage ?: detailFailure ?: when {
            entry != null && cachedItem != null -> label(R.string.online_available_offline)
            detailJob?.isActive == true && score == null -> label(R.string.online_loading_details)
            score?.stale == true -> label(R.string.online_cached_details)
            else -> ""
        }
        status.visibility = if (status.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        detailBody.removeAllViews()
        detailBody.addView(style.text(entry?.title ?: item?.displayName.orEmpty(), 15f, strong = true))
        val actions = row()
        actions.action(label(R.string.action_back)) { backToList() }
        if (entry == null) {
            detailBody.addView(actions)
            if (item != null) {
                detailBody.addView(style.text(localSource(item)))
                item.warnings.take(8).forEach { detailBody.addView(style.text(it)) }
                val playback = row()
                playback.action(label(R.string.online_play), enabled = mayActivate() && item.id !in model.songs.problems.value,
                    primary = true) { activateLocal(item) }
                detailBody.addView(playback)
            }
            return
        }
        val current = compatibility
        val selected = score?.entry ?: entry
        val favorite = entry.id in prefs.favorites
        actions.action(label(if (favorite) R.string.game_music_unfavorite else R.string.game_music_favorite), selected = favorite) {
            prefs.toggleFavorite(selected)
            render()
        }
        if (detailFailure != null) actions.action(label(R.string.retry)) { openDetail(entry, true) }
        detailBody.addView(actions)
        val bpm = NumberFormat.getNumberInstance().format(selected.bpm)
        detailBody.addView(style.text(label(R.string.game_music_online_meta,
            selected.composer.ifBlank { label(R.string.online_unknown_composer) },
            bpm, songDuration(selected.durationMs), selected.noteCount)))
        detailBody.addView(style.text(label(R.string.online_uploader) + "  " + selected.uploader))
        if (selected.difficulty > 0) detailBody.addView(style.text(label(R.string.online_difficulty_level, selected.difficulty)))
        if (selected.tags.isNotEmpty()) detailBody.addView(style.text(selected.tags.joinToString(" · ")))
        detailBody.addView(style.text(current?.let { songCompatibilityLabel(context, it.status) }
            ?: label(R.string.game_music_compat_unknown)))
        if (current != null && current.unmappedCount > 0) {
            detailBody.addView(style.text(label(R.string.game_music_unmapped, current.unmappedCount)))
        }
        (current?.errors.orEmpty() + current?.warnings.orEmpty()).take(8).forEach { detailBody.addView(style.text(it)) }
        val ready = score != null && current != null && current.status != SongCompatibility.INCOMPATIBLE
        val playable = ready && current.unmappedCount == 0
        val download = row()
        if (cachedItem == null) download.action(label(R.string.online_download_only),
            enabled = ready && transferJob?.isActive != true && !model.libraryBusy.value) { transfer(selected, false) }
        download.action(label(if (cachedItem == null) R.string.online_download_play else R.string.online_play),
            enabled = mayActivate() && (cachedItem != null || playable), primary = true) { transfer(selected, true) }
        detailBody.addView(download)
    }

    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, style.dp(2), 0, style.dp(2))
    }

    private fun LinearLayout.action(text: String, enabled: Boolean = true, selected: Boolean = false,
        primary: Boolean = false, onClick: () -> Unit) {
        addView(style.button(text, selected = selected, primary = primary, size = 11f, action = onClick).apply {
            isEnabled = enabled
        }, LinearLayout.LayoutParams(0, -2, 1f).apply {
            if (isNotEmpty()) marginStart = style.dp(3)
        })
    }

    private fun label(@StringRes res: Int, vararg args: Any): String =
        if (args.isEmpty()) context.getString(res) else context.getString(res, *args)
}
