package com.gguf.zerocopy.data.invent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

// ─── Invent Project & Role model (dashboard "4 squares") ────────────────────

/**
 * A role inside an Invent project: the identity/command given to the model
 * ("who he is, what he should do") plus the model assignment and tuning.
 *
 * The three built-in roles are Planner, Debugger and Coder. The coder can
 * never be deleted. Extra roles can be added by the user (name + description).
 */
data class InventRoleConfig(
    val role: String,                 // "planner" | "debugger" | "coder" | custom
    val description: String = "",     // system prompt / identity command
    val modelPath: String = "",
    val modelName: String = "",       // empty → UI shows "Unknown" if file missing
    val contextWindow: Int = 2048,
    val maxTokens: Int = 512,
    val thinkingEnabled: Boolean = true,
    val backgroundWork: Boolean = false, // coder only: keep generating in background
    val isBuiltin: Boolean = false,
    val isPlanner: Boolean = false,
    val isDebugger: Boolean = false,
    val isCoder: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("role", role)
        put("description", description)
        put("modelPath", modelPath)
        put("modelName", modelName)
        put("contextWindow", contextWindow)
        put("maxTokens", maxTokens)
        put("thinkingEnabled", thinkingEnabled)
        put("backgroundWork", backgroundWork)
        put("isBuiltin", isBuiltin)
        put("isPlanner", isPlanner)
        put("isDebugger", isDebugger)
        put("isCoder", isCoder)
    }

    companion object {
        fun fromJson(o: JSONObject) = InventRoleConfig(
            role = o.optString("role"),
            description = o.optString("description"),
            modelPath = o.optString("modelPath"),
            modelName = o.optString("modelName"),
            contextWindow = o.optInt("contextWindow", 2048),
            maxTokens = o.optInt("maxTokens", 512),
            thinkingEnabled = o.optBoolean("thinkingEnabled", true),
            backgroundWork = o.optBoolean("backgroundWork", false),
            isBuiltin = o.optBoolean("isBuiltin", false),
            isPlanner = o.optBoolean("isPlanner", false),
            isDebugger = o.optBoolean("isDebugger", false),
            isCoder = o.optBoolean("isCoder", false)
        )

        val PLANNER = InventRoleConfig(
            role = "planner", isBuiltin = true, isPlanner = true,
            description = "You are the Planner. You interview the user one question at a time to fully understand the project, then design the architecture, split the work into parts and write a per-file summary for the Coder. You never write code."
        )
        val DEBUGGER = InventRoleConfig(
            role = "debugger", isBuiltin = true, isDebugger = true,
            description = "You are the Debugger. You read every file summary and the project readme.txt, inspect the generated code, find bugs and improve the files. You answer debugging questions."
        )
        val CODER = InventRoleConfig(
            role = "coder", isBuiltin = true, isCoder = true,
            description = "You are the Coder. You generate each file of the project following the Planner's per-file summaries. You write complete, working code."
        )
    }
}

/**
 * One of the four dashboard squares. A project owns roles (model assignments),
 * sessions and the generated file tree.
 */
data class InventProject(
    val id: String,
    val name: String = "Project",
    val roles: List<InventRoleConfig> = emptyList(),
    val sessionIds: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("createdAt", createdAt)
        put("roles", JSONArray().apply { roles.forEach { put(it.toJson()) } })
        put("sessions", JSONArray().apply { sessionIds.forEach { put(it) } })
    }

    fun withRoles(roles: List<InventRoleConfig>) = copy(roles = roles)
    fun withSessionIds(ids: List<String>) = copy(sessionIds = ids)

    companion object {
        fun fromJson(o: JSONObject): InventProject {
            val rolesArr = o.optJSONArray("roles")
            val sessArr = o.optJSONArray("sessions")
            return InventProject(
                id = o.optString("id"),
                name = o.optString("name", "Project"),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                roles = (0 until (rolesArr?.length() ?: 0)).mapNotNull { i ->
                    try { InventRoleConfig.fromJson(rolesArr!!.getJSONObject(i)) } catch (_: Exception) { null }
                },
                sessionIds = (0 until (sessArr?.length() ?: 0)).mapNotNull { i -> sessArr?.optString(i) }
            )
        }

        fun defaultRoles() = listOf(
            InventRoleConfig.PLANNER,
            InventRoleConfig.DEBUGGER,
            InventRoleConfig.CODER
        )
    }
}

// ─── Project store ──────────────────────────────────────────────────────────

object InventProjectStore {

    private fun projectsRoot(ctx: Context) = File(ctx.filesDir, "invent_projects").also { it.mkdirs() }

    fun projectDir(ctx: Context, projectId: String) = File(projectsRoot(ctx), projectId).also { it.mkdirs() }

    fun projectFile(ctx: Context, projectId: String) = File(projectDir(ctx, projectId), "project.json")

    /** Project-level generated files (the dashboard file manager browses here). */
    fun filesDir(ctx: Context, projectId: String) = File(projectDir(ctx, projectId), "files").also { it.mkdirs() }

