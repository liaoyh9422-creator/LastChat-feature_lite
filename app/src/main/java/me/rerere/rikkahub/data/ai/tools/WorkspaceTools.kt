package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceStorageArea
import java.io.ByteArrayOutputStream

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_list" to false,
    "workspace_stat" to false,
    "workspace_glob" to false,
    "workspace_grep" to false,
    "workspace_read_file" to false,
    "workspace_write_file" to false,
    "workspace_edit_file" to false,
    "workspace_mkdir" to false,
    "workspace_delete" to true,
    "workspace_rename" to false,
    "workspace_shell" to true,
)

fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>): Boolean =
    overrides[name] ?: WorkspaceToolDefaultApprovals[name] ?: false

suspend fun createWorkspaceTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val approvalOverrides = workspaceRepository.getById(workspaceId)?.toolApprovalOverrides().orEmpty()
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, approvalOverrides)

    val shellCwd = cwd?.removePrefix("/workspace/")?.removePrefix("/workspace")

    return listOf(
        createListTool(workspaceId, ::needsApproval, workspaceRepository),
        createStatTool(workspaceId, ::needsApproval, workspaceRepository),
        createGlobTool(workspaceId, ::needsApproval, workspaceRepository),
        createGrepTool(workspaceId, ::needsApproval, workspaceRepository),
        createReadFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createWriteFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createEditFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createMkdirTool(workspaceId, ::needsApproval, workspaceRepository),
        createDeleteTool(workspaceId, ::needsApproval, workspaceRepository),
        createRenameTool(workspaceId, ::needsApproval, workspaceRepository),
        createShellTool(workspaceId, ::needsApproval, workspaceRepository, shellCwd),
    )
}

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg")

private fun String.isImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private fun createListTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_list",
    description = "List files and directories using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs. Use /workspace for the workspace files area.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = false)
                put("area", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional area selector: files or linux. Defaults to automatic path-based resolution.")
                })
            },
            required = emptyList(),
        )
    },
    requiresUserApproval = needsApproval("workspace_list"),
    execute = {
        val params = it.jsonObject
        val path = params.string("path")?.takeIf { p -> p.isNotBlank() } ?: "/workspace"
        val (area, relativePath) = rootfsPathToAreaAndRelative(path, params.string("area"))
        val entries = workspaceRepository.listFiles(workspaceId, area, relativePath)
        buildJsonObject {
            put("path", path)
            put("area", area.name.lowercase())
            put("entries", kotlinx.serialization.json.JsonArray(entries.map { entry -> entry.toJson() }))
        }
    },
)

private fun createStatTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_stat",
    description = "Get metadata for a file or directory using the assistant's bound workspace Rootfs.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("area", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional area selector: files or linux. Defaults to automatic path-based resolution.")
                })
            },
            required = listOf("path"),
        )
    },
    requiresUserApproval = needsApproval("workspace_stat"),
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val (area, relativePath) = rootfsPathToAreaAndRelative(path, params.string("area"))
        val entry = workspaceRepository.statPath(workspaceId, area, relativePath)
        buildJsonObject {
            put("path", path)
            put("area", area.name.lowercase())
            put("entry", entry.toJson())
        }
    },
)

private fun createGlobTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_glob",
    description = "Find files by glob pattern in the assistant's bound workspace files area.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("pattern", buildJsonObject {
                    put("type", "string")
                    put("description", "Glob pattern, for example **/*.md or src/*.kt")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional relative path inside /workspace to start from. Defaults to root.")
                })
            },
            required = listOf("pattern"),
        )
    },
    requiresUserApproval = needsApproval("workspace_glob"),
    execute = {
        val params = it.jsonObject
        val pattern = params.string("pattern") ?: error("pattern is required")
        val path = params.string("path").orEmpty()
        val entries = workspaceRepository.glob(workspaceId, pattern, path)
        buildJsonObject {
            put("pattern", pattern)
            put("path", if (path.isBlank()) "/workspace" else "/workspace/${path.trimStart('/')}" )
            put("entries", kotlinx.serialization.json.JsonArray(entries.map { entry -> entry.toJson() }))
        }
    },
)

