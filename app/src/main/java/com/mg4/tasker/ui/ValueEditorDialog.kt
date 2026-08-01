package com.mg4.tasker.ui

import android.app.TimePickerDialog
import android.content.Context
import android.view.View
import android.widget.ArrayAdapter
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mg4.tasker.R
import com.mg4.tasker.bridge.BridgeContract
import com.mg4.tasker.databinding.DialogValueEditorBinding
import com.mg4.tasker.model.Action
import com.mg4.tasker.model.CompareOp
import com.mg4.tasker.model.Condition
import com.mg4.hardware.catalog.ConditionType
import com.mg4.hardware.catalog.ValueKind
import com.mg4.tasker.util.BtDevices
import java.util.Calendar

/**
 * Value entry for a condition or an action.
 *
 * A single dialog drives every type: it reads the [com.mg4.hardware.catalog.ValueSpec] and
 * shows only the matching control. That is what lets a catalogue entry be added without
 * writing a line of UI.
 */
object ValueEditorDialog {

    /** Days of the week in display order — Calendar.SUNDAY is 1, not 7. */
    private val WEEK_DAYS = listOf(
        Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
        Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
    )

    fun editCondition(
        context: Context,
        condition: Condition,
        dynamicMax: Int?,
        currentPoint: String? = null,
        onDone: (Condition) -> Unit
    ) {
        val binding = DialogValueEditorBinding.inflate(android.view.LayoutInflater.from(context))
        val spec = condition.type.spec
        val labels = Labels(context)

        // Operator: offered only where ordering means something. On a drive mode,
        // "below Sport" is meaningless — so it is not offered.
        val ops = if (condition.type.comparable) CompareOp.entries else listOf(CompareOp.EQ, CompareOp.NE)
        if (spec.kind == ValueKind.NUMBER || spec.kind == ValueKind.ENUM) {
            binding.opSpinner.visibility = View.VISIBLE
            binding.opSpinner.adapter = simpleAdapter(context, ops.map { labels.operator(it) })
            binding.opSpinner.setSelection(ops.indexOf(condition.op).coerceAtLeast(0))
        }

        val state = bindValue(
            context = context,
            binding = binding,
            spec = spec,
            dynamicMax = dynamicMax,
            gated = false,
            initialNumber = condition.number,
            initialFlag = condition.flag,
            initialText = condition.text,
            currentPoint = currentPoint,
            firmwareEnum = condition.type == ConditionType.FIRMWARE_GEN,
            choices = when {
                condition.type == ConditionType.FIRMWARE_GEN ->
                    com.mg4.hardware.catalog.VehicleEnums.FIRMWARE_GENS.map { Choice(it, it) }
                spec.kind == ValueKind.BT_DEVICE ->
                    BtDevices.bonded(context).map { Choice(it.mac, "${it.name} (${it.mac})") }
                else -> emptyList()
            },
            emptyChoiceMessage = context.getString(R.string.value_no_bt_devices),
            initialMinutesFrom = condition.minutesFrom,
            initialMinutesTo = condition.minutesTo,
            initialDays = condition.days
        )

        MaterialAlertDialogBuilder(context)
            .setTitle(condition.type.labelRes)
            .setView(binding.root)
            .setNegativeButton(R.string.editor_cancel, null)
            .setPositiveButton(R.string.value_ok) { _, _ ->
                val op = if (binding.opSpinner.visibility == View.VISIBLE)
                    ops[binding.opSpinner.selectedItemPosition] else condition.op
                onDone(
                    condition.copy(
                        op = op,
                        number = state.number(),
                        flag = state.flag(),
                        text = state.text(),
                        minutesFrom = state.minutesFrom,
                        minutesTo = state.minutesTo,
                        days = state.days()
                    )
                )
            }
            .show()
    }

    fun editAction(
        context: Context,
        action: Action,
        dynamicMax: Int?,
        profiles: List<Pair<String, String>>,
        /** What the car reports for this setting now — see [ActionType.currentKey]. */
        currentValue: Number? = null,
        onDone: (Action) -> Unit
    ) {
        val binding = DialogValueEditorBinding.inflate(android.view.LayoutInflater.from(context))
        val spec = action.type.spec

        val choices = when (spec.kind) {
            ValueKind.PROFILE -> profiles.map { Choice(it.first, it.second) }
            ValueKind.APP     -> launchableApps(context)
            else              -> emptyList()
        }

        val state = bindValue(
            context = context,
            binding = binding,
            spec = spec,
            dynamicMax = dynamicMax,
            gated = action.type.gated,
            // A fresh action carries no value yet, so the control opens on what the car
            // reports right now rather than on the bottom of its range.
            initialNumber = seedNumber(action, currentValue),
            initialFlag = action.flag,
            initialText = action.text,
            firmwareEnum = false,
            choices = choices,
            emptyChoiceMessage = context.getString(
                if (spec.kind == ValueKind.PROFILE) R.string.value_no_profiles
                else R.string.value_no_bt_devices
            ),
            initialMinutesFrom = action.minutesFrom,
            initialMinutesTo = action.minutesTo,
            initialDays = emptyList()
        )

        MaterialAlertDialogBuilder(context)
            .setTitle(action.type.labelRes)
            .setView(binding.root)
            .setNegativeButton(R.string.editor_cancel, null)
            .setPositiveButton(R.string.value_ok) { _, _ ->
                onDone(
                    action.copy(
                        number = state.number().toInt(),
                        flag = state.flag(),
                        text = state.text(),
                        minutesFrom = state.minutesFrom,
                        minutesTo = state.minutesTo
                    )
                )
            }
            .show()
    }

