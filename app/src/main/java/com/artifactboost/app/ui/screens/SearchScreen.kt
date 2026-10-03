package com.artifactboost.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artifactboost.app.ArtifactBoostApp
import com.artifactboost.app.data.GHRepo
import com.artifactboost.app.data.RepoSort
import com.artifactboost.app.ui.components.CardSurface
import com.artifactboost.app.ui.components.EmptyStateView
import com.artifactboost.app.ui.components.InlineBanner
import com.artifactboost.app.ui.components.RepoCardRow
import com.artifactboost.app.ui.theme.AppTheme
import kotlinx.coroutines.launch

/**
 * 搜索全站仓库：自己的、别人的公开仓库，只要能搜到就能进去下载。
 * 对应 iOS 版的 SearchView。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onOpenRepo: (GHRepo) -> Unit = {}) {
    val colors = AppTheme.colors
    val session = ArtifactBoostApp.instance.session
    val scope = rememberCoroutineScope()

    var keyword by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(RepoSort.BEST_MATCH) }
    var results by remember { mutableStateOf<List<GHRepo>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var directInput by remember { mutableStateOf("") }
    var directError by remember { mutableStateOf<String?>(null) }
    var isOpening by remember { mutableStateOf(false) }

    fun search() {
        val client = session.client.value ?: return
        val text = keyword.trim()
        if (text.isEmpty()) return
        isSearching = true
        errorMessage = null
        directError = null
        scope.launch {
            try {
                results = client.searchRepos(text, sort)
            } catch (e: Exception) {
                errorMessage = session.message(e)
            }
            isSearching = false
        }
    }

    fun openDirect() {
        val client = session.client.value ?: return
        val fullName = parseFullName(directInput)
        if (fullName == null) {
            directError = "格式不对，示例：cli/cli 或 https://github.com/cli/cli"
            return
        }
        isOpening = true
        directError = null
        errorMessage = null
        scope.launch {
            try {
                val repo = client.repo(fullName)
                directInput = ""
                onOpenRepo(repo)
            } catch (e: Exception) {
                directError = session.message(e)
            }
            isOpening = false
        }
    }

    Scaffold(
        containerColor = colors.canvas,
        topBar = {
            TopAppBar(
                title = { Text("搜索", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = colors.strongText) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 直接打开仓库
            item {
                CardSurface {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("直接打开仓库", fontSize = 12.sp, color = colors.muted, fontWeight = FontWeight.SemiBold)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = directInput,
                                onValueChange = { directInput = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("owner/repo 或 GitHub 链接", fontSize = 13.sp) },
                                leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null, tint = colors.muted) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    imeAction = ImeAction.Go,
                                ),
                                keyboardActions = KeyboardActions(onGo = { openDirect() }),
                                shape = RoundedCornerShape(10.dp),
                            )
                            if (isOpening) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = colors.blue, strokeWidth = 2.dp)
                            } else {
                                TextButton(
                                    onClick = { openDirect() },
                                    enabled = directInput.isNotBlank(),
                                ) { Text("打开", fontWeight = FontWeight.SemiBold) }
                            }
                        }
                        if (directError != null) {
                            InlineBanner(directError!!, colors.orange, Icons.Filled.Warning)
                        }
                        Text(
                            "贴一个仓库地址就能进去下载，例如 cli/cli 或 https://github.com/cli/cli",
                            fontSize = 11.sp,
                            color = colors.subtle,
                        )
                    }
                }
            }

            // 搜索框
            item {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索 GitHub 仓库", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = colors.muted) },
                    trailingIcon = {
                        if (keyword.isNotEmpty()) {
                            IconButton(onClick = { keyword = "" }) {
                                Icon(Icons.Filled.Clear, contentDescription = "清空", tint = colors.muted)
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                    shape = RoundedCornerShape(10.dp),
                )
            }

            // 排序
            item {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    RepoSort.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = sort == option,
                            onClick = {
                                sort = option
                                if (keyword.isNotBlank()) search()
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, RepoSort.entries.size),
                            label = { Text(option.title, fontSize = 12.sp) },
                        )
                    }
                }
            }

            if (errorMessage != null) {
                item { CardSurface { InlineBanner(errorMessage!!, colors.orange, Icons.Filled.Warning) } }
            }

            when {
                isSearching -> item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.padding(24.dp), color = colors.blue)
                    }
                }

                results.isEmpty() -> item {
                    EmptyStateView(
                        icon = Icons.Filled.Search,
                        title = if (keyword.isBlank()) "搜索全站仓库" else "没有搜到「$keyword」",
                        message = "在搜索框输入关键词后回车。公开仓库不需要你拥有它，" +
                            "能搜到就能下载它的产物、发行版和源码。",
                    )
                }

                else -> {
                    item { Text("${results.size} 个结果", fontSize = 12.sp, color = colors.muted) }
                    items(results, key = { it.id }) { repo ->
                        CardSurface {
                            Box(modifier = Modifier.clickable { onOpenRepo(repo) }) { RepoCardRow(repo) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 支持 owner/repo、github.com/owner/repo、完整链接（多余路径会被截掉）。
 */
fun parseFullName(raw: String): String? {
    var text = raw.trim()
    if (text.isEmpty()) return null
    // 从 Markdown 里复制时常见的 <https://github.com/owner/repo> 包裹
    if (text.startsWith("<") && text.endsWith(">") && text.length >= 2) {
        text = text.substring(1, text.length - 1).trim()
        if (text.isEmpty()) return null
    }

    val lower = text.lowercase()
    val looksLikeUrl = text.contains("://") || lower.contains("github.com")
    if (!looksLikeUrl) {
        // 裸 owner/repo 分支：之前实现强制要求 host 含 github.com，
        // 导致最常见的 "cli/cli" 输入永远返回 null，这就是直接打开无效的主因。
        val clean = text.substringBefore('?').substringBefore('#').trim()
        val parts = clean.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val owner = parts[0]
        var repo = parts[1]
        if (repo.lowercase().endsWith(".git")) repo = repo.dropLast(4)
        if (!isValidRepoPart(owner) || !isValidRepoPart(repo)) return null
        return "$owner/$repo"
    }

    if (!text.contains("://")) text = "https://$text"

    // 不依赖 java.net.URL：host 解析更宽松，兼容没有 scheme 的输入
    val withoutScheme = text.substringAfter("://")
    val host = withoutScheme.substringBefore('/').substringBefore('?').substringBefore('#').lowercase()
    if (!host.contains("github.com")) return null

    val path = withoutScheme.substringAfter('/', "").substringBefore('?').substringBefore('#')
    val parts = path.split('/').filter { it.isNotBlank() }
    if (parts.size < 2) return null
    var repo = parts[1]
    if (repo.lowercase().endsWith(".git")) repo = repo.dropLast(4)
    if (!isValidRepoPart(parts[0]) || !isValidRepoPart(repo)) return null
    return "${parts[0]}/$repo"
}

private val RepoNamePart = Regex("^[A-Za-z0-9_.-]+$")
private fun isValidRepoPart(s: String): Boolean = RepoNamePart.matches(s)
