package com.mg4.tasker.ui

import android.app.TimePickerDialog
import android.app.DatePickerDialog
import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.chip.Chip
import com.mg4.tasker.R
import com.mg4.tasker.bridge.BridgeContract
import com.mg4.tasker.engine.ConditionEvaluator
import com.mg4.tasker.databinding.DialogValueEditorBinding
import com.mg4.tasker.databinding.ScreenValueEditorBinding
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

        if (spec.kind == ValueKind.PHYSICAL_BUTTON) {
            val buttons = com.mg4.hardware.PhysicalButtonEventDecoder.Button.entries
            val presses = com.mg4.hardware.PhysicalButtonEventDecoder.Press.entries
            binding.physicalButtonBlock.visibility = View.VISIBLE
            binding.physicalButtonSpinner.adapter = simpleAdapter(context, buttons.map { buttonLabel(it.name) })
            binding.physicalPressSpinner.adapter = simpleAdapter(
                context, listOf(context.getString(R.string.physical_press_short), context.getString(R.string.physical_press_long))
            )
            binding.physicalButtonSpinner.setSelection(
                buttons.indexOfFirst { condition.number.toInt() in it.codes }.coerceAtLeast(0)
            )
            binding.physicalPressSpinner.setSelection(presses.indexOfFirst { it.name == condition.text }.coerceAtLeast(0))
            showEditor(context, condition.type.labelRes, binding) {
                    onDone(condition.copy(
                        number = buttons[binding.physicalButtonSpinner.selectedItemPosition].codes.first().toFloat(),
                        text = presses[binding.physicalPressSpinner.selectedItemPosition].name
                    ))
                    true
            }
            return
        }

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

        showEditor(context, condition.type.labelRes, binding) {
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
                true
        }
    }

    private fun buttonLabel(name: String): String = name.lowercase()
        .split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    fun editAction(
        context: Context,
        action: Action,
        dynamicMax: Int?,
        profiles: List<Pair<String, String>>,
        contacts: List<com.mg4.tasker.util.ContactDirectory.Entry> = emptyList(),
        /** What the car reports for this setting now — see [ActionType.currentKey]. */
        currentValue: Number? = null,
        onDone: (Action) -> Unit
    ) {
        val binding = DialogValueEditorBinding.inflate(android.view.LayoutInflater.from(context))
        val spec = action.type.spec

        val choices = when (spec.kind) {
            ValueKind.PROFILE -> profiles.map { Choice(it.first, it.second) }
            ValueKind.APP     -> launchableApps(context)
            ValueKind.CONTACT -> contacts.map { Choice(it.number, it.label) }
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
            initialPayload = action.payload.orEmpty(),
            firmwareEnum = false,
            choices = choices,
            emptyChoiceMessage = context.getString(when (spec.kind) {
                ValueKind.PROFILE -> R.string.value_no_profiles
                ValueKind.CONTACT -> R.string.value_no_contacts
                else -> R.string.value_no_bt_devices
            }),
            initialMinutesFrom = action.minutesFrom,
            initialMinutesTo = action.minutesTo,
            initialDays = emptyList()
        )

        showEditor(context, action.type.labelRes, binding) {
                if (spec.kind == ValueKind.CONTACT && state.text().isBlank()) {
                    binding.contactBlock.error = context.getString(R.string.value_call_required)
                    return@showEditor false
                }
                onDone(
                    action.copy(
                        number = state.number().toInt(),
                        flag = state.flag(),
                        text = state.text(),
                        payload = state.payload(),
                        displayName = if (spec.kind == ValueKind.CONTACT)
                            state.choiceLabel().ifBlank { null }
                        else action.displayName,
                        minutesFrom = state.minutesFrom,
                        minutesTo = state.minutesTo
                    )
                )
                true
        }
    }

    /** Full-screen, touch-first editor. The caller keeps the typed model callback. */
    private fun showEditor(
        context: Context,
        titleRes: Int,
        content: DialogValueEditorBinding,
        save: () -> Boolean
    ) {
        val shell = ScreenValueEditorBinding.inflate(android.view.LayoutInflater.from(context))
        (content.root.parent as? android.view.ViewGroup)?.removeView(content.root)
        val placeholder = shell.valueEditorContent.root
        val parent = placeholder.parent as android.view.ViewGroup
        val index = parent.indexOfChild(placeholder)
        parent.removeViewAt(index)
        parent.addView(content.root, index, android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        val dialog = android.app.Dialog(context, R.style.Theme_MG4_Picker)
        shell.editorTitle.setText(titleRes)
        shell.editorCancel.setOnClickListener { dialog.dismiss() }
        shell.editorSave.setOnClickListener {
            if (save()) dialog.dismiss()
        }
        dialog.setContentView(shell.root)
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        dialog.show()
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
        val payload: () -> String,
        val choiceLabel: () -> String,
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
        initialPayload: String = "",
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
        var payload = initialPayload
        var choiceLabel = ""
        val selectedDays = initialDays.toMutableList()
        val state = State(
            number = { number }, flag = { flag }, text = { text }, payload = { payload },
            choiceLabel = { choiceLabel },
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
                    choiceLabel = choices[index].label
                    binding.choiceSpinner.onItemSelected { i ->
                        text = choices[i].value
                        choiceLabel = choices[i].label
                    }
                }
            }

            ValueKind.CONTACT -> {
                binding.contactBlock.visibility = View.VISIBLE
                if (choices.isEmpty()) {
                    binding.choiceEmpty.visibility = View.VISIBLE
                    binding.choiceEmpty.text = emptyChoiceMessage
                    binding.contactInput.setText(initialText, false)
                    text = initialText
                } else {
                    val labels = choices.map { it.label }
                    binding.contactInput.setAdapter(
                        ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, labels)
                    )
                    binding.contactInput.threshold = 0
                    val initial = choices.indexOfFirst { it.value == initialText }
                    if (initial >= 0) {
                        text = choices[initial].value
                        choiceLabel = choices[initial].label
                        binding.contactInput.setText(choiceLabel, false)
                    } else {
                        text = initialText
                        binding.contactInput.setText(initialText, false)
                    }
                    binding.contactInput.setOnClickListener { binding.contactInput.showDropDown() }
                    binding.contactInput.setOnItemClickListener { parent, _, index, _ ->
                        val selectedLabel = parent.getItemAtPosition(index).toString()
                        choices.firstOrNull { it.label == selectedLabel }?.let { selected ->
                            text = selected.value
                            choiceLabel = selected.label
                        }
                    }
                }
                // The same field is deliberately both a contact search and a phone-number
                // entry. A selected contact stores its number; typing replaces that selection
                // and clears the contact label so the rule summary shows the entered number.
                binding.contactInput.doAfterTextChanged { editable ->
                    val entered = editable?.toString().orEmpty().trim()
                    if (entered != choiceLabel) {
                        text = entered
                        choiceLabel = ""
                    }
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
                        // A Chip's default 14sp is below the suite's reading floor.
                        setTextSize(
                            TypedValue.COMPLEX_UNIT_PX,
                            context.resources.getDimension(R.dimen.text_body)
                        )
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) selectedDays.add(day) else selectedDays.remove(day)
                        }
                    }
                    binding.daysGroup.addView(chip)
                }
            }

            ValueKind.DATE -> {
                binding.dateButton.visibility = View.VISIBLE
                val initial = parseDate(initialText) ?: Calendar.getInstance()
                text = formatIsoDate(initial)
                binding.dateButton.text = formatDisplayDate(context, initial)
                binding.dateButton.setOnClickListener {
                    DatePickerDialog(
                        context,
                        { _, year, month, day ->
                            val selected = Calendar.getInstance().apply { set(year, month, day) }
                            text = formatIsoDate(selected)
                            binding.dateButton.text = formatDisplayDate(context, selected)
                        },
                        initial.get(Calendar.YEAR),
                        initial.get(Calendar.MONTH),
                        initial.get(Calendar.DAY_OF_MONTH)
                    ).show()
                }
            }

            ValueKind.TEXT -> {
                binding.textBlock.visibility = View.VISIBLE
                spec.hintRes.takeIf { it != 0 }?.let { binding.textInput.hint = context.getString(it) }
                binding.textInput.setText(initialText)
                binding.textInput.addTextChangedListener { text = it }
            }

            ValueKind.WEBHOOK -> {
                binding.textBlock.visibility = View.VISIBLE
                binding.textInput.hint = context.getString(R.string.value_webhook_url)
                binding.textInput.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_URI
                binding.textInput.setText(initialText)
                binding.textInput.addTextChangedListener { text = it.trim() }

                // The body only makes sense for POST — GET carries no request body — so it
                // stays hidden until the switch says POST.
                fun verbLabel(isPost: Boolean) = context.getString(
                    if (isPost) R.string.value_webhook_method_post else R.string.value_webhook_method_get
                )
                binding.boolSwitch.visibility = View.VISIBLE
                binding.boolSwitch.isChecked = initialFlag
                binding.boolSwitch.setText(verbLabel(initialFlag))
                binding.payloadBlock.visibility = if (initialFlag) View.VISIBLE else View.GONE
                binding.boolSwitch.setOnCheckedChangeListener { view, checked ->
                    flag = checked
                    view.setText(verbLabel(checked))
                    binding.payloadBlock.visibility = if (checked) View.VISIBLE else View.GONE
                }

                binding.payloadInput.setText(initialPayload)
                binding.payloadInput.addTextChangedListener { payload = it }
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
                binding.locationActions.visibility = View.VISIBLE
                binding.locationCurrent.setOnClickListener {
                    binding.locationCurrent.isEnabled = false
                    com.mg4.tasker.util.CarLocation.requestCurrent(context) { fix ->
                        binding.locationCurrent.isEnabled = true
                        if (fix != null) {
                            val point = String.format(
                                java.util.Locale.US, "%.6f,%.6f", fix.latitude, fix.longitude
                            )
                            binding.textInput.setText(point)
                        }
                    }
                }
                binding.locationMap.setOnClickListener {
                    val centre = text.ifBlank { currentPoint.orEmpty() }
                    // The button did nothing at all on the car: the MG4's map app publishes no
                    // geo: filter, and the failure was swallowed. MapApps falls back to the
                    // explicit component the head unit's own launcher uses.
                    if (com.mg4.tasker.util.MapApps.open(context, centre) == null) {
                        Toast.makeText(context, R.string.value_location_map_none, Toast.LENGTH_SHORT).show()
                    }
                }

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

            ValueKind.NONE, ValueKind.CONTACT, ValueKind.PHYSICAL_BUTTON -> Unit
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

    private fun parseDate(value: String): Calendar? {
        val date = runCatching { java.time.LocalDate.parse(value) }.getOrNull() ?: return null
        return Calendar.getInstance().apply { set(date.year, date.monthValue - 1, date.dayOfMonth) }
    }

    private fun formatIsoDate(date: Calendar): String = String.format(
        java.util.Locale.US, "%04d-%02d-%02d",
        date.get(Calendar.YEAR), date.get(Calendar.MONTH) + 1, date.get(Calendar.DAY_OF_MONTH)
    )

    private fun formatDisplayDate(context: Context, date: Calendar): String =
        android.text.format.DateFormat.getDateFormat(context).format(date.time)

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
        ArrayAdapter(context, R.layout.item_value_choice, items).apply {
            setDropDownViewResource(R.layout.item_value_choice)
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
