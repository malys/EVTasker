package com.evsuite.tasker.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.evsuite.tasker.BuildConfig
import com.evsuite.tasker.R
import com.evsuite.tasker.databinding.ActivityMainBinding
import com.evsuite.tasker.store.UpdateLaunchStore

class MainActivity : AppCompatActivity() {

    /**
     * One screen of the application, in the order the top bar lists them.
     *
     * [tabId] is the top-bar button that selects it — the same button the page-change
     * callback marks as current, and the id the adapter keys its fragments on. The pair
     * being declared once is what keeps the button and the swipe agreeing about where
     * they lead.
     */
    private class Screen(val tabId: Int, val create: () -> Fragment)

    private lateinit var binding: ActivityMainBinding
    private val consoleUnlock = ConsoleUnlock()
    private var consoleVisible = BuildConfig.CONSOLE_VISIBLE_BY_DEFAULT

    /** Rebuilt by [rebuildScreens]; Console is absent until it is unlocked. */
    private var screens: List<Screen> = emptyList()

    /**
     * Reads [screens] on every call rather than capturing it, so unlocking the console
     * only needs a notify — the pages already built keep their fragments.
     *
     * The ids are the tab ids, not the positions: inserting Console in the middle of the
     * list shifts Configuration by one, and a position-keyed adapter would hand
     * Configuration's fragment to the console's page.
     */
    private val pagerAdapter by lazy {
        object : FragmentStateAdapter(this) {
            override fun getItemCount() = screens.size
            override fun createFragment(position: Int) = screens[position].create()
            override fun getItemId(position: Int) = screens[position].tabId.toLong()
            override fun containsItem(itemId: Long) = screens.any { it.tabId.toLong() == itemId }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        consoleVisible = BuildConfig.CONSOLE_VISIBLE_BY_DEFAULT ||
            (savedInstanceState?.getBoolean(STATE_CONSOLE_VISIBLE) == true)
        updateConsoleVisibility()
        rebuildScreens()

        binding.content.adapter = pagerAdapter
        // Only the neighbours are kept alive. Holding all five would mean the diagnostic
        // and console fragments polling in the background of a screen nobody is reading.
        binding.content.offscreenPageLimit = 1
        binding.content.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = markCurrentPage(position)
        })

        TAB_IDS.forEach { tabId ->
            tab(tabId).setOnClickListener {
                // Stable is the offline channel: keep its support console out of the normal
                // driver navigation, while still leaving it reachable to an on-vehicle
                // diagnostician. The tap that unlocks it still opens Diagnostic, so the
                // gesture never costs the diagnostician the screen they asked for.
                if (tabId == R.id.tabDiagnostic && !consoleVisible &&
                    consoleUnlock.onDiagnosticTap()
                ) {
                    unlockConsole()
                }
                // Animated, so the button does the same thing the swipe does: the direction
                // of travel is what tells the driver where they are in the row.
                goTo(tabId, smooth = true)
            }
        }

        // Rotation restores the page from the pager's own saved state: only pick the
        // initial tab on first show, otherwise the screen jumps back to the rules.
        if (savedInstanceState == null) {
            // After an upgrade, make the new build's live vehicle/capability verdicts the
            // first thing the user sees. Never launch this activity from a package-replaced
            // receiver: that could create a driver-facing screen while the car is moving.
            val initialTab = if (UpdateLaunchStore.shouldOpenDiagnostic(this)) {
                R.id.tabDiagnostic
            } else {
                R.id.tabRules
            }
            // Without animation: at startup there is no movement to explain, only a
            // starting screen.
            goTo(initialTab, smooth = false)
        }
        markCurrentPage(binding.content.currentItem)

        // Unstable builds check for a newer pre-release; the stable flavor's UpdateHook is
        // a no-op and the stable APK contains no updater code at all.
        com.evsuite.tasker.update.UpdateHook.checkInBackground(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_CONSOLE_VISIBLE, consoleVisible)
        super.onSaveInstanceState(outState)
    }

    private fun rebuildScreens() {
        screens = buildList {
            add(Screen(R.id.tabRules) { RulesFragment() })
            add(Screen(R.id.tabHistory) { HistoryFragment() })
            add(Screen(R.id.tabDiagnostic) { DiagnosticFragment() })
            if (consoleVisible) add(Screen(R.id.tabConsole) { ConsoleFragment() })
            add(Screen(R.id.tabConfig) { ConfigFragment() })
        }
    }

    private fun unlockConsole() {
        consoleVisible = true
        updateConsoleVisibility()
        rebuildScreens()
        val index = screens.indexOfFirst { it.tabId == R.id.tabConsole }
        if (index >= 0) pagerAdapter.notifyItemInserted(index)
    }

    private fun updateConsoleVisibility() {
        binding.tabConsole.visibility = if (consoleVisible) View.VISIBLE else View.GONE
    }

    /** Moves the pager to [tabId]'s page; a no-op for a tab that is not a page. */
    private fun goTo(tabId: Int, smooth: Boolean) {
        val index = screens.indexOfFirst { it.tabId == tabId }
        if (index >= 0) binding.content.setCurrentItem(index, smooth)
    }

    /**
     * Marks the top-bar tab of the page on screen.
     *
     * Only `isSelected` is set: fill, text, icon and stroke come from the
     * res/color/nav_tab_*.xml selectors applied by the Widget.EV.NavTab style. Painting
     * the colors here as well would declare them twice, and it is the copy in code that
     * would win — including on the states (pressed, disabled) it knows nothing about.
     * `isSelected` also serves TalkBack, which announces the current destination.
     */
    private fun markCurrentPage(position: Int) {
        screens.forEachIndexed { index, screen ->
            tab(screen.tabId).isSelected = index == position
        }
    }

    private fun tab(tabId: Int): MaterialButton = findViewById(tabId)

    private companion object {
        const val STATE_CONSOLE_VISIBLE = "consoleVisible"

        /** Every tab in the bar, including the console's while it is still hidden. */
        val TAB_IDS = listOf(
            R.id.tabRules,
            R.id.tabHistory,
            R.id.tabDiagnostic,
            R.id.tabConsole,
            R.id.tabConfig
        )
    }
}