private fun createGrepTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_grep",
    description = "Search text in files within the assistant's bound workspace files area.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Search query")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional relative path inside /workspace to search from. Defaults to root.")
                })
                put("regex", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Treat query as regex. Defaults to false.")
                })
                put("ignore_case", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Ignore case. Defaults to true.")
                })
                put("include", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional glob filter, for example **/*.kt")
                })
            },
            required = listOf("query"),
        )
    },
    requiresUserApproval = needsApproval("workspace_grep"),
    execute = {
        val params = it.jsonObject
        val query = params.string("query") ?: error("query is required")
        val path = params.string("path").orEmpty()
        val regex = params["regex"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val ignoreCase = params["ignore_case"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val includeGlob = params.string("include")
        val matches = workspaceRepository.grep(workspaceId, query, path, regex, ignoreCase, includeGlob)
        buildJsonObject {
            put("query", query)
            put("path", if (path.isBlank()) "/workspace" else "/workspace/${path.trimStart('/')}" )
            put("matches", kotlinx.serialization.json.JsonArray(matches.map { match ->
                buildJsonObject {
                    put("path", match.path)
                    put("line", match.line)
                    put("text", match.text)
                }
            }))
        }
    },
)

private fun createReadFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_read_file",
    description = "Read a file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs. Use /workspace for the workspace files area. Supports UTF-8 text files and image files (png, jpg, jpeg, gif, webp, bmp).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
            },
            required = listOf("path"),
        )
    },
    requiresUserApproval = needsApproval("workspace_read_file"),
    execute = {
        val path = it.jsonObject.absolutePath("path")
        if (path.isImagePath()) {
            buildJsonObject {
                put("path", path)
                put("description", "Image file read is not yet supported in this migration target")
                put("ok", false)
            }
        } else {
            val text = workspaceRepository.readTextInRootfs(workspaceId, path)
            buildJsonObject {
                put("path", path)
                put("text", text)
            }
        }
    },
)

private fun createWriteFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_write_file",
    description = "Write a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs. Use /workspace for the workspace files area.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "UTF-8 text content to write")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to overwrite an existing file. Defaults to true.")
                })
            },
            required = listOf("path", "text"),
        )
    },
    requiresUserApproval = needsApproval("workspace_write_file"),
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val text = params.string("text") ?: error("text is required")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val entry = workspaceRepository.writeTextInRootfs(workspaceId, path, text, overwrite)
        entry.toJson()
    },
)

private fun createEditFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_edit_file",
    description = "Edit a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs. Use /workspace for the workspace files area. Provide old_text and new_text. By default old_text must occur exactly once; set replace_all=true to replace every occurrence. If no exact match is found, whitespace-tolerant line matching is attempted automatically.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact text to replace")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Replacement text")
                })
                put("replace_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to replace every occurrence. Defaults to false.")
                })
            },
            required = listOf("path", "old_text", "new_text"),
        )
    },
    requiresUserApproval = needsApproval("workspace_edit_file"),
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val oldText = params.string("old_text") ?: error("old_text is required")
        val newText = params.string("new_text") ?: error("new_text is required")
        val replaceAll = params["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        require(oldText.isNotEmpty()) { "old_text must not be empty" }

        val original = workspaceRepository.readTextInRootfs(workspaceId, path)
        val result = try {
            replaceText(original, oldText, newText, replaceAll)
        } catch (e: IllegalArgumentException) {
            error("${e.message} (path: $path)")
        }
        val entry = workspaceRepository.writeTextInRootfs(workspaceId, path, result.updated, overwrite = true)
        buildJsonObject {
            put("path", entry.path)
            put("replacements", result.replacements)
            if (result.strategy != ExactReplacer.name) put("matchStrategy", result.strategy)
            put("sizeBytes", entry.sizeBytes)
            put("updatedAt", entry.updatedAt)
        }
    },
)

private fun createMkdirTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_mkdir",
    description = "Create a directory in the assistant's bound workspace Rootfs. Only /workspace paths are writable.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
            },
            required = listOf("path"),
        )
    },
    requiresUserApproval = needsApproval("workspace_mkdir"),
    execute = {
        val path = it.jsonObject.absolutePath("path")
        val (area, relativePath) = rootfsPathToAreaAndRelative(path)
        require(area == WorkspaceStorageArea.FILES) { "Only /workspace paths are creatable in the current migration target" }
        val entry = workspaceRepository.createDirectory(workspaceId, relativePath)
        buildJsonObject {
            put("path", path)
            put("ok", true)
            put("entry", entry.toJson())
        }
    },
)

private fun createDeleteTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_delete",
    description = "Delete a file or directory in the assistant's bound workspace Rootfs. Only /workspace paths are writable.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("recursive", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Delete directories recursively. Defaults to false.")
                })
            },
            required = listOf("path"),
        )
    },
    requiresUserApproval = needsApproval("workspace_delete"),
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val recursive = params["recursive"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val (area, relativePath) = rootfsPathToAreaAndRelative(path)
        require(area == WorkspaceStorageArea.FILES) { "Only /workspace paths are deletable in the current migration target" }
        val deleted = workspaceRepository.deleteFile(workspaceId, area, relativePath, recursive)
        buildJsonObject {
            put("path", path)
            put("deleted", deleted)
        }
    },
)

private fun createRenameTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_rename",
    description = "Rename or move a file or directory within the assistant's bound workspace files area.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("from", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute source path inside Rootfs")
                })
                put("to", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute destination path inside Rootfs")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Overwrite destination if it exists. Defaults to false.")
                })
            },
            required = listOf("from", "to"),
        )
    },
    requiresUserApproval = needsApproval("workspace_rename"),
    execute = {
        val params = it.jsonObject
        val from = params.absolutePath("from")
        val to = params.absolutePath("to")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val (fromArea, fromPath) = rootfsPathToAreaAndRelative(from)
        val (toArea, toPath) = rootfsPathToAreaAndRelative(to)
        require(fromArea == WorkspaceStorageArea.FILES && toArea == WorkspaceStorageArea.FILES) {
            "Only /workspace paths are renameable in the current migration target"
        }
        val entry = workspaceRepository.moveFile(workspaceId, fromPath, toPath, overwrite)
        buildJsonObject {
            put("from", from)
            put("to", to)
            put("entry", entry.toJson())
        }
    },
)

private fun createShellTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
) = Tool(
    name = "workspace_shell",
    description = buildString {
        append("Run a shell command in the assistant's bound workspace Rootfs. The workspace files area is mounted at /workspace. ")
        append("Use cwd for a path relative to the workspace files root. ")
        if (!defaultCwd.isNullOrBlank()) {
            append("Defaults to '$defaultCwd'. ")
        }
        append("Requires Rootfs to be installed and ready.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        if (!defaultCwd.isNullOrBlank()) {
                            "Working directory relative to the workspace files root. Defaults to '$defaultCwd'."
                        } else {
                            "Working directory relative to the workspace files root. Defaults to root."
                        }
                    )
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put("description", "Command timeout in seconds. Defaults to 30, max $SHELL_TIMEOUT_MAX_SECONDS.")
                })
            },
            required = listOf("command"),
        )
    },
    requiresUserApproval = needsApproval("workspace_shell"),
    execute = {
        val params = it.jsonObject
        val command = params.string("command") ?: error("command is required")
        val cwd = (params.string("cwd") ?: defaultCwd.orEmpty())
            .removePrefix("/workspace/").removePrefix("/workspace")
        val timeoutMillis = params.string("timeout")?.toLongOrNull()
            ?.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS)
            ?.times(1_000L)
            ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS
        val result = workspaceRepository.executeCommand(workspaceId, command, cwd, timeoutMillis)
        result.toJson()
    },
)

private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private suspend fun WorkspaceRepository.readTextInRootfs(
    workspaceId: String,
    path: String,
): String {
    val (area, relativePath) = rootfsPathToAreaAndRelative(path)
    val size = fileSize(workspaceId, area, relativePath)
    require(size <= MAX_READ_FILE_BYTES) {
        "File is too large to read: $path (${size / 1024 / 1024}MB, max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB). Use shell commands like head, tail, or grep to read parts of it."
    }
    val buffer = ByteArrayOutputStream(size.toInt())
    exportFile(workspaceId, area, relativePath, buffer)
    return buffer.toString(Charsets.UTF_8.name())
}

private fun rootfsPathToAreaAndRelative(
    path: String,
    areaHint: String? = null,
): Pair<WorkspaceStorageArea, String> {
    val hintedArea = when (areaHint?.trim()?.lowercase()) {
        "files", "workspace" -> WorkspaceStorageArea.FILES
        "linux", "rootfs" -> WorkspaceStorageArea.LINUX
        else -> null
    }
    val trimmed = path.trimEnd('/')
    if (hintedArea != null) {
        val relative = when (hintedArea) {
            WorkspaceStorageArea.FILES -> trimmed.removePrefix("/workspace").trimStart('/')
            WorkspaceStorageArea.LINUX -> trimmed.trimStart('/').removePrefix("workspace/")
        }
        return hintedArea to relative
    }
    return if (trimmed == "/workspace" || trimmed.startsWith("/workspace/")) {
        WorkspaceStorageArea.FILES to trimmed.removePrefix("/workspace").trimStart('/')
    } else {
        WorkspaceStorageArea.LINUX to trimmed.trimStart('/')
    }
}

private suspend fun WorkspaceRepository.writeTextInRootfs(
    workspaceId: String,
    path: String,
    text: String,
    overwrite: Boolean,
): WorkspaceFileEntry {
    val (area, relativePath) = rootfsPathToAreaAndRelative(path)
    require(area == WorkspaceStorageArea.FILES) {
        "Only /workspace paths are writable in the current migration target"
    }
    return writeText(workspaceId, relativePath, text, overwrite)
}

private fun kotlinx.serialization.json.JsonObject.absolutePath(name: String): String {
    val path = string(name)?.replace('\\', '/')?.trim() ?: error("$name is required")
    require(path.isNotBlank()) { "$name is required" }
    require(path.startsWith("/")) { "$name must be an absolute path inside Rootfs" }
    require(!path.contains('\u0000')) { "$name contains invalid character" }
    return path
}

private fun JsonObjectBuilder.putPathProperty(required: Boolean) {
    put("path", buildJsonObject {
        put("type", "string")
        put(
            "description",
            if (required) {
                "Absolute path inside Rootfs. Use /workspace for the workspace files area."
            } else {
                "Optional absolute path inside Rootfs. Use /workspace for the workspace files area."
            }
        )
    })
}

private fun WorkspaceFileEntry.toJson() = buildJsonObject {
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("sizeBytes", sizeBytes)
    put("updatedAt", updatedAt)
}

private fun WorkspaceCommandResult.toJson() = buildJsonObject {
    put("exitCode", exitCode)
    put("stdout", stdout)
    put("stderr", stderr)
    put("timedOut", timedOut)
    if (truncated) put("truncated", true)
}