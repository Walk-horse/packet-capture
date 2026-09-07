package com.ht.stream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ht.stream.data.HttpExchange
import com.ht.stream.data.RequestStore

/** 收藏的请求列表 */
@Composable
fun FavoritesScreen(onBack: () -> Unit, onOpen: (HttpExchange) -> Unit) {
    RequestStore.tick.collectAsState()
    val exchanges by RequestStore.exchanges.collectAsState()
    val favorites = exchanges.filter { it.favorite }

    Column(Modifier.fillMaxSize().background(StreamColors.BgGray)) {
        StreamTopBar(title = "收藏请求", onBack = onBack)
        Text(
            "共 ${favorites.size} 个收藏",
            fontSize = 12.sp,
            color = StreamColors.SubText,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        LazyColumn(
            Modifier.fillMaxSize().background(Color.White),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            if (favorites.isEmpty()) {
                item {
                    Text(
                        "暂无收藏，可在请求详情页点 ☆ 收藏",
                        fontSize = 13.sp,
                        color = StreamColors.SubText,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            items(favorites, key = { it.id }) { e ->
                ExchangeRow(e) { onOpen(e) }
            }
        }
    }
}
