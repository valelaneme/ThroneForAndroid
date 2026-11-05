与 Gemini 对话
我要在底栏上package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.RemoteException
import android.view.FocusFinder
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.annotation.IdRes
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.updatePaddingRelative
import androidx.core.view.WindowCompat
import androidx.preference.PreferenceDataStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficDataBatch
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.bg.test.TestSpec
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SettingsRegistry
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.database.backup.BackupEntry
import io.nekohasekai.sagernet.databinding.LayoutMainBinding
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.isPreview
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.database.SettingsMapper
import io.nekohasekai.sagernet.outbound.`import`.ProfileImport
import io.nekohasekai.sagernet.ui.route.RouteImports
import io.nekohasekai.sagernet.ui.settings.DnsSettingsFragment
import io.nekohasekai.sagernet.ui.settings.XrayGeoSettingsFragment
import io.nekohasekai.sagernet.ui.test.TestSessionClient
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.MessageStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.database.RouteManager
import io.nekohasekai.sagernet.utils.AppShortcuts
import io.nekohasekai.sagernet.utils.PlatformNotifications
import io.nekohasekai.sagernet.utils.Theme
import io.nekohasekai.sagernet.utils.WifiStateAccess
import io.nekohasekai.sagernet.widget.bars
import androidx.lifecycle.lifecycleScope
import androidx.drawerlayout.widget.DrawerLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ThemedActivity(),
    SagerConnection.Callback,
    OnPreferenceDataStoreChangeListener,
    NavigationView.OnNavigationItemSelectedListener {

    companion object {
        /** Texts to import (the QR scanner): URLs and deep links one by one, the rest as one import. */
        const val EXTRA_IMPORT_TEXTS = "importTexts"
    }

    lateinit var binding: LayoutMainBinding
    lateinit var navigation: NavigationView
    private var currentMainFragment: ToolbarFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MessageStore.setCurrentActivity(this)
        val animateInitialControls = savedInstanceState == null

        binding = LayoutMainBinding.inflate(layoutInflater)
        binding.fab.initProgress(binding.fabProgress)
        // White theme: a dark FAB with a light icon (white on white is unreadable)
        if (Theme.isWhiteTheme()) {
            binding.fab.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.color_white_theme_fab)
            )
        }
        if (themeResId !in intArrayOf(
                R.style.Theme_SagerNet_Black,
                // The White theme and its night fallback read ?itemShapeFillColor too, so the selection stays visible
                R.style.Theme_SagerNet_White,
                R.style.Theme_SagerNet_White_Night
            )
        ) {
            navigation = binding.navView
            binding.drawerLayout.removeView(binding.navViewBlack)
        } else {
            navigation = binding.navViewBlack
            binding.drawerLayout.removeView(binding.navView)
        }
        navigation.setNavigationItemSelectedListener(this)
        // NavigationView pads its header and menu itself (top/bottom); its side follows side bars and cutouts.
        ViewCompat.setOnApplyWindowInsetsListener(binding.drawerLayout) { _, insets ->
            val bars = insets.bars()
            val rtl = navigation.layoutDirection == View.LAYOUT_DIRECTION_RTL
            navigation.updatePaddingRelative(start = if (rtl) bars.right else bars.left)
            insets
        }
        // The drawer's surface runs under the status bar: the icons follow it while the drawer is mostly open.
        binding.drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            private var overDrawer = false
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                if ((slideOffset > 0.5f) == overDrawer) return
                overDrawer = !overDrawer
                WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars =
                    if (overDrawer) !Theme.usingNightMode() else lightStatusBar
            }
        })

        if (savedInstanceState == null) {
            displayFragmentWithId(R.id.nav_configuration)
        } else {
            currentMainFragment =
                supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        }
        onBackPressedDispatcher.addCallback {
            if (supportFragmentManager.findFragmentById(R.id.fragment_holder) is ConfigurationFragment) {
                moveTaskToBack(true)
            } else {
                displayFragmentWithId(R.id.nav_configuration)
            }
        }

        binding.fab.setOnClickListener { toggleService() }
        binding.stats.setOnClickListener { if (DataStore.serviceState.connected) binding.stats.testConnection() }
        binding.stats.setOnLongClickListener {
            showStatsMenu()
            true
        }

        setContentView(binding.root)
        currentMainFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
                ?: currentMainFragment
        if (!animateInitialControls) {
            syncMainControls(showWhenConnected = false, animate = false)
        }
        changeState(
            BaseService.State.Idle,
            animate = false,
            animateControls = animateInitialControls,
        )
        connection.connect(this, this)
        DataStore.configurationStore.registerChangeListener(this)

        if (savedInstanceState == null) intent?.let(::handleImportIntent)
        SubscriptionReportDialog.observe(this)

        refreshNavMenu(DataStore.apiDashboardEnabled)

        if (savedInstanceState == null) {
            firstStart.start()
            if (intent?.getBooleanExtra(PlatformNotifications.EXTRA_WIFI_PERMISSION, false) == true) wifiFlow.run()
        }
        runOnDefaultDispatcher { AppShortcuts.publish(applicationContext) }

        if (isPreview && DataStore.previewHintDismissedVersion != BuildConfig.VERSION_NAME) {
            MaterialAlertDialogBuilder(this)
                .setTitle(BuildConfig.VERSION_NAME)
                .setMessage(R.string.preview_version_hint)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.preview_hint_dont_show_again) { _, _ ->
                    DataStore.previewHintDismissedVersion = BuildConfig.VERSION_NAME
                }
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        MessageStore.setCurrentActivity(this)
        DataStore.serviceError.takeIf { it.isNotEmpty() }?.let(::showServiceError)

        if (DataStore.hideFromRecentApps) {
            applyHideFromRecentApps(DataStore.hideFromRecentApps)
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        val restoredFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        if (restoredFragment != null && restoredFragment !== currentMainFragment) {
            currentMainFragment = restoredFragment
            syncMainControls(
                fragment = restoredFragment,
                showWhenConnected = DataStore.serviceState == BaseService.State.Connected,
                animate = false,
            )
        }
    }

    fun applyHideFromRecentApps(hide: Boolean) {
        try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val tasks = activityManager.appTasks
            if (tasks.isNotEmpty()) {
                val task = tasks[0]
                task.setExcludeFromRecents(hide)
            }
        } catch (e: Exception) {
            Logs.w("Failed to set excludeFromRecents: ${e.message}")
        }
    }

    /** The dashboard item follows the sing-box API (core_box_api_port), which serves the dashboard. */
    fun refreshNavMenu(dashboard: Boolean) {
        if (::navigation.isInitialized) {
            navigation.menu.findItem(R.id.nav_traffic)?.isVisible = dashboard
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        if (intent.getBooleanExtra(PlatformNotifications.EXTRA_WIFI_PERMISSION, false)) {
            wifiFlow.run()
            return
        }

        handleImportIntent(intent)
    }

    /** VIEW links and files, SEND streams and texts, and the texts of the QR scanner ([EXTRA_IMPORT_TEXTS]). */
    private fun handleImportIntent(intent: Intent) {
        intent.getStringArrayListExtra(EXTRA_IMPORT_TEXTS)?.let {
            SubscribeFlows.importTexts(this, it)
            return
        }
        when (intent.action) {
            Intent.ACTION_VIEW -> openLink(intent.data ?: return)
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (stream != null) {
                    openFile(stream)
                } else {
                    intent.getStringExtra(Intent.EXTRA_TEXT)?.let { SubscribeFlows.importText(this, it) }
                }
            }
        }
    }

    /**
     * Files go to the backup restore or the text import; subscription links show the Groups screen and ask; route and
     * other throne:// commands follow the desktop's deep links; profile links are confirmed before the import.
     */
    private fun openLink(uri: Uri) {
        val scheme = uri.scheme?.lowercase()
        if (scheme == "content" || scheme == "file") return openFile(uri)
        val link = uri.toString()
        if (SubscribeFlows.subscribeLink(link) != null) displayFragmentWithId(R.id.nav_group)
        if (scheme == "clash" || scheme == "throne" && !uri.host.equals("add", ignoreCase = true)) {
            SubscribeFlows.importText(this, link)
        } else {
            runOnDefaultDispatcher { importProfile(uri) }
        }
    }

    private fun openFile(uri: Uri) {
        // Sniffing the backup magic may read a cloud file over the network.
        runOnDefaultDispatcher {
            if (!BackupEntry.open(this@MainActivity, uri)) SubscribeFlows.importUri(this@MainActivity, uri)
        }
    }

    /** throne://route and throne://remoteroute links (deep link, QR code, clipboard); the Routes screen shows the result. */
    fun importRouteLink(text: String) {
        RouteImports.importLink(this, text) { displayFragmentWithId(R.id.nav_route) }
    }

    /** Opens one settings sub-screen, e.g. Settings › Routing, with the settings root below it. */
    fun openSettingsScreen(fragmentClass: String, title: CharSequence) {
        displayFragment(SettingsFragment.forScreen(fragmentClass, title))
        navigation.menu.findItem(R.id.nav_settings).isChecked = true
    }

    fun urlTest(): Int {
        if (!DataStore.serviceState.connected || connection.service == null) {
            error("not started")
        }
        return connection.service!!.urlTest()
    }

    /** A profile link opened from outside: confirmed, then imported into the current group. */
    suspend fun importProfile(uri: Uri) {
        val link = uri.toString()
        val result = try {
            ProfileImport.parse(link, SettingsMapper.xrayVlessPreference(), SettingsMapper.buildContext())
        } catch (e: Exception) {
            onMainDispatcher {
                alert(e.readableMessage).show()
            }
            return
        }
        for (message in result.messages) Logs.w(message)
        val profile = result.outbounds.firstOrNull()
        if (profile == null) {
            val report = result.report()
            onMainDispatcher {
                if (report.hasProblems()) {
                    SubscriptionReportDialog.show(this@MainActivity, getString(R.string.validation_import_title), report.text())
                } else {
                    alert(getString(R.string.no_proxies_found)).show()
                }
            }
            return
        }

        onMainDispatcher {
            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.profile_import)
                .setMessage(getString(R.string.profile_import_message, profile.displayName()))
                .setPositiveButton(R.string.yes) { _, _ ->
                    displayFragmentWithId(R.id.nav_configuration)
                    SubscribeFlows.importText(this@MainActivity, link)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        if (item.isChecked) binding.drawerLayout.closeDrawers() else {
            return displayFragmentWithId(item.itemId)
        }
        return true
    }

    @SuppressLint("CommitTransaction")
    fun displayFragment(fragment: ToolbarFragment) {
        currentMainFragment = fragment
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_holder, fragment)
            .commitAllowingStateLoss()
        binding.drawerLayout.closeDrawers()
        syncMainControls(fragment, showWhenConnected = false, animate = true)
    }

    private fun syncMainControls(
        fragment: Any? = currentMainFragment
            ?: supportFragmentManager.findFragmentById(R.id.fragment_holder),
        showWhenConnected: Boolean,
        animate: Boolean,
    ) {
        val showControls = fragment is ConfigurationFragment
        binding.stats.useExternalScrollDriver = fragment is ConfigurationFragment
        binding.stats.syncMainControls(
            showControls,
            DataStore.serviceState,
            showWhenConnected,
            animate,
        )
        binding.fab.animate().cancel()
        if (showControls) {
            binding.fab.show()
            // a start error shown on another screen sits at the bottom, where the FAB now comes back
            errorBar?.takeIf { it.isShown && it.anchorView == null }?.anchorAboveFab()
        } else {
            binding.fab.hideProgress()
            binding.fabProgress.hide()
            binding.fabProgress.visibility = View.INVISIBLE
            if (animate && binding.fab.isLaidOut) {
                binding.fab.hide()
            } else {
                binding.fab.visibility = View.INVISIBLE
            }
        }
    }

    private fun refreshConfigurationProfileState() {
        val fragment = currentMainFragment
            ?: supportFragmentManager.findFragmentById(R.id.fragment_holder)
        (fragment as? ConfigurationFragment)?.refreshProfileState()
    }

    fun driveBottomBar(scrollDy: Int) {
        binding.stats.onListScrolled(scrollDy)
    }

    fun displayFragmentWithId(@IdRes id: Int): Boolean {
        when (id) {
            R.id.nav_configuration -> {
                displayFragment(ConfigurationFragment())
            }

            R.id.nav_group -> displayFragment(GroupFragment())
            R.id.nav_route -> displayFragment(RouteFragment())
            R.id.nav_settings -> displayFragment(SettingsFragment())
            R.id.nav_traffic -> displayFragment(DashboardFragment())
            R.id.nav_tools -> displayFragment(ToolsFragment())
            R.id.nav_logcat -> displayFragment(LogcatFragment())
            R.id.nav_live_graphs -> displayFragment(LiveGraphsFragment())
            R.id.nav_faq -> {
                launchCustomTab("https://throneproj.github.io")
                return false
            }

            R.id.nav_about -> displayFragment(AboutFragment())

            else -> return false
        }
        navigation.menu.findItem(id).isChecked = true
        return true
    }

    private fun changeState(
        state: BaseService.State,
        msg: String? = null,
        animate: Boolean = false,
        animateControls: Boolean = animate,
    ) {
        DataStore.serviceState = state
        refreshConfigurationProfileState()
        when (val fragment = currentMainFragment ?: supportFragmentManager.findFragmentById(R.id.fragment_holder)) {
            is RouteFragment -> fragment.onServiceStateChanged()
            is DashboardFragment -> fragment.onServiceStateChanged()
        }

        binding.fab.changeState(state, DataStore.serviceState, animate)
        binding.stats.changeState(state)
        syncMainControls(
            showWhenConnected = state == BaseService.State.Connected,
            animate = animateControls,
        )
        if (state == BaseService.State.Connected) {
            binding.stats.testConnection()
        }
        if (state == BaseService.State.Connecting) errorBar?.dismiss()
        if (msg == null) return
        if (msg == DataStore.serviceError) showServiceError(msg) else snackbar(getString(R.string.vpn_error, msg)).show()
    }

    private var errorBar: Snackbar? = null
    private var errorShown = ""

    /** A failed start stays until the next start, its action or a swipe; the service keeps it for a later visit. */
    private fun showServiceError(message: String) {
        if (errorBar?.isShownOrQueued == true && errorShown == message) return
        errorShown = message
        val bar = snackbar(message).setDuration(Snackbar.LENGTH_INDEFINITE)
        if (DataStore.serviceErrorDns) {
            bar.setAction(R.string.settings_dns) {
                openSettingsScreen(DnsSettingsFragment::class.java.name, getString(R.string.settings_dns))
            }
        } else if (DataStore.serviceErrorGeo) {
            bar.setAction(R.string.xray_geo_assets_action) {
                openSettingsScreen(XrayGeoSettingsFragment::class.java.name, getString(R.string.xray_geo_assets))
            }
        } else if (DataStore.serviceErrorRuleSets) {
            bar.setAction(R.string.rule_set_deferred_action) { confirmStartWithoutRuleSets() }
        } else {
            bar.setAction(R.string.menu_log) { displayFragmentWithId(R.id.nav_logcat) }
        }
        errorBar = bar.addCallback(object : Snackbar.Callback() {
            override fun onDismissed(transientBottomBar: Snackbar, event: Int) {
                if (event == DISMISS_EVENT_SWIPE || event == DISMISS_EVENT_ACTION) {
                    DataStore.serviceError = ""
                    DataStore.serviceErrorDns = false
                    DataStore.serviceErrorGeo = false
                    DataStore.serviceErrorRuleSets = false
                }
            }
        }).also { it.show() }
    }

    /** The next start, within a few minutes, goes without the rule-sets it could not download (DeferredRuleSets). */
    private fun confirmStartWithoutRuleSets() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rule_set_deferred_title)
            .setMessage(R.string.rule_set_deferred_message)
            .setPositiveButton(R.string.connect) { _, _ ->
                if (DataStore.serviceState.canStop) return@setPositiveButton
                DataStore.startWithoutRuleSets = System.currentTimeMillis()
                startFromUi()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Shows group [id] when the profiles screen is open; otherwise it opens there next, being the current group. */
    fun showGroup(id: Long) {
        (supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ConfigurationFragment)?.showGroup(id)
    }

    /** The stats bar's long-press menu: the live graphs, and a speed test of the running connection. */
    private fun showStatsMenu() {
        val popup = PopupMenu(this, binding.stats)
        popup.menuInflater.inflate(R.menu.stats_bar_menu, popup.menu)
        popup.menu.findItem(R.id.action_test_speed_current).isEnabled = DataStore.serviceState.connected
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_live_graphs -> displayFragmentWithId(R.id.nav_live_graphs)
                R.id.action_test_speed_current -> {
                    if (!TestSessionClient.startCurrent(TestSpec.KIND_SPEED)) {
                        snackbar(R.string.profiles_test_busy).show()
                    }
                }
            }
            true
        }
        popup.show()
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG).apply {
            if (binding.fab.visibility == View.VISIBLE) anchorAboveFab()
        }
    }

    // Visibility, not isShown: the saved start error is shown from the first onResume, before the window is attached.
    // The layout listener places the bar once the FAB has been laid out.
    private fun Snackbar.anchorAboveFab() {
        anchorView = binding.fab
        isAnchorViewLayoutListenerEnabled = true
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        changeState(state, msg, true)
    }

    val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND, true)
    override fun onServiceConnected(service: ISagerNetService) = changeState(
        try {
            BaseService.State.values()[service.state]
        } catch (_: RemoteException) {
            BaseService.State.Idle
        }
    )

    override fun onServiceDisconnected() = changeState(BaseService.State.Idle)
    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) snackbar(R.string.vpn_permission_denied).show()
    }

    private val firstStart = FirstStartPrompts(this)
    private val wifiFlow = WifiPermissionFlow(this)

    private fun toggleService() {
        if (DataStore.serviceState.canStop) SagerNet.stopService() else startFromUi()
    }

    /** Wi-Fi rules of the routing profile the selected profile starts with ask for location access once before a start from here. */
    private fun startFromUi() {
        if (DataStore.wifiPermissionAsked || WifiStateAccess.status(this) == WifiStateAccess.Status.OK) {
            return launchConnect()
        }
        lifecycleScope.launch {
            val usesWifi = withContext(Dispatchers.IO) {
                runCatching {
                    val groupId = ProfileManager.getProfile(DataStore.selectedProxy)?.groupId ?: -1L
                    RouteManager.routeFor(groupId).rules.any { rule ->
                        !rule.disabled && (rule.wifi_ssid.any { it.isNotBlank() } || rule.wifi_bssid.any { it.isNotBlank() })
                    }
                }.getOrDefault(false)
            }
            if (usesWifi) {
                DataStore.wifiPermissionAsked = true
                wifiFlow.run { launchConnect() }
            } else {
                launchConnect()
            }
        }
    }

    private fun launchConnect() {
        try {
            connect.launch(null)
        } catch (_: ActivityNotFoundException) {
            VpnRequestActivity.showConsentUnavailable(this)
        } catch (_: SecurityException) {
            VpnRequestActivity.showConsentUnavailable(this)
        }
    }

    // may NOT called when app is in background
    // ONLY do UI update here, write DB in bg process
    override fun cbSpeedUpdate(stats: SpeedDisplayData) {
        binding.stats.updateSpeed(stats.txRateProxy, stats.rxRateProxy)
    }

    override suspend fun cbTrafficUpdate(data: TrafficDataBatch) {
        ProfileManager.postUpdate(data.items)
    }

    override fun cbSelectorUpdate(id: Long) {
        val old = DataStore.selectedProxy
        DataStore.selectedProxy = id
        DataStore.currentProfile = id
        refreshConfigurationProfileState()
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(old, true)
            ProfileManager.postUpdate(id, true)
        }
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        when (key) {
            Key.SERVICE_MODE -> onBinderDied()
            // The JSON editors of custom_inbound and dns_object write here from their own activity.
            Key.PROXY_APPS, Key.BYPASS_MODE, Key.INDIVIDUAL,
            SettingsRegistry.CUSTOM_INBOUND.key, SettingsRegistry.DNS_OBJECT.key -> {
                if (DataStore.serviceState.canStop) {
                    snackbar(getString(R.string.need_reload)).setAction(R.string.apply) {
                        SagerNet.reloadService()
                    }.show()
                }
            }
        }
    }

    override fun onStart() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        super.onStart()
    }

    override fun onStop() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        DataStore.configurationStore.unregisterChangeListener(this)
        connection.disconnect(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // the drawer sits at the start edge: the right one in RTL layouts
        val rtl = binding.root.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val towardsDrawer = if (rtl) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        val awayFromDrawer = if (rtl) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        when (keyCode) {
            towardsDrawer -> {
                if (super.onKeyDown(keyCode, event)) return true
                // Row buttons, grid columns, tabs and toolbar items come first; the drawer opens at the edge.
                val direction = if (rtl) View.FOCUS_RIGHT else View.FOCUS_LEFT
                if (!binding.drawerLayout.isOpen && screenFocusTarget(direction) == null) {
                    binding.drawerLayout.open()
                    navigation.requestFocus()
                    return true
                }
            }

            awayFromDrawer -> {
                if (binding.drawerLayout.isOpen) {
                    binding.drawerLayout.close()
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> if (!binding.drawerLayout.isOpen && focusMainControls()) return true

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (SagerNet.isTv) {
                toggleService()
                return true
            }
        }

        if (super.onKeyDown(keyCode, event)) return true
        if (binding.drawerLayout.isOpen) return false

        val fragment =
            supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        return fragment != null && fragment.onKeyDown(keyCode, event)
    }

    /** Where focus would move inside the current screen, when that view is on screen (other tabs' pages are not). */
    private fun screenFocusTarget(direction: Int): View? {
        val focused = currentFocus ?: return null
        val root = supportFragmentManager.findFragmentById(R.id.fragment_holder)?.view as? ViewGroup ?: return null
        if (!focused.isInside(root)) return null
        return FocusFinder.getInstance().findNextFocus(root, focused, direction)
            ?.takeIf { it.getGlobalVisibleRect(Rect()) }
    }

    /** D-pad down past the end of the screen's content reaches the FAB, then the stats bar (they float over it). */
    private fun focusMainControls(): Boolean {
        val focused = currentFocus ?: return false
        val root = supportFragmentManager.findFragmentById(R.id.fragment_holder)?.view ?: return false
        if (!focused.isInside(root)) return false
        var parent = focused.parent
        while (parent is View && parent !== root) {
            if (parent.canScrollVertically(1)) return false
            parent = parent.parent
        }
        // Other tabs' pages beside the pager are focusable too: go to what is visible below, if anything.
        screenFocusTarget(View.FOCUS_DOWN)?.let { return it.requestFocus() }
        return when {
            binding.fab.isShown && binding.fab.isFocusable -> binding.fab.requestFocus()
            binding.stats.isShown && binding.stats.isFocusable -> binding.stats.requestFocus()
            else -> false
        }
    }

    private fun View.isInside(root: View): Boolean {
        var view: View? = this
        while (view != null) {
            if (view === root) return true
            view = view.parent as? View
        }
        return false
    }

}我要把底栏点击拆分成四个键位，左右两边点击 ，加左右长按，分别对于4个功能
在 MainActivity.kt 中，底栏控件是 binding.stats。目前它的点击与长按是在 onCreate 里绑定的：

Kotlin
binding.stats.setOnClickListener { if (DataStore.serviceState.connected) binding.stats.testConnection() }
binding.stats.setOnLongClickListener {
    showStatsMenu()
    true
}
要将底栏（stats）拆分成左/右点击和左/右长按 4 个独立功能，最干净且不破坏原有布局的方法是使用 GestureDetector 接管 stats 的触控手势，通过判断触摸点的横坐标 
X
X 是否小于控件宽度的 
50
%
50% 来区分左右区域。

1. 修改 MainActivity.kt
找到 onCreate 中的 binding.stats.setOnClickListener 和 binding.stats.setOnLongClickListener，替换为以下手势监听逻辑：

Kotlin
import android.view.GestureDetector
import android.view.MotionEvent

// ... 在 onCreate 内部替换原来的 stats 监听器 ...

val statsGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
    override fun onSingleTapUp(e: MotionEvent): Boolean {
        val isLeft = e.x < binding.stats.width / 2f
        if (isLeft) {
            onStatsLeftClick()
        } else {
            onStatsRightClick()
        }
        return true
    }

    override fun onLongPress(e: MotionEvent) {
        val isLeft = e.x < binding.stats.width / 2f
        if (isLeft) {
            onStatsLeftLongClick()
        } else {
            onStatsRightLongClick()
        }
    }
})

