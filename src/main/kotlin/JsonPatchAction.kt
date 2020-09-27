import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.flipkart.zjsonpatch.JsonPatch
import com.flipkart.zjsonpatch.JsonPatchApplicationException
import com.intellij.json.JsonLanguage
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages

class JsonPatchAction : AnAction() {

    private val mapper = ObjectMapper()

    private val log: Logger = Logger.getInstance(JsonPatchAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)!!
        val originalContent = mapper.readTree(psiFile.text)

        val jsonPatchInput = Messages.showMultilineInputDialog(
            e.project,
            "Enter JSON Patch value",
            "JSON Patch",
            "",
            Messages.getInformationIcon(),
            object : InputValidator {
                override fun checkInput(inputString: String?): Boolean {
                    if (inputString == null || inputString.isBlank()) {
                        return false
                    }
                    try {
                        val jsonPatch = mapper.readTree(inputString)
                        JsonPatch.apply(jsonPatch, originalContent)
                    } catch (e: Exception) {
                        when (e) {
                            is JsonMappingException, is JsonProcessingException -> {
                                log.info("Error converting input to JSON", e)
                            }
                            is JsonPatchApplicationException -> {
                                log.info("Can't apply this patch", e)
                            }
                            else -> {
                                log.info("Unexpected error", e)
                            }
                        }
                        return false
                    }
                    return true
                }

                override fun canClose(inputString: String?): Boolean {
                    return true
                }
            }
        )
        val jsonPatch = mapper.readTree(jsonPatchInput)
        val patchedContent = JsonPatch.apply(jsonPatch, originalContent)
        val editor = e.getData(CommonDataKeys.EDITOR)
        val document = editor!!.document
        runWriteAction {
            document.setText(patchedContent.toPrettyString().replace("\r\n", "\n"))
        }
    }

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.PSI_FILE)
        if (file != null && !file.isWritable) {
            log.debug("Can't write to read-only file")
        }
        file?.language?.isKindOf(JsonLanguage.INSTANCE)?.let {
            e.presentation.isEnabledAndVisible = it
        }
        super.update(e)
    }

}
