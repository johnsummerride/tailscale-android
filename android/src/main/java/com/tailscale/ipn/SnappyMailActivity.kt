// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

// SnappyMailActivity shows the self-hosted SnappyMail webmail that lives on the tailnet.
//
// The webmail is only reachable once the Tailscale VPN is up, so this activity brings the tunnel
// up (asking for the VPN consent dialog the first time) and waits for the engine to report
// Ipn.State.Running before it loads the page. Plain HTTP is used by the webmail, which is why the
// manifest sets android:usesCleartextTraffic="true".
class SnappyMailActivity : Activity() {
  private companion object {
    private const val TAG = "SnappyMail"
    private const val VPN_PERMISSION_REQUEST_CODE = 7001
    private const val WEBMAIL_URL = "http://100.83.40.43:8090"
    private const val MAX_RETRIES = 5
    private const val RETRY_DELAY_MS = 3000L
  }

  private lateinit var webView: WebView
  private lateinit var statusView: TextView
  private lateinit var actionButton: Button
  private var pageRequested = false
  private var vpnStartRequested = false
  private var retryCount = 0
  // Set when the main frame fails, so onPageFinished does not reveal the WebView after the
  // error page WebView renders in place of the webmail.
  private var mainFrameFailed = false
  private var destroyed = false
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Touching App.get() makes sure libtailscale is initialized and the Notifier, which
    // publishes the IPN state, is running.
    App.get()

    val root = FrameLayout(this)