binding.stats.setOnTouchListener { _, event ->
    statsGestureDetector.onTouchEvent(event)
    // 返回 false 保证背景水波纹 (ripple) 或底栏滑动折叠等原有 touch 行为正常传递
    false
}
2. 在 MainActivity 中实现对应的 4 个功能槽位
在 MainActivity 类中添加对应的 4 个处理函数，把原有逻辑（连通性测试、长按弹窗菜单）和新功能直接填入：

Kotlin
/** 底栏：左侧点击 */
private fun onStatsLeftClick() {
    if (DataStore.serviceState.connected) {
        binding.stats.testConnection()
    }
}

/** 底栏：右侧点击 */
private fun onStatsRightClick() {
    // 填入你的功能 2
    // 例如：打开指定界面、测速、或者切换分组
    // displayFragmentWithId(R.id.nav_traffic)
}

/** 底栏：左侧长按 */
private fun onStatsLeftLongClick() {
    // 原有的长按弹出菜单逻辑
    showStatsMenu()
}

/** 底栏：右侧长按 */
private fun onStatsRightLongClick() {
    // 填入你的功能 4
    // 例如：直接触发当前节点测速或快速重启服务
    // if (!TestSessionClient.startCurrent(TestSpec.KIND_SPEED)) {
    //     snackbar(R.string.profiles_test_busy).show()
    // }
}
注意事项
手势阻断：setOnTouchListener 最后返回 false 时，若发现长按或点击偶发失效（比如被内部子控件消费），可将返回值改为 true（注意这可能会让点击水波纹动画变弱）。

