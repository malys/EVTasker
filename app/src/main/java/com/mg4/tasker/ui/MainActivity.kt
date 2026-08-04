package com.mg4.tasker.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.mg4.tasker.R
import com.mg4.tasker.databinding.ActivityMainBinding
import com.mg4.tasker.store.UpdateLaunchStore

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tabGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            show(
                when (checkedId) {
                    R.id.tabHistory    -> HistoryFragment()
                    R.id.tabDiagnostic -> DiagnosticFragment()
                    R.id.tabConsole    -> ConsoleFragment()
                    R.id.tabConfig     -> ConfigFragment()
                    else               -> RulesFragment()
                }
            )
        }

        // Rotation restores the fragment via the FragmentManager: only re-select the
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
            binding.tabGroup.check(initialTab)
        }

        // Unstable builds check for a newer pre-release; the stable flavor's UpdateHook is
        // a no-op and the stable APK contains no updater code at all.
        com.mg4.tasker.update.UpdateHook.checkInBackground(this)
    }

    private fun show(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.content, fragment)
            .commit()
    }
}
