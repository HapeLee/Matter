package org.kazusa.matter.data.model

enum class UserPostCollection { LIKES, FAVORITES }

data class UserPostsPage(val posts: List<WeiboStatusDto>, val nextPage: Int?)