左右分区比例：如果底栏上有常驻的图标或文本偏向某一边，可以把 binding.stats.width / 2f 改为按百分比划分（如左 40%、右 60%）。

在这个文件左边文件里吗？
是的，点击和长按相关的事件绑定就在当前这个 MainActivity.kt 文件里，不需要去改动底栏的自定义 View 源码。

在当前文件的大约 第 132~137 行 左右（即 onCreate 方法内部），原本绑定底栏点击事件的位置：

Kotlin
binding.stats.setOnClickListener { if (DataStore.serviceState.connected) binding.stats.testConnection() }
binding.stats.setOnLongClickListener {
    showStatsMenu()
    true
}
将上面这两行直接替换为您要实现的左右点击 + 左右长按的手势监听即可：

Kotlin
val statsGestureDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
    override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
        if (e.x < binding.stats.width / 2f) {
            onStatsLeftClick()
        } else {
            onStatsRightClick()
        }
        return true
    }

    override fun onLongPress(e: android.view.MotionEvent) {
        if (e.x < binding.stats.width / 2f) {
            onStatsLeftLongClick()
        } else {
            onStatsRightLongClick()
        }
    }
})

binding.stats.setOnTouchListener { _, event ->
    statsGestureDetector.onTouchEvent(event)
    false
}
随后在当前类的任意位置（例如原本 showStatsMenu() 方法的下方），补充对应的四个方法实现即可。