    webView =
        WebView(this).apply {
          setBackgroundColor(Color.WHITE)
          visibility = View.INVISIBLE
          settings.javaScriptEnabled = true
          settings.domStorageEnabled = true
          settings.databaseEnabled = true
          settings.useWideViewPort = true
          settings.loadWithOverviewMode = true
          settings.builtInZoomControls = true
          settings.displayZoomControls = false
          // Keep navigation inside the WebView; links in the webmail stay here. The client also
          // reports load failures instead of leaving the user on a silent white screen.
          webViewClient =
              object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                  super.onPageStarted(view, url, favicon)
                  TSLog.d(TAG, "page started: $url")
                  mainFrameFailed = false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                  super.onPageFinished(view, url)
                  TSLog.d(TAG, "page finished: $url")
                  if (mainFrameFailed) {
                    // onPageFinished still fires for the error page; keep the status text up.
                    return
                  }
                  // A real page landed: reveal it and reset the retry budget.
                  retryCount = 0
                  pageRequested = true
                  statusView.visibility = View.GONE
                  actionButton.visibility = View.GONE
                  webView.visibility = View.VISIBLE
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                  super.onReceivedError(view, request, error)
                  val code = error?.errorCode ?: -1
                  val description = error?.description?.toString().orEmpty()
                  if (request != null && !request.isForMainFrame) {
                    TSLog.d(TAG, "subresource error $code $description: ${request.url}")
                    return
                  }
                  mainFrameFailed = true
                  TSLog.d(TAG, "main frame error $code $description: ${request?.url}")
                  scheduleRetry(code, description)
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                  super.onReceivedHttpError(view, request, errorResponse)
                  if (request != null && !request.isForMainFrame) {
                    return
                  }
                  mainFrameFailed = true
                  val statusCode = errorResponse?.statusCode ?: -1
                  TSLog.d(TAG, "main frame http error $statusCode: ${request?.url}")
                  pageRequested = false
                  showStatus(
                      getString(R.string.snappymail_http_error, statusCode),
                      getString(R.string.snappymail_retry),
                  ) { retryFromScratch() }
                }
              }
        }
    root.addView(webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

    val statusPanel =
        LinearLayout(this).apply {
          orientation = LinearLayout.VERTICAL
          gravity = Gravity.CENTER
          setPadding(48, 48, 48, 48)
          setBackgroundColor(Color.WHITE)
        }
    statusView =
        TextView(this).apply {
          text = getString(R.string.snappymail_connecting)
          gravity = Gravity.CENTER
          textSize = 16f
        }
    statusPanel.addView(
        statusView,
        LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = 32 },
    )
    actionButton =
        Button(this).apply {
          text = getString(R.string.snappymail_retry)
          visibility = View.GONE
        }
    statusPanel.addView(actionButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
    root.addView(statusPanel, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

    setContentView(root)

    observeVpnState()
    updateForState(Notifier.state.value)
  }

  private fun observeVpnState() {
    scope.launch {
      Notifier.state.collect { state -> updateForState(state) }
    }
  }

  private fun updateForState(state: Ipn.State) {
    TSLog.d(TAG, "tailscale state: $state")
    when (state) {
      Ipn.State.Running -> {
        statusView.visibility = View.GONE
        actionButton.visibility = View.GONE
        webView.visibility = View.VISIBLE
        loadWebmailIfNeeded()
      }
      Ipn.State.Starting,
      Ipn.State.Stopping -> showStatus(getString(R.string.snappymail_connecting), null, null)
      Ipn.State.NeedsLogin,
      Ipn.State.NeedsMachineAuth ->
          showStatus(getString(R.string.snappymail_needs_login), getString(R.string.snappymail_sign_in)) {
            startActivity(Intent(this@SnappyMailActivity, MainActivity::class.java))
          }
      else -> {
        showStatus(getString(R.string.snappymail_starting_vpn), null, null)
        startVpnIfNeeded()
      }
    }
  }

  private fun showStatus(message: String, actionLabel: String?, action: (() -> Unit)?) {
    if (!pageRequested) {
      webView.visibility = View.INVISIBLE
    }
    statusView.text = message
    statusView.visibility = View.VISIBLE
    if (actionLabel != null && action != null) {
      actionButton.text = actionLabel
      actionButton.setOnClickListener { action() }
      actionButton.visibility = View.VISIBLE
    } else {
      actionButton.visibility = View.GONE
    }
  }

  private fun loadWebmailIfNeeded() {
    if (pageRequested) {
      return
    }
    pageRequested = true
    TSLog.d(TAG, "loading $WEBMAIL_URL")
    webView.loadUrl(WEBMAIL_URL)
  }

  // scheduleRetry is called for a failed main-frame load. It clears pageRequested so the status
  // text is visible (showStatus only hides the WebView while no page has been requested) and so
  // loadWebmailIfNeeded() is allowed to run again.
  private fun scheduleRetry(errorCode: Int, description: String) {
    if (destroyed) {
      return
    }
    pageRequested = false
    val failure = getString(R.string.snappymail_load_failed, "$errorCode $description".trim())
    if (retryCount >= MAX_RETRIES) {
      TSLog.d(TAG, "giving up after $retryCount retries")
      showStatus(
          getString(R.string.snappymail_load_failed_giving_up, failure, retryCount),
          getString(R.string.snappymail_retry),
      ) { retryFromScratch() }
      return
    }
    retryCount += 1
    TSLog.d(TAG, "retry $retryCount/$MAX_RETRIES in ${RETRY_DELAY_MS}ms")
    val message =
        failure +
            "\n" +
            getString(
                R.string.snappymail_retrying,
                (RETRY_DELAY_MS / 1000).toInt(),
                retryCount,
                MAX_RETRIES,
            )
    showStatus(message, null, null)
    webView.postDelayed(
        {
          if (!destroyed) {
            loadWebmailIfNeeded()
          }
        },
        RETRY_DELAY_MS,
    )
  }

  private fun retryFromScratch() {
    retryCount = 0
    mainFrameFailed = false
    pageRequested = false
    loadWebmailIfNeeded()
  }

  private fun startVpnIfNeeded() {
    if (vpnStartRequested) {
      return
    }
    vpnStartRequested = true
    val prepareIntent = VpnService.prepare(this)
    if (prepareIntent != null) {
      // The VPN consent dialog has to be answered before the tunnel can be established.
      startActivityForResult(prepareIntent, VPN_PERMISSION_REQUEST_CODE)
    } else {
      App.get().getAppScopedViewModel().setVpnPrepared(true)
      App.get().startVPN()
    }
  }

  @Deprecated("Deprecated in Java")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode != VPN_PERMISSION_REQUEST_CODE) {
      return
    }
    if (resultCode == RESULT_OK) {
      App.get().getAppScopedViewModel().setVpnPrepared(true)
      App.get().startVPN()
    } else {
      TSLog.d(TAG, "VPN permission denied")
      vpnStartRequested = false
      showStatus(getString(R.string.snappymail_vpn_denied), getString(R.string.snappymail_retry)) {
        startVpnIfNeeded()
      }
    }
  }

  @Suppress("DEPRECATION")
  override fun onBackPressed() {
    if (this::webView.isInitialized && webView.canGoBack()) {
      webView.goBack()
    } else {
      super.onBackPressed()
    }
  }

  override fun onDestroy() {
    destroyed = true
    scope.cancel()
    webView.destroy()
    super.onDestroy()
  }
}
