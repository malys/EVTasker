package com.evsuite.tasker.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import androidx.appcompat.app.AppCompatActivity
import com.evsuite.tasker.R
import com.evsuite.tasker.databinding.ActivityConfirmActionBinding

/**
 * The window behind [com.evsuite.hardware.catalog.ActionType.ASK_CONFIRM].
 *
 * An activity rather than a dialog for the reason
 * [ProfileAutomationWarningActivity] is one: it has to be readable from the driver's seat,
 * and it must appear over whatever the head unit is showing — the rule that asks is not
 * running inside EVTasker's own UI.
 *
 * Every way out that is not "yes" is a no. Leaving the question is an answer, and it is not
 * the permissive one.
 */
class ConfirmActionActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_QUESTION = "question"
        private const val EXTRA_TIMEOUT_MS = "timeoutMs"

        fun intent(context: Context, question: String, timeoutMs: Long): Intent =
            Intent(context, ConfirmActionActivity::class.java)
                .putExtra(EXTRA_QUESTION, question)
                .putExtra(EXTRA_TIMEOUT_MS, timeoutMs)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    private var answered = false
    private var countdown: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityConfirmActionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // The rule may have given up while this window was starting. Nothing left to answer.
        if (!ConfirmPrompt.register { runOnUiThread { finish() } }) {
            answered = true
            finish()
            return
        }

        binding.confirmQuestion.text = intent.getStringExtra(EXTRA_QUESTION).orEmpty()
        binding.confirmNo.setOnClickListener { answer(ConfirmPrompt.Answer.NO) }
        binding.confirmYes.setOnClickListener { answer(ConfirmPrompt.Answer.YES) }

        val timeoutMs = intent.getLongExtra(EXTRA_TIMEOUT_MS, ConfirmPrompt.TIMEOUT_MS)
        countdown = object : CountDownTimer(timeoutMs, 1_000L) {
            override fun onTick(remainingMs: Long) {
                binding.confirmCountdown.text =
                    getString(R.string.confirm_countdown, (remainingMs / 1000L).toInt())
            }

            override fun onFinish() {
                binding.confirmCountdown.text = getString(R.string.confirm_countdown, 0)
            }
        }.also { it.start() }
    }

    private fun answer(answer: ConfirmPrompt.Answer) {
        if (answered) return
        answered = true
        ConfirmPrompt.answer(answer)
        finish()
    }

    /**
     * Covers the back gesture and any dismissal the platform performs on its own: the rule
     * gets its no here rather than waiting out the whole timeout for an answer that is
     * already given.
     */
    override fun onDestroy() {
        countdown?.cancel()
        countdown = null
        if (!answered) {
            answered = true
            ConfirmPrompt.answer(ConfirmPrompt.Answer.NO)
        }
        super.onDestroy()
    }
}