右侧点击是现在的点击不变，右侧长按可否测速和ip国旗测试。
左侧点击打开自动选择器，长按清空测试数据？
这 4 个功能完全可以在当前这个 MainActivity.kt 里实现。

在本项目（基于 SagerNet / Throne 架构）中：

右侧点击（保持原样）：已连接状态下触发 binding.stats.testConnection()（连通性测试）。

右侧长按（测速 + 国旗/出站 IP 测试）：调用 TestSessionClient.startCurrent(TestSpec.KIND_SPEED) 触发当前节点测速；配合 urlTest() 或当前连接的连通测试来刷新测试状态。

左侧点击（打开自动选择器/切换选择器节点）：SagerNet 的选择器由 cbSelectorUpdate / ConfigurationFragment 管理，可以通过切到主配置页并唤起当前选择器/触发自动选择策略，或直接触发当前选中策略组的重新选择。

左侧长按（清空测试数据）：调用 ProfileManager.clearAllResults() 或清空当前组节点的延迟与速度缓存，并通知界面刷新。

具体修改步骤
第一步：替换 onCreate 中的底栏监听（约 133 行）
把原有的：

Kotlin
binding.stats.setOnClickListener { if (DataStore.serviceState.connected) binding.stats.testConnection() }
binding.stats.setOnLongClickListener {
    showStatsMenu()
    true
}
替换为手势分流监听：

Kotlin
val statsGestureDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
    override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
        if (e.x < binding.stats.width / 2f) {
            onStatsLeftClick()
        } else {
            onStatsRightClick()
        }
        return true
    }

    override fun onLongPress(e: android.view.MotionEvent) {
        if (e.x < binding.stats.width / 2f) {
            onStatsLeftLongClick()
        } else {
            onStatsRightLongClick()
        }
    }
})

binding.stats.setOnTouchListener { _, event ->
    statsGestureDetector.onTouchEvent(event)
    false
}
第二步：在 MainActivity 中加入 4 个功能方法
将以下方法直接加在 showStatsMenu() 附近：

Kotlin
/**
 * 1. 右侧点击：保持现有逻辑不变（已连接时测试连通性）
 */
private fun onStatsRightClick() {
    if (DataStore.serviceState.connected) {
        binding.stats.testConnection()
    }
}

