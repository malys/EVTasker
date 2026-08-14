package com.evsuite.tasker.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.evsuite.tasker.databinding.ActivityProfileAutomationWarningBinding

/**
 * Full-screen confirmation shown before EVTasker adds an EVProfile profile action.
 *
 * This is deliberately an activity rather than an alert dialog: the warning is long,
 * safety-relevant setup guidance and must remain readable at the head unit's distance.
 */
class ProfileAutomationWarningActivity : AppCompatActivity() {

    companion object {
        fun intent(context: Context) = Intent(context, ProfileAutomationWarningActivity::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityProfileAutomationWarningBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.warningCancel.setOnClickListener { finish() }
        binding.warningConfirm.setOnClickListener {
            setResult(Activity.RESULT_OK)
            finish()
        }
    }
}
