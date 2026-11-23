package com.yogi.reader

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebView

/** A WebView whose text-selection menu is Kindle-like: Highlight, Note, Define, Translate, Copy, Share. */
class ReaderWebView(ctx: Context) : WebView(ctx) {
    var onAction: ((Int) -> Unit)? = null

    companion object {
        const val HIGHLIGHT = 1
        const val NOTE = 2
        const val DEFINE = 3
        const val TRANSLATE = 4
        const val COPY = 5
        const val SHARE = 6
        val NAMES = listOf("Highlight" to HIGHLIGHT, "Note" to NOTE, "Define" to DEFINE, "Translate" to TRANSLATE, "Copy" to COPY, "Share" to SHARE)
    }

    private fun wrap(orig: ActionMode.Callback): ActionMode.Callback {
        val base = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                orig.onCreateActionMode(mode, menu)
                menu.clear()
                for ((i, p) in NAMES.withIndex()) menu.add(Menu.NONE, p.second, i, p.first)
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = true
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                onAction?.invoke(item.itemId)
                mode.finish()
                return true
            }
            override fun onDestroyActionMode(mode: ActionMode) { orig.onDestroyActionMode(mode) }
        }
        if (Build.VERSION.SDK_INT >= 23 && orig is ActionMode.Callback2) {
            return object : ActionMode.Callback2() {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu) = base.onCreateActionMode(mode, menu)
                override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = base.onPrepareActionMode(mode, menu)
                override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = base.onActionItemClicked(mode, item)
                override fun onDestroyActionMode(mode: ActionMode) = base.onDestroyActionMode(mode)
                override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) = orig.onGetContentRect(mode, view, outRect)
            }
        }
        return base
    }

    override fun startActionMode(callback: ActionMode.Callback): ActionMode? = super.startActionMode(wrap(callback))

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? = super.startActionMode(wrap(callback), type)
}
