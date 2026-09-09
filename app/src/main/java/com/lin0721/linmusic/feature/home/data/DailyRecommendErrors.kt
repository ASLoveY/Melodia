package com.lin0721.linmusic.feature.home.data

import com.lin0721.linmusic.core.network.AppError
import com.lin0721.linmusic.core.network.ResourceProvider
import com.lin0721.linmusic.core.network.toUserMessage

fun Throwable.dailyRecommendMessage(resources: ResourceProvider): String = when (this) {
    is AppError.BizError -> {
        val detail = rawMsg?.replace(Regex("<[^>]*>"), "")?.filterNot { it.isISOControl() }?.trim()?.take(160)
        "${detail?.takeIf { it.isNotBlank() } ?: "每日推荐暂时不可用"}（服务代码 $code）"
    }
    AppError.Unauthorized -> "尚未登录或登录已失效，请登录后查看每日推荐"
    else -> toUserMessage(resources)
}