    fun listProjects(ctx: Context): List<InventProject> =
        projectsRoot(ctx).listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            val f = File(dir, "project.json")
            if (!f.exists()) return@mapNotNull null
            try { InventProject.fromJson(JSONObject(f.readText())) } catch (_: Exception) { null }
        }?.sortedByDescending { it.createdAt } ?: emptyList()

    fun loadProject(ctx: Context, projectId: String): InventProject? {
        val f = projectFile(ctx, projectId)
        if (!f.exists()) return null
        return try { InventProject.fromJson(JSONObject(f.readText())) } catch (_: Exception) { null }
    }

    fun saveProject(ctx: Context, project: InventProject) {
        val f = projectFile(ctx, project.id)
        f.parentFile?.mkdirs()
        f.writeText(project.toJson().toString())
    }

    fun createProject(ctx: Context, name: String = "Project"): InventProject {
        val p = InventProject(
            id = UUID.randomUUID().toString().take(8),
            name = name,
            roles = InventProject.defaultRoles(),
            createdAt = System.currentTimeMillis()
        )
        saveProject(ctx, p)
        return p
    }

    /** X on the square: remove everything INSIDE the project but keep the square. */
    fun clearProjectContents(ctx: Context, projectId: String) {
        val dir = projectDir(ctx, projectId)
        dir.listFiles()?.forEach { f ->
            if (f.name != "project.json") f.deleteRecursively()
        }
    }

    fun deleteProject(ctx: Context, projectId: String) {
        projectDir(ctx, projectId).deleteRecursively()
    }

    /** True when the configured model file still exists in the app. */
    fun modelExists(role: InventRoleConfig, knownPaths: Set<String>): Boolean =
        role.modelPath.isEmpty() || knownPaths.contains(role.modelPath)
}

// ─── Project templates (empty-slot "+ New Project") ─────────────────────────

object InventTemplates {
    data class Template(
        val id: String,
        val label: String,
        val tagline: String,
        val description: String,
        val seedFiles: Map<String, String> = emptyMap()
    )

    val ALL: List<Template> = listOf(
        Template(
            id = "blank", label = "Blank", tagline = "From scratch",
            description = "The three roles and an empty folder. Best for custom ideas."
        ),
        Template(
            id = "android", label = "Android app", tagline = "Kotlin + Compose",
            description = "Kotlin / Jetpack Compose app skeleton with build files.",
            seedFiles = mapOf(
                "readme.txt" to "Android app project\nBuild the complete app described by the planner.\n",
                "app/src/main/java/MainActivity.kt" to "package com.example.app\n\nimport android.os.Bundle\nimport androidx.activity.ComponentActivity\n\nclass MainActivity : ComponentActivity() {\n    override fun onCreate(savedInstanceState: Bundle?) {\n        super.onCreate(savedInstanceState)\n    }\n}\n",
                "app/build.gradle.kts" to "plugins { id(\"com.android.application\") }\nandroid { namespace = \"com.example.app\" }\n"
            )
        ),
        Template(
            id = "python", label = "Python tool", tagline = "CLI / script",
            description = "A Python command-line tool with entry point and README.",
            seedFiles = mapOf(
                "readme.txt" to "Python CLI tool project\nBuild the tool described by the planner.\n",
                "main.py" to "#!/usr/bin/env python3\n\"\"\"Entry point for the tool.\"\"\"\n\n\ndef main() -> None:\n    print(\"TODO: implement\")\n\n\nif __name__ == \"__main__\":\n    main()\n"
            )
        ),
        Template(
            id = "cpp", label = "C++ tool", tagline = "Native console",
            description = "A C++17 console application with CMake build.",
            seedFiles = mapOf(
                "readme.txt" to "C++ tool project\nBuild the tool described by the planner.\n",
                "src/main.cpp" to "#include <iostream>\n\nint main() {\n    std::cout << \"TODO: implement\" << std::endl;\n    return 0;\n}\n",
                "CMakeLists.txt" to "cmake_minimum_required(VERSION 3.16)\nproject(app)\nadd_executable(app src/main.cpp)\n"
            )
        ),
        Template(
            id = "web", label = "Web page", tagline = "HTML / CSS / JS",
            description = "A static website with index, styles and script.",
            seedFiles = mapOf(
                "readme.txt" to "Static website project\nBuild the site described by the planner.\n",
                "index.html" to "<!DOCTYPE html>\n<html>\n<head>\n  <meta charset=\"utf-8\">\n  <title>App</title>\n  <link rel=\"stylesheet\" href=\"style.css\">\n</head>\n<body>\n  <h1>TODO: implement</h1>\n  <script src=\"script.js\"></script>\n</body>\n</html>\n",
                "style.css" to "body { font-family: sans-serif; }\n",
                "script.js" to "// TODO: implement\n"
            )
        )
    )

    /** Create a project with the template's seed files already in its folder. */
    fun createProject(ctx: Context, name: String, templateId: String): InventProject {
        val template = ALL.find { it.id == templateId } ?: ALL.first()
        val p = InventProject(
            id = UUID.randomUUID().toString().take(8),
            name = name.ifBlank { template.label },
            roles = InventProject.defaultRoles(),
            createdAt = System.currentTimeMillis()
        )
        InventProjectStore.saveProject(ctx, p)
        val dir = InventProjectStore.filesDir(ctx, p.id)
        template.seedFiles.forEach { (rel, content) ->
            val f = File(dir, rel)
            f.parentFile?.mkdirs()
            f.writeText(content)
        }
        return p
    }
}