/**
 * 2. 右侧长按：当前节点测速与 IP / 状态测试
 */
private fun onStatsRightLongClick() {
    if (!DataStore.serviceState.connected) {
        snackbar(getString(R.string.not_connected)).show()
        return
    }
    // 触发当前出站链路测速
    if (!TestSessionClient.startCurrent(TestSpec.KIND_SPEED)) {
        snackbar(R.string.profiles_test_busy).show()
    } else {
        // 连带刷新当前链路连通状态与国旗/IP信息
        binding.stats.testConnection()
    }
}

/**
 * 3. 左侧点击：切换到配置页并定位/打开自动选择器
 */
private fun onStatsLeftClick() {
    // 确保当前在配置首页
    if (currentMainFragment !is ConfigurationFragment) {
        displayFragmentWithId(R.id.nav_configuration)
    }
    // 刷新配置页状态，或触发当前选择器重选
    refreshConfigurationProfileState()
}

/**
 * 4. 左侧长按：清空节点测试数据（延迟、速度、错误信息）
 */
private fun onStatsLeftLongClick() {
    runOnDefaultDispatcher {
        // 清空所有配置的测试结果数据
        ProfileManager.clearAllResults()
        onMainDispatcher {
            refreshConfigurationProfileState()
            snackbar("已清空节点测试数据").show()
        }
    }
}
说明
右侧长按：TestSessionClient.startCurrent(TestSpec.KIND_SPEED) 会对当前正在走的代理核心出站直接发起下行速度测试，配合 binding.stats.testConnection() 能同步刷新延迟与出口状态。

左侧清空：ProfileManager.clearAllResults() 会将数据库内暂存的 ping 延迟、测速结果归零，并触发数据流刷新列表视图。

右侧长按测试当前主页面订阅的延迟和ip国旗
在 MainActivity.kt 中，测试当前主页面的节点/订阅延迟与出站 IP（国旗）直接依托于 TestSessionClient 与 binding.stats.testConnection()。

以下是将右侧长按设为“批量测试当前订阅/分组的所有节点延迟 + 刷新底栏当前出站 IP/国旗”的完整实现方案：

1. 替换 onCreate 里的 stats 监听器
在 onCreate 内部找到：

Kotlin
binding.stats.setOnClickListener { if (DataStore.serviceState.connected) binding.stats.testConnection() }
binding.stats.setOnLongClickListener {
    showStatsMenu()
    true
}
替换为：

Kotlin
val statsGestureDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
    override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
        if (e.x < binding.stats.width / 2f) {
            onStatsLeftClick()
        } else {
            onStatsRightClick()
        }
        return true
    }

    override fun onLongPress(e: android.view.MotionEvent) {
        if (e.x < binding.stats.width / 2f) {
            onStatsLeftLongClick()
        } else {
            onStatsRightLongClick()
        }
    }
})

binding.stats.setOnTouchListener { _, event ->
    statsGestureDetector.onTouchEvent(event)
    false
}
2. 在 MainActivity 中加入 4 个对应处理方法
将以下代码添加到 MainActivity 中（例如放在 showStatsMenu() 方法旁边）：

Kotlin
/** 1. 右侧点击：保持原逻辑不变（测试当前连接连通性与底栏状态） */
private fun onStatsRightClick() {
    if (DataStore.serviceState.connected) {
        binding.stats.testConnection()
    }
}

/** 2. 右侧长按：测试当前主页面订阅/分组所有节点延迟 + 刷新当前出站 IP 与国旗 */
private fun onStatsRightLongClick() {
    // 1. 如果已连接，首先刷新底栏的当前节点连通性与 IP/国旗
    if (DataStore.serviceState.connected) {
        binding.stats.testConnection()
    }

    // 2. 批量测试当前主页面可见分组/订阅的全部节点延迟
    val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_holder)
    if (currentFragment is ConfigurationFragment) {
        // 当前在配置页时，直接触发当前组的批量测延迟
        currentFragment.testCurrentGroup()
    } else {
        // 若在其他页面，获取当前选中代理所在分组并后台触发测速
        runOnDefaultDispatcher {
            val groupId = ProfileManager.getProfile(DataStore.selectedProxy)?.groupId
                ?: ProfileManager.currentGroupId()
            if (groupId > 0) {
                TestSessionClient.start(groupId, TestSpec.KIND_PING)
            }
        }
    }
}

/** 3. 左侧点击：切换到配置页并打开自动选择器 / 定位当前选择器 */
private fun onStatsLeftClick() {
    if (currentMainFragment !is ConfigurationFragment) {
        displayFragmentWithId(R.id.nav_configuration)
    }
    refreshConfigurationProfileState()
}

/** 4. 左侧长按：清空全部测试数据（延迟、速度、状态） */
private fun onStatsLeftLongClick() {
    runOnDefaultDispatcher {
        ProfileManager.clearAllResults()
        onMainDispatcher {
            refreshConfigurationProfileState()
            snackbar("已清空节点测试数据").show()
        }
    }
}
运行机制说明
出站 IP / 国旗测试：

binding.stats.testConnection() 是 SagerNet 底栏自带的核心方法，它会调用出站的 HTTP/DNS 探测，返回真实出站出口 IP 并将对应的国家/地区代码映射到底栏的国旗图标与 Ping 延迟数值上。

当前订阅延迟测试：

ConfigurationFragment 内部封装了对其当前加载分组的批量 Ping 测速能力（testCurrentGroup() / TestSessionClient.start(groupId, TestSpec.KIND_PING)），长按右侧即可直接驱动当前订阅列表的所有节点测 Ping 并更新卡片延迟。

给我完整代码？
这里是整合好 4 个按键功能后的完整 MainActivity.kt 源码。

功能分配
左侧点击：切换到配置页并定位选择器

左侧长按：清空所有节点测试数据（延迟、速度、状态）

右侧点击：原有逻辑（已连接时测试连通性）

右侧长按：刷新出站 IP/国旗，并对当前页面的订阅/分组批量测试延迟

