package com.mg4.tasker.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.mg4.tasker.R
import com.mg4.tasker.databinding.ActivityMainBinding

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
                    else               -> RulesFragment()
                }
            )
        }

        // Rotation restores the fragment via the FragmentManager: only re-select the
        // initial tab on first show, otherwise the screen jumps back to the rules.
        if (savedInstanceState == null) {
            binding.tabGroup.check(R.id.tabRules)
        }

        // Unstable builds check for a newer pre-release; the stable flavor's UpdateHook is
        // a no-op and the stable APK contains no updater code or network permission.
        com.mg4.tasker.update.UpdateHook.checkInBackground(this)
    }

    private fun show(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.content, fragment)
            .commit()
    }
}