    // -------------------------------------------------------------------------

    /**
     * The car's present value, but only for an action being created.
     *
     * Reopening a saved action must show what the rule says, not what the car happens to be
     * doing — otherwise editing the name of a rule would quietly rewrite its brightness. A
     * fresh action is recognisable by carrying the model defaults.
     */
    private fun seedNumber(action: Action, currentValue: Number?): Float {
        if (action.number != 0 || currentValue == null) return action.number.toFloat()
        return currentValue.toFloat()
    }

    private data class Choice(val value: String, val label: String)

    /** Readers for the controls actually shown; the others return the original value. */
    private class State(
        val number: () -> Float,
        val flag: () -> Boolean,
        val text: () -> String,
        val days: () -> List<Int>,
        var minutesFrom: Int,
        var minutesTo: Int
    )

    private fun bindValue(
        context: Context,
        binding: DialogValueEditorBinding,
        spec: com.mg4.hardware.catalog.ValueSpec,
        dynamicMax: Int?,
        gated: Boolean,
        initialNumber: Float,
        initialFlag: Boolean,
        initialText: String,
        currentPoint: String? = null,
        firmwareEnum: Boolean,
        choices: List<Choice>,
        emptyChoiceMessage: String,
        initialMinutesFrom: Int,
        initialMinutesTo: Int,
        initialDays: List<Int>
    ): State {
        if (gated) binding.gatedExplain.visibility = View.VISIBLE

        var number = initialNumber
        var flag = initialFlag
        var text = initialText
        val selectedDays = initialDays.toMutableList()
        val state = State(
            number = { number }, flag = { flag }, text = { text },
            days = { selectedDays.toList() },
            minutesFrom = initialMinutesFrom, minutesTo = initialMinutesTo
        )

        when (spec.kind) {
            ValueKind.BOOL -> {
                binding.boolSwitch.visibility = View.VISIBLE
                binding.boolSwitch.isChecked = initialFlag
                binding.boolSwitch.setText(if (initialFlag) R.string.value_enabled else R.string.value_disabled)
                binding.boolSwitch.setOnCheckedChangeListener { view, checked ->
                    flag = checked
                    view.setText(if (checked) R.string.value_enabled else R.string.value_disabled)
                }
            }

            ValueKind.NUMBER -> {
                binding.numberBlock.visibility = View.VISIBLE
                // max = -1: bound known only at runtime (media volume). Prefer the real
                // vehicle value, falling back if it is missing.
                val max = if (spec.max >= 0) spec.max else (dynamicMax ?: spec.fallbackMax)
                binding.numberSlider.valueFrom = spec.min.toFloat()
                binding.numberSlider.valueTo = max.toFloat()
                val start = initialNumber.coerceIn(spec.min.toFloat(), max.toFloat())
                binding.numberSlider.value = start
                number = start

                val unit = spec.unitRes.takeIf { it != 0 }?.let { " " + context.getString(it) } ?: ""
                binding.numberValue.text = "${start.toInt()}$unit"
                binding.numberSlider.addOnChangeListener { _, value, _ ->
                    number = value
                    binding.numberValue.text = "${value.toInt()}$unit"
                }
            }

            ValueKind.ENUM -> {
                binding.choiceSpinner.visibility = View.VISIBLE
                if (firmwareEnum) {
                    val labels = choices.map { it.label }
                    binding.choiceSpinner.adapter = simpleAdapter(context, labels)
                    binding.choiceSpinner.setSelection(
                        choices.indexOfFirst { it.value == initialText }.coerceAtLeast(0)
                    )
                    text = choices.getOrNull(binding.choiceSpinner.selectedItemPosition)?.value ?: initialText
                    binding.choiceSpinner.onItemSelected { index -> text = choices[index].value }
                } else {
                    val options = spec.options
                    binding.choiceSpinner.adapter =
                        simpleAdapter(context, options.map { context.getString(it.labelRes) })
                    val index = options.indexOfFirst { it.value == initialNumber.toInt() }.coerceAtLeast(0)
                    binding.choiceSpinner.setSelection(index)
                    number = options.getOrNull(index)?.value?.toFloat() ?: initialNumber
                    binding.choiceSpinner.onItemSelected { i -> number = options[i].value.toFloat() }
                }
            }

            ValueKind.BT_DEVICE, ValueKind.PROFILE, ValueKind.APP -> {
                if (choices.isEmpty()) {
                    binding.choiceEmpty.visibility = View.VISIBLE
                    binding.choiceEmpty.text = emptyChoiceMessage
                } else {
                    binding.choiceSpinner.visibility = View.VISIBLE
                    binding.choiceSpinner.adapter = simpleAdapter(context, choices.map { it.label })
                    val index = choices.indexOfFirst { it.value == initialText }.coerceAtLeast(0)
                    binding.choiceSpinner.setSelection(index)
                    text = choices[index].value
                    binding.choiceSpinner.onItemSelected { i -> text = choices[i].value }
                }
            }

            ValueKind.TIME_RANGE -> {
                binding.timeBlock.visibility = View.VISIBLE
                val labels = Labels(context)
                binding.timeFrom.text = labels.formatTime(state.minutesFrom)
                binding.timeTo.text = labels.formatTime(state.minutesTo)
                binding.timeFrom.setOnClickListener {
                    pickTime(context, state.minutesFrom) {
                        state.minutesFrom = it
                        binding.timeFrom.text = labels.formatTime(it)
                    }
                }
                binding.timeTo.setOnClickListener {
                    pickTime(context, state.minutesTo) {
                        state.minutesTo = it
                        binding.timeTo.text = labels.formatTime(it)
                    }
                }
            }

            ValueKind.DAYS -> {
                binding.daysGroup.visibility = View.VISIBLE
                val labels = Labels(context)
                WEEK_DAYS.forEach { day ->
                    val chip = Chip(context).apply {
                        this.text = labels.dayLabel(day)
                        isCheckable = true
                        isChecked = day in selectedDays
                        minHeight = context.resources.getDimensionPixelSize(R.dimen.touch_target)
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) selectedDays.add(day) else selectedDays.remove(day)
                        }
                    }
                    binding.daysGroup.addView(chip)
                }
            }