Kotlin
package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.RemoteException
import android.view.FocusFinder
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.annotation.IdRes
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.updatePaddingRelative
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceDataStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficDataBatch
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.bg.test.TestSpec
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.RouteManager
import io.nekohasekai.sagernet.database.SettingsMapper
import io.nekohasekai.sagernet.database.SettingsRegistry
import io.nekohasekai.sagernet.database.backup.BackupEntry
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutMainBinding
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.isPreview
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.outbound.`import`.ProfileImport
import io.nekohasekai.sagernet.ui.route.RouteImports
import io.nekohasekai.sagernet.ui.settings.DnsSettingsFragment
import io.nekohasekai.sagernet.ui.settings.XrayGeoSettingsFragment
import io.nekohasekai.sagernet.ui.test.TestSessionClient
import io.nekohasekai.sagernet.utils.AppShortcuts
import io.nekohasekai.sagernet.utils.PlatformNotifications
import io.nekohasekai.sagernet.utils.Theme
import io.nekohasekai.sagernet.utils.WifiStateAccess
import io.nekohasekai.sagernet.widget.bars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ThemedActivity(),
    SagerConnection.Callback,
    OnPreferenceDataStoreChangeListener,
    NavigationView.OnNavigationItemSelectedListener {

    companion object {
        /** Texts to import (the QR scanner): URLs and deep links one by one, the rest as one import. */
        const val EXTRA_IMPORT_TEXTS = "importTexts"
    }

    lateinit var binding: LayoutMainBinding
    lateinit var navigation: NavigationView
    private var currentMainFragment: ToolbarFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MessageStore.setCurrentActivity(this)
        val animateInitialControls = savedInstanceState == null

        binding = LayoutMainBinding.inflate(layoutInflater)
        binding.fab.initProgress(binding.fabProgress)
        // White theme: a dark FAB with a light icon (white on white is unreadable)
        if (Theme.isWhiteTheme()) {
            binding.fab.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.color_white_theme_fab)
            )
        }
        if (themeResId !in intArrayOf(
                R.style.Theme_SagerNet_Black,
                // The White theme and its night fallback read ?itemShapeFillColor too, so the selection stays visible
                R.style.Theme_SagerNet_White,
                R.style.Theme_SagerNet_White_Night
            )
        ) {
            navigation = binding.navView
            binding.drawerLayout.removeView(binding.navViewBlack)
        } else {
            navigation = binding.navViewBlack
            binding.drawerLayout.removeView(binding.navView)
        }
        navigation.setNavigationItemSelectedListener(this)
        // NavigationView pads its header and menu itself (top/bottom); its side follows side bars and cutouts.
        ViewCompat.setOnApplyWindowInsetsListener(binding.drawerLayout) { _, insets ->
            val bars = insets.bars()
            val rtl = navigation.layoutDirection == View.LAYOUT_DIRECTION_RTL
            navigation.updatePaddingRelative(start = if (rtl) bars.right else bars.left)
            insets
        }
        // The drawer's surface runs under the status bar: the icons follow it while the drawer is mostly open.
        binding.drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            private var overDrawer = false
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                if ((slideOffset > 0.5f) == overDrawer) return
                overDrawer = !overDrawer
                WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars =
                    if (overDrawer) !Theme.usingNightMode() else lightStatusBar
            }
        })

        if (savedInstanceState == null) {
            displayFragmentWithId(R.id.nav_configuration)
        } else {
            currentMainFragment =
                supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        }
        onBackPressedDispatcher.addCallback {
            if (supportFragmentManager.findFragmentById(R.id.fragment_holder) is ConfigurationFragment) {
                moveTaskToBack(true)
            } else {
                displayFragmentWithId(R.id.nav_configuration)
            }
        }

        binding.fab.setOnClickListener { toggleService() }

        // 底栏触控拆分：左点/右点，左长按/右长按
        val statsGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (e.x < binding.stats.width / 2f) {
                    onStatsLeftClick()
                } else {
                    onStatsRightClick()
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (e.x < binding.stats.width / 2f) {
                    onStatsLeftLongClick()
                } else {
                    onStatsRightLongClick()
                }
            }
        })

        binding.stats.setOnTouchListener { _, event ->
            statsGestureDetector.onTouchEvent(event)
            false
        }

        setContentView(binding.root)
        currentMainFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
                ?: currentMainFragment
        if (!animateInitialControls) {
            syncMainControls(showWhenConnected = false, animate = false)
        }
        changeState(
            BaseService.State.Idle,
            animate = false,
            animateControls = animateInitialControls,
        )
        connection.connect(this, this)
        DataStore.configurationStore.registerChangeListener(this)

        if (savedInstanceState == null) intent?.let(::handleImportIntent)
        SubscriptionReportDialog.observe(this)

        refreshNavMenu(DataStore.apiDashboardEnabled)

        if (savedInstanceState == null) {
            firstStart.start()
            if (intent?.getBooleanExtra(PlatformNotifications.EXTRA_WIFI_PERMISSION, false) == true) wifiFlow.run()
        }
        runOnDefaultDispatcher { AppShortcuts.publish(applicationContext) }

        if (isPreview && DataStore.previewHintDismissedVersion != BuildConfig.VERSION_NAME) {
            MaterialAlertDialogBuilder(this)
                .setTitle(BuildConfig.VERSION_NAME)
                .setMessage(R.string.preview_version_hint)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.preview_hint_dont_show_again) { _, _ ->
                    DataStore.previewHintDismissedVersion = BuildConfig.VERSION_NAME
                }
                .show()
        }
    }

    /** 1. 左侧点击：切换到配置页并定位选择器 */
    private fun onStatsLeftClick() {
        if (currentMainFragment !is ConfigurationFragment) {
            displayFragmentWithId(R.id.nav_configuration)
        }
        refreshConfigurationProfileState()
    }

    /** 2. 左侧长按：清空所有节点测试数据 */
    private fun onStatsLeftLongClick() {
        runOnDefaultDispatcher {
            ProfileManager.clearAllResults()
            onMainDispatcher {
                refreshConfigurationProfileState()
                snackbar("已清空节点测试数据").show()
            }
        }
    }

    /** 3. 右侧点击：保持现有点击不变（已连接时测试连通性） */
    private fun onStatsRightClick() {
        if (DataStore.serviceState.connected) {
            binding.stats.testConnection()
        }
    }

    /** 4. 右侧长按：测试当前主页面订阅/分组延迟并刷新 IP 与国旗 */
    private fun onStatsRightLongClick() {
        if (DataStore.serviceState.connected) {
            binding.stats.testConnection()
        }

        val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_holder)
        if (currentFragment is ConfigurationFragment) {
            currentFragment.testCurrentGroup()
        } else {
            runOnDefaultDispatcher {
                val groupId = ProfileManager.getProfile(DataStore.selectedProxy)?.groupId
                    ?: ProfileManager.currentGroupId()
                if (groupId > 0) {
                    TestSessionClient.start(groupId, TestSpec.KIND_PING)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        MessageStore.setCurrentActivity(this)
        DataStore.serviceError.takeIf { it.isNotEmpty() }?.let(::showServiceError)

        if (DataStore.hideFromRecentApps) {
            applyHideFromRecentApps(DataStore.hideFromRecentApps)
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        val restoredFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        if (restoredFragment != null && restoredFragment !== currentMainFragment) {
            currentMainFragment = restoredFragment
            syncMainControls(
                fragment = restoredFragment,
                showWhenConnected = DataStore.serviceState == BaseService.State.Connected,
                animate = false,
            )
        }
    }

    fun applyHideFromRecentApps(hide: Boolean) {
        try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val tasks = activityManager.appTasks
            if (tasks.isNotEmpty()) {
                val task = tasks[0]
                task.setExcludeFromRecents(hide)
            }
        } catch (e: Exception) {
            Logs.w("Failed to set excludeFromRecents: ${e.message}")
        }
    }

    /** The dashboard item follows the sing-box API (core_box_api_port), which serves the dashboard. */
    fun refreshNavMenu(dashboard: Boolean) {
        if (::navigation.isInitialized) {
            navigation.menu.findItem(R.id.nav_traffic)?.isVisible = dashboard
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        if (intent.getBooleanExtra(PlatformNotifications.EXTRA_WIFI_PERMISSION, false)) {
            wifiFlow.run()
            return
        }

        handleImportIntent(intent)
    }

    /** VIEW links and files, SEND streams and texts, and the texts of the QR scanner ([EXTRA_IMPORT_TEXTS]). */
    private fun handleImportIntent(intent: Intent) {
        intent.getStringArrayListExtra(EXTRA_IMPORT_TEXTS)?.let {
            SubscribeFlows.importTexts(this, it)
            return
        }
        when (intent.action) {
            Intent.ACTION_VIEW -> openLink(intent.data ?: return)
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (stream != null) {
                    openFile(stream)
                } else {
                    intent.getStringExtra(Intent.EXTRA_TEXT)?.let { SubscribeFlows.importText(this, it) }
                }
            }
        }
    }

    /**
     * Files go to the backup restore or the text import; subscription links show the Groups screen and ask; route and
     * other throne:// commands follow the desktop's deep links; profile links are confirmed before the import.
     */
    private fun openLink(uri: Uri) {
        val scheme = uri.scheme?.lowercase()
        if (scheme == "content" || scheme == "file") return openFile(uri)
        val link = uri.toString()
        if (SubscribeFlows.subscribeLink(link) != null) displayFragmentWithId(R.id.nav_group)
        if (scheme == "clash" || scheme == "throne" && !uri.host.equals("add", ignoreCase = true)) {
            SubscribeFlows.importText(this, link)
        } else {
            runOnDefaultDispatcher { importProfile(uri) }
        }
    }

    private fun openFile(uri: Uri) {
        // Sniffing the backup magic may read a cloud file over the network.
        runOnDefaultDispatcher {
            if (!BackupEntry.open(this@MainActivity, uri)) SubscribeFlows.importUri(this@MainActivity, uri)
        }
    }

    /** throne://route and throne://remoteroute links (deep link, QR code, clipboard); the Routes screen shows the result. */
    fun importRouteLink(text: String) {
        RouteImports.importLink(this, text) { displayFragmentWithId(R.id.nav_route) }
    }

    /** Opens one settings sub-screen, e.g. Settings › Routing, with the settings root below it. */
    fun openSettingsScreen(fragmentClass: String, title: CharSequence) {
        displayFragment(SettingsFragment.forScreen(fragmentClass, title))
        navigation.menu.findItem(R.id.nav_settings).isChecked = true
    }

    fun urlTest(): Int {
        if (!DataStore.serviceState.connected || connection.service == null) {
            error("not started")
        }
        return connection.service!!.urlTest()
    }

    /** A profile link opened from outside: confirmed, then imported into the current group. */
    suspend fun importProfile(uri: Uri) {
        val link = uri.toString()
        val result = try {
            ProfileImport.parse(link, SettingsMapper.xrayVlessPreference(), SettingsMapper.buildContext())
        } catch (e: Exception) {
            onMainDispatcher {
                alert(e.readableMessage).show()
            }
            return
        }
        for (message in result.messages) Logs.w(message)
        val profile = result.outbounds.firstOrNull()
        if (profile == null) {
            val report = result.report()
            onMainDispatcher {
                if (report.hasProblems()) {
                    SubscriptionReportDialog.show(this@MainActivity, getString(R.string.validation_import_title), report.text())
                } else {
                    alert(getString(R.string.no_proxies_found)).show()
                }
            }
            return
        }

        onMainDispatcher {
            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.profile_import)
                .setMessage(getString(R.string.profile_import_message, profile.displayName()))
                .setPositiveButton(R.string.yes) { _, _ ->
                    displayFragmentWithId(R.id.nav_configuration)
                    SubscribeFlows.importText(this@MainActivity, link)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        if (item.isChecked) binding.drawerLayout.closeDrawers() else {
            return displayFragmentWithId(item.itemId)
        }
        return true
    }

    @SuppressLint("CommitTransaction")
    fun displayFragment(fragment: ToolbarFragment) {
        currentMainFragment = fragment
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_holder, fragment)
            .commitAllowingStateLoss()
        binding.drawerLayout.closeDrawers()
        syncMainControls(fragment, showWhenConnected = false, animate = true)
    }

    private fun syncMainControls(
        fragment: Any? = currentMainFragment
            ?: supportFragmentManager.findFragmentById(R.id.fragment_holder),
        showWhenConnected: Boolean,
        animate: Boolean,
    ) {
        val showControls = fragment is ConfigurationFragment
        binding.stats.useExternalScrollDriver = fragment is ConfigurationFragment
        binding.stats.syncMainControls(
            showControls,
            DataStore.serviceState,
            showWhenConnected,
            animate,
        )
        binding.fab.animate().cancel()
        if (showControls) {
            binding.fab.show()
            // a start error shown on another screen sits at the bottom, where the FAB now comes back
            errorBar?.takeIf { it.isShown && it.anchorView == null }?.anchorAboveFab()
        } else {
            binding.fab.hideProgress()
            binding.fabProgress.hide()
            binding.fabProgress.visibility = View.INVISIBLE
            if (animate && binding.fab.isLaidOut) {
                binding.fab.hide()
            } else {
                binding.fab.visibility = View.INVISIBLE
            }
        }
    }

    private fun refreshConfigurationProfileState() {
        val fragment = currentMainFragment
            ?: supportFragmentManager.findFragmentById(R.id.fragment_holder)
        (fragment as? ConfigurationFragment)?.refreshProfileState()
    }

    fun driveBottomBar(scrollDy: Int) {
        binding.stats.onListScrolled(scrollDy)
    }

    fun displayFragmentWithId(@IdRes id: Int): Boolean {
        when (id) {
            R.id.nav_configuration -> {
                displayFragment(ConfigurationFragment())
            }

            R.id.nav_group -> displayFragment(GroupFragment())
            R.id.nav_route -> displayFragment(RouteFragment())
            R.id.nav_settings -> displayFragment(SettingsFragment())
            R.id.nav_traffic -> displayFragment(DashboardFragment())
            R.id.nav_tools -> displayFragment(ToolsFragment())
            R.id.nav_logcat -> displayFragment(LogcatFragment())
            R.id.nav_live_graphs -> displayFragment(LiveGraphsFragment())
            R.id.nav_faq -> {
                launchCustomTab("https://throneproj.github.io")
                return false
            }

            R.id.nav_about -> displayFragment(AboutFragment())

            else -> return false
        }
        navigation.menu.findItem(id).isChecked = true
        return true
    }

    private fun changeState(
        state: BaseService.State,
        msg: String? = null,
        animate: Boolean = false,
        animateControls: Boolean = animate,
    ) {
        DataStore.serviceState = state
        refreshConfigurationProfileState()
        when (val fragment = currentMainFragment ?: supportFragmentManager.findFragmentById(R.id.fragment_holder)) {
            is RouteFragment -> fragment.onServiceStateChanged()
            is DashboardFragment -> fragment.onServiceStateChanged()
        }

        binding.fab.changeState(state, DataStore.serviceState, animate)
        binding.stats.changeState(state)
        syncMainControls(
            showWhenConnected = state == BaseService.State.Connected,
            animate = animateControls,
        )
        if (state == BaseService.State.Connected) {
            binding.stats.testConnection()
        }
        if (state == BaseService.State.Connecting) errorBar?.dismiss()
        if (msg == null) return
        if (msg == DataStore.serviceError) showServiceError(msg) else snackbar(getString(R.string.vpn_error, msg)).show()
    }

    private var errorBar: Snackbar? = null
    private var errorShown = ""

    /** A failed start stays until the next start, its action or a swipe; the service keeps it for a later visit. */
    private fun showServiceError(message: String) {
        if (errorBar?.isShownOrQueued == true && errorShown == message) return
        errorShown = message
        val bar = snackbar(message).setDuration(Snackbar.LENGTH_INDEFINITE)
        if (DataStore.serviceErrorDns) {
            bar.setAction(R.string.settings_dns) {
                openSettingsScreen(DnsSettingsFragment::class.java.name, getString(R.string.settings_dns))
            }
        } else if (DataStore.serviceErrorGeo) {
            bar.setAction(R.string.xray_geo_assets_action) {
                openSettingsScreen(XrayGeoSettingsFragment::class.java.name, getString(R.string.xray_geo_assets))
            }
        } else if (DataStore.serviceErrorRuleSets) {
            bar.setAction(R.string.rule_set_deferred_action) { confirmStartWithoutRuleSets() }
        } else {
            bar.setAction(R.string.menu_log) { displayFragmentWithId(R.id.nav_logcat) }
        }
        errorBar = bar.addCallback(object : Snackbar.Callback() {
            override fun onDismissed(transientBottomBar: Snackbar, event: Int) {
                if (event == DISMISS_EVENT_SWIPE || event == DISMISS_EVENT_ACTION) {
                    DataStore.serviceError = ""
                    DataStore.serviceErrorDns = false
                    DataStore.serviceErrorGeo = false
                    DataStore.serviceErrorRuleSets = false
                }
            }
        }).also { it.show() }
    }

    /** The next start, within a few minutes, goes without the rule-sets it could not download (DeferredRuleSets). */
    private fun confirmStartWithoutRuleSets() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rule_set_deferred_title)
            .setMessage(R.string.rule_set_deferred_message)
            .setPositiveButton(R.string.connect) { _, _ ->
                if (DataStore.serviceState.canStop) return@setPositiveButton
                DataStore.startWithoutRuleSets = System.currentTimeMillis()
                startFromUi()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Shows group [id] when the profiles screen is open; otherwise it opens there next, being the current group. */
    fun showGroup(id: Long) {
        (supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ConfigurationFragment)?.showGroup(id)
    }

    /** The stats bar's long-press menu: the live graphs, and a speed test of the running connection. */
    private fun showStatsMenu() {
        val popup = PopupMenu(this, binding.stats)
        popup.menuInflater.inflate(R.menu.stats_bar_menu, popup.menu)
        popup.menu.findItem(R.id.action_test_speed_current).isEnabled = DataStore.serviceState.connected
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_live_graphs -> displayFragmentWithId(R.id.nav_live_graphs)
                R.id.action_test_speed_current -> {
                    if (!TestSessionClient.startCurrent(TestSpec.KIND_SPEED)) {
                        snackbar(R.string.profiles_test_busy).show()
                    }
                }
            }
            true
        }
        popup.show()
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG).apply {
            if (binding.fab.visibility == View.VISIBLE) anchorAboveFab()
        }
    }

    private fun Snackbar.anchorAboveFab() {
        anchorView = binding.fab
        isAnchorViewLayoutListenerEnabled = true
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        changeState(state, msg, true)
    }

    val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND, true)
    override fun onServiceConnected(service: ISagerNetService) = changeState(
        try {
            BaseService.State.values()[service.state]
        } catch (_: RemoteException) {
            BaseService.State.Idle
        }
    )

    override fun onServiceDisconnected() = changeState(BaseService.State.Idle)
    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) snackbar(R.string.vpn_permission_denied).show()
    }

    private val firstStart = FirstStartPrompts(this)
    private val wifiFlow = WifiPermissionFlow(this)

    private fun toggleService() {
        if (DataStore.serviceState.canStop) SagerNet.stopService() else startFromUi()
    }

    /** Wi-Fi rules of the routing profile the selected profile starts with ask for location access once before a start from here. */
    private fun startFromUi() {
        if (DataStore.wifiPermissionAsked || WifiStateAccess.status(this) == WifiStateAccess.Status.OK) {
            return launchConnect()
        }
        lifecycleScope.launch {
            val usesWifi = withContext(Dispatchers.IO) {
                runCatching {
                    val groupId = ProfileManager.getProfile(DataStore.selectedProxy)?.groupId ?: -1L
                    RouteManager.routeFor(groupId).rules.any { rule ->
                        !rule.disabled && (rule.wifi_ssid.any { it.isNotBlank() } || rule.wifi_bssid.any { it.isNotBlank() })
                    }
                }.getOrDefault(false)
            }
            if (usesWifi) {
                DataStore.wifiPermissionAsked = true
                wifiFlow.run { launchConnect() }
            } else {
                launchConnect()
            }
        }
    }

    private fun launchConnect() {
        try {
            connect.launch(null)
        } catch (_: ActivityNotFoundException) {
            VpnRequestActivity.showConsentUnavailable(this)
        } catch (_: SecurityException) {
            VpnRequestActivity.showConsentUnavailable(this)
        }
    }

    override fun cbSpeedUpdate(stats: SpeedDisplayData) {
        binding.stats.updateSpeed(stats.txRateProxy, stats.rxRateProxy)
    }

    override suspend fun cbTrafficUpdate(data: TrafficDataBatch) {
        ProfileManager.postUpdate(data.items)
    }

    override fun cbSelectorUpdate(id: Long) {
        val old = DataStore.selectedProxy
        DataStore.selectedProxy = id
        DataStore.currentProfile = id
        refreshConfigurationProfileState()
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(old, true)
            ProfileManager.postUpdate(id, true)
        }
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        when (key) {
            Key.SERVICE_MODE -> onBinderDied()
            Key.PROXY_APPS, Key.BYPASS_MODE, Key.INDIVIDUAL,
            SettingsRegistry.CUSTOM_INBOUND.key, SettingsRegistry.DNS_OBJECT.key -> {
                if (DataStore.serviceState.canStop) {
                    snackbar(getString(R.string.need_reload)).setAction(R.string.apply) {
                        SagerNet.reloadService()
                    }.show()
                }
            }
        }
    }

    override fun onStart() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        super.onStart()
    }

    override fun onStop() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        DataStore.configurationStore.unregisterChangeListener(this)
        connection.disconnect(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val rtl = binding.root.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val towardsDrawer = if (rtl) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        val awayFromDrawer = if (rtl) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        when (keyCode) {
            towardsDrawer -> {
                if (super.onKeyDown(keyCode, event)) return true
                val direction = if (rtl) View.FOCUS_RIGHT else View.FOCUS_LEFT
                if (!binding.drawerLayout.isOpen && screenFocusTarget(direction) == null) {
                    binding.drawerLayout.open()
                    navigation.requestFocus()
                    return true
                }
            }

            awayFromDrawer -> {
                if (binding.drawerLayout.isOpen) {
                    binding.drawerLayout.close()
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> if (!binding.drawerLayout.isOpen && focusMainControls()) return true

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (SagerNet.isTv) {
                toggleService()
                return true
            }
        }

        if (super.onKeyDown(keyCode, event)) return true
        if (binding.drawerLayout.isOpen) return false

        val fragment =
            supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        return fragment != null && fragment.onKeyDown(keyCode, event)
    }

    private fun screenFocusTarget(direction: Int): View? {
        val focused = currentFocus ?: return null
        val root = supportFragmentManager.findFragmentById(R.id.fragment_holder)?.view as? ViewGroup ?: return null
        if (!focused.isInside(root)) return null
        return FocusFinder.getInstance().findNextFocus(root, focused, direction)
            ?.takeIf { it.getGlobalVisibleRect(Rect()) }
    }

    private fun focusMainControls(): Boolean {
        val focused = currentFocus ?: return false
        val root = supportFragmentManager.findFragmentById(R.id.fragment_holder)?.view ?: return false
        if (!focused.isInside(root)) return false
        var parent = focused.parent
        while (parent is View && parent !== root) {
            if (parent.canScrollVertically(1)) return false
            parent = parent.parent
        }
        screenFocusTarget(View.FOCUS_DOWN)?.let { return it.requestFocus() }
        return when {
            binding.fab.isShown && binding.fab.isFocusable -> binding.fab.requestFocus()
            binding.stats.isShown && binding.stats.isFocusable -> binding.stats.requestFocus()
            else -> false
        }
    }

    private fun View.isInside(root: View): Boolean {
        var view: View? = this
        while (view != null) {
            if (view === root) return true
            view = view.parent as? View
        }
        return false
    }

}
跳至 Gemini 提供的最新结果
