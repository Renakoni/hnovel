package indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.textformatting

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.dmzz_yyhyy.lightnovelreader.data.format.FormatRepository
import indi.dmzz_yyhyy.lightnovelreader.data.format.FormattingRule
import indi.dmzz_yyhyy.lightnovelreader.ui.components.regexAnnotatedString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EditTextFormattingRuleDialogViewModel @Inject constructor(
    private val formattingRepository: FormatRepository,
) : ViewModel() {
    private var bookId: String? = null
    var formattingRule: FormattingRule? by mutableStateOf(null)
        private set
    var matchTextFieldValue by mutableStateOf(TextFieldValue())
        private set

    fun load(bookId: String, ruleId: Int) {
        this.bookId = bookId
        if (ruleId == -1) {
            formattingRule = FormattingRule(
                id = -1,
                name = "",
                match = "",
                replacement = "",
                isRegex = false,
                isEnabled = true
            )
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            formattingRule = formattingRepository.getFormattingRules(ruleId)
            formattingRule?.match?.let {
                updateMatch(TextFieldValue(it))
            }
        }
    }

    fun updateName(name: String) {
        formattingRule = formattingRule?.copy(
            name = name
        )
    }

    fun updateMatch(match: TextFieldValue) {
        formattingRule = formattingRule?.copy(
            match = match.text
        )
        matchTextFieldValue =
            if (formattingRule?.isRegex == true)
                match.copy(
                    annotatedString = regexAnnotatedString(match.text)
                )
            else
                match.copy(
                    text = match.text
                )
    }

    fun updateReplacement(replacement: String) {
        formattingRule = formattingRule?.copy(
            replacement = replacement
        )
    }

    fun updateIsRegex(isRegex: Boolean) {
        formattingRule = formattingRule?.copy(
            isRegex = isRegex
        )
    }

    fun onConfirmation() {
        formattingRule ?: return
        bookId ?: return
        CoroutineScope(Dispatchers.IO).launch {
            if (formattingRule!!.id == -1)
                formattingRepository.insertRule(bookId!!, formattingRule!!)
            else
                formattingRepository.updateRule(bookId!!, formattingRule!!)
        }
    }

    fun onDelete() {
        if (formattingRule?.id != null && formattingRule?.id == -1) return
        CoroutineScope(Dispatchers.IO).launch {
            formattingRepository.deleteRule(ruleId = formattingRule?.id!!)
        }
    }
}