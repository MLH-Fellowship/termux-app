package com.termux.app

import androidx.appcompat.app.AppCompatActivity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.RelativeLayout

/**
 * Basic embedded browser for viewing help pages.
 */
class TermuxHelpActivity : androidx.appcompat.app.AppCompatActivity() {
    var mWebView: WebView? = null
    override fun onCreate(savedInstanceState: Bundle) {
        super.onCreate(savedInstanceState)
        val progressLayout = RelativeLayout(this)
        val lParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lParams.addRule(RelativeLayout.CENTER_IN_PARENT)
        val progressBar = ProgressBar(this)
        progressBar.isIndeterminate = true
        progressBar.layoutParams = lParams
        progressLayout.addView(progressBar)
        mWebView = WebView(this)
        val settings = mWebView!!.settings
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.setAppCacheEnabled(false)
        setContentView(progressLayout)
        mWebView!!.clearCache(true)
        mWebView!!.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (url.startsWith("https://wiki.termux.com")) {
                    // Inline help.
                    setContentView(progressLayout)
                    return false
                }
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: ActivityNotFoundException) {
                    // Android TV does not have a system browser.
                    setContentView(progressLayout)
                    return false
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                setContentView(mWebView)
            }
        }
        mWebView!!.loadUrl("https://wiki.termux.com/wiki/Main_Page")
    }

    override fun onBackPressed() {
        if (mWebView!!.canGoBack()) {
            mWebView!!.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
