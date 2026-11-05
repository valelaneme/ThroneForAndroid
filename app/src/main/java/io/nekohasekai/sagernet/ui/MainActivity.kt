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
import io.nekohasekai.sagernet.database.GroupRepo
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
import io.nekohasekai.sagernet.ui.profiles.ProfileTests
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

        // 底栏触控拆分：左点/右点，左长按/右长按手势监听
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

    /** 1. 左侧点击：预留空间（可按需加入其他功能） */
    private fun onStatsLeftClick() {
        // 当前留空
    }

    /** 2. 左侧长按：清空测试数据（参照 ProfileItemMenu 逻辑安全清理当前分组/全部节点结果） */
    private fun onStatsLeftLongClick() {
        lifecycleScope.launch(Dispatchers.IO) {
            val groupId = ProfileManager.currentGroupId()
            val profileIds = ProfileManager.memberIds(groupId)
            if (profileIds.isNotEmpty()) {
                ProfileManager.clearTestResults(profileIds)
                GroupRepo.postReload(groupId)
            }
            withContext(Dispatchers.Main) {
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

    /** 4. 右侧长按：测试当前主页面节点的延迟和 IP/国家国旗 */
    private fun onStatsRightLongClick() {
        if (!DataStore.serviceState.connected) {
            snackbar("服务未连接").show()
            return
        }
        
        // 刷新底层连通性状态
        binding.stats.testConnection()

        // 触发当前所选配置或当前分组的 IP 测试与延迟测试
        lifecycleScope.launch(Dispatchers.IO) {
            val currentProfileId = DataStore.selectedProxy
            val groupId = ProfileManager.currentGroupId()
            if (currentProfileId > 0L) {
                // 参照 ProfileItemMenu 的安全调用方式，对当前节点发起 IP/国旗测试和 URL 测试
                val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_holder)
                if (currentFragment is ConfigurationFragment) {
                    ProfileTests.startProfiles(currentFragment, TestSpec.KIND_IP, listOf(currentProfileId), groupId)
                    ProfileTests.startProfiles(currentFragment, TestSpec.KIND_URL, listOf(currentProfileId), groupId)
                }
            }
        }
        snackbar("正在检测当前节点延迟与 IP 国旗...").show()
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
