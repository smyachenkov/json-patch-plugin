import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.flipkart.zjsonpatch.JsonPatch
import com.flipkart.zjsonpatch.JsonPatchApplicationException
import com.intellij.json.JsonLanguage
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable

class JsonPatchAction : AnAction() {

    private val mapper = ObjectMapper()

    private val log: Logger = Logger.getInstance(JsonPatchAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)!!
        if (psiFile.text.isEmpty()) {
            Messages.showErrorDialog(
                "Can't apply patch to empty document",
                "JSON must be valid"
            )
            return
        }
        val originalContent: JsonNode
        try {
            originalContent = mapper.readTree(psiFile.text)
        } catch (e: JsonProcessingException) {
            Messages.showErrorDialog(
                "Can't convert this file to JSON document: ${e.originalMessage}",
                "JSON must be valid"
            )
            return
        }

        val clipboard = CopyPasteManager.getInstance().allContents

        val jsonPatchInput = if (clipboardHasValidPatch(clipboard, originalContent)) {
            clipboard[0].getTransferData(DataFlavor.stringFlavor) as String
        } else {
            getPatchFromDialog(e.project!!, originalContent)
        }

        val jsonPatch = mapper.readTree(jsonPatchInput)
        val patchedContent = JsonPatch.apply(jsonPatch, originalContent)
        val editor = e.getData(CommonDataKeys.EDITOR)
        val document = editor!!.document

        log.debug("Replacing content of file ${psiFile.name} with new content: ${patchedContent.toPrettyString()}")

        runWriteAction {
            document.setText(
                patchedContent
                    .toPrettyString()
                    .replace("\r\n", "\n")
            )
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

    private fun clipboardHasValidPatch(clipboard: Array<Transferable>, originalContent: JsonNode): Boolean {
        if (clipboard.isEmpty()) {
            return false
        }
        val latestEntry = clipboard[0].getTransferData(DataFlavor.stringFlavor) as String
        return latestEntry.isValidJsonPatch(originalContent)
    }

    private fun getPatchFromDialog(project: Project, originalContent: JsonNode) =
        Messages.showMultilineInputDialog(
            project,
            "Enter JSON Patch value. It must be valid, or else it cannot be applied",
            "JSON Patch",
            "[\n]",
            Messages.getInformationIcon(),
            object : InputValidator {
                override fun checkInput(inputString: String?) = inputString.isValidJsonPatch(originalContent)
                override fun canClose(inputString: String?): Boolean = true
            }
        )

    private fun String?.isValidJsonPatch(originalContent: JsonNode): Boolean {
        if (this == null || this.isBlank()) {
            return false
        }
        try {
            val jsonPatch = mapper.readTree(this)
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
}
