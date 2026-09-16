package ac.mdiq.podcini.ui.utils

import ac.mdiq.podcini.R
import ac.mdiq.podcini.storage.utils.durationStringFull
import ac.mdiq.podcini.storage.utils.toSafeUri
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Loge
import ac.mdiq.podcini.utils.Logt
import ac.mdiq.podcini.utils.isCallable
import ac.mdiq.podcini.utils.openInSystemDefault
import ac.mdiq.podcini.utils.shareText
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.AttributeSet
import android.view.ContextMenu
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.get
import androidx.core.view.size
import kotlin.math.max

class ShownotesWebView : WebView, View.OnLongClickListener {

    private var selectedUrl: String? = null
    private var timecodeSelectedListener: ((Int) -> Unit)? = null
    private var pageFinishedListener: (()->Unit)? = null

    constructor(context: Context) : super(context) {
        setup()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        setup()
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        setup()
    }

    private fun setup() {
        setBackgroundColor(Color.TRANSPARENT)
        // Use cached resources, even if they have expired
//        if (!NetworkUtils.networkAvailable()) getSettings().cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
//        settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK

//        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.useWideViewPort = false
        settings.loadWithOverviewMode = true
        setOnLongClickListener(this)

        webViewClient = object : WebViewClient() {
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if ((ShownotesCleaner.isTimecodeLink(url) || ShownotesCleaner.isHTTPTimecodeLink(url)) && timecodeSelectedListener != null) timecodeSelectedListener!!(ShownotesCleaner.getTimecodeLinkTime(url))
                else openInSystemDefault(url)
                return true
            }
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
//                Logd(TAG, "Page finished")
                pageFinishedListener?.invoke()
            }
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                view?.let { v->
                    (v.parent as? ViewGroup)?.removeView(v)
                    v.destroy()
                }
                Loge(TAG, "WebViewClient failure")
                return true
            }
        }
    }

     override fun onLongClick(v: View): Boolean {
        val r: HitTestResult = hitTestResult
        when (r.type) {
            HitTestResult.SRC_ANCHOR_TYPE -> {
                Logd(TAG, "Link of webview was long-pressed. Extra: " + r.extra)
                selectedUrl = r.extra
                showContextMenu()
                return true
            }
            HitTestResult.EMAIL_TYPE -> {
                Logd(TAG, "E-Mail of webview was long-pressed. Extra: " + r.extra)
                ContextCompat.getSystemService(context, ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Podcini", r.extra))
                // TODO: is checking SDK_INT <= 32 necessary?
                Logt(TAG, context.getString(R.string.copied_to_clipboard))
                return true
            }
            else -> {
                selectedUrl = null
                return false
            }
        }
    }

    private fun onContextItemSelected(item: MenuItem): Boolean {
        if (selectedUrl == null) return false
        val itemId = item.itemId
        when (itemId) {
            ContextAction.OPEN_IN_BROWSER.id -> openInSystemDefault(selectedUrl!!)
            ContextAction.SHARE_URL.id -> context.shareText(selectedUrl!!, R.string.share_url_label)
            ContextAction.COPY_URL.id -> {
                val clipData: ClipData = ClipData.newPlainText(selectedUrl, selectedUrl)
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(clipData)
                Logt(TAG, context.getString(R.string.copied_to_clipboard))
            }
            ContextAction.GOTO.id -> {
                if ((ShownotesCleaner.isTimecodeLink(selectedUrl) || ShownotesCleaner.isHTTPTimecodeLink(selectedUrl)) && timecodeSelectedListener != null)
                    timecodeSelectedListener!!(ShownotesCleaner.getTimecodeLinkTime(selectedUrl))
                else Loge(TAG, "Selected go_to_position_item, but URL was not timecode link: $selectedUrl")
            }
            else -> {
                selectedUrl = null
                return false
            }
        }
        selectedUrl = null
        return true
    }

    override fun onCreateContextMenu(menu: ContextMenu) {
        super.onCreateContextMenu(menu)
        if (selectedUrl == null) return
        if (ShownotesCleaner.isTimecodeLink(selectedUrl) || ShownotesCleaner.isHTTPTimecodeLink(selectedUrl)) {
            menu.add(Menu.NONE, ContextAction.GOTO.id, Menu.NONE, ContextAction.GOTO.titleRes)
            menu.setHeaderTitle(durationStringFull(ShownotesCleaner.getTimecodeLinkTime(selectedUrl)))
        } else {
            val uri = selectedUrl!!.toSafeUri()
            val intent = Intent(Intent.ACTION_VIEW, uri)
            if (isCallable(intent)) menu.add(Menu.NONE, ContextAction.OPEN_IN_BROWSER.id, Menu.NONE, ContextAction.OPEN_IN_BROWSER.titleRes)
            menu.add(Menu.NONE, ContextAction.COPY_URL.id, Menu.NONE, ContextAction.COPY_URL.titleRes)
            menu.add(Menu.NONE, ContextAction.SHARE_URL.id, Menu.NONE, ContextAction.SHARE_URL.titleRes)
            menu.setHeaderTitle(selectedUrl)
        }
        setOnClickListeners(menu) { item: MenuItem -> this.onContextItemSelected(item) }
    }

    /**
     * When pressing a context menu item, Android calls onContextItemSelected
     * for ALL fragments in arbitrary order, not just for the fragment that the
     * context menu was created from. This assigns the listener to every menu item,
     * so that the correct fragment is always called first and can consume the click.
     *
     * Note that Android still calls the onContextItemSelected methods of all fragments
     * when the passed listener returns false.
     */
    private fun setOnClickListeners(menu: Menu?, listener: MenuItem.OnMenuItemClickListener?) {
        for (i in 0 until menu!!.size) {
            menu[i].subMenu?.let { setOnClickListeners(it, listener) }
            menu[i].setOnMenuItemClickListener(listener)
        }
    }

    fun setTimecodeSelectedListener(timecodeSelectedListener: ((Int) -> Unit)?) {
        this.timecodeSelectedListener = timecodeSelectedListener
    }

    fun setPageFinishedListener(pageFinishedListener: (()->Unit)?) {
        this.pageFinishedListener = pageFinishedListener
    }

    @Deprecated("Deprecated in Java")
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(max(measuredWidth, minimumWidth), max(measuredHeight, minimumHeight))
    }

    companion object {
        private val TAG: String = ShownotesWebView::class.simpleName ?: "Anonymous"

        enum class ContextAction(@StringRes val titleRes: Int) {
            GOTO(R.string.go_to_position_label),
            OPEN_IN_BROWSER(R.string.open_in_browser_label),
            COPY_URL(R.string.copy_url_label),
            SHARE_URL(R.string.share_url_label);
            val id: Int get() = ordinal

            companion object {
                fun fromId(id: Int): ContextAction? = entries.firstOrNull { it.id == id }
            }
        }
    }
}