            ValueKind.TEXT -> {
                binding.textBlock.visibility = View.VISIBLE
                binding.textInput.setText(initialText)
                binding.textInput.addTextChangedListener { text = it }
            }

            /**
             * Two of the existing controls at once: the text field holds the point, the
             * slider the radius. Prefilling the field with the car's current position is
             * what makes this usable at the wheel — nobody types coordinates on a head unit,
             * and "here" is the place almost every such rule is about.
             */
            ValueKind.LOCATION -> {
                binding.textBlock.visibility = View.VISIBLE
                binding.textInput.hint = context.getString(R.string.value_location_hint)
                val start = initialText.ifBlank { currentPoint.orEmpty() }
                binding.textInput.setText(start)
                text = start
                binding.textInput.addTextChangedListener { text = it }

                binding.numberBlock.visibility = View.VISIBLE
                binding.numberSlider.valueFrom = spec.min.toFloat()
                binding.numberSlider.valueTo = spec.max.toFloat()
                val radius = initialNumber.takeIf { it > 0f } ?: spec.min.toFloat()
                val clamped = radius.coerceIn(spec.min.toFloat(), spec.max.toFloat())
                binding.numberSlider.value = clamped
                number = clamped
                val unit = spec.unitRes.takeIf { it != 0 }?.let { " " + context.getString(it) } ?: ""
                binding.numberValue.text = "${clamped.toInt()}$unit"
                binding.numberSlider.addOnChangeListener { _, value, _ ->
                    number = value
                    binding.numberValue.text = "${value.toInt()}$unit"
                }
            }

            ValueKind.NONE -> Unit
        }

        return state
    }

    private fun pickTime(context: Context, minutes: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(
            context,
            { _, hour, minute -> onPicked(hour * 60 + minute) },
            minutes / 60, minutes % 60, true
        ).show()
    }

    private fun launchableApps(context: Context): List<Choice> {
        val pm = context.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                Choice(pkg, info.loadLabel(pm).toString())
            }
            .distinctBy { it.value }
            .sortedBy { it.label }
    }

    private fun simpleAdapter(context: Context, items: List<String>) =
        ArrayAdapter(context, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun android.widget.Spinner.onItemSelected(onSelect: (Int) -> Unit) {
        onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long
            ) = onSelect(position)

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    private fun android.widget.EditText.addTextChangedListener(onChange: (String) -> Unit) {
        addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = onChange(s?.toString() ?: "")
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
    }
}
